package mx.cinvestav.controllers.nodes
import cats.implicits._
import cats.effect._
import mx.cinvestav.commons.events.ServiceReplicator.AddedService
//
import mx.cinvestav.Declarations.CreateCacheNodeCfg
import mx.cinvestav.config.DockerMode
import mx.cinvestav.helpers.Helpers
///
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.Declarations.Payloads.{CreateCacheNode, CreateCacheNodeResponseV2}
import mx.cinvestav.commons.types.{NodeId,SystemReplicationResponse}
import mx.cinvestav.commons.Implicits._
import mx.cinvestav.events.Events
//
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.circe.CirceEntityDecoder._
//
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
//
import concurrent.duration._
import language.postfixOps

object Create {

  def apply(payload:CreateCacheNode,hostLogPath:String,maxAR:Int)(implicit ctx:NodeContext) = {
    for {
      _                 <- ctx.logger.debug("CREATE_NODE")
      arrivalTime       <- IO.realTime.map(_.toMillis)
      arrivalTimeNanos  <- IO.monotonic.map(_.toNanos)
      currentState      <- ctx.state.get
      rawEvents         = currentState.events
      events            = Events.orderAndFilterEventsMonotonic(events=rawEvents)
      nodes             = Events.onlyAddedService(events=events)
      defaultCounter    = ctx.config.swarmNodes.map(_ -> 0).toMap
      counter           = nodes.asInstanceOf[List[AddedService]].groupBy(_.swarmNodeId).map{
        case (snid,xs) => snid -> xs.length
      } |+| defaultCounter
      currentNodeIndex  = nodes.length
      _                 <- ctx.logger.debug(s"NODE_LENGTH $currentNodeIndex")
//          Check if the number of nodes does not reach the max number of nodes.
      response          <- if(nodes.length < maxAR) {
        for {
          _               <- IO.unit
          prefix          = ctx.config.nodeIdPrefix
          nodeId          = if(ctx.config.autoNodeId) NodeId.auto(prefix = prefix) else NodeId(s"${prefix}$currentNodeIndex")

          _               <- ctx.logger.debug(s"AFTER_CREATE_NODE $nodeId")
          dockerMode      = DockerMode.fromString(ctx.config.dockerMode)
//        ________________________________________________________________
          dockerLogPath        = "/app/logs"
          dockerStoragePath    = "/app/data"
          totalMemoryCapacity  = payload.environments.getOrElse("TOTAL_MEMORY_CAPACITY",ctx.config.memoryBytes.toString)
          totalStorageCapacity = payload.environments.getOrElse("TOTAL_STORAGE_CAPACITY",ctx.config.baseTotalStorageCapacity.toString)
          defaultEnvs       =  Map(
            "NODE_ID" -> nodeId.value,
            "POOL_ID" -> ctx.config.pool.hostname,
            "NODE_HOST" -> "0.0.0.0",
            "NODE_PORT" ->  ctx.config.basePort.toString,
            //
            "CLOUD_ENABLED" -> ctx.config.cloudEnabled.toString,
            //
            "CACHE_POOL_HOSTNAME" -> ctx.config.cachePool.hostname,
            "CACHE_POOL_PORT"-> ctx.config.cachePool.port.toString,
            //
            "POOL_HOSTNAME" -> ctx.config.pool.hostname,
            "POOL_PORT" -> ctx.config.pool.port.toString,
            //
            "SERVICE_REPLICATOR_HOSTNAME" -> ctx.config.nodeId,
            "SERVICE_REPLICATOR_PORT" -> ctx.config.port.toString,
            //
            "CACHE_POLICY"-> payload.policy,
            "CACHE_SIZE" -> payload.cacheSize.toString,
            "TOTAL_STORAGE_CAPACITY" -> ctx.config.baseTotalStorageCapacity.toString,
            "TOTAL_MEMORY_CAPACITY" -> totalMemoryCapacity,
            "IN_MEMORY" -> ctx.config.pool.inMemory.toString,
            "STORAGE_PATH" -> dockerStoragePath,
            //
            "MONITORING_DELAY_MS" -> "1000",
            "API_VERSION" ->ctx.config.apiVersion.toString,
            "BUFFER_SIZE" -> ctx.config.bufferSize.toString,
            "LOG_PATH" ->  dockerLogPath,
          )
          cfg               = CreateCacheNodeCfg(
                          nodeId       = nodeId.value,
                          poolId       = ctx.config.poolId,
                          cachePolicy  = payload.policy,
                          cacheSize    = payload.cacheSize,
                          networkName  = payload.networkName,
                          environments = payload.environments ++ defaultEnvs,
                          hostLogPath  = ctx.config.hostLogPath,
                          dockerImage  = payload.image,
                          memoryBytes  = totalMemoryCapacity.toLong,
                          diskBytes    = totalStorageCapacity.toLong,
                          nanoCPUS     = ctx.config.nanoCPUS,
          )
// _________________________________________________________________________
          createdNode       <- if(dockerMode  == DockerMode.SWARM) Helpers.createCacheNodeSwarm(cfg,counter) else Helpers.createCacheNodeLocal(cfg)
          maybeIpAddress  = nodeId.value.some
          _               <- ctx.logger.debug("IP_ADDRESSES/HOSTNAME "+maybeIpAddress )
          serviceTime     <- IO.realTime.map(_.toMillis).map(_ - arrivalTime)
          response        <- maybeIpAddress match {
            case Some(ipAddress) => for {
              _ <- IO.unit
              maybePublicPort  = ctx.config.basePort.some
              _               <- ctx.logger.debug(s"PUBLIC_PORT $maybePublicPort")
              response        <- maybePublicPort match {
                case Some(publicPort) => for {
                  _                <- ctx.logger.debug(s"CONTAINER ON $ipAddress:$publicPort")
                  responsePayload  = SystemReplicationResponse(
                    nodeId       = nodeId.value,
                    url          = s"http://$ipAddress:6666",
                    milliSeconds = serviceTime,
                    ip           = ipAddress,
                    port         = publicPort,
                    dockerPort   = 6666,
                    containerId  =  createdNode.serviceId
                  )
                  now              <- IO.realTime.map(_.toMillis)
                  serviceTimeNanos <- IO.monotonic.map(_.toNanos).map(_ - arrivalTimeNanos)
                  addedNodeEvent   = AddedService(
                    serialNumber         = 0,
                    nodeId               = nodeId.value,
                    serviceId            = createdNode.serviceId,
                    ipAddress            = ipAddress,
                    port                 = publicPort,
                    totalStorageCapacity = totalStorageCapacity.toLong,
                    totalMemoryCapacity  = totalMemoryCapacity.toLong,
                    cacheSize            = payload.cacheSize,
                    cachePolicy          = payload.policy,
                    timestamp            = now,
                    serviceTimeNanos     = serviceTimeNanos,
                    correlationId        = createdNode.serviceId,
                    hostname             = nodeId.value,
                    swarmNodeId          = createdNode.selectedSwarmNodeId.getOrElse("")
                  )
                  _                <- Events.saveEvents(events = List(addedNodeEvent))
                  response        <- Ok(responsePayload.asJson)
                } yield response
                case None => for {
                  _        <- ctx.logger.error("NO_PUBLIC_PORT")
                  response <- BadRequest()
                } yield response
              }
            } yield response
            case None => BadRequest()
          }

//          Add NODE_ID to pending nodes
          _               <- ctx.state.update(s=>s.copy(pendingNodeCreation = s.pendingNodeCreation:+nodeId.value))
          _               <- ctx.logger.debug("_________________________________")
        } yield response
      }
//    The pool reaches the maximum number of nodes.
      else NoContent()
    } yield response
  }


}

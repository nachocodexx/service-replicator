package mx.cinvestav.controllers.nodes

import cats.implicits._
import cats.effect._
//
import mx.cinvestav.Declarations.CreateCacheNodeCfg
import mx.cinvestav.config.DockerMode
import mx.cinvestav.helpers.Helpers
///
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.Declarations.Payloads.{CreateCacheNode, CreateCacheNodeResponseV2}
import mx.cinvestav.commons.types.NodeId
import mx.cinvestav.commons.Implicits._
import mx.cinvestav.events.Events
import mx.cinvestav.events.Events.AddedService
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
          nodeId          = if(ctx.config.autoNodeId) NodeId.auto("cache-") else NodeId(s"cache-$currentNodeIndex")

          _               <- ctx.logger.debug(s"AFTER_CREATE_NODE $nodeId")
          dockerMode      = DockerMode.fromString(ctx.config.dockerMode)
//        ________________________________________________________________
          cfg             = CreateCacheNodeCfg(
                          nodeId       = nodeId.value,
                          poolId       = ctx.config.poolId,
                          cachePolicy  = payload.policy,
                          cacheSize    = payload.cacheSize,
                          networkName  = payload.networkName,
                          environments = payload.environments,
                          hostLogPath  = ctx.config.hostLogPath,
                          dockerImage  = payload.image,
                          memoryBytes  = ctx.config.memoryBytes,
                          nanoCPUS     = ctx.config.nanoCPUS
          )
// _________________________________________________________________________
          createdNode       <- if(dockerMode  == DockerMode.SWARM)
            Helpers.createCacheNodeSwarm(cfg,counter)
          else Helpers.createCacheNodeLocal(cfg)
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
                  responsePayload  = CreateCacheNodeResponseV2(
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
                    totalStorageCapacity = 40000000000L,
                    cacheSize            = payload.cacheSize,
                    cachePolicy          = payload.policy,
                    timestamp            = now,
                    serviceTimeNanos     = serviceTimeNanos,
                    correlationId        = createdNode.serviceId,
                    hostname             = nodeId.value,
                    swarmNodeId          = createdNode.selectedSwarmNodeId.getOrElse("")

                  )
                  _                <- Events.saveEvents(
                    events = List(addedNodeEvent)
                  )

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

package mx.cinvestav.controllers.nodes

import cats.effect.IO
import cats.implicits._
import mx.cinvestav.commons.types.NodeReplicationSchema
//
import mx.cinvestav.Declarations.{CreateCacheNodeCfg, NodeContext}
import mx.cinvestav.Declarations.Payloads.CreateStorageNode
import mx.cinvestav.commons.events.ServiceReplicator.AddedStorageNode
import mx.cinvestav.commons.types.SystemReplicationResponse
import mx.cinvestav.config.DockerMode
import mx.cinvestav.events.Events
import mx.cinvestav.helpers.Helpers
//
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.circe.CirceEntityDecoder._
//
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._


import java.util.UUID

object Createv3 {

  def apply(payload:NodeReplicationSchema) (implicit ctx:NodeContext)= for {
    now                 <- IO.realTime.map(_.toMillis)
    serviceTimeStart    <- IO.monotonic.map(_.toNanos)
    currentState        <- ctx.state.get
    rawEvents           = currentState.events
    events              = Events.orderAndFilterEventsMonotonic(events=rawEvents)
    nodes               = Events.onlyAddedStorageNode(events=events)
    dockerMode          = DockerMode.fromString(ctx.config.dockerMode)
    //   LOAD BALANCING
    //   - creates a map from swarm node to number storage nodes |SNs|
    defaultCounter       = ctx.config.swarmNodes.map(_ -> 0).toMap
    counter              = nodes.asInstanceOf[List[AddedStorageNode]].groupBy(_.swarmNodeId).map{
      case (snid,xs) => snid -> xs.length
    } |+| defaultCounter
    currentNodeIndex     = nodes.length
    suffix               = if(ctx.config.autoNodeId) UUID.randomUUID().toString.substring(0,5) else currentNodeIndex.toString
    nodeId               = if(payload.what.id.isEmpty) payload.what.metadata.getOrElse("NODE_ID",s"sn-$suffix") else payload.what.id
//  _____________________________________________________________________________________
    dockerLogPath        = "/app/logs"
    dockerStoragePath    = "/app/data"
    totalMemoryCapacity  = payload.what.metadata.getOrElse("TOTAL_MEMORY_CAPACITY",ctx.config.memoryBytes.toString)
    totalStorageCapacity = payload.what.metadata.getOrElse("TOTAL_STORAGE_CAPACITY",ctx.config.baseTotalStorageCapacity.toString)
    defaultEnvs          = Map(
      "NODE_ID" -> nodeId,
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
      "TOTAL_STORAGE_CAPACITY" -> ctx.config.baseTotalStorageCapacity.toString,
      "TOTAL_MEMORY_CAPACITY" -> totalMemoryCapacity,
      "IN_MEMORY" -> ctx.config.pool.inMemory.toString,
      "STORAGE_PATH" -> dockerStoragePath,
      //
      "MONITORING_DELAY_MS" -> "1000",
      "API_VERSION" ->ctx.config.apiVersion.toString,
      "BUFFER_SIZE" -> ctx.config.bufferSize.toString,
      "LOG_PATH" ->  dockerLogPath,
      "DELAY_REPLICA_MS" -> ctx.config.delayReplicaMs.toString,
      "NODE_INDEX" -> currentNodeIndex.toString
    ) ++ payload.what.metadata
    //  ____________________________________________________________________________________
    cfg                  = CreateCacheNodeCfg(
      nodeId       = nodeId,
      poolId       = ctx.config.poolId,
      environments = payload.what.metadata ++ defaultEnvs,
      hostLogPath  = ctx.config.hostLogPath,
      memoryBytes  = totalMemoryCapacity.toLong,
      diskBytes    = totalStorageCapacity.toLong,
      nanoCPUS     = ctx.config.nanoCPUS,
    )
    createdNode          <- if(dockerMode  == DockerMode.SWARM) Helpers.createCacheNodeSwarm(nrs = payload,cfg,counter) else Helpers.createCacheNodeLocal(cfg)
    serviceTimeEnd       <- IO.monotonic.map(_.toNanos)
    serviceTime          = serviceTimeEnd - serviceTimeStart
    responsePayload      = SystemReplicationResponse(
      nodeId       = nodeId,
      url          = s"http://$nodeId:6666",
      milliSeconds = serviceTime,
      ip           = nodeId,
      port         = 6666,
      dockerPort   = 6666,
      containerId  =  createdNode.serviceId
    )
    addedNodeEvent       = AddedStorageNode (
      serialNumber         = 0,
      nodeId               = nodeId,
      serviceId            = createdNode.serviceId,
      ipAddress            = nodeId,
      port                 = 6666,
      totalStorageCapacity = totalStorageCapacity.toLong,
      totalMemoryCapacity  = totalMemoryCapacity.toLong,
      timestamp            = now,
      serviceTimeNanos     = serviceTime,
      correlationId        = createdNode.serviceId,
      hostname             = nodeId,
      swarmNodeId          = createdNode.selectedSwarmNodeId.getOrElse("")
    )
    _                    <- Events.saveEvents(events = List(addedNodeEvent))
    response             <- Ok(responsePayload.asJson)
  } yield response

}

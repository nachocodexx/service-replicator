package mx.cinvestav.controllers.nodes

import cats.implicits._
import cats.effect.{IO, Ref}
import mx.cinvestav.Declarations.Payloads.CreateCacheNodeResponse
import mx.cinvestav.helpers.Helpers.CreateNodePayload
import mx.cinvestav.commons.events.AddedNode
import mx.cinvestav.config.DockerMode
import mx.cinvestav.helpers.Helpers
import retry.{RetryDetails, RetryPolicies}
///
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.Declarations.Payloads.{CreateCacheNode, CreateCacheNodeResponseV2}
import mx.cinvestav.commons.types.NodeId
import mx.cinvestav.events.Events
//
import org.http4s.Request
import org.http4s.dsl.io._
import org.typelevel.ci.CIString
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.circe.CirceEntityDecoder._
//
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import mx.cinvestav.events.Events.AddedService
import retry._
import retry.implicits._
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
      currentNodeIndex  = if(nodes.isEmpty) 0 else nodes.length+1
      _                 <- ctx.logger.debug(s"NODE_LENGTH ${nodes.length}")
      //    Check if the number of nodes does not reach the max number of nodes.
      response          <- if(nodes.length < maxAR) {
        for {
          _ <- IO.unit
//          payload         <- req.as[CreateCacheNode]
          nodeId          = if(ctx.config.autoNodeId) NodeId.auto("cache-") else NodeId(s"cache-$currentNodeIndex")
          _               <- ctx.logger.debug(s"AFTER_CREATE_NODE $nodeId")
          dockerMode      = DockerMode.fromString(ctx.config.dockerMode)
          serviceId       <- if(dockerMode  == DockerMode.SWARM) Helpers.createCacheNodeV3(
              nodeId = nodeId.value,
              poolId = ctx.config.poolId,
              cachePolicy = payload.policy,
              cacheSize= payload.cacheSize,
              networkName = payload.networkName,
              environments = payload.environments,hostLogPath = hostLogPath,
              dockerImage = payload.image

            ).onError{ e=>
              ctx.logger.debug(s"ERROR ${e.getMessage}")
            }
                           else Helpers.createCacheNodeV2(nodeId = nodeId.value,hostStoragePath = ctx.config.hostStoragePath,environments = payload.environments).onError(e=>ctx.logger.error(e.getMessage))
          maybeIpAddress   = nodeId.value.some
          _               <- ctx.logger.debug("IP_ADDRESSES/HOSTNAME "+maybeIpAddress )
          serviceTime     <- IO.realTime.map(_.toMillis).map(_ - arrivalTime)
          response        <- maybeIpAddress match {
            case Some(ipAddress) => for {
              _ <- IO.unit
              maybePublicPort  = ctx.config.basePort.some
              _               <- ctx.logger.debug(s"PUBLIC_PORT $maybePublicPort")
              response        <- maybePublicPort match {
                case Some(publicPort) => for {
                  _              <- ctx.logger.debug(s"CONTAINER ON $ipAddress:$publicPort")
                  responsePayload = CreateCacheNodeResponseV2(
                    nodeId       = nodeId.value,
                    url          = s"http://$ipAddress:6666",
                    milliSeconds = serviceTime,
                    ip           = ipAddress,
                    port         = publicPort,
                    dockerPort   = 6666
                  )
                  now              <- IO.realTime.map(_.toMillis)
                  serviceTimeNanos <- IO.monotonic.map(_.toNanos).map(_ - arrivalTimeNanos)
                  addedNodeEvent   = AddedService(
                    serialNumber =0,
                    nodeId = nodeId.value,
                    serviceId = serviceId,
                    ipAddress = ipAddress,
                    port = publicPort,
                    totalStorageCapacity = 40000000000L,
                    cacheSize = payload.cacheSize,
                    cachePolicy = payload.policy,
                    timestamp = now,
                    serviceTimeNanos =serviceTimeNanos,
                    monotonicTimestamp = 0,
                    correlationId = serviceId,
                    hostname = nodeId.value
                  )
                  _ <- Events.saveEvents(
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
        } yield response
      }
//    The pool reaches the maximum number of nodes.
      else NoContent()
    } yield response
  }

  def v2(req:Request[IO])(implicit ctx:NodeContext) = {

    var lastUsedPort:Option[List[Int]] = None
    for {
      //
      arrivalTime      <- IO.realTime.map(_.toMillis)
      arrivalTimeNanos <- IO.monotonic.map(_.toNanos)
      currentState     <- ctx.state.get
      //        SEMAPHORE
      _                <- currentState.s.acquire
      rawEvents        = currentState.events
      events           = Events.orderAndFilterEventsMonotonic(events=rawEvents)
      currentNodeLength = req.headers.get(CIString("Pool-Node-Length")).flatMap(_.head.value.toIntOption).getOrElse(1)
      hostLogPath       = req.headers.get(CIString("Host-Log-Path")).map(_.head.value).getOrElse("/test/logs")
      usedPorts         = Events.onlyAddedService(events = events).map(_.asInstanceOf[AddedNode]).map(_.port)
      //      ___________________________________________
      payload           <- req.as[CreateCacheNode]
      nodeId            = NodeId.auto("cache-")
      newPort           = Helpers.getAnAvailablePort(currentState.basePort + currentNodeLength,maxTries = 100,usedPorts = usedPorts)
      _                 <- IO.delay{
        lastUsedPort = (newPort::Nil).some
      }
      _ <- ctx.logger.debug(s"NEW_PORT $newPort")

      createCachePayload = CreateNodePayload
        .fromCreateCacheNode(payload)
        .copy(
          port = newPort,
          hostLogPath = hostLogPath,
        )

      response <- Ref[IO].of(newPort).flatMap{ref =>
        ref.get.flatMap(x=>Helpers.createNodeV2(createCachePayload.copy(port = x)))
          .retryingOnAllErrors(
            policy = RetryPolicies.limitRetries[IO](10) join RetryPolicies.exponentialBackoff(1 seconds),
            onError = (e:Throwable,d:RetryDetails)=> for {
              _            <- ctx.logger.error(e.getMessage)
              retryPortIO = (lastUsedPorts:List[Int]) =>{
                for {
                  _ <- IO.unit
                  newUsedPorts = Events.onlyAddedService(events = events).map(_.asInstanceOf[AddedNode]).map(_.port)
                  newPort      = Helpers.getAnAvailablePort(currentState.basePort + currentNodeLength,maxTries = 100,usedPorts = newUsedPorts++lastUsedPorts)
                  _            <- ctx.logger.debug("NEW_USED_PORTS "+newUsedPorts.toString)
                  _            <- ctx.logger.debug(s"RETRY_NEW_PORT $newPort")
                  _            <- ctx.logger.debug(s"RETRY_LAST_USED_PORTS $lastUsedPorts")
                  _            <- IO.delay{
                    lastUsedPort = (lastUsedPorts:+newPort).some
                  }
                  _            <- ref.set(newPort)

                } yield ()
              }
              _            <- lastUsedPort match {
                case Some(value) =>  retryPortIO(value)
                case None => retryPortIO(Nil)
              }
            } yield ()
          )
      }.flatMap{
        case (containerId, containerData) =>
          for {
            _              <- ctx.logger.debug("CREATE NEW CACHE_NODE")
            maybeIpAddress <- ctx.dockerClientX.getIpAddress(containerId = containerId,networkName =payload.networkName)

            _ <- ctx.logger.debug("IP_ADDRESSES "+maybeIpAddress )
            serviceTime     <- IO.realTime.map(_.toMillis).map(_ - arrivalTime)
            response        <- maybeIpAddress match {
              case Some(containerIpAddress) => for {
                _               <- IO.unit
                responsePayload = CreateCacheNodeResponse(
                  nodeId=containerData.nodeId,
                  url = s"http://$containerIpAddress:${containerData.port}",
                  milliSeconds = serviceTime
                )
                now              <- IO.realTime.map(_.toMillis)
                serviceTimeNanos <- IO.monotonic.map(_.toNanos).map(_ - arrivalTimeNanos)
                addedNodeEvent   = AddedNode(
                  serialNumber =0,
                  nodeId = ctx.config.nodeId,
                  addedNodeId = containerData.nodeId,
                  ipAddress = containerIpAddress,
                  port = containerData.port,
                  totalStorageCapacity = 40000000000L,
                  cacheSize = payload.cacheSize,
                  cachePolicy = payload.policy,
                  timestamp = now,
                  serviceTimeNanos =serviceTimeNanos,
                  monotonicTimestamp = 0,
                )
                _ <- Events.saveEvents(
                  events = List(addedNodeEvent)
                )
                _                <- currentState.s.release.delayBy(2 seconds)
                response        <- Ok(responsePayload.asJson)
              } yield response
              case None => Forbidden()
            }
          } yield response
      }

    } yield response
  }

}

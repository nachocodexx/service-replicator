package mx.cinvestav.controllers.nodes

import cats.implicits._
import cats.effect._
import mx.cinvestav.Declarations.Implicits._
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.events.Events
import mx.cinvestav.events.Events.{AddedService, StartedService}
import mx.cinvestav.helpers.Helpers
//import org.http4s.Status.{NoContent, NotFound}
//
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._

import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityEncoder._

object Started {

  def apply(req:Request[IO],nodeId:String)(implicit ctx:NodeContext) = for {
    _                 <- ctx.logger.debug(s"STARTED $nodeId")
    arrivalTime       <- IO.realTime.map(_.toNanos)
    arrivalTimeNanos  <- IO.monotonic.map(_.toNanos)
    currentState      <- ctx.state.get
    maybePublicPort   <- ctx.dockerClientX.getPortListByNodeId(containerName = nodeId).map(_.flatMap(_.headOption))
    ipAddress         <- ctx.dockerClientX.getIpAddress(containerId = nodeId,ctx.config.dockerNetworkName).map(_.getOrElse("127.0.0.1"))
//    headers           = req.headers
//  ________________________________________________________
    res               <- maybePublicPort match {
      case Some(publicPort) => for {
        _                 <- IO.unit
        _  <- ctx.config.pool.updateNodeNetworkCfg(nodeId,publicPort,ipAddress)
          .flatTap(x=>ctx.logger.debug(s"PUBLIC_PORT_STATUS $x")).start
        events            = Events.orderAndFilterEventsMonotonic(events=currentState.events)
        maybeAddedService = Events.onlyAddedService(events=events).map(_.asInstanceOf[AddedService]).find(_.nodeId==nodeId)
        res               <- maybeAddedService match {
          case Some(addedService) => for {
            _              <- IO.unit
            startedService = StartedService(
              nodeId = nodeId,
              serviceId = addedService.serviceId,
              timestamp = arrivalTime,
              serviceTimeNanos = 0L,
              correlationId = addedService.correlationId
            )
            _              <- Events.saveEvents(events = startedService :: Nil)
            systems        = List(ctx.config.pool,ctx.config.monitoring,ctx.config.dataReplicator)
            _              <- ctx.logger.debug(s"HERE_1")
            xs             <- systems.traverse{ n=> Helpers.addNode(n)(addedService).handleErrorWith{ e=>
              ctx.logger.error("AQUI: "+e.getMessage) *> NoContent().map(_.status)
            }
            }
              .onError(e=>
                ctx.logger.error(e.getMessage)
              )
            res     <- NoContent()
          } yield res
          case None => NotFound()
        }
        endAt             <- IO.monotonic.map(_.toNanos).map(_ - arrivalTimeNanos)
        _                 <- ctx.logger.debug(s"NODE_STARTED $nodeId $endAt")
      } yield res
      case None => Forbidden()
    }

  } yield res

}

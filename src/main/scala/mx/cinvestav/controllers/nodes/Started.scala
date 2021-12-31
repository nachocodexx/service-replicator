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
    arrivalTime       <- IO.realTime.map(_.toNanos)
    arrivalTimeNanos  <- IO.monotonic.map(_.toNanos)
    currentState      <- ctx.state.get
    events            = Events.orderAndFilterEventsMonotonic(events=currentState.events)
    maybeAddedService = Events.onlyAddedService(events=events).map(_.asInstanceOf[AddedService]).find(_.nodeId==nodeId)
    res               <- maybeAddedService match {
      case Some(addedService) => for {
        _               <- IO.unit
        startedService = StartedService(
          nodeId = nodeId,
          serviceId = addedService.serviceId,
          timestamp = arrivalTime,
          serviceTimeNanos = 0L,
          correlationId = addedService.correlationId
        )
        _              <- Events.saveEvents(events = startedService :: Nil)
        xs <- List(ctx.config.pool,ctx.config.monitoring).traverse(n=>Helpers.addNode(n)(addedService)).onError(e=>ctx.logger.error(e.getMessage))
        _ <- ctx.logger.debug(s"STATUS $xs")
        res            <- NoContent()
      } yield res
      case None => NotFound()
    }
    endAt             <- IO.monotonic.map(_.toNanos).map(_ - arrivalTimeNanos)
    _                 <- ctx.logger.debug(s"NODE_STARTED $nodeId $endAt")
  } yield res

}

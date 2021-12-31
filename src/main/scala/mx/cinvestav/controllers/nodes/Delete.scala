package mx.cinvestav.controllers.nodes

import cats.effect.IO
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.events.Events
import mx.cinvestav.events.Events.{AddedService, RemovedService}
import org.http4s.dsl.io._

object Delete {

  def apply (nodeId:String)(implicit ctx:NodeContext) = {

    for {
      arrivalTime       <- IO.realTime.map(_.toMillis)
      arrivalTimeNanos  <- IO.monotonic.map(_.toNanos)
      currentState      <- ctx.state.get
      rawEvents         = currentState.events
      events            = Events.orderAndFilterEventsMonotonic(events=rawEvents)
      maybeService      = Events.onlyAddedService(events=events).map(_.asInstanceOf[AddedService]).find(_.nodeId==nodeId)
      response          <- maybeService match {
        case Some(addedService) => for {
          _   <- ctx.logger.debug(s"REMOVING $nodeId")
          _   <- ctx.dockerClientX.deleteService(addedService.serviceId)
            .onError{ e=>
              ctx.logger.error(e.getMessage)
            }
          now <- IO.realTime.map(_.toMillis)
          serviceTimeNanos <- IO.monotonic.map(_.toNanos).map(_ - arrivalTimeNanos)
          removed =  RemovedService(
            serialNumber = 0,
            nodeId = nodeId,
            serviceId = addedService.serviceId,
            timestamp = now,
            serviceTimeNanos = serviceTimeNanos,
            correlationId = addedService.correlationId
          )
          _ <- Events.saveEvents(events = List(removed))
          res <- NoContent()
        } yield res
        case None =>
          ctx.logger.debug(s"$nodeId not found") *> NoContent()
      }
    } yield response
  }

}

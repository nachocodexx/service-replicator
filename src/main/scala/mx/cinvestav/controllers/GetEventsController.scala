package mx.cinvestav.controllers

import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.events.Events
import mx.cinvestav.Declarations.Implicits._
//
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityEncoder._
import io.circe.generic.auto._
import io.circe.syntax._

object GetEventsController {

  def apply()(implicit ctx:NodeContext) = {

    for{
      _                <- ctx.logger.debug("HERE!")
      currentState     <- ctx.state.get
      rawEvents        = currentState.events
      events           = Events.orderAndFilterEventsMonotonic(events=rawEvents)
      response         <- Ok(events.asJson)
    } yield response
  }

}

package mx.cinvestav.controllers

import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import mx.cinvestav.Declarations.Implicits._
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.commons.events.ServiceReplicator.AddedService
import mx.cinvestav.events.Events
//
import io.circe.syntax._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._

object StatsController {

  def apply()(implicit ctx:NodeContext) = {
    for{
      currentState     <- ctx.state.get
      rawEvents        = currentState.events
      events           = Events.orderAndFilterEventsMonotonic(events=rawEvents)
      nodes            = Events.onlyAddedService(events=events)

      nodesJson = nodes.map(_.asInstanceOf[AddedService]).map{ node=>
        Json.obj(
          "nodeId" -> node.nodeId.asJson,
          "port" -> node.port.asJson,
          "policy" -> node.cachePolicy.asJson,
          "cacheSize" -> node.cacheSize.asJson
        )
      }
      stats = Json.obj(
        "nodes" -> nodes.length.asJson
      )
      response         <- Ok(stats)
    } yield response
  }

}

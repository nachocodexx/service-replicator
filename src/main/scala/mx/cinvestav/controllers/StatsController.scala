package mx.cinvestav.controllers

import cats.effect._
import cats.implicits._
//
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import mx.cinvestav.Declarations.Implicits._
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.commons.types.Monitoring.PoolInfo
import mx.cinvestav.events.Events
import mx.cinvestav.events.Events.AddedService
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
      nodes            = Events.onlyAddedService(events=events).sortBy(_.nodeId)
      poolInfo         <- ctx.config.monitoring.getInfo().compile.lastOrError.handleErrorWith{e =>
        ctx.logger.error(e.getMessage) *> PoolInfo.empty.pure[IO]
      }
      infos            = poolInfo.infos.sortBy(_.nodeId)

      nodesJson = (nodes zip infos).map{
        case (n,info) =>
          val node = n.asInstanceOf[AddedService]
        Json.obj(
          "nodeId" -> node.nodeId.asJson,
          "port"      -> node.port.asJson,
          "policy"    -> node.cachePolicy.asJson,
          "cacheSize" -> node.cacheSize.asJson,
          "info"      -> info.asJson
        )
      }
      stats = Json.obj(
        "nodes" ->  nodesJson.asJson,
        "uf"        -> poolInfo.uf().asJson
      )
      response         <- Ok(stats)
    } yield response
  }

}

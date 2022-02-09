package mx.cinvestav.controllers.pool
import cats.implicits._
import cats.effect._
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.commons.types.NodeId
import mx.cinvestav.helpers.Helpers
//
import org.http4s._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
//
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._

object Create {

  case class CreatePool(
                         index:Int,
                         port:Int,
                         nodes:Int,
                         maxAR:Int,
                         maxRF:Int
                       )

  def apply(req:Request[IO])(implicit ctx:NodeContext) = for {
    _       <- ctx.logger.debug("CREATE_POOL")
    payload <- req.as[CreatePool]
    _       <- ctx.logger.debug(payload.asJson.toString)
    index   = payload.index
    port    = payload.port
//
    run     = for {
      _       <- Helpers.createLB(
        index  = index,
        nodeId = NodeId(s"pool-$index").value,
        port   = port +1
      )
      _       <- Helpers.createDR(
        index =index,
        nodeId = NodeId(s"dr-$index").value,
        port  = payload.port + 2
      )
      _       <- Helpers.createMonitoring(
        index=index,
        nodeId = NodeId(s"monitoring-$index").value,
        port = port + 3
      )
      _       <- Helpers.createSR(
        index=payload.index,
        port = payload.port,
        nodes=payload.nodes
      )
    } yield ()
    _ <- run.start
//
    res     <- NoContent()
  } yield res

}

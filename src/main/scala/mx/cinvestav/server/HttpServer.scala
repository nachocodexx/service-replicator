package mx.cinvestav.server

import cats.data.Kleisli
import cats.effect.IO.{IOCont, Uncancelable}
import cats.implicits._
import cats.effect._
import mx.cinvestav.Declarations.Payloads.CreateCacheNode
import mx.cinvestav.commons.events
import mx.cinvestav.commons.events.ServiceReplicator.AddedService
import mx.cinvestav.controllers.morin.GetContainer
import mx.cinvestav.controllers
import mx.cinvestav.helpers.Helpers
import mx.cinvestav.events.Events
import mx.cinvestav.events.Events.RemovedService
import org.http4s.Http
//
//
import mx.cinvestav.commons.events.AddedNode
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.commons.types.NodeId
//
import org.http4s.{Request, Response}
import org.typelevel.ci.CIString
import org.http4s.HttpRoutes
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.Router
import org.http4s.implicits._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
//
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
//
import concurrent.ExecutionContext.global
import scala.concurrent.duration._
import language.postfixOps


class HttpServer()(implicit ctx:NodeContext){

  def apiBaseRouteName = s"/api/v${ctx.config.apiVersion}"

  def httpApp: Kleisli[IO, Request[IO], Response[IO]] = Router[IO](
    s"$apiBaseRouteName/morin" -> HttpRoutes.of[IO] {
      case req@POST -> Root / "create" => controllers.morin.Create(req)
      case req@GET -> Root / "pendejo" => Ok("UN POCOOOOOOO")
    },
    s"$apiBaseRouteName/nodes" ->  HttpRoutes.of[IO]{
      case req@POST -> Root / "delete" / nodeId => controllers.nodes.Delete(nodeId)
      case req@POST -> Root / "start" / nodeId => controllers.nodes.Started(req,nodeId)
//
      case req@GET -> Root / nodeId => GetContainer(nodeId)
      case req@GET -> Root  => Ok()
//
      case req@POST -> Root  => for {
        payload     <- req.as[CreateCacheNode]
//
        hostLogPath = req.headers.get(CIString("Host-Log-Path")).map(_.head.value).getOrElse(ctx.config.hostLogPath)
        maxAR       = req.headers.get(CIString("Max-AR")).flatMap(_.head.value.toIntOption).getOrElse(ctx.config.maxAr)
//
        res         <- controllers.nodes.Create(payload,hostLogPath = hostLogPath,maxAR = maxAR)
      } yield res
//      case req@POST -> Root / "v2" =>controllers.nodes.Create.v2(req)
    },
  s"$apiBaseRouteName" -> HttpRoutes.of[IO]{
      case req@GET -> Root /"events" => controllers.GetEventsController()
      case req@GET -> Root / "stats" => controllers.StatsController()
      case req@POST -> Root / "pool" => controllers.pool.Create(req)
      case req@POST -> Root / "reset"=> for {
        _   <- ctx.state.update{ s=>
          s.copy(
            events = s.events.filter{
              case _:Events.RemovedService=> true
              case _:Events.StartedService => true
              case _: AddedService => true
              case _:Events.CreatedPool => true
            }
          )
        }
        res <- NoContent()
      } yield res
    },
  ).orNotFound

  def run(): IO[Unit] =
    BlazeServerBuilder[IO](global)
      .bindHttp(ctx.config.port,ctx.config.host)
      .withHttpApp(httpApp = httpApp)
      .withMaxConnections(ctx.config.maxConnections)
      .withBufferSize(ctx.config.bufferSize)
      .withResponseHeaderTimeout(ctx.config.responseHeaderTimeoutMs milliseconds)
      .serve
      .compile
      .drain
}

object HttpServer {

  def apply()(implicit ctx:NodeContext) = new HttpServer()


//      .withHttpApp("",)

}

package mx.cinvestav.server

import cats.data.Kleisli
import cats.effect.IO.{IOCont, Uncancelable}
import cats.implicits._
import cats.effect._
import cats.effect.std.Semaphore
import mx.cinvestav.Declarations.Payloads.CreateStorageNode
import mx.cinvestav.commons.events
import mx.cinvestav.commons.events.ServiceReplicator.AddedStorageNode
import mx.cinvestav.commons.types.NodeReplicationSchema
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


class HttpServer(s:Semaphore[IO])(implicit ctx:NodeContext){

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
        serviceTimeStart <- IO.monotonic.map(_.toNanos)
        headers          = req.headers
        eventId         = headers.get(CIString("Event-Id")).map(_.head.value).getOrElse("")
        _                <- s.acquire
        payload          <- req.as[NodeReplicationSchema]
        res              <- controllers.nodes.Createv3(payload = payload)
        _                <- s.release
        serviceTimeEnd   <- IO.monotonic.map(_.toNanos)
        serviceTime      = serviceTimeEnd - serviceTimeStart
        _                <- ctx.logger.debug(s"CREATE_NODE $eventId $serviceTime")
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
              case _: AddedStorageNode => true
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

  def apply(s:Semaphore[IO])(implicit ctx:NodeContext) = new HttpServer(s=s)

//      .withHttpApp("",)

}

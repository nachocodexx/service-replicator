package mx.cinvestav.controllers.morin
import cats.implicits._
import cats.effect._
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.events.Events
import mx.cinvestav.events.Events.AddedContainer
//
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
//
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
//
import mx.cinvestav.Declarations.Docker
import mx.cinvestav.helpers.Helpers
import mx.cinvestav.commons.docker

object Create {
  case class CreateNode(
                         nodeId:String,
                         image:String,
                         networkName:String,
                         ports:docker.Ports,
                         envs:Map[String,String],
                         labels:Map[String,String],
                         workers:Int,
                         volumes:Map[String,String],
//                         metadata:Map[String,String] = Map.empty[String,String]
                       )



  def apply(req:Request[IO])(implicit ctx:NodeContext)= for {
//    _              <- ctx.logger.debug("BEFORE_CREATE_NODE")
    serviceTimeStart <- IO.monotonic.map(_.toNanos)
    payload          <- req.as[CreateNode]
    nodeId           = payload.nodeId
    image            = payload.image
    envs             = payload.envs
    labels           = payload.labels
    ports            = payload.ports
    networkName      = payload.networkName
    volumes          = payload.volumes

    _              <- ctx.logger.debug(s"CREATEING_NODE $nodeId")
    containerId    <- Helpers.createNode(
      nodeId       = nodeId,
      image        = image,
      networkName  = networkName,
      ports        = ports,
      environments = envs,
      labels       = labels,
      volumes      = volumes
    )
    serviceTime    <- IO.monotonic.map(_.toNanos).map(_ - serviceTimeStart)
//    now            =
    jsonRes        = Json.obj(
      "containerId"-> containerId.asJson,
      "serviceTime"-> serviceTime.asJson
    )
    addedService   = AddedContainer(
      nodeId             =  containerId,
      ipAddress          = containerId,
      hostname           = containerId,
      ports              = ports::Nil,
      serviceTimeNanos   = serviceTime,
      labels             = labels,
      image              = docker.Image.fromString(image),
      envs               = envs,
    )
    _              <- Events.saveEvents(events = addedService::Nil)
    res            <- Ok(jsonRes)
  } yield res

}

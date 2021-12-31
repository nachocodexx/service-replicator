package mx.cinvestav

import cats.Order
import cats.effect._
import cats.effect.std.Semaphore
import com.github.dockerjava.api.model.HostConfig
import io.circe.{Decoder, Encoder, HCursor}
import mx.cinvestav.commons.events.{AddedNode, Del, Downloaded, EventX, Evicted, Get, Missed, Push, Put, RemovedNode, Replicated, Uploaded}
import mx.cinvestav.commons.types.NodeX
import mx.cinvestav.config.DefaultConfig
import mx.cinvestav.events.Events.AddedService
import org.http4s.client.Client
//import mx.cinvestav.events.Events.GetInProgress
import org.typelevel.log4cats.Logger
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._

object Declarations {

  object Payloads {
    case class DockerImage(name:String,tag:String)
    case class CreateCacheNode(
                                cacheSize:Int,
                                policy:String,
                                networkName:String,
                                environments:Map[String,String]= Map.empty[String,String],
                                image:DockerImage = DockerImage("nachocode/cache-node","v2")
                              )
    case class CreateCacheNodeResponse(
                                        nodeId:String,
                                        url:String,
                                        milliSeconds:Long
                                      )
    case class CreateCacheNodeResponseV2(
                                        nodeId:String,
                                        url:String,
                                        milliSeconds:Long,
                                        ip:String,
                                        port:Int,
                                        dockerPort:Int
                                      )
  }



  object Implicits {
    implicit val nodeXOrder: Order[NodeX] = new Order[NodeX] {
      override def compare(x: NodeX, y: NodeX): Int = Order.compare[Int](x.nodeId.hashCode,y.nodeId.hashCode)
    }

    implicit val eventDecoderX:Decoder[EventX] = (hCursor:HCursor) =>{
      for {
        eventType <- hCursor.get[String]("eventType")
        decoded   <- eventType match {
          case "UPLOADED" => hCursor.as[Uploaded]
          case "EVICTED" => hCursor.as[Evicted]
          case "DOWNLOADED" => hCursor.as[Downloaded]
          case "PUT" => hCursor.as[Put]
          case "GET" => hCursor.as[Get]
          case "DEL" => hCursor.as[Del]
          case "PUSH" => hCursor.as[Push]
          case "MISSED" => hCursor.as[Missed]
          case "ADDED_NODE" => hCursor.as[AddedNode]
          case "REMOVED_NODE" => hCursor.as[RemovedNode]
          case "REPLICATED" => hCursor.as[Replicated]
          case "ADDED_SERVICE" => hCursor.as[AddedService]
        }
      } yield decoded
    }
    implicit val eventXEncoder: Encoder[EventX] = {
      case p: Put => p.asJson
      case g: Get => g.asJson
      case d: Del => d.asJson
      case push:Push => push.asJson
      case x: Uploaded => x.asJson
      case y: Downloaded => y.asJson
      case y: AddedNode => y.asJson
      case rmn: RemovedNode => rmn.asJson
      case x:Evicted => x.asJson
      case r: Replicated => r.asJson
      case m: Missed => m.asJson
      case m: AddedService => m.asJson
//      case sd:Transfered => sd.asJson
//      case sd:GetInProgress => sd.asJson
    }
  }
  case class NodeState(
                        ip:String,
                        basePort:Int=10000,
                        createdNodes:List[String],
                        events:List[EventX],
                        s:Semaphore[IO]
                      )
  case class NodeContext(
                          config:DefaultConfig,
                          state: Ref[IO,NodeState],
                          dockerClientX: DockerClientX,
                          logger:Logger[IO],
                          client:Client[IO]
                        )
  object Docker {

    case class CreateContainerData(name:Name,image:Image,hostname: Hostname,envs:Envs,hostConfig: HostConfig)
    case class Envs(values:Map[String,String]){
      def build:Array[String] = {
        values.toArray.map{
          case (key, value) => s"$key=$value"
        }
      }
    }
    case class Hostname(value:String){
      def build:String = value
    }
    case class Name(value:String){
      def build:String = value
    }
    case class Image(repository:String,tag:Option[String]= Some("latest")){
      def build:String = tag match {
        case Some(tag) => s"$repository:$tag"
        case None => s"$repository:latest"
      }
    }
  }

}

package mx.cinvestav

import cats.implicits._
import cats.Order
import cats.effect._
import cats.effect.std.Semaphore
//
import com.github.dockerjava.api.model.HostConfig
import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder, HCursor}
import mx.cinvestav.commons.events.{AddedNode, Del, Downloaded, EventX, Evicted, Get, Missed, Push, Put, RemovedNode, Replicated, Uploaded}
import mx.cinvestav.commons.types.{NodeId, NodeX}
import mx.cinvestav.config.DefaultConfig
import mx.cinvestav.events.Events.AddedService
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import mx.cinvestav.Declarations.Payloads.CreateCacheNode

object Declarations {

  object Payloads {
    case class DockerImage(name:String,tag:String)
    object DockerImage{
      def fromString(x:String) = {
        val xx   = x.split(':')
        val name = xx(0)
        val tag  = xx(1)
        DockerImage(name,tag)
      }
    }
    case class CreateCacheNode(
                                cacheSize:Int,
                                policy:String,
                                networkName:String,
                                environments:Map[String,String]= Map.empty[String,String],
                                image:Docker.Image = Docker.Image("nachocode/cache-node","v2".some)
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
                                        dockerPort:Int,
                                        containerId:String=""
                                      )
  }



  object Implicits {
    implicit val dockerImageDecoder:Decoder[Docker.Image] = new Decoder[Docker.Image] {
      override def apply(c: HCursor): Result[Docker.Image] = for {
        tag        <- c.get[String]("tag")
        repository <- c.get[String]("repository")
        image      = Docker.Image(repository, Option.when(tag.nonEmpty)(tag))
      } yield image
    }
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
                        s:Semaphore[IO],
                        pendingNodeCreation:List[String]=Nil
                      )
  case class NodeContext(
                          config:DefaultConfig,
                          state: Ref[IO,NodeState],
                          dockerClientX: DockerClientX,
                          logger:Logger[IO],
                          client:Client[IO]
                        )


//  case class CreateNodePayload(
//                                nodeId:String = NodeId.auto("cache-").value,
//                                poolId:String ="cache-pool-0",
//                                cachePolicy:String = "LFU",
//                                host:String = "0.0.0.0",
//                                port:Int = 6000,
//                                cacheSize:Int = 10,
//                                networkName:String="my-net",
//                                environments:Map[String,String] = Map.empty[String,String],
//                                hostLogPath:String = "/test/logs",
//                                dockerImage:DockerImage = DockerImage("nachocode/cache-node","v2")
//                              )
//

  case class CreateCacheNodeCfg(
                                 nodeId:String ,
                                 poolId:String,
                                 cachePolicy:String ,
                                 cacheSize:Int,
                                 environments:Map[String,String] = Map.empty[String,String],
                                 memory:Long =1000000000,
                                 networkName:String="my-net",
                                 hostLogPath:String = "/test/logs",
                                 hostStoragePath:String = "/test/sink",
                                 dockerImage:Docker.Image = Docker.Image("nachocode/cache-node","v2".some),
                                 volumes:Map[String,String] = Map.empty[String,String]
                               )
    object CreateCacheNodeCfg {
      def fromCreateCacheNode(nodeId: NodeId,payload:CreateCacheNode)(implicit ctx:NodeContext) = CreateCacheNodeCfg(
        nodeId = nodeId.value,
        poolId = ctx.config.poolId,
        cachePolicy = payload.policy,
        cacheSize= payload.cacheSize,
        networkName = payload.networkName,
        environments = payload.environments,
      )
    }
  object Docker {

    case class Ports(host:Int,docker:Int)
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
    object Image {
      def fromString(x:String) = {
        val xx   = x.split(':')
        val name = xx(0)
        if(xx.length == 1) Image(name,None)
        else{
          val tag  = xx.lastOption
          Image(name,tag)
        }
      }
    }
  }

}

package mx.cinvestav.config

import cats.implicits._
import cats.effect._
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.config
import mx.cinvestav.events.Events.AddedService
import org.http4s.{Request, Uri,Method}
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.circe.CirceEntityEncoder._

case object DockerMode extends Enumeration {
  type DockerMode = String
  val LOCAL, SWARM = Value

  def fromString(x:String): config.DockerMode.Value = x match {
    case "LOCAL" => LOCAL
    case "SWARM" => SWARM
  }
}

sealed trait INode {
  def hostname:String
  def port:Int
}

case class Monitoring(hostname:String,port:Int) extends INode {
  def addNode(addedService: AddedService)={

  }
}
case class Pool(hostname:String,port:Int,inMemory:Boolean) extends INode{
  def addNode(addedService: AddedService)(implicit ctx:NodeContext)= {
      val uri     = Uri.unsafeFromString(s"http://$hostname:$port/api/v${ctx.config.apiVersion}/nodes/add")
      val request = Request[IO](
        method = Method.POST,
        uri = uri
      ).withEntity(addedService)
//      val addCacheNode = Add
      ctx.client.status(request)
  }
}
case class DefaultConfig(
                        nodeId:String,
                        poolId:String,
                        host:String,
                        port:Int,
                        dockerSock:String,
                        basePort:Int,
                        apiVersion:Int,
                        dockerMode:DockerMode.DockerMode = "LOCAL",
                        pool:Pool,
                        cachePool:Pool,
                        cloudEnabled:Boolean,
                        baseTotalStorageCapacity:Long,
                        baseCachePolicy:String,
                        baseCacheSize:Int,
                        initNodes:Int,
                        hostLogPath:String,
                        maxAr:Int,
                        dockerNetworkName:String= "my-net",
                        autoNodeId:Boolean,
                        hostStoragePath:String,
                        daemonDelayMs:Long,
                        monitoring: Monitoring,
                        daemonEnabled:Boolean
//                        rabbitmq: RabbitMQClusterConfig
                        )

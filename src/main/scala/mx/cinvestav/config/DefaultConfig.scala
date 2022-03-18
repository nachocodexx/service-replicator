package mx.cinvestav.config

import fs2.Stream
import mx.cinvestav.commons.events.ServiceReplicator.AddedService
import mx.cinvestav.commons.types.Monitoring.PoolInfo
import org.http4s.{Header, Headers}
import org.typelevel.ci.CIString
//
import cats.implicits._
import cats.effect._
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.config
import org.http4s.{Method, Request, Response, Uri}
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.circe.CirceEntityDecoder._

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

case class DataReplicator(hostname:String,port:Int) extends INode

case class Monitoring(hostname:String,port:Int) extends INode {
  def  getInfo()(implicit ctx:NodeContext): Stream[IO, PoolInfo] = {
    val apiVersion = s"v${ctx.config.apiVersion}"
    val uri     = Uri.unsafeFromString(s"http://$hostname:$port/api/$apiVersion/pool/info")
    val request = Request[IO](
      method = Method.GET,
      uri = uri
    )
    ctx.client.stream(req = request).evalMap{
      x=>
        x.as[PoolInfo].handleErrorWith(e=>ctx.logger.error(e.getMessage) *> PoolInfo.empty.pure[IO])
    }
  }
}
case class Pool(hostname:String,port:Int,inMemory:Boolean) extends INode{
  def addNode(addedService: AddedService)(implicit ctx:NodeContext)= {
      val uri     = Uri.unsafeFromString(s"http://$hostname:$port/api/v${ctx.config.apiVersion}/nodes/add")
      val request = Request[IO](
        method = Method.POST,
        uri = uri
      ).withEntity(addedService)
      ctx.client.status(request)
  }
  def updateNodeNetworkCfg(nodeId:String, publicPort:Int, ipAddress:String)(implicit ctx:NodeContext) = {
    val uri = Uri.unsafeFromString(s"http://$hostname:$port/api/v${ctx.config.apiVersion}/nodes/$nodeId/network-cfg")
    val request = Request[IO](
      method  = Method.POST,
      uri     = uri,
      headers = Headers(
        Header.Raw(CIString("Public-Port"),publicPort.toString),
        Header.Raw(CIString("Ip-Address"),ipAddress),
      )
    )
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
                        daemonEnabled:Boolean,
                        dataReplicator: DataReplicator,
                        threshold:Double,
                        createNodeCoolDownMs:Int,
                        nodeIdPrefix:String
//                        rabbitmq: RabbitMQClusterConfig
                        )

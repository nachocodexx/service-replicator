package mx.cinvestav.helpers

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref, Resource}
import cats.implicits._
import com.github.dockerjava.api.model._
import io.circe.generic.auto._
import io.circe.syntax._
import mx.cinvestav.Declarations.Docker
//

import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.Declarations.Payloads._
import mx.cinvestav.commons.types.NodeId
import mx.cinvestav.config.INode
import mx.cinvestav.events.Events.AddedService
import org.http4s.{EntityEncoder, Headers, Method, Request, Uri}
import retry._
import retry.implicits._

import java.net.DatagramSocket
import scala.concurrent.duration._
import scala.language.postfixOps
//
//import collection.mutable._

object Helpers {

  import java.net.ServerSocket


  def addNode(n:INode)(addedService: AddedService,headers:Headers = Headers.empty)(implicit ctx:NodeContext,e:EntityEncoder[IO,AddedService])= {
    val hostname   = n.hostname
    val port       = n.port
    val apiVersion =  ctx.config.apiVersion
    val uri = Uri.unsafeFromString(s"http://$hostname:$port/api/v$apiVersion/nodes/add")
    val request = Request[IO](
      method = Method.POST,
      uri = uri,
      headers = headers
    ).withEntity(addedService)
    //      val addCacheNode = Add
    ctx.client.status(request)
  }
    def getAnAvailablePort(initPort:Int,maxTries:Int = 1000,usedPorts:List[Int]): Int = {
      val it = List.fill(maxTries)(initPort).zipWithIndex.map{
        case (_initPort,index) => _initPort+index
      }.filterNot(x=>usedPorts.contains(x)).iterator
      //  ______________________________________________
      //  _________________________________________
      var isAvailable = false
      var lastPort    = it.next()
      do {
        isAvailable     = Helpers.isPortAvailable(lastPort).unsafeRunSync()
        if(!isAvailable)
          lastPort   = it.next()
      } while(it.hasNext && !isAvailable && !usedPorts.contains(lastPort))
      //    {
      //    }
      //  ___________________________________________________
      lastPort
    }

  def isPortAvailable(port: Int): IO[Boolean] = {
    val ssRes = Resource.make{
      IO.delay(new ServerSocket(port))
    }(_.close().pure[IO])
    val dsRes = Resource.make{
      IO.delay(new DatagramSocket(port))
    }(_.close().pure[IO])
    (
      for {
        ss <- ssRes
        ds <- dsRes
      } yield true
    ).use(flag => flag.pure[IO]).recover{
      case _ => false
    }
//    if (port < 1025 || port > 65535) throw new IllegalArgumentException("Invalid start port: " + port)
//    var ss:ServerSocket = null
//    var ds:DatagramSocket = null
//    try {
//       ss = new ServerSocket(port)
//      val ds = new DatagramSocket(port)
//      ss.setReuseAddress(true)
//      ds.setReuseAddress(true)
//      return true
//    } catch {
//      case e: IOException =>
//    } finally {
//      if (ds != null) ds.close()
//      if (ss != null) try ss.close()
//      catch {
//        case e: IOException =>
//
//        /* should not be thrown */
//      }
//    }
//    false
  }


  case class CreateNodePayload(
                                nodeId:String = NodeId.auto("cache-").value,
                                poolId:String ="cache-pool-0",
                                cachePolicy:String = "LFU",
                                host:String = "0.0.0.0",
                                port:Int = 6000,
                                cacheSize:Int = 10,
                                networkName:String="my-net",
                                environments:Map[String,String] = Map.empty[String,String],
                                hostLogPath:String = "/test/logs",
                                dockerImage:DockerImage = DockerImage("nachocode/cache-node","v2")
                              )
  object CreateNodePayload {
    def fromCreateCacheNode(x:CreateCacheNode) = CreateNodePayload(
      poolId = "",
      cachePolicy = x.policy,
      port = 6666,
      cacheSize = x.cacheSize,
      networkName = x.networkName,
      environments = x.environments,
      dockerImage = x.image

    )
  }


  def createNodeV2(x: CreateNodePayload)(implicit ctx:NodeContext) = createCacheNode(
    nodeId = x.nodeId,
    poolId= x.poolId,
    cachePolicy = x.cachePolicy,
    host=x.host,
    port=x.port,
    cacheSize = x.cacheSize,
    networkName = x.networkName,
    environments = x.environments,
    hostLogPath = x.hostLogPath,
    dockerImage = x.dockerImage).map(y=>(y,x))




  def createCacheNodeV3(
                         nodeId:String = NodeId.auto("cache-").value,
                         poolId:String ="cache-pool-0",
                         cachePolicy:String = "LFU",
                         host:String = "0.0.0.0",
                         cacheSize:Int = 10,
                         networkName:String="my-net",
                         environments:Map[String,String] = Map.empty[String,String],
                         hostLogPath:String = "/test/logs",
                         dockerImage:DockerImage = DockerImage("nachocode/cache-node","ex0")
                       )(implicit ctx:NodeContext): IO[String] = {
    for {
      currentState  <- ctx.state.get
      containerName = Docker.Name(nodeId)
      image         = Docker.Image(dockerImage.name,Some(dockerImage.tag))
      dockerLogPath = "/app/logs"
      //
      envs          = Docker.Envs(
        Map(
          "NODE_HOST" -> host,
          //          "NODE_PORT" ->  port.toString,
          "NODE_PORT" ->  "6666",
          "CACHE_POLICY"->cachePolicy,
          "CACHE_SIZE" -> cacheSize.toString,
          "NODE_ID" -> nodeId,
          "POOL_ID" -> poolId,
          "LOG_PATH" ->  dockerLogPath
        )  ++ environments
      )

      x <- ctx.dockerClientX.createService(
        name = containerName,
        image = image,
        labels =  Map("test"->"test"),
        envs   = envs,
        dockerLogPath = dockerLogPath,
        hostLogPath = hostLogPath,
        networkName = networkName
      )
      containerId  = x.getId
      _ <- ctx.logger.debug(s"CONTAINER_ID $containerId")
      _ <- ctx.state.update(s=>s.copy(createdNodes =s.createdNodes:+containerId))
    } yield containerId

  }




  def createCacheNodeV2(
                       nodeId:String = NodeId.auto("cache-").value,
                       networkName:String="my-net",
                       environments:Map[String,String] = Map.empty[String,String],
                       hostLogPath:String = "/test/logs",
                       hostStoragePath:String = "/test/sink",
                       dockerImage:DockerImage = DockerImage("nachocode/cache-node","v2")
                     )(implicit ctx:NodeContext): IO[String] = {
    for {
      currentState  <- ctx.state.get
      ports2        = new Ports()
      exposedPort2 = new ExposedPort(ctx.config.basePort)
        _           <- IO.delay{ ports2.bind(exposedPort2, Ports.Binding.empty() )}
//      x = ports2.getBindings.get(exposedPort2)
      containerName = Docker.Name(nodeId)
      image         = Docker.Image(dockerImage.name,Some(dockerImage.tag))
      hostname      = Docker.Hostname(nodeId)
      dockerLogPath = "/app/logs"
      storagePath   = "/app/data"
      //
      envs          = Docker.Envs(
        Map(
          "NODE_ID" -> nodeId,
          "POOL_ID" -> ctx.config.pool.hostname,
          "NODE_HOST" -> "0.0.0.0",
          "NODE_PORT" ->  ctx.config.basePort.toString,
//
          "CLOUD_ENABLED" -> ctx.config.cloudEnabled.toString,
//
          "CACHE_POOL_HOSTNAME" -> ctx.config.cachePool.hostname,
          "CACHE_POOL_PORT"-> ctx.config.cachePool.port.toString,
//
          "POOL_HOSTNAME" -> ctx.config.pool.hostname,
          "POOL_PORT" -> ctx.config.pool.port.toString,
//
          "SERVICE_REPLICATOR_HOSTNAME" -> ctx.config.nodeId,
          "SERVICE_REPLICATOR_PORT" -> ctx.config.port.toString,
//
          "CACHE_POLICY"->ctx.config.baseCachePolicy,
          "CACHE_SIZE" -> ctx.config.baseCacheSize.toString,
          "TOTAL_STORAGE_CAPACITY" -> ctx.config.baseTotalStorageCapacity.toString,
          "IN_MEMORY" -> ctx.config.pool.inMemory.toString,
          "STORAGE_PATH" -> storagePath,
//
          "MONITORING_DELAY_MS" -> "1000",
          "API_VERSION" ->ctx.config.apiVersion.toString,

//
          "LOG_PATH" ->  dockerLogPath
        )  ++ environments
      )
      _              <- ctx.logger.debug(envs.asJson.toString())

      storageVol     = new Volume(storagePath)
      logVol         = new Volume(dockerLogPath)
//      Logging binding
      logVolBind     = new Bind(hostLogPath,logVol)
//      Storage binging
      storageVolBind = new Bind(hostStoragePath,storageVol)
//
      binds          = new Binds(logVolBind,storageVolBind)
      hostConfig     = new HostConfig()
        .withPortBindings(ports2)
        .withNetworkMode(networkName)
        .withBinds(binds)

      containerId <- ctx.dockerClientX.createContainer(
        name=containerName,
        image=image,
        hostname=hostname,
        envs=envs,
        hostConfig = hostConfig
      )
        .map(
//          _.withExposedPorts(exposedPort)
          _.withExposedPorts(exposedPort2)
        )
        .map(_.exec()).map(_.getId)
      //        .onError{ e=>
      //          ctx.logger.error(s"CREATE_CONTAINER_ERROR ${e.getMessage}")
      //        }
      _ <- ctx.dockerClientX.startContainer(containerId).map(_.exec())
        .onError{e =>
          ctx.logger.error(s"START_CONTAINER_ERROR ${e.getMessage}") *> ctx.dockerClientX.deleteContainer(containerId).map(_.exec()).void
        }
//      _ <- ctx.state.update(s=>s.copy(createdNodes =s.createdNodes:+containerId))
    } yield containerId

  }





  def createCacheNode(
                       nodeId:String = NodeId.auto("cache-").value,
                       //                       currentNodesLength:Int,
                       poolId:String ="cache-pool-0",
                       cachePolicy:String = "LFU",
                       host:String = "0.0.0.0",
                       port:Int = 6000,
                       cacheSize:Int = 10,
                       networkName:String="my-net",
                       environments:Map[String,String] = Map.empty[String,String],
                       hostLogPath:String = "/test/logs",
                       dockerImage:DockerImage = DockerImage("nachocode/cache-node","ex0")
                     )(implicit ctx:NodeContext): IO[String] = {
    for {
      currentState  <- ctx.state.get
//      lastPort      = basePort
//      newPort       = lastPort + currentNodesLength
      exposedPort   = ExposedPort.tcp(port)
      bindPort      = Ports.Binding.bindPort(port)
      ports         = new Ports()
      _ <- IO.delay{
        ports.bind(exposedPort,bindPort)
      }
//
      containerName = Docker.Name(nodeId)
      image         = Docker.Image(dockerImage.name,Some(dockerImage.tag))
      hostname      = Docker.Hostname(nodeId)
      dockerLogPath = "/app/logs"
//
      envs          = Docker.Envs(
        Map(
          "NODE_HOST" -> host,
          "NODE_PORT" ->  port.toString,
          "CACHE_POLICY"->cachePolicy,
          "CACHE_SIZE" -> cacheSize.toString,
          "NODE_ID" -> nodeId,
          "POOL_ID" -> poolId,
          "LOG_PATH" ->  dockerLogPath
        )  ++ environments
      )
      _ <- ctx.logger.debug(envs.asJson.toString())

      logVol     = new Volume(dockerLogPath)
      logVolBind = new Bind(hostLogPath,logVol)
      hostConfig    = new HostConfig()
        .withPortBindings(ports)
        .withNetworkMode(networkName)
        .withBinds(new Binds(logVolBind))

      containerId <- ctx.dockerClientX.createContainer(
              name=containerName,
              image=image,
              hostname=hostname,
              envs=envs,
              hostConfig = hostConfig
      )
        .map(
          _.withExposedPorts(exposedPort)
        )
        .map(_.exec()).map(_.getId)
      _ <- ctx.dockerClientX.startContainer(containerId).map(_.exec())
        .onError{e =>
          ctx.logger.error(s"START_CONTAINER_ERROR ${e.getMessage}") *> ctx.dockerClientX.deleteContainer(containerId).map(_.exec()).void
        }
      _ <- ctx.state.update(s=>s.copy(createdNodes =s.createdNodes:+containerId))
    } yield containerId

  }
  def createContainerRetry(data:Docker.CreateContainerData)(implicit ctx:NodeContext) = {
    Ref[IO].of(data).flatMap{ ref=>
      val policy = RetryPolicies.limitRetries[IO](maxRetries = 10) join RetryPolicies.exponentialBackoff[IO](1 seconds)
      val action = ref.get.flatMap(ctx.dockerClientX.createContainerV2).retryingOnAllErrors(
        policy = policy,
        onError = (e:Throwable,d:RetryDetails)=>
          ctx.logger.error(e.getMessage) *> ref.set(data)
      )
      IO.unit
    }
  }

}

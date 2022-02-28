package mx.cinvestav.helpers

import cats.data.NonEmptyList
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref, Resource}
import cats.implicits._
import com.github.dockerjava.api.model._
import io.circe.generic.auto._
import io.circe.syntax._
import mx.cinvestav.Declarations.{CreateCacheNodeCfg, CreatedNode, Docker}
import mx.cinvestav.commons.docker
import mx.cinvestav.config.DockerMode
import org.http4s.Status

import scala.util.Random
//

import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.Declarations.Payloads._
import mx.cinvestav.commons.types.NodeId
import mx.cinvestav.config.INode
import mx.cinvestav.events.Events.AddedService
import org.http4s.{EntityEncoder, Headers, Method, Request, Uri,Response}
import retry._
import retry.implicits._

import java.net.DatagramSocket
import scala.concurrent.duration._
import scala.language.postfixOps
import java.net.ServerSocket
//import orrg
import retry._
import retry.implicits._

object Helpers {



  def createCacheNode(cfg: CreateCacheNodeCfg)(implicit ctx:NodeContext) = {
    val dockerMode      = DockerMode.fromString(ctx.config.dockerMode)
    if(dockerMode == DockerMode.SWARM) createCacheNodeSwarm(cfg) else createCacheNodeLocal(cfg)
  }
  def createCacheNodeLocal(cfg: CreateCacheNodeCfg)(implicit ctx:NodeContext): IO[CreatedNode] = {
    val program = for {
      _                <- IO.unit
      nodeId           = cfg.nodeId
      networkName      = cfg.networkName
      environments     = cfg.environments
      hostLogPath      = cfg.hostLogPath
      hostStoragePath  = cfg.hostStoragePath
      image            = cfg.dockerImage
      cacheSize        = cfg.cacheSize
      cachePolicy      = cfg.cachePolicy
      memory           = cfg.memoryBytes
//
      currentState     <- ctx.state.get
      ports2           = new Ports()
      exposedPort2     = ExposedPort.tcp(ctx.config.basePort)
      emptyPortBinding = Ports.Binding.empty()
        _              <- IO.delay{ ports2.bind(exposedPort2, emptyPortBinding )}
      containerName    = docker.Name(nodeId)
//      image            = Docker.Image(dockerImage.,Some(dockerImage.tag))
      hostname         = docker.Hostname(nodeId)
//
      dockerLogPath    = "/app/logs"
//
      storagePath      = "/app/data"
      //
      envs          = docker.Envs(
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
          "CACHE_POLICY"-> cachePolicy,
          "CACHE_SIZE" -> cacheSize.toString,
          "TOTAL_STORAGE_CAPACITY" -> ctx.config.baseTotalStorageCapacity.toString,
          "IN_MEMORY" -> ctx.config.pool.inMemory.toString,
          "STORAGE_PATH" -> storagePath,
          //
          "MONITORING_DELAY_MS" -> "1000",
          "API_VERSION" ->ctx.config.apiVersion.toString,
          "BUFFER_SIZE" -> "65536",
          "LOG_PATH" ->  dockerLogPath
        )  ++ environments
      )
      storageVol     = new Volume(storagePath)
      logVol         = new Volume(dockerLogPath)
//      Logging binding
      logVolBind     = new Bind(hostLogPath,logVol)
//      Storage binging
      storageVolBind = new Bind(s"$hostStoragePath/$nodeId",storageVol)
//
      binds          = new Binds(logVolBind,storageVolBind)
      hostConfig     = new HostConfig()
        .withPortBindings(ports2)
        .withNetworkMode(networkName)
        .withBinds(binds)
        .withMemory(memory)
        .withCpuPercent(20)

      containerId <- ctx.dockerClientX.createContainer(
        name=containerName,
        image=image,
        hostname=hostname,
        envs=envs,
        hostConfig = hostConfig,
        labels = Map(
          "type"->"storage-node",
          "nacho" -> ""
        )
      )
        .map(_.withExposedPorts(exposedPort2).exec()).map(_.getId)
        .onError{e=>ctx.logger.debug(e.getMessage)}

      _ <- ctx.dockerClientX.startContainer(containerId).map(_.exec())
        .onError{e =>
          ctx.logger.error(s"START_CONTAINER_ERROR ${e.getMessage}") *> ctx.dockerClientX.deleteContainer(containerId).map(_.exec()).void
        }
//      _ <- ctx.state.update(s=>s.copy(createdNodes =s.createdNodes:+containerId))
      createdNode = CreatedNode(serviceId = containerId,selectedSwarmNodeId = None)
    } yield createdNode
    program.onError(e=>ctx.logger.error(e.getMessage))

  }

  def createCacheNodeSwarm(cfg: CreateCacheNodeCfg,counter:Map[String,Int]=Map.empty[String,Int])(implicit ctx:NodeContext): IO[CreatedNode] = {
    val program = for {
      currentState      <- ctx.state.get
      nodeId            = cfg.nodeId
      poolId            = ""
      cachePolicy       = ctx.config.baseCachePolicy
      host              = "0.0.0.0"
      cacheSize         = ctx.config.baseCacheSize
      networkName       = cfg.networkName
      environments      = cfg.environments
      hostLogPath       = cfg.hostLogPath
      hostStoragePath   = cfg.hostStoragePath
      image             = cfg.dockerImage
      memoryBytes       = cfg.memoryBytes
      nanoCPUS          = cfg.nanoCPUS
      containerName     = docker.Name(nodeId)
      dockerLogPath     = "/app/logs"
      dockerStoragePath = "/app/data"
      //
      envs              = docker.Envs(
        Map(

          "NODE_ID" -> nodeId,
          "POOL_ID" -> ctx.config.pool.hostname,
          "NODE_HOST" -> host,
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
          "CACHE_POLICY"-> cachePolicy,
          "CACHE_SIZE" -> cacheSize.toString,
          "TOTAL_STORAGE_CAPACITY" -> ctx.config.baseTotalStorageCapacity.toString,
          "IN_MEMORY" -> ctx.config.pool.inMemory.toString,
          "STORAGE_PATH" -> dockerStoragePath,
          //
          "MONITORING_DELAY_MS" -> "1000",
          "API_VERSION" ->ctx.config.apiVersion.toString,
          "BUFFER_SIZE" -> ctx.config.bufferSize.toString,
          "LOG_PATH" ->  dockerLogPath
        )  ++ environments
      )

      logMount      = new Mount()
        .withType(MountType.BIND)
        .withSource(hostLogPath)
        .withTarget(dockerLogPath)
      storageMount  = new Mount()
        .withType(MountType.BIND)
        .withSource(s"$hostStoragePath/$nodeId")
        .withTarget(dockerStoragePath)

      myNetAttach = new NetworkAttachmentConfig()
         .withTarget(networkName)

//
      selectedNode <- ctx.config.loadBalancer match {
        case "ROUND_ROBIN" =>
          for {
            _              <- IO.unit
            nodeIds        = NonEmptyList.fromListUnsafe(ctx.config.swarmNodes)
            lb             = mx.cinvestav.commons.balancer.deterministic.RoundRobin(nodeIds)
            selectedNodeId = lb.balanceWith(nodeIds = nodeIds,counter = counter)
          } yield selectedNodeId
        case "PSEUDO_RANDOM" => for {
          _            <- IO.unit
          nodeIds      = NonEmptyList.fromListUnsafe(ctx.config.swarmNodes)
          lb           = mx.cinvestav.commons.balancer.deterministic.PseudoRandom(nodeIds = nodeIds)
          selectedNode = lb.balance
//            = ctx.config.swarmNodes(rnd.nextInt(ctx.config.swarmNodes.length))
        } yield selectedNode
      }
//

      _            <- ctx.logger.debug(s"SELECTED_NODE $selectedNode")

      x            <- ctx.dockerClientX.createService(
        name                     = containerName,
        image                    = image,
        labels                   = Map("type"->"storage-node"),
        envs                     = envs,
        mounts                   = logMount::storageMount::Nil,
        networkAttachmentConfigs = myNetAttach::Nil,
        constraints              = List(s"node.labels.nodeid==$selectedNode"),
        memoryBytes              = memoryBytes,
        nanoCPUS                 = nanoCPUS
      ).onError{ e=>
         ctx.logger.error(e.getMessage)
      }
      _         <- ctx.logger.debug(s"CREATE_SERVICE_RES $x")
      serviceId  = x.getId
      _ <- ctx.logger.debug(s"SERVICE_ID $serviceId")
      _ <- ctx.state.update(s=>s.copy(createdNodes =s.createdNodes:+serviceId))
      createdNode = CreatedNode(serviceId = serviceId,selectedSwarmNodeId = selectedNode.some)
    } yield createdNode
    program.onError{ e=>
      ctx.logger.debug(s"ERROR ${e.getMessage}")
    }

  }



  //_______________________________________________________________________________________________
//   create load balancer
  def createLB(
                index:Int,
                nodeId:String = NodeId.auto("pool").value,
                maxAR:Int = 5,
                maxRF:Int = 3,
                port:Int= 5000,
                environments:Map[String,String]=Map.empty[String,String]
              )(implicit ctx:NodeContext) = {
    for {
      _             <- ctx.logger.debug("CREATE_LB")
      ports2        = new Ports()
      exposedPort2  = new ExposedPort(port)
      _             <- IO.delay{ ports2.bind(exposedPort2, Ports.Binding.bindPort(port) )}
      containerName = docker.Name(nodeId)
      image         = docker.Image("nachocode/storage-pool",Some("v2") )
      hostname      = docker.Hostname(nodeId)
      dockerLogPath = "/app/logs"
      //
      envs          = docker.Envs(
        Map(
          "NODE_ID" -> nodeId,
          "NODE_HOST" -> "0.0.0.0",
          "NODE_PORT" -> port.toString,
          //
          "MAX_RF" -> maxRF.toString,
          "MAX_AR" -> maxAR.toString,
          "CLOUD_ENABLED" -> "true",
          //
          "HOST_LOG_PATH" -> ctx.config.hostLogPath,
          "LOG_PATH" ->  dockerLogPath,
          "RETURN_HOSTNAME" -> "true",
          //
          "DATA_REPLICATION_HOSTNAME"->s"dr-$index",
          "DATA_REPLICATION_PORT"->(port+2).toString,
          "DATA_REPLICATION_API_VERSION" -> ctx.config.apiVersion.toString,
          //
          "SYSTEM_REPLICATION_PROTOCOL"->"http",
          "SYSTEM_REPLICATION_IP"->s"sr-$index",
          "SYSTEM_REPLICATION_HOSTNAME" -> s"sr-$index",
          "SYSTEM_REPLICATION_PORT" -> (port-1).toString,
          //
          "UPLOAD_LOAD_BALANCER" -> "UF",
          "DOWNLOAD_LOAD_BALANCER"->"UF",
          "API_VERSION" ->ctx.config.apiVersion.toString,
        ) ++ environments
      )
      logVol         = new Volume(dockerLogPath)
      //      Logging binding
      logVolBind     = new Bind(ctx.config.hostLogPath,logVol)
      //
      binds          = new Binds(logVolBind)
      hostConfig     = new HostConfig()
        .withPortBindings(ports2)
        .withNetworkMode(ctx.config.dockerNetworkName)
        .withBinds(binds)

      containerId <- ctx.dockerClientX.createContainer(
        name=containerName,
        image=image,
        hostname=hostname,
        envs=envs,
        hostConfig = hostConfig,
        labels = Map("type"->"sr")
      )
        .map(_.withExposedPorts(exposedPort2))
        .map(_.exec()).map(_.getId)
      _ <- ctx.dockerClientX.startContainer(containerId).map(_.exec())
        .onError{e =>
          ctx.logger.error(s"START_CONTAINER_ERROR ${e.getMessage}") *> ctx.dockerClientX.deleteContainer(containerId).map(_.exec()).void
        }
    } yield ()
  }
// Create a data replicator
  def createDR(
                index:Int,
                nodeId:String = NodeId.auto("dr").value,
                replicationStrategy:String = "NONE",
                port:Int= 5000,
                environments:Map[String,String]=Map.empty[String,String]
              )(implicit ctx:NodeContext) = {
    for {
      _             <- ctx.logger.debug("CREATE_DR")
      ports2        = new Ports()
      exposedPort2  = new ExposedPort(port)
      //      _             <- IO.delay{ ports2.bind(exposedPort2, Ports.Binding.empty() )}
      _             <- IO.delay{ ports2.bind(exposedPort2, Ports.Binding.bindPort(port) )}
      containerName = docker.Name(nodeId)
      image         = docker.Image("nachocode/data-replicator",Some("v2") )
      hostname      = docker.Hostname(nodeId)
      dockerLogPath = "/app/logs"
      //
      envs          = docker.Envs(
        Map(
          "NODE_ID" -> nodeId,
          "POOL_ID" -> ctx.config.poolId,
          //
          "NODE_HOST" -> "0.0.0.0",
          "NODE_PORT" -> port.toString,
          //
          "VERSION" -> "0.0.1",
          "REPLICATION_STRATEGY" -> replicationStrategy,
          "API_VERSION" ->ctx.config.apiVersion.toString,
          "LOG_PATH" ->  dockerLogPath
        ) ++ environments
      )
      logVol         = new Volume(dockerLogPath)
      //      Logging binding
      logVolBind     = new Bind(ctx.config.hostLogPath,logVol)
      //
      binds          = new Binds(logVolBind)
      hostConfig     = new HostConfig()
        .withPortBindings(ports2)
        .withNetworkMode(ctx.config.dockerNetworkName)
        .withBinds(binds)

      containerId <- ctx.dockerClientX.createContainer(
        name=containerName,
        image=image,
        hostname=hostname,
        envs=envs,
        hostConfig = hostConfig,
        labels = Map("type"->"sr")
      )
        .map(_.withExposedPorts(exposedPort2))
        .map(_.exec()).map(_.getId)
      _ <- ctx.dockerClientX.startContainer(containerId).map(_.exec())
        .onError{e =>
          ctx.logger.error(s"START_CONTAINER_ERROR ${e.getMessage}") *> ctx.dockerClientX.deleteContainer(containerId).map(_.exec()).void
        }
    } yield ()
  }
//  Create service replicator
  def createSR(
                index:Int,
                nodeId:String = NodeId.auto("sr-").value,
                nodes:Int =0,
                port:Int= 5000,
                environments:Map[String,String]=Map.empty[String,String]
              )(implicit ctx:NodeContext) = {
    for {
      _            <- ctx.logger.debug("CREATE_SR")
      ports2        = new Ports()
      exposedPort2  = new ExposedPort(port)
      _             <- IO.delay{ ports2.bind(exposedPort2, Ports.Binding.bindPort(port) )}
      containerName = docker.Name(nodeId)
      image         = docker.Image("nachocode/system-rep",Some("v2") )
      hostname      = docker.Hostname(nodeId)
      dockerLogPath = "/app/logs"
      envs          = docker.Envs(
        Map(
          "NODE_ID" -> nodeId,
          "POOL_ID" -> ctx.config.pool.hostname,
          "NODE_HOST" -> "0.0.0.0",
          "NODE_PORT" -> port.toString,
          //
          "POOL_HOSTNAME" -> s"pool-$index",
          "POOL_PORT" -> s"${port+1}",
          "POOL_IN_MEMORY" -> "false",
          //
          "DATA_REPLICATOR_HOSTNAME"->s"dr-$index",
          "DATA_REPLICATOR_PORT"->(port+2).toString,
          //
          "MONITORING_HOSTNAME" -> s"monitoring-$index",
          "MONITORING_PORT" -> s"${port+3}",
          //
          "CACHE_POOL_HOSTNAME" -> s"cache-pool-${index}",
          "CACHE_POOL_PORT"-> s"${port+4}",
          "CACHE_POOL_IN_MEMORY"->"true",
          //
          "BASE_PORT" -> "6666",
          "BASE_TOTAL_STORAGE_CAPACITY" -> "1000000",
          "BASE_CACHE_POLICY" -> "LFU",
          "BASE_CACHE_SIZE" -> "10",
          "INIT_NODES" -> nodes.toString,
          "HOST_LOG_PATH" -> ctx.config.hostLogPath,
          //
          "DOCKER_SOCK" -> "unix:///app/src/docker.sock",
          "DOCKER_NETWORK_NAME"->ctx.config.dockerNetworkName,
          "DOCKER_MODE" -> "LOCAL",
          //
          "DAEMON_ENABLED" -> "true",
          "DAEMON_DELAY_MS" -> "5000",
          "CREATE_NODE_COOL_DOWN_MS" ->"40000",
          //
          "API_VERSION" ->ctx.config.apiVersion.toString,
          "LOG_PATH" ->  dockerLogPath,
          "AUTO_NODE_ID" -> ctx.config.autoNodeId.toString
        ) ++ environments
      )
      logVol         = new Volume(dockerLogPath)
      dockerVol      = new Volume("/app/src/docker.sock")
      //      Logging binding
      logVolBind     = new Bind(ctx.config.hostLogPath,logVol)
      dockerVolBind = new Bind("/var/run/docker.sock",dockerVol)
      //
      binds          = new Binds(logVolBind,dockerVolBind)
      hostConfig     = new HostConfig()
        .withPortBindings(ports2)
        .withNetworkMode(ctx.config.dockerNetworkName)
        .withBinds(binds)
      //    _______________________________________
      containerId <- ctx.dockerClientX.createContainer(
        name=containerName,
        image=image,
        hostname=hostname,
        envs=envs,
        hostConfig = hostConfig,
        labels = Map("type"->"sr")
      )
        .map(_.withExposedPorts(exposedPort2))
        .map(_.exec()).map(_.getId)
      //     ________________________________________
      _ <- ctx.dockerClientX.startContainer(containerId).map(_.exec())
        .onError{e =>
          ctx.logger.error(s"START_CONTAINER_ERROR ${e.getMessage}") *> ctx.dockerClientX.deleteContainer(containerId).map(_.exec()).void
        }
    } yield ()
  }

  //_______________________________________________________________________________________________

  def createMonitoring(
                        index:Int,
                        nodeId:String = NodeId.auto("pool").value,
                        port:Int= 5000,
                        environments:Map[String,String]=Map.empty[String,String]
                      )(implicit ctx:NodeContext) = {
    for {
      _             <- ctx.logger.debug("CREATE_MONITORING")
      ports2        = new Ports()
      exposedPort2  = new ExposedPort(port)
      //      _             <- IO.delay{ ports2.bind(exposedPort2, exposedPort2 )}
      _             <- IO.delay{ ports2.bind(exposedPort2, Ports.Binding.bindPort(port) )}

      //      _             <- IO.delay{ ports2.bind(exposedPort2, Ports.Binding.empty() )}
      containerName = docker.Name(nodeId)
      image         = docker.Image("nachocode/monitoring",Some("v2") )
      hostname      = docker.Hostname(nodeId)
      dockerLogPath = "/app/logs"
      //
      envs          = docker.Envs(
        Map(
          "NODE_ID" -> nodeId,
          "POOL_ID" -> ctx.config.poolId,
          "NODE_HOST" -> "0.0.0.0",
          "NODE_PORT" -> port.toString,
          "API_VERSION" ->ctx.config.apiVersion.toString,
          "LOG_PATH" ->  dockerLogPath
        ) ++ environments
      )
      logVol         = new Volume(dockerLogPath)
      //      Logging binding
      logVolBind     = new Bind(ctx.config.hostLogPath,logVol)
      //
      binds          = new Binds(logVolBind)
      hostConfig     = new HostConfig()
        .withPortBindings(ports2)
        .withNetworkMode(ctx.config.dockerNetworkName)
        .withBinds(binds)

      containerId <- ctx.dockerClientX.createContainer(
        name=containerName,
        image=image,
        hostname=hostname,
        envs=envs,
        hostConfig = hostConfig,
        labels = Map("type"->"monitoring")
      )
        .map(_.withExposedPorts(exposedPort2))
        .map(_.exec()).map(_.getId)
      _ <- ctx.dockerClientX.startContainer(containerId).map(_.exec())
        .onError{e =>
          ctx.logger.error(s"START_CONTAINER_ERROR ${e.getMessage}") *> ctx.dockerClientX.deleteContainer(containerId).map(_.exec()).void
        }
    } yield ()
  }
  def createNode(nodeId:String= "",ports:docker.Ports, image:String, networkName:String="my-net", environments:Map[String,String],volumes:Map[String,String]=Map.empty[String,String],
                 labels:Map[String,String]=Map.empty[String,String])(implicit ctx:NodeContext) =
    {
      for {
        _                <- IO.unit
        exportedPorts    = ExposedPort.tcp(ports.docker)
        bindPorts        = if(ports.host===0) Ports.Binding.empty() else Ports.Binding.bindIp(ports.host.toString)
        ports2           = new Ports(exportedPorts,bindPorts)
        containerName    = docker.Name(nodeId)
        dockerImage      = docker.Image.fromString(image)
        hostname         = docker.Hostname(nodeId)
//      _________________________________________________
        _binds           = volumes.map{
          case (hostBind, dockerBind) =>
            val vol = new Volume(dockerBind)
            new Bind(hostBind,vol)
        }.toList
        binds = new Binds(_binds:_*)
//      _________________________________________________
        hostConfig     = new HostConfig()
          .withPortBindings(ports2)
          .withNetworkMode(networkName)
          .withBinds(binds)
//      __________________________________________________
        containerId <- ctx.dockerClientX
          .createContainer(name=containerName, image=dockerImage,
          hostname=hostname,
          envs=docker.Envs(environments),
          hostConfig = hostConfig,
          labels = labels
        )
          .map(_.withExposedPorts(exportedPorts).exec()).map(_.getId)
          .onError(e=>ctx.logger.error(e.getMessage))
//      _________________________________________________
        _ <- ctx.dockerClientX.startContainer(containerId).map(_.exec()).onError{e =>
          ctx.logger.error(s"START_CONTAINER_ERROR ${e.getMessage}") *> ctx.dockerClientX.deleteContainer(containerId).map(_.exec()).void
        }
      } yield containerId}

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
      containerName = docker.Name(nodeId)
      image         = docker.Image(dockerImage.name,Some(dockerImage.tag))
      hostname      = docker.Hostname(nodeId)
      dockerLogPath = "/app/logs"
//
      envs          = docker.Envs(
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

//  def createNodeV2(x: CreateNodePayload)(implicit ctx:NodeContext) = createCacheNode(
//    nodeId = x.nodeId,
//    poolId= x.poolId,
//    cachePolicy = x.cachePolicy,
//    host=x.host,
//    port=x.port,
//    cacheSize = x.cacheSize,
//    networkName = x.networkName,
//    environments = x.environments,
//    hostLogPath = x.hostLogPath,
//    dockerImage = x.dockerImage).map(y=>(y,x))
//

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

    ctx.client.status(request).retryingOnFailuresAndAllErrors(
      wasSuccessful = (status:Status)=> (status.code == 204).pure[IO],
      onFailure     = (status:Status,rd:RetryDetails) => ctx.logger.error(s"ON_FAILURE $status $rd"),
      onError       = (e:Throwable,rd:RetryDetails) => ctx.logger.error(s"ON_ERROR ${e.getMessage} $rd"),
      policy = RetryPolicies.limitRetries[IO](10).join(RetryPolicies.exponentialBackoff(baseDelay = 1 seconds))
    )
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


}

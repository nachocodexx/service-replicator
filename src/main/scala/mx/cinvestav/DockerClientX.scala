package mx.cinvestav

import mx.cinvestav.Declarations.Docker._
import cats.effect._
import cats.implicits._
import com.github.dockerjava.api.command.{CreateServiceResponse, ListContainersCmd}
import com.github.dockerjava.api.model.{ContainerSpec, EndpointResolutionMode, EndpointSpec, ExposedPort, HostConfig, Mount, MountType, NetworkAttachmentConfig, PortConfig, Ports, ResourceRequirements, ResourceSpecs, ServicePlacement, ServiceSpec, TaskSpec}
import com.github.dockerjava.core.{DockerClientConfig, DockerClientImpl}
import com.github.dockerjava.transport.DockerHttpClient
import mx.cinvestav.Declarations.NodeContext
import retry._
import mx.cinvestav.commons.docker
import retry.implicits._
//impor
import collection.JavaConverters._




class DockerClientX(config: DockerClientConfig,httpClient: DockerHttpClient) {
    private val client = DockerClientImpl.getInstance(config, httpClient)



  def getIpAddressByContainerId(containerId:String, networkName:String): IO[Option[String]] = IO.delay{
    val x= client.listContainersCmd()
      .withNameFilter(
        List(containerId).asJava
      ).exec()
      .asScala
      .toList
      x.map(_.getNetworkSettings.getNetworks.get(networkName).getIpAddress)
      .headOption
  }
  def getIpAddressByServiceId(serviceId:String)(implicit ctx:NodeContext): IO[Option[String]] = {
    val services = getServiceById(serviceId=serviceId)
    for {
      ss  <- services
      _   <- ctx.logger.debug(s"SERVICES $ss")
      s     = ss.headOption
      endpoint = s.map(_.getEndpoint)
      _  <- ctx.logger.debug(s"ENDPOINT $endpoint")
      virtualIps = endpoint.map(_.getVirtualIPs.toList)
      _ <- ctx.logger.debug(s"VIPS $virtualIps")
      headVip = virtualIps.flatMap(_.headOption)
      ip = headVip.map(_.getAddr)
    } yield ip
//    services.map{ ss=>

//    }
  }


  def getPorts(containerId:String)= IO.delay{
    client.listContainersCmd()
      .withIdFilter(
        List(containerId).asJava
      ).exec().asScala.toList
      .map{ x=>
        x.getPorts
      }.headOption.map(x=>x.toList)
  }
  def getPortListByNodeId(containerName:String) =
    getPortByNodeId(containerName = containerName)
    .map(_.map(_.map(_.getPublicPort).distinct))



  def getPortByNodeId(containerName:String)= IO.delay{
    client.listContainersCmd()
      .withNameFilter(
        List(containerName).asJava
      )
      .exec().asScala.toList
      .map{ x=>
        x.getPorts
      }.headOption.map(x=>x.toList)
  }

  def getServiceById(serviceId:String) = {
    IO.delay{
      client.listServicesCmd()
        .withIdFilter(
          (serviceId::Nil).asJava
        ).exec().asScala.toList
    }
  }

  def getPortByServiceId(serviceId:String) ={
    val services = getServiceById(serviceId)
    for {
      ss <- services
      x  = ss.headOption.map(_.getEndpoint.getPorts.toList)
    } yield x
//    services.map{
//      ss=>
//    }
  }


  def getContainerById(containerId:String): IO[ListContainersCmd] = IO.delay{
      client.listContainersCmd()
        .withIdFilter(
          (containerId::Nil).asJava
        )
    }

    def startContainer(containerId:String) = IO.delay{
      client.startContainerCmd(containerId)
    }

  def deleteContainer(containerId:String) = {
    IO.delay{
      client.removeContainerCmd(containerId)
    }
  }


  def deleteService(serviceId:String): IO[Unit] = {
    IO.defer{
      client.removeServiceCmd(serviceId).exec().pure[IO]
    }.void
  }

  def createService(
                     name:docker.Name,
                     image:docker.Image,
                     labels:Map[String,String],
                     envs: docker.Envs,
                     maxReplicas:Int =1,
                     constraints:List[String] =List.empty[String],
                     mounts:List[Mount] = List.empty[Mount],
                     networkAttachmentConfigs: List[NetworkAttachmentConfig] = List.empty[NetworkAttachmentConfig],
                     memoryBytes:Long = 1073741824,
                     nanoCPUS:Long =1000000000
                   ): IO[CreateServiceResponse] = {

    val containerSpec = new ContainerSpec()
      .withEnv(envs.build.toList.asJava)
      .withHostname(name.build)
      .withImage(image.build)
      .withLabels(labels.asJava)
      .withMounts(mounts.asJava)

    val servicePlacement = new ServicePlacement()
      .withConstraints(constraints.asJava)
      .withMaxReplicas(maxReplicas)
//
    val resourcesSpecs = new ResourceSpecs()
      .withMemoryBytes(memoryBytes)
      .withNanoCPUs(nanoCPUS)

    val resources = new ResourceRequirements()
      .withLimits(resourcesSpecs)
    val taskSpec = new TaskSpec()
      .withContainerSpec(containerSpec)
      .withNetworks(networkAttachmentConfigs.asJava)
      .withPlacement(servicePlacement)
      .withResources(resources)

    val ports = List(
      new PortConfig()
        .withTargetPort(6666)
    ) .asJava
//    ___________________________________________________________________________
    val endpointSpec = new EndpointSpec()
      .withMode(EndpointResolutionMode.VIP)
      .withPorts(ports)

    val serviceSpec = new ServiceSpec()
      .withName(name.build)
      .withLabels(labels.asJava)
      .withTaskTemplate(taskSpec)
      .withNetworks(networkAttachmentConfigs.asJava)
      .withEndpointSpec(endpointSpec)

    IO.delay{
      client.createServiceCmd(serviceSpec).exec()
    }
  }

   def createContainer(name:docker.Name,
                       image:docker.Image,
                       hostname: docker.Hostname,
                       envs:docker.Envs,
                       hostConfig: HostConfig,
                       labels:Map[String,String] = Map.empty[String,String]
                      ) = {
     IO.pure(
       client.createContainerCmd(image.build)
         .withName(name.build)
         .withHostName(hostname.build)
         .withEnv(envs.build: _*)
         .withHostConfig(hostConfig)
         .withLabels(labels.asJava)

//         .with
     )
   }

  def createContainerV2(x: CreateContainerData) = {
    IO.pure(
      client.createContainerCmd(x.image.build)
        .withName(x.name.build)
        .withHostName(x.hostname.build)
        .withEnv(x.envs.build: _*)
        .withHostConfig(x.hostConfig)
    )
  }

}
object DockerClientX {

  def apply(config: DockerClientConfig,httpClient: DockerHttpClient) =
    new DockerClientX(config,httpClient)

}

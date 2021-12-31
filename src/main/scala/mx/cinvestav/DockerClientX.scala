package mx.cinvestav

import mx.cinvestav.Declarations.Docker._
import cats.effect._
import cats.implicits._
import com.github.dockerjava.api.command.{CreateServiceResponse, ListContainersCmd}
import com.github.dockerjava.api.model.{ContainerSpec, ExposedPort, HostConfig, Mount, MountType, NetworkAttachmentConfig, Ports, ServiceSpec, TaskSpec}
import com.github.dockerjava.core.{DockerClientConfig, DockerClientImpl}
import com.github.dockerjava.transport.DockerHttpClient

import java.util
//
import retry._
import retry.implicits._
//impor
import collection.JavaConverters._




class DockerClientX(config: DockerClientConfig,httpClient: DockerHttpClient) {
    private val client = DockerClientImpl.getInstance(config, httpClient)

  def getIpAddress(containerId:String,networkName:String) = IO.delay{
    client.listContainersCmd()
      .withIdFilter(
        List(containerId).asJava
      ).exec()
      .asScala
      .toList
      .map(_.getNetworkSettings.getNetworks.get(networkName).getIpAddress)
      .headOption
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
  def getPortByName(containerName:String)= IO.delay{
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
        )
    }
  }

  def getServiceIdByNodeI(serviceId:String) = for {
//    service       <- getServiceById(serviceId).map(_.exec().asScala.toList)
    _<- IO.unit
//    services        = client.listServicesCmd()
//    x             = service.head
//    spec          = x.getSpec
//    taskTemplate  = spec.getTaskTemplate
//    containerSpec = taskTemplate.getContainerSpec
//    y             = taskTemplate.getNetworks
//    z             = y
  } yield()

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
                     name:Name,
                     image: Image,
                     labels:Map[String,String],
                     envs: Envs,
                     networkName:String="my-net",
                     hostLogPath:String="/home/jcastillo/logs",
                     dockerLogPath:String="/app/logs",
                   ): IO[CreateServiceResponse] = {
//    val networks    = List((new NetworkAttachmentConfig()).with).asJava
    val logMount       = (new Mount())
      .withType(MountType.BIND)
      .withSource(hostLogPath)
      .withTarget(dockerLogPath)

    val containerSpec = (new ContainerSpec())
      .withEnv(envs.build.toList.asJava)
      .withHostname(name.build)
      .withImage(image.build)
      .withLabels(labels.asJava)
      .withMounts(List(logMount).asJava)


    val myNetAttach = (new NetworkAttachmentConfig())
      .withTarget(networkName)

    val taskSpec = (new TaskSpec())
      .withContainerSpec(containerSpec)
      .withNetworks(List(myNetAttach).asJava)




    val serviceSpec = (new ServiceSpec())
      .withName(name.build)
      .withLabels(labels.asJava)
      .withTaskTemplate(taskSpec)
      .withNetworks(List(myNetAttach).asJava)

    IO.delay{
      client.createServiceCmd(serviceSpec).exec()
    }
  }

   def createContainer(name:Name,
                       image:Image,
                       hostname: Hostname,
                       envs:Envs,
                       hostConfig: HostConfig
                      ) = {
     IO.pure(
       client.createContainerCmd(image.build)
       .withName(name.build)
       .withHostName(hostname.build)
       .withEnv(envs.build: _*)
       .withHostConfig(hostConfig)
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

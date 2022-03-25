package mx.cinvestav

import cats.implicits._
import cats.effect._
import cats.effect.std.{Queue, Semaphore}
import fs2.Stream
import mx.cinvestav.Declarations.Payloads.CreateCacheNode
import mx.cinvestav.Declarations.{NodeContext, NodeState}
import mx.cinvestav.config.DefaultConfig
import mx.cinvestav.server.HttpServer
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.client.Client
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.ConfigSource

import java.net.InetAddress
import scala.concurrent.ExecutionContext.global
//
import com.github.dockerjava.core.{DefaultDockerClientConfig, DockerClientConfig}
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.github.dockerjava.transport.DockerHttpClient
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.transport.DockerHttpClient.Request
import com.github.dockerjava.api.DockerClient
//
import org.apache.commons.io.IOUtils
//
import scala.concurrent.duration._
import scala.language.postfixOps
//
import pureconfig._
import pureconfig.generic.auto._

object Main extends IOApp{
  implicit val config: DefaultConfig = ConfigSource.default.loadOrThrow[DefaultConfig]
  implicit val unsafeLogger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  def initContext(client:Client[IO]): IO[NodeContext] = for {
    _                  <- Logger[IO].debug(s"-> SERVICE_REPLICATOR[${config.nodeId}]")
    s                  <- Semaphore[IO](1)
    _initState         = NodeState(
      ip           = InetAddress.getLocalHost.getHostAddress,
      basePort     =  config.basePort,
      createdNodes = Nil,
      events = Nil,
      s = s,
      replicationStrategy = config.replicationStrategy
    )
    _                  <- Logger[IO].debug(_initState.toString)
    state              <- IO.ref(_initState)
    dockerClientConfig = new DefaultDockerClientConfig.Builder()
      .withDockerHost(config.dockerSock)
      .withDockerTlsVerify(false)
      .build();

    _ <- Logger[IO].debug(s"DOCKER_HOST ${dockerClientConfig.getDockerHost}")
    dockerHttpClient   = new ApacheDockerHttpClient.Builder()
      .dockerHost(dockerClientConfig.getDockerHost)
      .build();
    dockerClientX = DockerClientX(
      config = dockerClientConfig,
      httpClient = dockerHttpClient
    )
    ctx   = NodeContext(config=config,logger=unsafeLogger,state=state,dockerClientX = dockerClientX ,client=client)
  } yield ctx

  override def run(args: List[String]): IO[ExitCode] = for {
    (client,finalizer)         <- BlazeClientBuilder[IO](global).resource.allocated
    implicit0(ctx:NodeContext) <- initContext(client)
    q                          <- Queue.dropping[IO,Int](1)
    _                          <- Stream.fromQueueUnterminated(queue=q)
      .evalMap { _ =>
        val payload  = CreateCacheNode(cacheSize = config.baseCacheSize, policy = config.baseCachePolicy, networkName = config.dockerNetworkName)
        for {
          _ <- ctx.logger.debug("CREATE_NEW_NODE")
          _ <- controllers.nodes.Create(payload=payload,hostLogPath=ctx.config.hostLogPath,maxAR= ctx.config.maxAr)
          _ <- IO.sleep(ctx.config.createNodeCoolDownMs milliseconds)
          _ <- ctx.logger.debug("FINISHED_CREATED_NODE")
        } yield ()
      }
      .compile.drain.start
    _ <- if(ctx.config.daemonEnabled) DaemonReplicator(q=q,period = ctx.config.daemonDelayMs.milliseconds).compile.drain.onError(e=>ctx.logger.error(e.getMessage)).start else IO.unit
//
    _ <- (0 until config.initNodes).toList.traverse{ _=>
      val payload  = CreateCacheNode(
        cacheSize = config.baseCacheSize, policy = config.baseCachePolicy, networkName = config.dockerNetworkName)
      controllers.nodes.Create(payload=payload,hostLogPath = config.hostLogPath, maxAR = config.maxAr)
    }
    _ <- new HttpServer().run()
    _ <- finalizer
  } yield ExitCode.Success
}

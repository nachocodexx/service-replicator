import cats.implicits._
import cats.effect._
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import mx.cinvestav.Declarations.Docker
import mx.cinvestav.Declarations.Payloads._
import mx.cinvestav.DockerClientX
import mx.cinvestav.Main.config
import mx.cinvestav.helpers.Helpers
import org.http4s._
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}
//
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
//
import scala.concurrent.ExecutionContext.global

class UnitTest extends munit .CatsEffectSuite {
  val resourceClient: Resource[IO, Client[IO]] =  BlazeClientBuilder[IO](global).resource
  implicit val unsafeLogger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  test("Test port availability"){
    for {
      _ <- IO.unit
      dockerClientConfig = new DefaultDockerClientConfig
      .Builder()
      .withDockerHost(config.dockerSock)
      .withDockerTlsVerify(false)
      .build()
//    _________________________________________________________
      dockerHttpClient   = new ApacheDockerHttpClient.Builder()
        .dockerHost(dockerClientConfig.getDockerHost)
        .build()
//    __________________________________________________________
      dockerClientX = DockerClientX(
        config = dockerClientConfig,
        httpClient = dockerHttpClient
      )
      containerId = "cache-a9f5S2Nec1Ms"
      x           <- dockerClientX.getIpAddressByContainerId(containerId,"my-net")
      y           <- dockerClientX.getPortListByNodeId(containerId)
//    ____________________________________________________________
      _ <- IO.println(s"IP_ADDRESS: $x")
      _ <- IO.println(s"PORT: $y")
    } yield ()
  }

  test("K"){
    val x = Map("NODE_ID"->"sn-0")
    val y = Map("XNODE_ID"-> "sn-xxx","NODE_PORT"-> 6666)
    val z = x ++ y
    println(z)
  }


}

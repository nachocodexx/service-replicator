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
      x           <- dockerClientX.getIpAddress(containerId,"my-net")
      y           <- dockerClientX.getPortListByNodeId(containerId)
//    ____________________________________________________________
      _ <- IO.println(s"IP_ADDRESS: $x")
      _ <- IO.println(s"PORT: $y")
    } yield ()
  }

  test("Basic") {
    val payload = CreateCacheNode(
      cacheSize    = 10,
      policy       = "LFU",
      environments =  Map.empty[String,String],
      networkName  = "my-net",
      image        = Docker.Image("","".some)
    )
    resourceClient.use{ client =>
      val req  = Request[IO](
        method =  Method.POST,
        uri    = Uri.unsafeFromString("http://localhost:3000/api/v6/create/cache-node" )
      ).withEntity(payload)

      for {
        resStatus <- client.status(req=req)
        _ <- IO.println(resStatus)
      } yield()
    }
  }

}

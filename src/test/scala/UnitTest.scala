import cats.implicits._
import cats.effect._
import mx.cinvestav.Declarations.Payloads._
import mx.cinvestav.helpers.Helpers
import org.http4s._
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client
//
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
//
import scala.concurrent.ExecutionContext.global

class UnitTest extends munit .CatsEffectSuite {
  val resourceClient: Resource[IO, Client[IO]] =  BlazeClientBuilder[IO](global).resource
  test("Test port availability"){
//    val port = Helpers.getAnAvailablePort(initPort = 3000)
//    println(port)
//    Helpers.isPortAvailable(3000)
//      .flatMap(IO.println)
  }

  test("Basic") {
    val payload = CreateCacheNode(
//      poolId    = "cache-pool-0",
      cacheSize = 10,
      policy    = "LFU",
//      basePort = 6000,
      environments =  Map.empty[String,String],
      networkName = "my-net",
      image = DockerImage("","")
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

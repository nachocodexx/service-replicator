package mx.cinvestav

import cats.implicits._
import cats.effect._
import fs2._

import language.postfixOps
import scala.concurrent.duration._
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.commons.Implicits._
import mx.cinvestav.events.Events
import mx.cinvestav.events.Events.AddedService
//
import org.http4s._
//import i
//import
object DaemonReplicator {
  case class MemoryInfo(total:Double,free:Double,used:Double)
  case class NodeInfo(
                     RAMInfo:MemoryInfo,
                     JVMMemoryInfo:MemoryInfo,
                     systemCPUUsage:Double,
                     cpuUsage:Double,
                     RAMUf:Double,
                     cacheSize:Int,
                     usedCacheSize:Int,
                     availableCacheSize:Int,
                     ufCacheSize:Double,
                     cachePolicy:String,
                     totalStorageCapacity:Long,
                     availableStorageCapacity:Long,
                     usedStorageCapacity:Long,
                     ufStorageCapacity:Double
                     )

  def apply(period:FiniteDuration = 1000 milliseconds)(implicit ctx:NodeContext) = {
    Stream.awakeEvery[IO](period =period).flatMap{ _=>
      for {
        _            <- ctx.logger.debug(s"DAEMON REPLICATOR").pureS
        currentState <- ctx.state.get.pureS
        apiVersion   = ctx.config.apiVersion
        _            <- ctx.logger.debug("GET_NODE_INFO_FROM_MONITORING").pureS
//        events       = Events.orderAndFilterEventsMonotonic(events=currentState.events)
//        nodes        = Events.onlyAddedService(events=events).map(_.asInstanceOf[AddedService])

//        uris         = nodes.map{ node=>
//          Uri.unsafeFromString(s"http://${node.hostname}:${node.port}/api/v$apiVersion/info")
//        }
//        requests     = uris.map{ uri=>
//          Request[IO](
//            method = Method.GET,
//            uri    = uri
//          )
//        }
//        responses   <- Stream.emits(requests).covary[IO].flatMap{request =>
//          for {
//            response <- ctx.client.stream(request)
//            _        <- ctx.logger.debug(response.toString).pureS
//          } yield ()
//        }


       } yield ()
    }
  }

}

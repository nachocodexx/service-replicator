package mx.cinvestav

import cats.implicits._
import cats.effect._
import cats.effect.std.Queue
import fs2._

import language.postfixOps
import scala.concurrent.duration._
import mx.cinvestav.Declarations.NodeContext
import mx.cinvestav.commons.Implicits._
import mx.cinvestav.commons.types.Monitoring.PoolInfo
import mx.cinvestav.commons.balancer.v3.UF
//
import org.http4s._
object DaemonReplicator {

  def strategy0(poolInfo:PoolInfo)(implicit ctx:NodeContext) ={
    val infos          = poolInfo.infos
    val cacheSizes     = infos.map(_.cacheSize)
    val usedCacheSizes = infos.map(_.usedCacheSize)
    val total          = cacheSizes.sum
    val used           = usedCacheSizes.sum
    val uf             = UF.calculate(total=total,used=used,objectSize=0)
    println(s"UF: $uf")
    uf >= ctx.config.threshold
  }

  def apply(q:Queue[IO,Int],period:FiniteDuration = 1000 milliseconds)(implicit ctx:NodeContext) = {
    Stream.awakeEvery[IO](period =period).flatMap{ _=>
      for {
        _            <- ctx.logger.debug(s"DAEMON REPLICATOR").pureS
        currentState <- ctx.state.get.pureS
        apiVersion   = ctx.config.apiVersion
//        _            <- ctx.logger.debug("GET_NODE_INFO_FROM_MONITORING").pureS
        poolInfo     <- ctx.config.monitoring.getInfo()
        active       = DaemonReplicator.strategy0(poolInfo)
//        _            <- ctx.logger.debug(s"ACTIVE: $active").pureS
        _            <- if(active) q.offer(0).pureS else IO.unit.pureS
//        _            <-
//        x = infos.map(_.)
       } yield ()
    }
  }

}

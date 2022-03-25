package mx.cinvestav.controllers.morin
import cats.implicits._
import cats.effect._

import mx.cinvestav.Declarations.NodeContext
import org.http4s.Http
//import org.http4s.
import org.http4s.dsl.io._

object GetContainer {

  def apply(nodeId:String)(implicit ctx:NodeContext) = {
    for {
      _ <- IO.unit
      res <- Ok()
    } yield res
  }

}

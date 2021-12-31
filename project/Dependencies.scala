import sbt._


object Dependencies {
  def apply(): Seq[ModuleID] = {
    lazy val Commons = "mx.cinvestav" %% "commons" % "0.0.5"
    lazy val PureConfig = "com.github.pureconfig" %% "pureconfig" % "0.15.0"
    lazy val MUnitCats ="org.typelevel" %% "munit-cats-effect-3" % "1.0.3" % Test
    lazy val Log4Cats =   "org.typelevel" %% "log4cats-slf4j"   % "2.1.1"
//    lazy val DockerClient = "com.github.docker-java" %% "docker-java-core" % "3.2.12"
    lazy val DockerClient = "com.github.docker-java" % "docker-java" % "3.2.12"
    lazy val DockerTransportHttpClient5 = "com.github.docker-java" % "docker-java-transport-httpclient5" % "3.2.3"
    val catsRetryVersion = "3.1.0"
    lazy val CatsRetry = "com.github.cb372" %% "cats-retry" % catsRetryVersion
    lazy val CatsRetryMTL = "com.github.cb372" %% "cats-retry-mtl" % catsRetryVersion
    lazy val Logback   = "ch.qos.logback" % "logback-classic" % "1.2.3"

    val circeVersion = "0.14.1"
    lazy val Circe = Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion)
    val http4sVersion = "1.0.0-M23"
    lazy val Http4s =Seq(
      "org.http4s" %% "http4s-dsl" ,
      "org.http4s" %% "http4s-blaze-server" ,
      "org.http4s" %% "http4s-blaze-client",
      "org.http4s" %% "http4s-circe"
    ).map(_ % http4sVersion)
    Seq(Logback,PureConfig,Commons,MUnitCats,Log4Cats,DockerClient,DockerTransportHttpClient5,CatsRetry,CatsRetryMTL) ++ Http4s++Circe
  }
}



import sbtassembly.MergeStrategy



lazy val app =(project in file(".")).settings(
  name := "system-replication-trigger",
  version := "0.1",
  scalaVersion := "2.13.6",
  assemblyJarName := "system-rep.jar",
  assembly / mainClass := Some("mx.cinvestav.Main"),
  libraryDependencies ++= Dependencies(),
  addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.0" cross CrossVersion.full),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  ThisBuild / assemblyMergeStrategy := {
    case x if x.contains("reflect.properties")=> MergeStrategy.last
    case x if x.contains("scala-collection-compat.properties")=> MergeStrategy.last
    case x if x.contains("META-INF/io.netty.versions.properties")=> MergeStrategy.last
    case x if x.contains("META-INF/versions/9/module-info.class")=> MergeStrategy.last
//
    case x if x.contains("module-info.class")=> MergeStrategy.last
    case x if x.contains("mozilla/public-suffix-list.txt")=> MergeStrategy.last
    case x if x.contains("org/apache/commons/logging/Log.class")=> MergeStrategy.last
    case x if x.contains("org/apache/commons/logging/LogConfigurationException.class")=> MergeStrategy.last
    case x if x.contains("org/apache/commons/logging/LogFactory.class")=> MergeStrategy.last
    case x if x.contains("org/apache/commons/logging/impl/NoOpLog.class")=> MergeStrategy.last
    case x if x.contains("org/apache/commons/logging/impl/SimpleLog$1.class")=> MergeStrategy.last
    case x if x.contains("org/apache/commons/logging/impl/SimpleLog.class")=> MergeStrategy.last
    case x =>
      //      println(x)
      val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
      oldStrategy(x)
  }
//    defaultMergeStrategy
//    case x if x.contains("reflect.properties")=> MergeStrategy.last
//    case x if x.contains("scala-collection-compat.properties")=> MergeStrategy.last
//    case x if x.endsWith("META-INF/io.netty.versions.properties")=> MergeStrategy.last
//    case x =>
//      //      println(x)
//      val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
//      oldStrategy(x)
//  }
)

import sbt._
import com.twitter.sbt._
import sbt.Keys._
import sbtassembly.Plugin._
import AssemblyKeys._
import java.io.File

object Zipkin extends Build {

  val CASSIE_VERSION  = "0.23.0"
  val FINAGLE_VERSION = "5.3.20"
  val OSTRICH_VERSION = "8.2.9"
  val UTIL_VERSION    = "5.3.13"

  val proxyRepo = Option(System.getenv("SBT_PROXY_REPO"))
  val travisCi = Option(System.getenv("SBT_TRAVIS_CI")) // for adding travis ci maven repos before others

  lazy val testDependencies = Seq(
    "org.scala-tools.testing" % "specs_2.9.1"  % "1.6.9" % "test",
    "org.jmock"               % "jmock"        % "2.4.0" % "test",
    "org.hamcrest"            % "hamcrest-all" % "1.1"   % "test",
    "cglib"                   % "cglib"        % "2.2.2" % "test",
    "asm"                     % "asm"          % "1.5.3" % "test",
    "org.objenesis"           % "objenesis"    % "1.1"   % "test"
  )

  def zipkinSettings = Seq(
    organization := "com.twitter",
    version := "1.0.1-SNAPSHOT",
    crossPaths := false            /* Removes Scala version from artifact name */
  )
  def defaultSettings = Project.defaultSettings ++ StandardProject.newSettings ++ TravisCiRepos.newSettings ++ zipkinSettings

  lazy val zipkin =
    Project(
      id = "zipkin",
      base = file(".")
    ) settings(
      crossPaths := false
    ) aggregate(hadoop, hadoopjobrunner, test, thrift, queryCore, queryService, common, scrooge, collectorScribe, web, cassandra, collectorCore, collectorService, kafka, redis)

  lazy val hadoop = Project(
    id = "zipkin-hadoop",
    base = file("zipkin-hadoop"),
    settings = defaultSettings ++ assemblySettings
  ).settings(
      name := "zipkin-hadoop",
      parallelExecution in Test := false,
      libraryDependencies ++= Seq(
        "com.twitter" % "scalding_2.9.1"       % "0.5.3",
        /*
          FIXME ElephantBird 3.0.0 picks up libthrift 0.7.0, which is currently
          incompatible with sbt-thrift so made these intransitive
        */
        "com.twitter.elephantbird" % "elephant-bird-cascading2"       % "3.0.5" intransitive(),
        "com.twitter.elephantbird" % "elephant-bird-core"       % "3.0.5" intransitive(),
        "org.slf4j" % "slf4j-log4j12"          % "1.6.4" % "runtime",
        "com.google.protobuf" % "protobuf-java" % "2.3.0",
        "org.apache.thrift" % "libthrift" % "0.5.0",
        "cascading" % "cascading-hadoop" % "2.0.7",
        /* Test dependencies */
        "org.scala-tools.testing" % "specs_2.9.1" % "1.6.9" % "test"
      ),
      resolvers ++= (proxyRepo match {
        case None => Seq(
          "Concurrent Maven Repo" at "http://conjars.org/repo")
        case Some(pr) => Seq() // if proxy is set we assume that it has the artifacts we would get from the above repo
      }),

      mainClass in assembly := Some("com.twitter.scalding.Tool"),

      ivyXML := // slim down the jar
        <dependencies>
            <exclude module="jms"/>
            <exclude module="jmxri"/>
            <exclude module="jmxtools"/>
            <exclude org="com.sun.jdmk"/>
            <exclude org="com.sun.jmx"/>
            <exclude org="javax.jms"/>
            <exclude org="org.mortbay.jetty"/>
        </dependencies>,
      mergeStrategy in assembly := {
        case inf if inf.startsWith("META-INF/") || inf.startsWith("project.clj") => MergeStrategy.discard
        case _ => MergeStrategy.deduplicate
      }
    ).dependsOn(thrift)

  lazy val hadoopjobrunner = Project(
    id = "zipkin-hadoop-job-runner",
    base = file("zipkin-hadoop-job-runner"),
    settings = defaultSettings ++ assemblySettings
  ).settings(
    name := "zipkin-hadoop-job-runner",
    parallelExecution in Test := false,
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-log4j12"          % "1.6.4" % "runtime",
      "javax.mail" % "mail" % "1.4.3",
      "com.github.spullara.mustache.java" % "compiler" % "0.8.2",
      "com.twitter" % "util-core" % UTIL_VERSION,
      "com.twitter" % "util-logging" % UTIL_VERSION,
      /* Test dependencies */
      "org.scala-tools.testing" % "specs_2.9.1" % "1.6.9" % "test"
    ),

    mergeStrategy in assembly := {
      case inf if inf.startsWith("META-INF/") || inf.startsWith("project.clj") => MergeStrategy.discard
      case _ => MergeStrategy.deduplicate
    }
  ).dependsOn(thrift)


  lazy val test   = Project(
    id = "zipkin-test",
    base = file("zipkin-test"),
    settings = defaultSettings ++ CompileThrift.newSettings
  ).settings(
    name := "zipkin-test",
    libraryDependencies ++= testDependencies
  ) dependsOn(queryService, collectorService)

  lazy val thrift =
    Project(
      id = "zipkin-thrift",
      base = file("zipkin-thrift"),
      settings = defaultSettings ++ SubversionPublisher.newSettings ++ CompileThrift.newSettings
    ).settings(
      name := "zipkin-thrift",
      libraryDependencies ++= Seq(
        "org.apache.thrift" % "libthrift" % "0.5.0",
        "org.slf4j" % "slf4j-api" % "1.5.8"
      ),
      sources in (Compile, doc) ~= (_ filter (_.getName contains "src_managed"))
    )

  lazy val common =
    Project(
      id = "zipkin-common",
      base = file("zipkin-common"),
      settings = defaultSettings ++ SubversionPublisher.newSettings
    ).settings(
      libraryDependencies ++= Seq(
        "com.twitter" % "finagle-ostrich4"  % FINAGLE_VERSION,
        "com.twitter" % "finagle-thrift"    % FINAGLE_VERSION,
        "com.twitter" % "finagle-zipkin"    % FINAGLE_VERSION,
        "com.twitter" % "ostrich"           % OSTRICH_VERSION,
        "com.twitter" % "util-core"         % UTIL_VERSION,

        "com.twitter.common.zookeeper" % "client"    % "0.0.6"
      ) ++ testDependencies
    )

  lazy val scrooge =
    Project(
      id = "zipkin-scrooge",
      base = file("zipkin-scrooge"),
      settings = defaultSettings ++ SubversionPublisher.newSettings ++ CompileThriftScrooge.newSettings
    ).settings(
      libraryDependencies ++= Seq(
        "com.twitter" % "finagle-ostrich4"  % FINAGLE_VERSION,
        "com.twitter" % "finagle-thrift"    % FINAGLE_VERSION,
        "com.twitter" % "finagle-zipkin"    % FINAGLE_VERSION,
        "com.twitter" % "ostrich"           % OSTRICH_VERSION,
        "com.twitter" % "util-core"         % UTIL_VERSION,

        /*
          FIXME Scrooge 3.0.0 picks up libthrift 0.8.0, which is currently
          incompatible with cassie 0.21.5 so made these intransitive
        */
        "com.twitter" % "scrooge"               % "3.0.1" intransitive(),
        "com.twitter" % "scrooge-runtime_2.9.2" % "3.0.1" intransitive()
      ) ++ testDependencies,

      CompileThriftScrooge.scroogeVersion := "3.0.1"

    ).dependsOn(common)

  lazy val collectorCore = Project(
    id = "zipkin-collector-core",
    base = file("zipkin-collector-core"),
    settings = defaultSettings ++ SubversionPublisher.newSettings
  ).settings(
    libraryDependencies ++= Seq(
      "com.twitter" % "finagle-ostrich4"  % FINAGLE_VERSION,
      "com.twitter" % "finagle-serversets"% FINAGLE_VERSION,
      "com.twitter" % "finagle-thrift"    % FINAGLE_VERSION,
      "com.twitter" % "finagle-zipkin"    % FINAGLE_VERSION,
      "com.twitter" % "ostrich"           % OSTRICH_VERSION,
      "com.twitter" % "util-core"         % UTIL_VERSION,
      "com.twitter" % "util-zk"           % UTIL_VERSION,
      "com.twitter" % "util-zk-common"    % UTIL_VERSION,

      "com.twitter.common.zookeeper" % "candidate" % "0.0.9",
      "com.twitter.common.zookeeper" % "group"     % "0.0.9"
    ) ++ testDependencies
  ).dependsOn(common, scrooge)

  lazy val cassandra = Project(
    id = "zipkin-cassandra",
    base = file("zipkin-cassandra"),
    settings = defaultSettings ++ SubversionPublisher.newSettings
  ).settings(
    libraryDependencies ++= Seq(
      "com.twitter"     % "cassie-core"       % CASSIE_VERSION,
      "com.twitter"     % "cassie-serversets" % CASSIE_VERSION,
      "com.twitter"     % "util-logging"      % UTIL_VERSION,
      "org.iq80.snappy" % "snappy"            % "0.1"
    ) ++ testDependencies,

    /* Add configs to resource path for ConfigSpec */
    unmanagedResourceDirectories in Test <<= baseDirectory {
      base =>
        (base / "config" +++ base / "src" / "test" / "resources").get
    }
  ).dependsOn(scrooge)

  lazy val queryCore =
    Project(
      id = "zipkin-query-core",
      base = file("zipkin-query-core"),
      settings = defaultSettings ++ SubversionPublisher.newSettings
    ).settings(
      libraryDependencies ++= Seq(
        "com.twitter" % "finagle-ostrich4"  % FINAGLE_VERSION,
        "com.twitter" % "finagle-serversets"% FINAGLE_VERSION,
        "com.twitter" % "finagle-thrift"    % FINAGLE_VERSION,
        "com.twitter" % "finagle-zipkin"    % FINAGLE_VERSION,
        "com.twitter" % "ostrich"           % OSTRICH_VERSION,
        "com.twitter" % "util-core"         % UTIL_VERSION,
        "com.twitter" % "util-zk"           % UTIL_VERSION,
        "com.twitter" % "util-zk-common"    % UTIL_VERSION,

        "com.twitter.common.zookeeper" % "candidate" % "0.0.9",
        "com.twitter.common.zookeeper" % "group"     % "0.0.9"
      ) ++ testDependencies
    ).dependsOn(common, scrooge)

  lazy val queryService = Project(
    id = "zipkin-query-service",
    base = file("zipkin-query-service"),
    settings = defaultSettings ++ SubversionPublisher.newSettings
  ).settings(
    libraryDependencies ++= testDependencies,

    PackageDist.packageDistZipName := "zipkin-query-service.zip",
    BuildProperties.buildPropertiesPackage := "com.twitter.zipkin",

    /* Add configs to resource path for ConfigSpec */
    unmanagedResourceDirectories in Test <<= baseDirectory {
      base =>
        (base / "config" +++ base / "src" / "test" / "resources").get
    }
  ).dependsOn(queryCore, cassandra)

  lazy val collectorScribe =
    Project(
      id = "zipkin-collector-scribe",
      base = file("zipkin-collector-scribe"),
      settings = defaultSettings ++ SubversionPublisher.newSettings
    ).settings(
      libraryDependencies ++= testDependencies
    ).dependsOn(collectorCore, scrooge)

  lazy val kafka =
    Project(
      id = "zipkin-kafka",
      base = file("zipkin-kafka"),
      settings = defaultSettings ++ SubversionPublisher.newSettings
    ).settings(
      libraryDependencies ++= Seq(
        "org.clojars.jasonjckn"      % "kafka_2.9.1"    % "0.7.0"
      ) ++ testDependencies,
      resolvers ++= (proxyRepo match {
        case None => Seq(
          "clojars" at "http://clojars.org/repo")
        case Some(pr) => Seq() // if proxy is set we assume that it has the artifacts we would get from the above repo
      })
    ).dependsOn(collectorCore, scrooge)

  lazy val collectorService = Project(
    id = "zipkin-collector-service",
    base = file("zipkin-collector-service"),
    settings = defaultSettings ++ SubversionPublisher.newSettings
  ).settings(
    libraryDependencies ++= testDependencies,

    PackageDist.packageDistZipName := "zipkin-collector-service.zip",
    BuildProperties.buildPropertiesPackage := "com.twitter.zipkin",

    /* Add configs to resource path for ConfigSpec */
    unmanagedResourceDirectories in Test <<= baseDirectory {
      base =>
        (base / "config" +++ base / "src" / "test" / "resources").get
    }
  ).dependsOn(collectorCore, collectorScribe, cassandra, kafka)

  lazy val web =
    Project(
      id = "zipkin-web",
      base = file("zipkin-web"),
      settings = defaultSettings
    ).settings(
      libraryDependencies ++= Seq(
        "com.twitter" % "finatra" % "1.1.1",

        "com.twitter.common.zookeeper" % "server-set" % "1.0.7",

        "com.twitter" % "finagle-serversets" % FINAGLE_VERSION,
        "com.twitter" % "finagle-zipkin"     % FINAGLE_VERSION
      ) ++ testDependencies,

      PackageDist.packageDistZipName := "zipkin-web.zip",
      BuildProperties.buildPropertiesPackage := "com.twitter.zipkin",

      /* Add configs to resource path for ConfigSpec */
      unmanagedResourceDirectories in Test <<= baseDirectory {
        base =>
          (base / "config" +++ base / "src" / "test" / "resources").get
      }
  ).dependsOn(common, scrooge)

  lazy val redis = Project(
    id = "zipkin-redis",
    base = file("zipkin-redis"),
    settings = defaultSettings ++ SubversionPublisher.newSettings
  ).settings(
    parallelExecution in Test := false,
    libraryDependencies ++= Seq(
      "com.twitter" % "finagle-redis"     % FINAGLE_VERSION,
      "org.slf4j" % "slf4j-log4j12"          % "1.6.4" % "runtime",
      "com.twitter"     % "util-logging"      % UTIL_VERSION
    ) ++ testDependencies,

    /* Add configs to resource path for ConfigSpec */
    unmanagedResourceDirectories in Test <<= baseDirectory {
      base =>
        (base / "config" +++ base / "src" / "test" / "resources").get
    }
  ).dependsOn(scrooge)
}

/*
 * We build our project using Travis CI. In order for it to finish in the max run time,
 * we need to use their local maven mirrors.
 */
object TravisCiRepos extends Plugin with Environmentalist {
  val travisCiResolvers = SettingKey[Seq[Resolver]](
    "travisci-central",
    "Use these resolvers when building on travis-ci"
  )

  val localRepo = SettingKey[File](
    "local-repo",
    "local folder to use as a repo (and where publish-local publishes to)"
  )

  val newSettings = Seq(
    travisCiResolvers := Seq(
      "travisci-central" at "http://maven.travis-ci.org/nexus/content/repositories/central/",
      "travisci-sonatype" at "http://maven.travis-ci.org/nexus/content/repositories/sonatype/"
    ),

    // configure resolvers for the build
    resolvers <<= (resolvers, travisCiResolvers) { (resolvers, travisCiResolvers) =>
      if("true".equalsIgnoreCase(System.getenv("SBT_TRAVIS_CI"))) {
        travisCiResolvers ++ resolvers
      } else {
        resolvers
      }
    },

    // don't add any special resolvers.
    externalResolvers <<= (resolvers) map identity
  )
}

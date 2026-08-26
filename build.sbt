import java.nio.file.Path
import sbtdynver.DynVerPlugin.autoImport.dynverSonatypeSnapshots
import com.geirsson.CiReleasePlugin
import com.jsuereth.sbtpgp.PgpKeys.publishSigned

name := "akka-platform-api"

disablePlugins(OpenApiGeneratorPlugin)

inThisBuild(
  Seq(
    organization := "io.akka",
    organizationName := "Akka",
    organizationHomepage := Some(url("https://akka.io")),
    homepage := Some(url("https://github.com/akka/platform-api")),
    description := "Akka Platform API Java client library",
    startYear := Some(2026),
    licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        id = "akka-developers",
        name = "Akka Developers",
        email = "akka.official@gmail.com",
        url = url("https://akka.io"))),
    scmInfo := Some(
      ScmInfo(url("https://github.com/akka/platform-api"), "scm:git@github.com:akka/platform-api.git")),
    dynverSonatypeSnapshots := true,
  ))

lazy val cloudsmithPublishSettings: Seq[Setting[_]] = Seq(
  publishTo := (
    if (isSnapshot.value) Some("Cloudsmith API".at("https://maven.cloudsmith.io/lightbend/akka-snapshots/"))
    else Some("Cloudsmith API".at("https://maven.cloudsmith.io/lightbend/akka/"))
  ),
  credentials ++= {
    (sys.env.get("PUBLISH_USER"), sys.env.get("PUBLISH_PASSWORD")) match {
      case (Some(user), Some(password)) =>
        Seq(Credentials("Cloudsmith API", "maven.cloudsmith.io", user, password))
      case _ => Nil
    }
  },
  pomIncludeRepository := (_ => false),
  publishConfiguration := publishConfiguration.value.withOverwrite(true),
  publishLocalConfiguration := publishLocalConfiguration.value.withOverwrite(true),
  setupGpgForPublish := { if (sys.env.contains("PGP_SECRET")) CiReleasePlugin.setupGpg() },
  publishSigned := publishSigned.dependsOn(setupGpgForPublish).value,
)

lazy val setupGpgForPublish = taskKey[Unit]("Import the PGP key before signing artifacts")

lazy val root = (project in file("."))
  .enablePlugins(ControlPlaneApis)
  .aggregate(`java-client`)
  .settings(
    scalaVersion := "2.13.18",
    publish / skip := true,
  )

val JacksonVersion = "2.21.1"
val JacksonAnnotationsVersion = "2.21"
val JacksonDatabindNullableVersion = "0.2.9"
val Jsr305Version = "3.0.2"
val JakartaAnnotationVersion = "1.3.5"

val openApiGenerateIncremental = taskKey[Seq[Path]]("openApiGenerate task that only runs when things have changed, and deletes everything first")

lazy val `java-client` = (project in file("java-client"))
  .enablePlugins(OpenApiGeneratorPlugin, AkkaGrpcPlugin)
  .settings(cloudsmithPublishSettings)
  .settings(
    name := "akka-platform-api-java-client",

    scalaVersion := "2.13.18",
    crossPaths := false,

    Compile / packageDoc / publishArtifact := false,

    (Compile / managedSourceDirectories) += target.value / "open-id-generator" / "src" / "main" / "java",

    (Compile / managedSources) ++= {
      val javaDir = (target.value / "open-id-generator" / "src" / "main" / "java").getAbsolutePath
      openApiGenerateIncremental.value.map(_.toFile).filter { file =>
        file.getAbsolutePath.startsWith(javaDir) && file.getName.endsWith(".java")
      }
    },

    openApiGeneratorName := "java",
    openApiInputSpec := (LocalRootProject / controlPlaneApiSchema / target).value.getAbsolutePath,
    openApiOutputDir := (target.value / "open-id-generator").getAbsolutePath,

    openApiAdditionalProperties := Map(
      "library" -> "native",
      "asyncNative" -> "true",
      "invokerPackage" -> "io.akka.platformapi.controlplane",
      "apiPackage" -> "io.akka.platformapi.controlplane.api",
      "modelPackage" -> "io.akka.platformapi.controlplane.model"
    ),

    openApiGenerateIncremental / fileInputs += (LocalRootProject / controlPlaneApiSchema / target).value.toGlob,
    openApiGenerateIncremental := Def.taskDyn {
      if (openApiGenerateIncremental.inputFileChanges.hasChanges) {
        IO.delete(target.value / "open-id-generator")
        Def.task {
          openApiGenerate.value.map(_.toPath)
        }
      } else {
        Def.task {
          ((target.value / "open-id-generator") ** "*").get().map(_.toPath)
        }
      }
    }.value,

    Compile / PB.protoSources += (LocalRootProject / baseDirectory).value / "schemas" / "federation-plane" / "protobuf",
    akkaGrpcGeneratedLanguages := Seq(AkkaGrpc.Java),
    akkaGrpcGeneratedSources := Seq(AkkaGrpc.Client),

    libraryDependencies ++= Seq(
      "com.fasterxml.jackson.core" % "jackson-core" % JacksonVersion,
      "com.fasterxml.jackson.core" % "jackson-annotations" % JacksonAnnotationsVersion,
      "com.fasterxml.jackson.core" % "jackson-databind" % JacksonVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % JacksonVersion,
      "org.openapitools" % "jackson-databind-nullable" % JacksonDatabindNullableVersion,
      "com.google.code.findbugs" % "jsr305" % Jsr305Version,
      "jakarta.annotation" % "jakarta.annotation-api" % JakartaAnnotationVersion % Provided,
    )

  )


import java.nio.file.Path

name := "akka-platform-api"

disablePlugins(OpenApiGeneratorPlugin)


lazy val root = (project in file("."))
  .enablePlugins(ControlPlaneApis)
  .aggregate(`java-client`)
  .settings(
    scalaVersion := "2.13.18",
  )

val JacksonVersion = "2.21.1"
val JacksonAnnotationsVersion = "2.21"
val JacksonDatabindNullableVersion = "0.2.9"
val Jsr305Version = "3.0.2"
val JakartaAnnotationVersion = "1.3.5"

val openApiGenerateIncremental = taskKey[Seq[Path]]("openApiGenerate task that only runs when things have changed, and deletes everything first")

lazy val `java-client` = (project in file("java-client"))
  .enablePlugins(OpenApiGeneratorPlugin, AkkaGrpcPlugin)
  .settings(
    name := "akka-platform-api-java-client",

    scalaVersion := "2.13.18",

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


addSbtPlugin("org.openapitools" % "sbt-openapi-generator" % "7.22.0")
addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc" % "2.5.10")
// provides sbt-dynver (version from git tags: v1.2.3 -> 1.2.3, else <version>-SNAPSHOT),
// sbt-pgp (publishSigned) and the CiReleasePlugin.setupGpg helper used to sign published artifacts
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.11.2")

libraryDependencies += "org.playframework" %% "play-json" % "3.0.6"

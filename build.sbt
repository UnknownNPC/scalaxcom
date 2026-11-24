// build.sbt
import Settings.*
import Dependencies.*
import PublishSettings.*

import scala.sys.process.Process
import scala.sys.process.ProcessLogger

// The official X API v2 OpenAPI spec — the same one the X XDKs
// (github.com/xdevplatform/xdk-typescript, ...) are generated from.
lazy val xOpenApiJsonUrl = "https://api.x.com/2/openapi.json"
lazy val xOpenApiVersion = "2.175"
lazy val xOpenApiSpecFileName = s"x-openapi-$xOpenApiVersion.json"

lazy val downloadXSchema = taskKey[File](
  "Downloads the X API v2 OpenAPI spec file",
).withRank(KeyRanks.Invisible)

lazy val generateClient = taskKey[Unit](
  "Generates the Scala/circe/sttp client from the downloaded X OpenAPI spec",
).withRank(KeyRanks.Invisible)

lazy val openApiGeneratorConfig = settingKey[File](
  "Path to the openapi-generator config file",
).withRank(KeyRanks.Invisible)

lazy val OpenApiGeneratorTool = config("openApiGeneratorTool").hide

// Required for scalafix's semantic rules (e.g. RemoveUnused).
ThisBuild / semanticdbEnabled := true

lazy val root = project.in(file("."))
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(
    name := "scalaxcom",
  )
  .aggregate(openApiClient, scalaClient)

lazy val openApiClient = project.in(file("openapi-client"))
  .settings(commonSettings)
  .settings(publishSettings)
  .settings(
    name := "scalaxcom-openapi",
    organization := "com.github.unknownnpc",

    downloadXSchema := {
      DownloadUtil.download(
        urlStr = xOpenApiJsonUrl,
        fileName = xOpenApiSpecFileName,
        destDir = baseDirectory.value / "schema",
        logger = streams.value.log,
      )
    },

    ivyConfigurations += OpenApiGeneratorTool,
    libraryDependencies +=
      ("org.openapitools" % "openapi-generator-cli" % openApiGeneratorVersion % OpenApiGeneratorTool)
        .intransitive(),

    openApiGeneratorConfig := baseDirectory.value / "openapi-config.yaml",

    generateClient := {
      val log = streams.value.log
      val specFile = downloadXSchema.value
      val generatorJar = Classpaths
        .managedJars(OpenApiGeneratorTool, Set("jar"), update.value)
        .map(_.data)
        .headOption
        .getOrElse(sys.error("openapi-generator-cli jar could not be resolved"))
      val outDir = baseDirectory.value
      val configFile = openApiGeneratorConfig.value
      val templateDir = baseDirectory.value / "templates"

      val fixedSpecFile = target.value / specFile.getName
      val specFixes: Seq[String => String] =
        Seq(
          RequiredOnlyCompositionSpecFix.fix,
          ScalarCompositionSpecFix.fix,
          EnumNameClashSpecFix.fix,
          OneOfSpecFix.fix,
          HeaderParamSpecFix.fix,
          SecuritySpecFix.fix,
        )
      IO.write(fixedSpecFile, specFixes.foldLeft(IO.read(specFile))((spec, fix) => fix(spec)))

      // Start from a clean generated package: removed spec schemas must not
      // linger as stale vendored files.
      IO.delete(baseDirectory.value / "src" / "main" / "scala" / "com" / "github" / "unknownnpc" / "scalaxcom" / "generated")

      val cmd = Seq(
        "java", "-jar", generatorJar.getAbsolutePath,
        "generate",
        "-g", "scala-sttp",
        "-i", fixedSpecFile.getAbsolutePath,
        "-o", outDir.getAbsolutePath,
        "-c", configFile.getAbsolutePath,
        "-t", templateDir.getAbsolutePath,
      )
      log.info(s"Running: ${cmd.mkString(" ")}")
      val exitCode = Process(cmd, outDir)
        .!(ProcessLogger(log.info(_), log.error(_)))
      if (exitCode != 0)
        sys.error(s"openapi-generator-cli failed with exit code $exitCode")
    },

    libraryDependencies ++= openApiRawClientDeps ++ scalaTest,
    publishTo := sonatypePublishToBundle.value,
  )

lazy val scalaClient = project.in(file("scala-client"))
  .settings(commonSettings)
  .settings(
    name := "scalaxcom-client",
    organization := "com.github.unknownnpc",
    libraryDependencies ++= scalaClientDeps ++ scalaTest,
    scalacOptions += "-Wunused:all",
    publishTo := sonatypePublishToBundle.value,
  )
  .dependsOn(openApiClient)

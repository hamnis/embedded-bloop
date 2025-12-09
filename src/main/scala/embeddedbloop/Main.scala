package embeddedbloop

import cats.syntax.all.*
import cats.effect.implicits.*
import com.monovore.decline.*
import com.monovore.decline.effect.CommandIOApp
import bloop.rifle.*
import cats.effect.{ExitCode, IO, Resource}
import coursier.paths.shaded.dirs.ProjectDirectories
import internal.BuildInfo as RifleBuildInfo
import io.circe.syntax.EncoderOps
import org.apache.commons.exec.PumpStreamHandler

import java.net.Socket
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.concurrent.duration.*
import scala.util.Properties

case class EmbeddedBloopOptions(
    version: String,
    stopOnExit: Boolean,
    waitForConnection: FiniteDuration)

object Main
    extends CommandIOApp(
      "embedded-bloop",
      "Bloop without the jank",
      version = cli.build.BuildInfo.projectVersion.getOrElse("main")) {
  override def main: Opts[IO[ExitCode]] = {
    val bloopVersion = Opts
      .option[String](
        "bloop-version",
        s"Which bloop version should we use. The default is ${RifleBuildInfo.version}")
      .withDefault(RifleBuildInfo.version)
    val disableSbtBloop = Opts
      .flag("intellij-disable-sbt-bloop", "Disables \"export sbt projects to Bloop before import\"")
      .orFalse
    val stopServerOnExit =
      Opts.flag("stop-server-on-exit", "Stop the bloop server when connection dies").orFalse
    val waitForConnection = Opts
      .option[FiniteDuration]("wait-for-connection", "Wait the duration for connection to server")
      .withDefault(30.seconds)
    val executableOverride = Opts
      .option[String]("executable-override", "Use this name for the executable")
      .orNone
    val options: Opts[EmbeddedBloopOptions] =
      (bloopVersion, stopServerOnExit, waitForConnection).mapN(EmbeddedBloopOptions.apply)

    Opts.subcommands(
      Command("setup-ide", "Installs embedded-bloop as bsp connection file")(
        (options, disableSbtBloop, executableOverride).mapN(setup)
      ),
      Command("bloop", "Runs the embedded-bloop as a bsp server and connection")(
        options.map(opts => IO.delay(Path.of(Properties.userDir)).flatMap(cwd => bloop(opts, cwd)))
      ),
      Command("exit", "Shutdown the server")(
        Opts(exit)
      )
    )
  }

  def setup(
      options: EmbeddedBloopOptions,
      disableSbtBloop: Boolean,
      executableOverride: Option[String]): IO[ExitCode] =
    Bloop.fetchBloopFrontend(options.version) >> IO
      .blocking {
        val pwd = Properties.userDir
        val bloopPath = Path.of(pwd, ".bsp", "bloop.json")
        val executable = executableOverride.orElse(detectExecutable)
        val connectionFile = Bloop.ConnectionFile.default(
          options.version,
          List(
            executable,
            Some("bloop"),
            Some(s"--bloop-version=${options.version}"),
            Option.when(options.stopOnExit)("--stop-server-on-exit")
          ).flatten
        )
        Files.createDirectories(bloopPath.getParent)
        Files.writeString(
          bloopPath,
          connectionFile.asJson.spaces2,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING)
        System.err.println(s"Installed ${bloopPath}")

        if (disableSbtBloop && Files.exists(Path.of(pwd, "build.sbt"))) {
          val bspSbtIgnore =
            """<?xml version="1.0" encoding="UTF-8"?>
          |<project version="4">
          |  <component name="BspSettings">
          |    <option name="linkedExternalProjectsSettings">
          |      <BspProjectSettings>
          |        <option name="externalProjectPath" value="$PROJECT_DIR$" />
          |        <option name="modules">
          |          <set>
          |            <option value="$PROJECT_DIR$" />
          |          </set>
          |        </option>
          |        <option name="runPreImportTask" value="false" />
          |      </BspProjectSettings>
          |    </option>
          |  </component>
          |</project>""".stripMargin
          val bspXml = Path.of(pwd, ".idea", "bsp.xml")
          Files.createDirectories(bspXml.getParent)
          if (!Files.exists(bspXml)) {
            Files.writeString(bspXml, bspSbtIgnore)
            System.err.println(s"Installed ${bspXml}")
          }

          if (!Files.exists(Path.of(pwd, ".bloop"))) {
            System.err.println("Make sure you run sbt bloopInstall after this")
          }
        }
      }
      .as(ExitCode.Success)

  private def detectExecutable: Option[String] = {
    val currentExecutable = ProcessHandle.current().info().command().get()

    if (currentExecutable.contains("ubi-hamnis-embedded-bloop")) {
      val localMise = if (Properties.isWin) {
        val project = ProjectDirectories.from(null, null, "mise")
        Path.of(project.dataLocalDir)
      } else {
        Path.of(Properties.userHome).resolve(".local/share/mise")
      }
      val version = cli.build.BuildInfo.projectVersion.getOrElse("0.1.1")
      val latestForMajor = version.substring(0, 1)
      Some(
        localMise
          .resolve(s"installs/ubi-hamnis-embedded-bloop/${latestForMajor}/embedded-bloop")
          .toString
      )
    } else if (currentExecutable.contains("java")) {
      Some("embedded-bloop")
    } else {
      Some(currentExecutable)
    }
  }

  val threadResource =
    Resource.make(IO.delay(BloopThreads.create()))(t => IO.blocking(t.shutdown()))

  private def handleSocket(socket: Socket): Resource[IO, PumpStreamHandler] =
    Resource.make(IO.delay {
      val handler = new PumpStreamHandler(System.out, System.err, System.in)
      handler.setProcessInputStream(socket.getOutputStream)
      handler.setProcessOutputStream(socket.getInputStream)
      handler.start()
      handler
    })(h => IO.blocking(h.stop()))

  def bloop(opts: EmbeddedBloopOptions, pwd: Path): IO[ExitCode] = {
    val resources = for {
      config <- Bloop.configFor(opts.version, pwd)
      logger = new MyBloopRifleLogger(config)
      threads <- threadResource
      socket <- Bloop.connect(opts.version, pwd, logger, threads).timeout(opts.waitForConnection)
      _ <- handleSocket(socket)
    } yield ExitCode.Success

    resources.onFinalize {
      Bloop.configFor(opts.version, pwd).use { config =>
        IO.whenA(opts.stopOnExit) {
          IO.blocking {
            val logger = new MyBloopRifleLogger(config)
            BloopRifle.exit(config, config.workingDir.toPath, logger)
            util.deleteDirectory(config.workingDir.toPath.resolve("bsp"))
          }
        }
      }
    }

    IO.blocking {
      Files.exists(pwd.resolve(".bloop"))
    }.flatMap(exist =>
      if (exist) resources.useForever
      else
        IO.consoleForIO
          .errorln("No .bloop directory in cwd, please run:\\nsbt bloopInstall")
          .as(ExitCode(1)))
  }

  def exit: IO[ExitCode] =
    Bloop.configFor(RifleBuildInfo.version, Path.of(Properties.userDir)).use { config =>
      IO.blocking(BloopRifle.exit(config, config.workingDir.toPath, new MyBloopRifleLogger(config)))
        .map(ExitCode.apply)
    }
}

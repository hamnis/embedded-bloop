//> using scala "2.13.16"
//> using options -Xsource:3 -Werror -Xfatal-warnings -deprecation
//> using dep "com.lihaoyi::os-lib:0.11.4"
//> using dep "com.lihaoyi::mainargs:0.7.6"
//> using dep "ch.epfl.scala::bloop-rifle:2.0.9"
//> using dep "io.get-coursier::coursier:2.1.25-M4"

import bloop.rifle.{BloopRifle, BloopRifleConfig, BloopRifleLogger, BloopServer, BloopThreads, BloopVersion, BspConnectionAddress}
import mainargs.{ParserForMethods, arg, main}
import os.Path

import java.nio.file.{FileSystems, Files, StandardOpenOption, StandardWatchEventKinds}
import scala.concurrent.duration.*
import coursier.*
import coursier.cache.shaded.dirs.ProjectDirectories

import java.io.{OutputStream, PrintStream}
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.{Channel, Channels, SocketChannel}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

object Main {
  @main
  def bsp(@arg version: String = "2.0.9"): Unit = {
    def fetchBloopFrontend(version: String) = {
      Await.result(coursier.Fetch().addDependencies(
        Dependency(Module(Organization("ch.epfl.scala"), ModuleName("bloop-frontend_2.12")), VersionConstraint(version))
      ).io.attempt.future()
        , 1.minute
      )
    }

    if (os.exists(os.pwd / ".bloop")) {
      val embedded = ProjectDirectories.from("net", "hamnaberg", "embedded-bloop")
      val cacheDir = java.nio.file.Path.of(embedded.cacheDir)
      Files.createDirectories(cacheDir)

      val pwd = os.pwd.toNIO
      val bspDir = cacheDir.resolve("bsp")
      val config = BloopRifleConfig.default(
        BloopRifleConfig.Address.DomainSocket(bspDir),
        fetchBloopFrontend,
        pwd.toFile
      ).copy(
        javaPath = sys.env.get("JAVA_HOME").map(h => (Path(h) / "bin" / "java").toString()).getOrElse("java"),
        //bspSocketOrPort = Some(() => BspConnectionAddress.UnixDomainSocket(bspDir.resolve("socket").toFile)),
        retainedBloopVersion = BloopRifleConfig.AtLeast(BloopVersion(version)),
      )

      val threads = BloopThreads.create()
      val logger = new MyBloopRifleLogger(config)
      val future = BloopRifle.startServer(config, threads.startServerChecks, logger, version, bloopJava = config.javaPath)
      Await.ready(future, 30.seconds)
      val socketFile = waitFor(bspDir.resolve("socket"), 30.seconds).fold {
        logger.error("Failed to find socket connection file within 30.seconds")
        sys.exit(1)
      }(identity)

      val bspAddress = UnixDomainSocketAddress.of(socketFile)
      val channel = SocketChannel.open(bspAddress)
      channel.configureBlocking(true)

      val latch = new CountDownLatch(1)
      val channelInputStream = Channels.newInputStream(channel)
      val channelOuputStream = Channels.newOutputStream(channel)

      if (channel.isConnected) {
        val group = new ThreadGroup("streams")
        logger.info("Connected to bsp, setting up streams")
        val pid = bspDir.resolve("pid")
        val bspServer = ProcessHandle.of(Files.readString(pid).toLong).orElseThrow()
        val thread1 = new Thread(group, () => {
          System.in.transferTo(channelOuputStream)
        }, "input")
        thread1.setDaemon(true)
        val thread2 = new Thread(group, () => {
          channelInputStream.transferTo(System.out)
        }, "output")
        thread2.setDaemon(true)
        Runtime.getRuntime.addShutdownHook(new Thread(() => {
          //safeClose("conn", conn.stop())
          safeClose("socket", channel.close())
          safeClose("threads", threads.shutdown())
          safeClose("interrupt", group.interrupt())
          safeClose("kill bloop", bspServer.destroy())
          latch.countDown()
        }))
      }

      logger.debug("After connection")
      latch.await()
      logger.debug("we are done")
    }

    else {
      Console.err.println("No .bloop directory in cwd, please run:\nsbt bloopInstall")
      sys.exit(1)
    }
  }

  @main
  def `setup-ide`(@arg version: String = "2.0.9", @arg intellij: Boolean = false): Unit = {
    val bloopPath = os.pwd / ".bsp" / "bloop.json"
    val executable = ProcessHandle.current().info().command().orElseThrow()
    val json =
      s"""{
         |  "name": "Bloop",
         |  "version": "${version}",
         |  "bspVersion": "2.1.1",
         |  "languages": [
         |    "scala",
         |    "java"
         |  ],
         |  "argv": [
         |    "$executable",
         |    "bsp"
         |  ]
         |}""".stripMargin

    os.write.over(bloopPath, json, createFolders = true)
    Console.err.println(s"Installed ${bloopPath}")

    if (intellij && os.exists(os.pwd / "build.sbt")) {
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
      Files.createDirectories((os.pwd / ".idea").toNIO)
      val bspXml = os.pwd / ".idea" / "bsp.xml"
      if (!os.exists(bspXml)) {
        os.write(bspXml, bspSbtIgnore)
        Console.err.println(s"Installed ${bspXml}")
      }
    }
  }

  def main(args: Array[String]): Unit = {
    ParserForMethods(this).runOrExit(args.toIndexedSeq)
  }

  private def safeClose(name: String, run: => Unit): Unit = {
    try {
      run
    } catch {
      case NonFatal(e) => {
        Console.err.println(s"Closed ${name}, but error occured")
        e.printStackTrace(Console.err)
      }
    }
  }

  private class MyBloopRifleLogger(config: BloopRifleConfig) extends BloopRifleLogger {
    private def fmt(level: String, s: String) = s"[$level] bloop-rifle: $s"

    override def info(msg: => String): Unit =
      Console.err.println(fmt("INFO", msg))

    override def debug(msg: => String, ex: Throwable): Unit = {
      Console.err.println(fmt("DEBUG", msg))
      ex.printStackTrace(Console.err)
    }

    override def debug(msg: => String): Unit = Console.err.println(fmt("DEBUG", msg))

    override def error(msg: => String, ex: Throwable): Unit = {
      Console.err.println(fmt("ERROR", msg))
      ex.printStackTrace(Console.err)
    }

    override def error(msg: => String): Unit = Console.err.println(fmt("ERROR", msg))

    override def bloopBspStdout: Option[OutputStream] = config.bspStdout

    override def bloopBspStderr: Option[OutputStream] = config.bspStderr

    override def bloopCliInheritStdout: Boolean = config.bspStdout.isDefined

    override def bloopCliInheritStderr: Boolean = config.bspStderr.isDefined
  }

  def waitFor(path: java.nio.file.Path, duration: FiniteDuration = 30.seconds): Option[java.nio.file.Path] = {
    val parent = path.getParent

    scala.util.Using.resource(FileSystems.getDefault.newWatchService()) { svc =>
      parent.register(svc, StandardWatchEventKinds.ENTRY_CREATE)
      if (Files.exists(path)) ()
      else {
        var valid = true
        var nextTimeout = duration.toMillis

        while (valid) {
          val t0 = System.currentTimeMillis()
          Console.err.println("Polling for changes")
          Option(svc.poll(nextTimeout, TimeUnit.MILLISECONDS)).flatMap(
            key => if (key.pollEvents().isEmpty) Some(key) else None
          ).fold(()) { key =>
            val elapsed = System.currentTimeMillis() - t0;
            nextTimeout = if (elapsed < nextTimeout) nextTimeout - elapsed else 0L
            valid = key.reset()
          }
        }
      }
      Option.when(Files.exists(path))(path)
    }
  }
}
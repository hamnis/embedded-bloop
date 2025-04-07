//> using scala "2.13.16"
//> using options -Xsource:3 -Werror -Xfatal-warnings -deprecation
//> using dep "com.lihaoyi::mainargs:0.7.6"
//> using dep "ch.epfl.scala::bloop-rifle:2.0.10"
//> using dep "io.get-coursier::coursier:2.1.25-M13"
//> using dep "org.apache.commons:commons-exec:1.5.0"

import bloop.rifle.*
import coursier.*
import coursier.cache.shaded.dirs.ProjectDirectories
import mainargs.{ParserForMethods, arg, main}
import org.apache.commons.exec.PumpStreamHandler

import java.io.{IOException, OutputStream}
import java.net.{ConnectException, Socket}
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, Promise}
import scala.util.Properties
import scala.util.control.NonFatal

object Main {
  private val folderIdMap = TrieMap.empty[Path, Int]
  private val connectionCounter = new AtomicInteger()

  @main
  def `setup-ide`(@arg version: String = "2.0.9", @arg intellij: Boolean = false, @arg stopServerOnExit: Boolean = true, @arg waitForConnectionSeconds: Int = 30): Unit = {
    fetchBloopFrontend(version).fold(err => {
      System.err.println(s"Unable to fetch bloop ${version}")
      err.printStackTrace()
      sys.exit(1)
    }, _ => ())

    val pwd = Properties.userDir
    val bloopPath = Path.of(pwd, ".bsp", "bloop.json")
    val executable = "embedded-bloop"
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
         |    "bsp",
         |    "--stop-server-on-exit=${stopServerOnExit}",
         |    "--wait-for-connection-seconds=${waitForConnectionSeconds}"
         |  ]
         |}""".stripMargin

    Files.createDirectories(bloopPath.getParent)
    Files.writeString(bloopPath, json, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    System.err.println(s"Installed ${bloopPath}")

    if (intellij && Files.exists(Path.of(pwd, "build.sbt"))) {
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
    }
  }

  @main
  def bsp(@arg version: String = "2.0.10", @arg stopServerOnExit: Boolean = true, @arg waitForConnectionSeconds: Int = 30): Unit = {
    val pwd = Path.of(Properties.userDir)

    if (Files.exists(pwd.resolve(".bloop"))) {
      val config: BloopRifleConfig = configFor(version, pwd)
      val threads = BloopThreads.create()

      val logger = new MyBloopRifleLogger(config)
      val (connection, process) = Await.result(connect(version, pwd, logger, threads), waitForConnectionSeconds.seconds)
      val handler = new PumpStreamHandler(System.out, System.err, System.in)
      handler.setProcessOutputStream(connection.getInputStream)
      handler.setProcessInputStream(connection.getOutputStream)
      handler.start()
      val latch = new CountDownLatch(1)

      Runtime.getRuntime.addShutdownHook(new Thread(() => {
        System.err.println("Shutting down")
        safeClose("socket", connection.close())
        safeClose("handler", handler.stop())
        safeClose("threads", threads.shutdown())
        if (stopServerOnExit) {
          safeClose("process", process.destroy())
        }
        latch.countDown()
      }))
      latch.await()
    }

    else {
      System.err.println("No .bloop directory in cwd, please run:\nsbt bloopInstall")
      sys.exit(1)
    }
  }

  private def fetchBloopFrontend(version: String) = {
    coursier.Fetch().addDependencies(
      Dependency(Module(Organization("ch.epfl.scala"), ModuleName("bloop-frontend_2.12")), VersionConstraint(version))
    ).either()
  }

  private def configFor(version: String, projectRoot: Path) = {
    val embedded = ProjectDirectories.from("net", "hamnaberg", "embedded-bloop")
    val cacheDir = Path.of(embedded.cacheDir)
    val workdir = cacheDir.resolve("bloop")
    val bspDir = workdir.resolve("bsp")

    BloopRifleConfig.default(
      BloopRifleConfig.Address.DomainSocket(bspDir),
      fetchBloopFrontend,
      workdir.toFile
    ).copy(
      javaPath = sys.env.get("JAVA_HOME").map(h => Path.of(h, "bin", "java").toString).getOrElse("java"),
      bspSocketOrPort = Some(() => setupUnixSocket(projectRoot, bspDir)),
      retainedBloopVersion = BloopRifleConfig.AtLeast(BloopVersion(version))
    )
  }

  private def setupUnixSocket(projectRoot: Path, bspDir: Path) = {
    val pid = ProcessHandle.current().pid()
    if (!Files.exists(bspDir)) {
      Files.createDirectories(bspDir.getParent)
      if (Properties.isWin)
        Files.createDirectory(bspDir)
      else
        Files.createDirectory(
          bspDir,
          PosixFilePermissions
            .asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
    }
    // We need to use a different socket for each folder, since it's a separate connection
    val uniqueFolderId = this.folderIdMap.getOrElseUpdate(projectRoot, connectionCounter.incrementAndGet())
    val socketPath = bspDir.resolve(s"$pid-$uniqueFolderId")

    deleteSocketFile(socketPath)
    BspConnectionAddress.UnixDomainSocket(socketPath.toFile)
  }

  //Delete the socket file if it already exists
  private def deleteSocketFile(path: Path) = {
    try Files.deleteIfExists(path)
    catch {
      case NonFatal(e) =>
        // This seems to be happening sometimes in tests
        e.printStackTrace()
        System.err.println("Unexpected error while deleting the BSP socket")
    }
  }

  private def connect(version: String, projectRoot: Path, logger: BloopRifleLogger, threads: BloopThreads): Future[(Socket, ProcessHandle)] = {
    val config = configFor(version, projectRoot)
    val maybeStartBloop = {
      val running = BloopRifle.check(config, logger)

      if (running) {
        logger.info("Found a Bloop server running")
        Future.unit
      } else {
        BloopRifle.startServer(
          config,
          threads.startServerChecks,
          logger,
          version,
          config.javaPath,
        )
      }
    }

    def openConnection(conn: BspConnection, period: FiniteDuration, timeout: FiniteDuration): Socket = {
      @tailrec
      def create(stopAt: Long): Socket = {
        val maybeSocket =
          try Right(conn.openSocket(period, timeout))
          catch {
            case e: ConnectException => Left(e)
          }
        maybeSocket match {
          case Right(socket) => socket
          case Left(e) =>
            if (System.currentTimeMillis() >= stopAt)
              throw new IOException(s"Can't connect to ${conn.address}", e)
            else {
              Thread.sleep(period.toMillis)
              create(stopAt)
            }
        }
      }

      create(System.currentTimeMillis() + timeout.toMillis)
    }

    def openBspConn = Future {
      val conn = BloopRifle.bsp(
        config,
        config.workingDir.toPath,
        logger,
      )
      val finished = Promise[Unit]()
      conn.closed.map(_ => ()).onComplete(finished.tryComplete)
      openConnection(conn, config.period, config.timeout)
    }

    def getServerPid = Future {
      val pid = Files.readString(config.workingDir.toPath.resolve("bsp/pid"))
      ProcessHandle.of(pid.toLong).orElseThrow(() => new RuntimeException("Missing pid file"))
    }

    for {
      _ <- maybeStartBloop
      conn <- openBspConn
      pid <- getServerPid
    } yield (conn, pid)
  }

  def main(args: Array[String]): Unit = {
    ParserForMethods(this).runOrExit(args.toIndexedSeq)
  }

  private def safeClose(name: String, run: => Unit): Unit = {
    try {
      run
    } catch {
      case NonFatal(e) => {
        System.err.println(s"Closed ${name}, but error occured")
        e.printStackTrace(System.err)
      }
    }
  }

  private class MyBloopRifleLogger(config: BloopRifleConfig) extends BloopRifleLogger {
    private def fmt(level: String, s: String) = s"[$level] bloop-rifle: $s"

    override def info(msg: => String): Unit =
      System.err.println(fmt("INFO", msg))

    override def debug(msg: => String, ex: Throwable): Unit = {
      System.err.println(fmt("DEBUG", msg))
      ex.printStackTrace(System.err)
    }

    override def debug(msg: => String): Unit = System.err.println(fmt("DEBUG", msg))

    override def error(msg: => String, ex: Throwable): Unit = {
      System.err.println(fmt("ERROR", msg))
      ex.printStackTrace(System.err)
    }

    override def error(msg: => String): Unit = System.err.println(fmt("ERROR", msg))

    override def bloopBspStdout: Option[OutputStream] = config.bspStdout

    override def bloopBspStderr: Option[OutputStream] = config.bspStderr

    override def bloopCliInheritStdout: Boolean = config.bspStdout.isDefined

    override def bloopCliInheritStderr: Boolean = config.bspStderr.isDefined
  }

}
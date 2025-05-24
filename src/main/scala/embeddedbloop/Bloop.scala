package embeddedbloop

import bloop.rifle.{BloopRifle, BloopRifleConfig, BloopRifleLogger, BloopThreads, BloopVersion, BspConnection, BspConnectionAddress}
import coursier.{Dependency, Module, ModuleName, Organization, VersionConstraint}
import coursier.cache.shaded.dirs.ProjectDirectories
import java.io.IOException
import java.net.{ConnectException, Socket}
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Properties
import scala.util.control.NonFatal

object Bloop {
  private val folderIdMap = TrieMap.empty[Path, Int]
  private val connectionCounter = new AtomicInteger()

  def fetchBloopFrontend(version: String) = {
    coursier.Fetch().addDependencies(
      Dependency(Module(Organization("ch.epfl.scala"), ModuleName("bloop-frontend_2.12")), VersionConstraint(version))
    ).either()
  }


  def configFor(version: String, projectRoot: Path) = {
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

  def connect(version: String, projectRoot: Path, logger: BloopRifleLogger, threads: BloopThreads): Future[(Socket, ProcessHandle)] = {
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
}

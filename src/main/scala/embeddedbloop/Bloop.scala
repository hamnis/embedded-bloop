package embeddedbloop

import bloop.rifle.*
import cats.effect.{IO, Resource, Temporal}
import coursier.paths.shaded.dirs.ProjectDirectories
import coursier.{Dependency, Module, ModuleName, Organization, VersionConstraint}
import io.circe.Codec

import java.io.File
import java.net.Socket
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Properties
import scala.util.control.NonFatal

object Bloop {
  private val folderIdMap = TrieMap.empty[Path, Int]
  private val connectionCounter = new AtomicInteger()
  private case class WorkDir(dir: Path) {
    val bsp = dir.resolve("bsp")
  }

  private val workdir = IO.delay {
    val embedded = ProjectDirectories.from("net", "hamnaberg", "embedded-bloop")
    val cacheDir = Path.of(embedded.cacheDir)
    val workdir = cacheDir.resolve("bloop")
    WorkDir(workdir)
  }

  def fetchBloopFrontend(version: String): IO[Seq[File]] =
    IO.fromFuture(IO.executionContext.flatMap(ec =>
      IO.delay {
        coursier
          .Fetch()
          .addDependencies(
            Dependency(
              Module(Organization("ch.epfl.scala"), ModuleName("bloop-frontend_2.12")),
              VersionConstraint(version))
          )
          .io
          .future()(ec)
      }))

  def configFor(version: String, projectRoot: Path): Resource[IO, BloopRifleConfig] = for {
    frontend <- Resource.eval(fetchBloopFrontend(version).attempt)
    work <- Resource.eval(workdir)
    unixSocket <- setupUnixSocket(projectRoot = projectRoot, bspDir = work.bsp)
    config <- Resource.eval(IO.delay {
      BloopRifleConfig
        .default(
          BloopRifleConfig.Address.DomainSocket(work.bsp),
          Function.const(frontend),
          work.dir.toFile
        )
        .copy(
          javaPath = sys.env
            .get("JAVA_HOME")
            .map(h => Path.of(h, "bin", "java").toString)
            .getOrElse("java"),
          bspSocketOrPort = Some(() => unixSocket),
          retainedBloopVersion = BloopRifleConfig.AtLeast(BloopVersion(version))
        )
    })
  } yield config

  private def setupUnixSocket(projectRoot: Path, bspDir: Path) = Resource.make(IO.blocking {
    val pid = ProcessHandle.current().pid()
    if (!Files.exists(bspDir)) {
      Files.createDirectories(bspDir.getParent)
      if (Properties.isWin)
        Files.createDirectory(bspDir)
      else
        Files.createDirectory(
          bspDir,
          PosixFilePermissions
            .asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        )
    }
    // We need to use a different socket for each folder, since it's a separate connection
    val uniqueFolderId =
      this.folderIdMap.getOrElseUpdate(projectRoot, connectionCounter.incrementAndGet())
    val socketPath = bspDir.resolve(s"$pid-$uniqueFolderId")

    deleteSocketFile(socketPath)
    BspConnectionAddress.UnixDomainSocket(socketPath.toFile)
  })(s => IO.blocking(deleteSocketFile(s.path.toPath)))

  // Delete the socket file if it already exists
  private def deleteSocketFile(path: Path) =
    try Files.deleteIfExists(path)
    catch {
      case NonFatal(e) =>
        // This seems to be happening sometimes in tests
        e.printStackTrace()
        System.err.println("Unexpected error while deleting the BSP socket")
    }

  def openBspConn(config: BloopRifleConfig, logger: BloopRifleLogger) = Resource
    .make {
      IO.blocking(BloopRifle.bsp(config, config.workingDir.toPath, logger))
    }(x => IO.fromFuture(IO.delay(x.closed)).void >> IO.blocking(x.stop()))
    .flatMap(openConnection(_, config.period, config.timeout))

  def connect(
      version: String,
      projectRoot: Path,
      logger: BloopRifleLogger,
      threads: BloopThreads): Resource[IO, Socket] =
    for {
      config <- configFor(version, projectRoot)
      running <- Resource.eval(IO.blocking {
        BloopRifle.check(config, logger)
      })
      _ <- Resource.eval(IO.unlessA(running) {
        IO.fromFuture(
          IO.delay {
            BloopRifle.startServer(
              config,
              threads.startServerChecks,
              logger,
              version,
              config.javaPath
            )
          }
        )
      })
      socket <- openBspConn(config, logger)
    } yield socket

  def openConnection(
      conn: BspConnection,
      period: FiniteDuration,
      timeout: FiniteDuration): Resource[IO, Socket] =
    Resource.fromAutoCloseable(
      retryWithBackoff(IO.blocking(conn.openSocket(period, timeout)), 500.millis, 10))

  def retryWithBackoff[A](ioa: IO[A], initialDelay: FiniteDuration, maxRetries: Int)(implicit
      timer: Temporal[IO]): IO[A] =

    ioa.handleErrorWith { error =>
      if (maxRetries > 0)
        IO.sleep(initialDelay) *> retryWithBackoff(ioa, initialDelay * 2, maxRetries - 1)
      else
        IO.raiseError(error)
    }

  case class ConnectionFile(
      name: String,
      version: String,
      bspVersion: String,
      languages: List[String],
      argv: List[String])

  object ConnectionFile {

    def default(version: String, argv: List[String]): ConnectionFile =
      ConnectionFile(
        name = "Embedded-Bloop",
        version = version,
        bspVersion = "2.1.1",
        languages = List("scala", "java"),
        argv = argv)

    implicit val circeCodec: Codec[ConnectionFile] =
      Codec.forTypedProduct5("name", "version", "bspVersion", "language", "argv")(apply)(
        unapply(_).get)
  }
}

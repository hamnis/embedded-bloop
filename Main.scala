//> using scala "3.6.4"
//> using dep "com.lihaoyi::os-lib:0.11.4"
//> using dep "com.lihaoyi::mainargs:0.7.6"

import mainargs.{ParserForMethods, arg, main}
import os.Path

import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.channels.SocketChannel
import java.nio.file.{FileSystems, Files, StandardWatchEventKinds}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object Main {
  @main
  def bsp(): Unit = {
    val path = os.temp.dir(deleteOnExit = true)
    val socket = path / "bsp.sock"
    val bloop = getDefaultInstallPath("bloop")

    if (os.exists(os.pwd / ".bloop")) {
      val javaHome = findJavaHome()
      val process = os.spawn(
        List(bloop, "--java-home", javaHome, "bsp", "--protocol", "local", "--socket", socket.toString, "--no-color"),
        cwd = os.pwd,
        destroyOnExit = true,
        shutdownGracePeriod = 2000,
      )

      Console.err.println(s"Waiting for connection")

      if (process.isAlive()) {
        waitFor(socket, 10.seconds)
        Console.err.println(s"socket detected ${socket}")

        val address = UnixDomainSocketAddress.of(path.toNIO)
        scala.util.Using.resource(SocketChannel.open(StandardProtocolFamily.UNIX)) { channel =>
          if channel.connect(address) then {
            val sock = channel.socket()
            System.in.transferTo(sock.getOutputStream)
            sock.getInputStream.transferTo(System.out)
          }
          else
            Console.err.println("Failed to connect")
            sys.exit(1)
        }
      } else sys.exit(1)
    } else {
      Console.err.println("No .bloop directory in cwd, please run:\nsbt bloopInstall")
      sys.exit(1)
    }
  }


  @main
  def `setup-ide`(@arg version: String = "2.0.9"): Unit = {
    val scriptName = "setup-bloop"
    val bloopPath = os.Path(".bsp/bloop.json")
    Files.createDirectories(bloopPath.toNIO.getParent)
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
         |    "${getDefaultInstallPath(scriptName)}",
         |    "bsp"
         |  ]
         |}""".stripMargin

    Files.writeString(bloopPath.toNIO, json)
  }

  def main(args: Array[String]): Unit = {
    ParserForMethods(this).runOrExit(args)
  }

  def findJavaHome() =
    sys.env.get("JAVA_HOME").orElse(getCommand("java").map(_.toNIO.getParent.getParent.toString)).getOrElse {
      Console.println("Unable to find java")
      sys.exit(1)
    }
}

def getCommand(command: String): Option[Path] = {
  Some(
    os.proc(List(s"which", command)).call(cwd = os.pwd)
  ).flatMap(res => Option.when(res.exitCode == 0)(os.Path(res.out.text())))
}

def getDefaultInstallPath(scriptName: String): String = {
  val onPath = getCommand(scriptName)
  onPath match
    case Some(value) => value.toString
    case None => {
      if os.exists(os.home / ".local" / "bin" / scriptName) then
        (os.home / ".local" / "bin" / scriptName).toString
      else if os.exists(os.home / "bin" / scriptName) then
        (os.home / "bin" / scriptName).toString
      else scriptName
    }
}

def waitFor(path: os.Path, duration: FiniteDuration = 30.seconds): Unit = {
  val parent = path.toNIO.getParent

  scala.util.Using.resource(FileSystems.getDefault.newWatchService()) { svc =>
    parent.register(svc, StandardWatchEventKinds.ENTRY_CREATE)
    if os.exists(path) then ()
    else {
      var valid = true
      var nextTimeout = duration.toMillis

      while (valid) {
        val t0 = System.currentTimeMillis()
        Console.err.println("Polling for changes")
        Option(svc.poll(nextTimeout, TimeUnit.MILLISECONDS)).flatMap(
          key => if key.pollEvents().isEmpty then Some(key) else None
        ).fold(()) { key =>
          val elapsed = System.currentTimeMillis() - t0;
          nextTimeout = if elapsed < nextTimeout then nextTimeout - elapsed else 0L
          valid = key.reset()
        }
      }
    }

    if (!os.exists(path)) {
      Console.err.println(s"BSP socket file missing after waiting for ${duration.toSeconds} seconds: ${path}")
      sys.exit(1)
    }
  }
}
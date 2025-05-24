package embeddedbloop

import bloop.rifle.*
import internal.BuildInfo as RifleBuildInfo
import mainargs.{ParserForMethods, arg, main}
import org.apache.commons.exec.PumpStreamHandler

import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.CountDownLatch
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Properties
import scala.util.control.NonFatal

object Main {

  @main
  def `setup-ide`(@arg version: String = RifleBuildInfo.version, @arg intellij: Boolean = false, @arg stopServerOnExit: Boolean = true, @arg waitForConnectionSeconds: Int = 30): Unit = {
    Bloop.fetchBloopFrontend(version).fold(err => {
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
         |    "bloop",
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

      if (!Files.exists(Path.of(pwd, ".bloop"))) {
        System.err.println("Make sure you run sbt bloopInstall after this")
      }
    }
  }

  @main
  def bloop(@arg version: String = RifleBuildInfo.version, @arg stopServerOnExit: Boolean = true, @arg waitForConnectionSeconds: Int = 30): Unit = {
    val pwd = Path.of(Properties.userDir)

    if (Files.exists(pwd.resolve(".bloop"))) {
      val config: BloopRifleConfig = Bloop.configFor(version, pwd)
      val threads = BloopThreads.create()

      val logger = new MyBloopRifleLogger(config)
      val (connection, onComplete, process) = Await.result(Bloop.connect(version, pwd, logger, threads), waitForConnectionSeconds.seconds)
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
        safeClose("conComplete", {
          Await.result(onComplete.future, 1.minute)
        })
        if (stopServerOnExit) {
          safeClose("process", {
            process.destroy()
            util.deleteDirectory(config.workingDir.toPath.resolve("bsp"))
          })
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


  def main(args: Array[String]): Unit = {
    ParserForMethods(this).runOrExit(args.toIndexedSeq)
  }

  private def safeClose(name: String, run: => Unit): Unit = {
    try {
      run
    } catch {
      case NonFatal(e) =>
        System.err.println(s"Closed ${name}, but error occured")
        e.printStackTrace(System.err)
    }
  }
}
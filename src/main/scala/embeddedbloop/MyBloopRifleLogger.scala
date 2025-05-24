package embeddedbloop

import bloop.rifle.{BloopRifleConfig, BloopRifleLogger}

import java.io.OutputStream

class MyBloopRifleLogger(config: BloopRifleConfig) extends BloopRifleLogger {
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

package embeddedbloop

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, SimpleFileVisitor}

object util {

  def deleteDirectory(resolved: Path): Unit = {
    if (Files.isDirectory(resolved)) {
      Files.walkFileTree(resolved, DeletingFileVisitor)
      Files.deleteIfExists(resolved)
    }
  }

  private object DeletingFileVisitor extends SimpleFileVisitor[Path] {
    @throws[IOException]
    override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
      Files.delete(file)
      FileVisitResult.CONTINUE
    }

    @throws[IOException]
    override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = {
      Files.delete(file)
      FileVisitResult.CONTINUE
    }

    @throws[IOException]
    override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = if (exc == null) {
      Files.delete(dir)
      FileVisitResult.CONTINUE
    } else throw exc
  }
}

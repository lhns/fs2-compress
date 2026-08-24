package de.lhns.fs2.compress

import cats.effect.IO
import fs2.Stream
import fs2.io

import java.io.IOException

object ResourceUtil {
  def resourceAsStream(name: String): Stream[IO, Byte] =
    Stream.eval(IO.blocking(Option(getClass.getResourceAsStream(name)))).flatMap {
      case Some(resource) => io.readInputStream(IO.pure(resource), 8192)
      case None => Stream.raiseError[IO](new IOException(s"Resource $name not found"))
    }
}

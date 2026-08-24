package de.lhns.fs2.compress

import fs2.Pipe

import scala.annotation.implicitNotFound

@implicitNotFound(
  "No Compressor[${F}] in scope. Import a default instance, for example `import GzipCompressor.default._`, or build " +
    "one with `GzipCompressor.make(...)` and make it implicit."
)
trait Compressor[F[_]] {
  def compress: Pipe[F, Byte, Byte]
}

object Compressor {
  def empty[F[_]]: Compressor[F] = new Compressor[F] {
    override def compress: Pipe[F, Byte, Byte] = identity
  }
}

package de.lhns.fs2.compress

import fs2.Pipe

import scala.annotation.implicitNotFound

@implicitNotFound(
  "No Decompressor[${F}] in scope. Import a default instance, for example `import GzipDecompressor.default._`, or " +
    "build one with `GzipDecompressor.make(...)` and make it implicit."
)
trait Decompressor[F[_]] {
  def decompress: Pipe[F, Byte, Byte]
}

object Decompressor {
  def empty[F[_]]: Decompressor[F] = new Decompressor[F] {
    override def decompress: Pipe[F, Byte, Byte] = identity
  }
}

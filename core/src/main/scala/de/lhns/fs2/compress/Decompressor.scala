package de.lhns.fs2.compress

import fs2.Pipe

trait Decompressor[F[_]] {
  def decompress: Pipe[F, Byte, Byte]
}

object Decompressor {
  def empty[F[_]]: Decompressor[F] = new Decompressor[F] {
    override def decompress: Pipe[F, Byte, Byte] = identity
  }
}

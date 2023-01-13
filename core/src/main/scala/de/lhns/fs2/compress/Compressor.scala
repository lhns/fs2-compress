package de.lhns.fs2.compress

import fs2.Pipe

trait Compressor[F[_]] {
  def compress: Pipe[F, Byte, Byte]
}

object Compressor {
  def empty[F[_]]: Compressor[F] = new Compressor[F] {
    override def compress: Pipe[F, Byte, Byte] = identity
  }
}

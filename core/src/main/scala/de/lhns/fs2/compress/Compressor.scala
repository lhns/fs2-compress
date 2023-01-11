package de.lhns.fs2.compress

import fs2.Pipe

trait Compressor[F[_]] {
  def compress: Pipe[F, Byte, Byte]
}

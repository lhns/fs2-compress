package de.lhns.fs2.compress

import fs2.Pipe

trait Decompressor[F[_]] {
  def decompress: Pipe[F, Byte, Byte]
}

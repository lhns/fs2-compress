package de.lhns.fs2.compress

import fs2.{Pipe, Stream}

trait Unarchiver[F[_], Size[A] <: Option[A], Underlying] {
  def unarchive: Pipe[F, Byte, (ArchiveEntry[Size, Underlying], Stream[F, Byte])]
}

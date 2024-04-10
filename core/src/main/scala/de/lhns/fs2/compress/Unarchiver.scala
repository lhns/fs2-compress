package de.lhns.fs2.compress

import fs2.{Pipe, Stream}

trait Unarchiver[F[_], Entry[A[B] <: Option[B]] <: ArchiveEntry[A], Size[A] <: Option[A]] {
  def unarchive: Pipe[F, Byte, (Entry[Size], Stream[F, Byte])]
}

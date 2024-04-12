package de.lhns.fs2.compress

import fs2.{Pipe, Stream}

trait Archiver[F[_], Size[A] <: Option[A]] {
  def archive: Pipe[F, (ArchiveEntry[Size, Any], Stream[F, Byte]), Byte]
}

package de.lhns.fs2.compress

import fs2.{Pipe, Stream}

trait Unarchiver[F[_], Entry] {
  def archiveEntry: ArchiveEntry[Entry]

  def unarchive: Pipe[F, Byte, (Entry, Stream[F, Byte])]
}

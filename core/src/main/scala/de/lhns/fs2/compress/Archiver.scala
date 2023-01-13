package de.lhns.fs2.compress

import fs2.{Pipe, Stream}

trait Archiver[F[_], Entry] {
  def archiveEntryConstructor: ArchiveEntryConstructor[Entry]

  def archiveEntry: ArchiveEntry[Entry]

  def archive: Pipe[F, (Entry, Stream[F, Byte]), Byte]
}

package de.lhns.fs2.compress

import fs2.{Pipe, Stream}

class ArchiveSingleFileCompressor[F[_], Entry](archiver: Archiver[F, Entry], entry: Entry) extends Compressor[F] {
  override def compress: Pipe[F, Byte, Byte] = { stream =>
    Stream.emit((entry, stream))
      .through(archiver.archive)
  }
}

object ArchiveSingleFileCompressor {
  def apply[F[_], Entry](archiver: Archiver[F, Entry], entry: Entry): ArchiveSingleFileCompressor[F, Entry] =
    new ArchiveSingleFileCompressor(archiver, entry)

  def forName[F[_], Entry](archiver: Archiver[F, Entry], name: String): ArchiveSingleFileCompressor[F, Entry] =
    new ArchiveSingleFileCompressor(archiver, archiver.archiveEntryConstructor(name))
}

class ArchiveSingleFileDecompressor[F[_], Entry](unarchiver: Unarchiver[F, Entry]) extends Decompressor[F] {
  override def decompress: Pipe[F, Byte, Byte] = { stream =>
    stream
      .through(unarchiver.unarchive)
      .flatMap {
        case (entry, s) if unarchiver.archiveEntry.isDirectory(entry) => s.drain
        case (_, s) => Stream.emit(s)
      }
      .head
      .flatten
  }
}

object ArchiveSingleFileDecompressor {
  def apply[F[_], Entry](unarchiver: Unarchiver[F, Entry]): ArchiveSingleFileDecompressor[F, Entry] =
    new ArchiveSingleFileDecompressor(unarchiver)
}

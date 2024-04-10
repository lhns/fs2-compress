package de.lhns.fs2.compress

import fs2.{Pipe, Stream}

class ArchiveSingleFileCompressor[F[_], Size[A] <: Option[A]](
                                                               archiver: Archiver[F, Size],
                                                               entry: ArchiveEntry[Size]
                                                             ) extends Compressor[F] {
  override def compress: Pipe[F, Byte, Byte] = { stream =>
    Stream.emit((entry, stream))
      .through(archiver.archive)
  }
}

object ArchiveSingleFileCompressor {
  def apply[F[_], Size[A] <: Option[A]](
                                         archiver: Archiver[F, Size],
                                         entry: ArchiveEntry[Size]
                                       ): ArchiveSingleFileCompressor[F, Size] =
    new ArchiveSingleFileCompressor(archiver, entry)

  def forName[F[_]](archiver: Archiver[F, Option], name: String): ArchiveSingleFileCompressor[F, Option] =
    new ArchiveSingleFileCompressor(archiver, ArchiveEntry[Option](name))

  def forName[F[_]](archiver: Archiver[F, Some], name: String, size: Long): ArchiveSingleFileCompressor[F, Some] =
    new ArchiveSingleFileCompressor(archiver, ArchiveEntry(name, Some(size)))
}

class ArchiveSingleFileDecompressor[
  F[_], Entry[A[B] <: Option[B]] <: ArchiveEntry[A], Size[A] <: Option[A]
](unarchiver: Unarchiver[F, Entry, Size]) extends Decompressor[F] {
  override def decompress: Pipe[F, Byte, Byte] = { stream =>
    stream
      .through(unarchiver.unarchive)
      .flatMap {
        case (entry, s) if entry.isDirectory => s.drain
        case (_, s) => Stream.emit(s)
      }
      .head
      .flatten
  }
}

object ArchiveSingleFileDecompressor {
  def apply[
    F[_], Entry[A[B] <: Option[B]] <: ArchiveEntry[A], Size[A] <: Option[A]
  ](unarchiver: Unarchiver[F, Entry, Size]): ArchiveSingleFileDecompressor[F, Entry, Size] =
    new ArchiveSingleFileDecompressor(unarchiver)
}

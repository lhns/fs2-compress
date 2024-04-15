package de.lhns.fs2.compress

import de.lhns.fs2.compress.ArchiveEntry.ArchiveEntryToUnderlying

import java.time.Instant

case class ArchiveEntry[+Size[A] <: Option[A], +Underlying](
    name: String,
    uncompressedSize: Size[Long] = None: Option[Long],
    isDirectory: Boolean = false,
    lastModified: Option[Instant] = None,
    lastAccess: Option[Instant] = None,
    creation: Option[Instant] = None,
    private val underlying: Underlying = ()
) {
  def withName(name: String): ArchiveEntry[Size, Underlying] = copy(name = name)

  def withUncompressedSize[S[A] <: Option[A]](uncompressedSize: S[Long]): ArchiveEntry[S, Underlying] =
    copy(uncompressedSize = uncompressedSize)

  def withIsDirectory(isDirectory: Boolean): ArchiveEntry[Size, Underlying] = copy(isDirectory = isDirectory)

  def withLastModified(lastModified: Option[Instant]): ArchiveEntry[Size, Underlying] =
    copy(lastModified = lastModified)

  def withUnderlying[U](underlying: U): ArchiveEntry[Size, U] = copy(underlying = underlying)

  def underlying[U](implicit U: ArchiveEntryToUnderlying[U]): U =
    U.underlying(this, underlying)
}

object ArchiveEntry {
  def fromUnderlying[Size[A] <: Option[A], U](underlying: U)(implicit
      U: ArchiveEntryFromUnderlying[Size, U]
  ): ArchiveEntry[Option, U] =
    U.archiveEntry(underlying)

  trait ArchiveEntryToUnderlying[+Underlying] {
    def underlying[S[A] <: Option[A]](entry: ArchiveEntry[S, Any], underlying: Any): Underlying
  }

  trait ArchiveEntryFromUnderlying[+Size[A] <: Option[A], Underlying] {
    def archiveEntry(underlying: Underlying): ArchiveEntry[Size, Underlying]
  }
}

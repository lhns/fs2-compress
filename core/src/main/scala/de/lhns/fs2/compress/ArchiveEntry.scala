package de.lhns.fs2.compress

import java.time.Instant

trait ArchiveEntry[-A] {
  def name(entry: A): String

  def size(entry: A): Option[Long]

  def isDirectory(entry: A): Boolean

  def lastModified(entry: A): Option[Instant]
}

object ArchiveEntry {
  def apply[A](implicit archiveEntry: ArchiveEntry[A]): ArchiveEntry[A] =
    archiveEntry

  object syntax {
    implicit class ArchiveEntryOps[A](val self: A) extends AnyVal {
      def name(implicit archiveEntry: ArchiveEntry[A]): String =
        archiveEntry.name(self)

      def size(implicit archiveEntry: ArchiveEntry[A]): Option[Long] =
        archiveEntry.size(self)

      def isDirectory(implicit archiveEntry: ArchiveEntry[A]): Boolean =
        archiveEntry.isDirectory(self)

      def lastModified(implicit archiveEntry: ArchiveEntry[A]): Option[Instant] =
        archiveEntry.lastModified(self)

      def to[B](implicit archiveEntry: ArchiveEntry[A], archiveEntryConstructor: ArchiveEntryConstructor[B]): B =
        archiveEntryConstructor.from(self)
    }
  }
}

trait ArchiveEntryConstructor[+A] {
  def apply(
             name: String,
             size: Option[Long] = None,
             isDirectory: Boolean = false,
             lastModified: Option[Instant] = None
           ): A

  def from[B](entry: B)(implicit archiveEntry: ArchiveEntry[B]): A = apply(
    name = archiveEntry.name(entry),
    size = archiveEntry.size(entry),
    isDirectory = archiveEntry.isDirectory(entry),
    lastModified = archiveEntry.lastModified(entry)
  )
}

object ArchiveEntryConstructor {
  def apply[A](implicit archiveEntryConstructor: ArchiveEntryConstructor[A]): ArchiveEntryConstructor[A] =
    archiveEntryConstructor
}

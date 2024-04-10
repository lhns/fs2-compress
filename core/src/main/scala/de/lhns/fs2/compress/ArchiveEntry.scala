package de.lhns.fs2.compress

import java.time.Instant

trait ArchiveEntry[+Size[A] <: Option[A]] {
  def name: String

  def size: Size[Long]

  def isDirectory: Boolean

  def lastModified: Option[Instant]
}

object ArchiveEntry {
  def apply[Size[A] <: Option[A]](
             name: String,
             size: Size[Long] = None: Option[Long],
             isDirectory: Boolean = false,
             lastModified: Option[Instant] = None
                                 ): ArchiveEntry[Size] = {
    val _name = name
    val _size = size
    val _isDirectory = isDirectory
    val _lastModified = lastModified

    new ArchiveEntry[Size] {
      override val name: String = _name
      override val size: Size[Long] = _size
      override val isDirectory: Boolean = _isDirectory
      override val lastModified: Option[Instant] = _lastModified
    }
  }
}

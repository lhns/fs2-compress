package de.lhns.fs2.compress

import cats.effect.{Async, Resource}
import cats.syntax.functor._
import de.lhns.fs2.compress.ArchiveEntry.{ArchiveEntryFromUnderlying, ArchiveEntryToUnderlying}
import de.lhns.fs2.compress.Archiver.checkUncompressedSize
import de.lhns.fs2.compress.Zip._
import fs2.io._
import fs2.{Pipe, Stream}

import java.io.{BufferedInputStream, InputStream, OutputStream}
import java.nio.file.attribute.FileTime
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}

object Zip {
  // The underlying information is lost if the name or isDirectory attribute of an ArchiveEntry is changed, except for
  // the CRC, which describes the data rather than the name and is carried over.
  implicit val zipArchiveEntryToUnderlying: ArchiveEntryToUnderlying[ZipEntry] =
    new ArchiveEntryToUnderlying[ZipEntry] {
      override def underlying[S[A] <: Option[A]](entry: ArchiveEntry[S, Any], underlying: Any): ZipEntry = {
        val fileOrDirName = entry.name match {
          case name if entry.isDirectory && !name.endsWith("/") => name + "/"
          case name if !entry.isDirectory && name.endsWith("/") => name.dropRight(1)
          case name => name
        }

        val zipEntry = underlying match {
          case zipEntry: ZipEntry if zipEntry.getName == fileOrDirName && zipEntry.isDirectory == entry.isDirectory =>
            new ZipEntry(zipEntry)

          case zipEntry: ZipEntry =>
            // The native entry describes a different name, so the rest of it may no longer be valid and the entry is
            // built again. The CRC is taken over the data rather than the name, so it comes along.
            val renamed = new ZipEntry(fileOrDirName)
            if (zipEntry.getCrc != -1) renamed.setCrc(zipEntry.getCrc)
            renamed

          case _ =>
            new ZipEntry(fileOrDirName)
        }

        entry.uncompressedSize.foreach(zipEntry.setSize)
        entry.lastModified.map(FileTime.from).foreach(zipEntry.setLastModifiedTime)
        entry.lastAccess.map(FileTime.from).foreach(zipEntry.setLastAccessTime)
        entry.creation.map(FileTime.from).foreach(zipEntry.setCreationTime)
        zipEntry
      }
    }

  implicit class ZipArchiveEntryOps[Size[A] <: Option[A], Underlying](
      private val entry: ArchiveEntry[Size, Underlying]
  ) extends AnyVal {

    /** Give the entry the CRC of the data it describes, which [[ZipArchiver.makeStored]] requires.
      *
      * An entry read out of a zip already carries its CRC and needs none of this. For one that does not, compute it
      * with `java.util.zip.CRC32`.
      *
      * The CRC survives a later [[ArchiveEntry.withName]], since renaming an entry does not change its data. Give the
      * entry a new one if the data itself changes.
      */
    def withCrc(crc: Long): ArchiveEntry[Size, ZipEntry] = {
      val zipEntry = entry.underlying[ZipEntry]
      zipEntry.setCrc(crc)
      entry.withUnderlying(zipEntry)
    }
  }

  implicit val zipArchiveEntryFromUnderlying: ArchiveEntryFromUnderlying[Option, ZipEntry] =
    new ArchiveEntryFromUnderlying[Option, ZipEntry] {
      override def archiveEntry(underlying: ZipEntry): ArchiveEntry[Option, ZipEntry] =
        ArchiveEntry(
          name = underlying.getName,
          isDirectory = underlying.isDirectory,
          uncompressedSize = Some(underlying.getSize).filterNot(_ == -1),
          lastModified = Option(underlying.getLastModifiedTime).map(_.toInstant),
          lastAccess = Option(underlying.getLastAccessTime).map(_.toInstant),
          creation = Option(underlying.getCreationTime).map(_.toInstant),
          underlying = underlying
        )
    }
}

class ZipArchiver[F[_]: Async, Size[A] <: Option[A]] private (method: Int, chunkSize: Int) extends Archiver[F, Size] {

  /** STORED writes the CRC into the entry header, which comes before the data it describes, so unlike DEFLATED it
    * cannot be computed as the entry is written. The caller has to supply it, except where there is no data and it is
    * therefore known to be zero.
    */
  private def prepared(zipEntry: ZipEntry): ZipEntry = {
    // An entry copied from another archive brings that archive's method and compressed size with it. Neither is part
    // of the model the caller works with, and the factory that made this archiver already said which method to write,
    // so both are set here rather than inherited.
    zipEntry.setMethod(method)

    if (method == ZipOutputStream.STORED) {
      if (zipEntry.isDirectory || zipEntry.getSize == 0) {
        zipEntry.setSize(0)
        zipEntry.setCrc(0)
      }

      if (zipEntry.getCrc == -1)
        throw new IllegalArgumentException(
          s"Entry ${zipEntry.getName} is stored uncompressed but carries no CRC. The STORED method writes the CRC " +
            "ahead of the data, so it cannot be computed while the entry is being written. Give the entry its CRC " +
            "with withCrc, or use ZipArchiver.makeDeflated."
        )

      // Stored data is written as it is, so this is the size. Left alone it would still hold the compressed size of
      // whatever archive the entry was copied from.
      zipEntry.setCompressedSize(zipEntry.getSize)
    } else {
      // Deflating produces a compressed size of its own, and writing rejects an entry that declares a different one.
      zipEntry.setCompressedSize(-1)
    }

    zipEntry
  }

  override def archive: Pipe[F, (ArchiveEntry[Size, Any], Stream[F, Byte]), Byte] = { stream =>
    OutputStreams.readWrappedOutputStream[F, ZipOutputStream](chunkSize) { outputStream =>
      val zipOutputStream = new ZipOutputStream(outputStream)
      zipOutputStream.setMethod(method)
      zipOutputStream
    } { zipOutputStream =>
      stream
        .through(checkUncompressedSize)
        .flatMap { case (archiveEntry, stream) =>
          // These writes happen in the stream rather than in a Resource so that they can be
          // cancelled. See OutputStreams.
          Stream.exec(Async[F].interruptible {
            zipOutputStream.putNextEntry(prepared(archiveEntry.underlying[ZipEntry]))
          }) ++
            stream
              .through(writeOutputStream(Async[F].pure[OutputStream](zipOutputStream), closeAfterUse = false)) ++
            Stream.exec(Async[F].interruptible(zipOutputStream.closeEntry()))
        }
    }
  }
}

object ZipArchiver {
  def apply[F[_], Size[A] <: Option[A]](implicit instance: ZipArchiver[F, Size]): ZipArchiver[F, Size] = instance

  @deprecated("Use makeDeflated or makeStored instead", "2.2")
  def make[F[_]: Async, Size[A] <: Option[A]](
      method: Int = ZipOutputStream.DEFLATED,
      chunkSize: Int = Defaults.defaultChunkSize
  ): ZipArchiver[F, Size] =
    new ZipArchiver(method, chunkSize)

  /** Make a new [[ZipArchiver]] which writes every entry with the DEFLATED method.
    */
  def makeDeflated[F[_]: Async](chunkSize: Int = Defaults.defaultChunkSize): ZipArchiver[F, Option] =
    make[F, Option](ZipOutputStream.DEFLATED, chunkSize)

  /** Make a new [[ZipArchiver]] which writes every entry with the STORED method.
    *
    * Entries are written uncompressed, which is worth having for data that is already compressed. The zip header for a
    * stored entry carries both the size and the CRC of the data, and both come before the data itself, so neither can
    * be worked out while the entry is being written. The size is required by the type, and the CRC is given to the
    * entry with `withCrc`:
    *
    * {{{
    * ArchiveEntry[Some, Any]("photo.jpg", Some(size)).withCrc(crc) -> data
    * }}}
    *
    * Directories and empty entries need nothing, since their CRC is zero. Neither does an entry read out of another
    * zip, which brings its CRC with it.
    */
  def makeStored[F[_]: Async](chunkSize: Int = Defaults.defaultChunkSize): ZipArchiver[F, Some] =
    make[F, Some](ZipOutputStream.STORED, chunkSize)
}

class ZipUnarchiver[F[_]: Async] private (chunkSize: Int) extends Unarchiver[F, Option, ZipEntry] {
  override def unarchive: Pipe[F, Byte, (ArchiveEntry[Option, ZipEntry], Stream[F, Byte])] = { stream =>
    stream
      .through(toInputStream[F])
      .map(new BufferedInputStream(_, chunkSize))
      .flatMap { inputStream =>
        Stream.resource(
          Resource.make(
            Async[F].blocking(new ZipInputStream(inputStream))
          )(s => Async[F].blocking(s.close()))
        )
      }
      .flatMap { zipInputStream =>
        Unarchiver
          .readEntries(
            // There is no closeEntry() finalizer here. getNextEntry() already calls closeEntry()
            // before it advances, so it was redundant, and because a finalizer cannot be
            // interrupted it drained the whole remaining entry whenever the stream was cancelled.
            Async[F].blocking(Option(zipInputStream.getNextEntry)),
            readInputStream(Async[F].pure[InputStream](zipInputStream), chunkSize, closeAfterUse = false)
          )
          .map { case (entry, data) => (ArchiveEntry.fromUnderlying(entry), data) }
      }
  }
}

object ZipUnarchiver {
  def apply[F[_]](implicit instance: ZipUnarchiver[F]): ZipUnarchiver[F] = instance

  def make[F[_]: Async](chunkSize: Int = Defaults.defaultChunkSize): ZipUnarchiver[F] =
    new ZipUnarchiver(chunkSize)
}

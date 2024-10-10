package de.lhns.fs2.compress

import cats.effect.{Async, Ref}
import cats.syntax.all._
import fs2.{Pipe, Stream}

trait Archiver[F[_], Size[A] <: Option[A]] {
  def archive: Pipe[F, (ArchiveEntry[Size, Any], Stream[F, Byte]), Byte]
}

object Archiver {
  def checkUncompressedSize[F[_]: Async, Size[A] <: Option[A]]
      : Pipe[F, (ArchiveEntry[Size, Any], Stream[F, Byte]), (ArchiveEntry[Size, Any], Stream[F, Byte])] =
    _.map { case (entry, bytes) =>
      val newBytes = (entry.uncompressedSize: Option[Long]) match {
        case None => bytes
        case Some(expectedSize: Long) =>
          Stream.eval(Ref[F].of(0L)).flatMap { sizeRef =>
            bytes.chunks.evalTap(chunk => sizeRef.update(_ + chunk.size)).unchunks ++
              Stream.exec(sizeRef.get.map { size =>
                if (size != expectedSize)
                  throw new IllegalStateException(
                    s"Entry size of $size bytes does not match size of $expectedSize bytes specified in header"
                  )
              })
          }
      }

      (entry, newBytes)
    }
}

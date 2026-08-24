package de.lhns.fs2.compress

import cats.effect.{Async, Ref}
import cats.syntax.all._
import fs2.{Pipe, Stream}

import scala.annotation.implicitNotFound

@implicitNotFound(
  "No Unarchiver[${F}, ${Size}, ${Underlying}] in scope. Import a default instance, for example `import ZipUnarchiver.default._`, "
    + "or build one with `ZipUnarchiver.make(...)` and make it implicit."
)
trait Unarchiver[F[_], Size[A] <: Option[A], Underlying] {
  def unarchive: Pipe[F, Byte, (ArchiveEntry[Size, Underlying], Stream[F, Byte])]
}

object Unarchiver {

  /** Emits every entry of an archive together with a stream of its data.
    *
    * An archive is read in one pass, so the data of an entry can only be read while the archive is positioned at that
    * entry. Moving on to the next one is allowed at any point, and whatever was left of the current entry is skipped:
    * `nextEntry` has to read past it to find the next header anyway. Reading data that was skipped in this way is a
    * mistake, and it fails with an `IllegalStateException` rather than quietly handing back the bytes of whichever
    * entry the archive is positioned at now.
    */
  private[compress] def readEntries[F[_]: Async, Underlying](
      nextEntry: F[Option[Underlying]],
      entryData: Stream[F, Byte]
  ): Stream[F, (Underlying, Stream[F, Byte])] =
    Stream.eval(Ref[F].of(0L)).flatMap { position =>
      def checkPosition(index: Long): F[Unit] =
        position.get.flatMap { current =>
          if (current == index) Async[F].unit
          else
            Async[F].raiseError[Unit](
              new IllegalStateException(
                s"The data of archive entry $index can no longer be read because the archive has moved on to entry " +
                  s"$current. The data of an entry has to be read before the next entry is pulled."
              )
            )
        }

      def fromIndex(index: Long): Stream[F, (Underlying, Stream[F, Byte])] =
        // The position is recorded before the archive advances, so that the data of the previous entry starts failing
        // as soon as this commits to moving on, rather than once it has finished skipping ahead.
        Stream.eval(position.set(index) *> nextEntry).flatMap {
          case None => Stream.empty
          case Some(entry) =>
            val data = Stream.exec(checkPosition(index)) ++
              entryData.chunks.evalTap(_ => checkPosition(index)).unchunks
            Stream.emit((entry, data)) ++ fromIndex(index + 1)
        }

      fromIndex(0L)
    }
}

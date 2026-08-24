package de.lhns.fs2.compress

import cats.effect.{Deferred, IO}
import fs2.Stream

/** The cancellation contract for an `Archiver` and `Unarchiver` pair.
  *
  * All the `Compressor` and `Decompressor` tests are inherited and run through `ArchiveSingleFileCompressor` and
  * `ArchiveSingleFileDecompressor`, which is what puts the per entry `Resource.make(putNextEntry)(closeEntry)` on the
  * cancellation path in the first place. The tests that only make sense for archives are added on top.
  *
  * `Underlying` is a type parameter rather than a wildcard because the third parameter of `Unarchiver` is invariant,
  * and existential types are awkward to write for 2.12, 2.13 and 3 at the same time.
  */
abstract class ArchiverCancellationSuite[Underlying] extends CompressorCancellationSuite {

  /** The archiver under test, wrapped to archive a single entry of the given size. */
  protected def singleEntryCompressor(name: String, size: Long, chunkSize: Int): Compressor[IO]

  protected def unarchiver(chunkSize: Int): Unarchiver[IO, Option, Underlying]

  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(singleEntryCompressor("test", payloadSize.toLong, chunkSize))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    ArchiveSingleFileDecompressor(unarchiver(chunkSize))

  test("unarchive: cancelling inside an entry stops reading from the source") {
    // Cancelling here leaves most of the entry unread. Closing the entry would read all of it just
    // to get past it, and that is what must not happen.
    for {
      archived <- compressedSample
      arrived <- Deferred[IO, Unit]
      sourceAndCount <- countingSource(archived)
      (source, pulled) = sourceAndCount
      program = source
        .through(unarchiver(readChunkSize).unarchive)
        .flatMap { case (_, body) => body.through(pauseAfterFirstChunk(arrived)) }
        .compile
        .drain
      _ <- cancelStopsReading(program, arrived, pulled)
    } yield ()
  }

  test("unarchive: not reading an entry still finishes") {
    // Here nothing is cancelled. `take` finishes with ExitCase.Succeeded, so skipping to the end of
    // the entry is work that should still happen, and this guards against a fix that deadlocks it.
    for {
      archived <- compressedSample
      _ <- finishes(
        Stream
          .chunk(archived)
          .through(unarchiver(readChunkSize).unarchive)
          .take(1)
          .map(_ => ())
          .compile
          .drain
      )
    } yield ()
  }
}

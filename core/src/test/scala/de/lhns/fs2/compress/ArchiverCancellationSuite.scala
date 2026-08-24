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

  test("unarchive: cancellation completes while an entry body is still arriving") {
    // This is issue #113 itself. We are inside the entry, the source is still delivering, and three
    // quarters of the entry have yet to arrive. Cancelling must not wait for the rest of it.
    for {
      archived <- compressedSample
      inEntry <- Deferred[IO, Unit]
      program = slowSource(archived, headSize(archived.size))
        .through(unarchiver(Defaults.defaultChunkSize).unarchive)
        .flatMap { case (_, body) => body.through(signalFirstChunk(inEntry)) }
        .compile
        .drain
      _ <- assertCancelsPromptly(program, inEntry.get)
    } yield ()
  }

  test("unarchive: not consuming an entry body still terminates") {
    // The source here is deliberately in memory and not slowed down. `take` finishes with
    // ExitCase.Succeeded rather than a cancellation, so skipping to the end of the entry is work
    // that should still happen. This test guards against a fix that deadlocks that path.
    for {
      archived <- compressedSample
      _ <- assertCompletesPromptly(
        Stream
          .chunk(archived)
          .through(unarchiver(Defaults.defaultChunkSize).unarchive)
          .take(1)
          .map(_ => ())
          .compile
          .drain
      )
    } yield ()
  }
}

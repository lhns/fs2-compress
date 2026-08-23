package de.lhns.fs2.compress

import cats.effect.{Deferred, IO}
import fs2.Stream

/** The cancellation contract for an `Archiver` / `Unarchiver` pair.
  *
  * Every `Compressor` / `Decompressor` scenario is inherited by routing through `ArchiveSingleFileCompressor` /
  * `ArchiveSingleFileDecompressor`, which is what puts the per entry `Resource.make(putNextEntry)(closeEntry)` on the
  * cancellation path in the first place. On top of that come the scenarios that only exist for archives.
  *
  * `Underlying` is a type parameter rather than a wildcard because the third parameter of `Unarchiver` is invariant and
  * existentials are awkward across 2.12 / 2.13 / 3.
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
    // This is issue #113 verbatim: we are provably inside the entry, the source is still delivering,
    // and three quarters of the entry have yet to arrive. Cancelling must not wait for the rest.
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
    // Deliberately an ungated, fully in memory source: `take` finalises with ExitCase.Succeeded
    // rather than cancellation, so skipping to the end of the entry here is legitimate work. This
    // guards against a fix that deadlocks the normal skip path.
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

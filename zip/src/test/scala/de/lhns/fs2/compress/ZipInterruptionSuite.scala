package de.lhns.fs2.compress

import cats.effect.{Deferred, IO}
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import scala.concurrent.duration._

/** Tests verifying that the zip archiver and unarchiver can be interrupted mid-entry.
  *
  * The underlying issue: `ZipInputStream.closeEntry()` and `ZipOutputStream.closeEntry()` are
  * called via `Async[F].blocking` in the zip-entry resource finalizer. These methods block the JVM
  * thread while draining/flushing remaining entry data. Because `IO.blocking` does not interrupt
  * its thread on cancellation, this prevents fiber cancellation from ever completing, causing the
  * application to hang on shutdown (see issue #113).
  *
  * TODO: Remove the `.ignore` tags once PR #156 is merged:
  *   https://github.com/lhns/fs2-compress/pull/156
  *
  * Without the fix in PR #156, these tests hang (the resource finalizer calls
  * `IO.blocking(closeEntry())` on a stalled source/output) and fail via the timeout assertion.
  * With the fix, `closeEntry()` is skipped on `ExitCase.Cancelled` / `ExitCase.Errored`, so
  * interruption completes promptly.
  */
class ZipInterruptionSuite extends CatsEffectSuite {

  private val zipUnarchiver: ZipUnarchiver[IO] = ZipUnarchiver.make()

  /** Builds a STORED-method zip file in memory.
    *
    * Using STORED (no deflate) gives a fully predictable byte layout:
    *   - local file header : 34 bytes (30 fixed + 4 for filename "test")
    *   - entry content     : `content.length` bytes (raw, 1:1 with input)
    *
    * This makes it trivial to reason about how many content bytes are available after feeding only
    * the first N bytes of the zip file to the unarchiver.
    */
  private def buildStoredZipBytes(content: Array[Byte]): IO[Array[Byte]] = {
    val archiver = ZipArchiver.makeStored[IO]()
    Stream
      .chunk(Chunk.array(content))
      .through(ArchiveSingleFileCompressor.forName(archiver, "test", content.length.toLong).compress)
      .compile
      .toVector
      .map(_.toArray)
  }

  // TODO: Remove `.ignore` once PR #156 is merged (https://github.com/lhns/fs2-compress/pull/156).
  //
  // How the bug manifests (ZipUnarchiver):
  //   The zip-entry Resource finalizer unconditionally calls
  //   `IO.blocking(zipInputStream.closeEntry())`. `closeEntry()` must drain ALL remaining entry
  //   bytes from the underlying InputStream before returning. If the InputStream is backed by a
  //   stalled source (e.g. a slow HTTP connection), `IO.blocking` never interrupts its thread, so
  //   `closeEntry()` blocks indefinitely and `fiber.cancel` never returns — the application
  //   cannot shut down in response to SIGTERM / Ctrl-C.
  //
  // Why this test is deterministic (no IO.sleep / timing):
  //   A `Deferred[IO, Unit]` (`entryAcquired`) fires the *instant* `getNextEntry()` returns —
  //   i.e. the entry resource is acquired and `closeEntry()` is guaranteed to run in cleanup.
  //   `entryAcquired.get` replaces the former `IO.sleep(n.millis)` hack: cancellation is
  //   triggered exactly when (and not before) the entry is open.
  //
  // Source design:
  //   The first 10 000 bytes of a 1 MiB STORED zip (34-byte header + 9 966 bytes of raw content)
  //   followed by `Stream.never[IO]` — no more bytes, no EOF. This models a stalled network
  //   stream. Once the initial bytes are consumed, any `IO.blocking(InputStream.read())` call
  //   blocks forever.
  //
  // Without PR #156: `fiber.cancel` waits for `IO.blocking(closeEntry())`, which is blocked
  //   reading remaining bytes from the stalled source → the 5-second timeout assertion fires.
  // With PR #156: ExitCase.Cancelled → `Async[F].unit` → cancellation completes instantly.
  test("zip unarchiver can be interrupted mid-entry".ignore) {
    for {
      random <- Random.scalaUtilRandom[IO]
      // Random (incompressible) content — the STORED zip is large so closeEntry() has many
      // bytes to drain, making the stall immediate and obvious.
      content <- random.nextBytes(1024 * 1024)
      zipBytes <- buildStoredZipBytes(content)

      // Fires the instant IO.blocking(getNextEntry()) returns Some(entry).
      entryAcquired <- Deferred[IO, Unit]

      // Source: 10 000 bytes (header + 9 966 bytes of content), then stalls forever.
      // Stream.never never yields EOF, so IO.blocking(InputStream.read()) blocks
      // indefinitely once the initial bytes are exhausted.
      fiber <- Stream
        .chunk[IO, Byte](Chunk.array(zipBytes).take(10000))
        .append(Stream.never[IO])
        .through(zipUnarchiver.unarchive)
        // evalTap fires exactly once — immediately after getNextEntry() returns.
        // No sleep, no polling: this is the precise moment the entry resource is acquired.
        .evalTap(_ => entryAcquired.complete(()).void)
        .flatMap { case (_, entryStream) => entryStream }
        .compile
        .drain
        .start

      // Block until we are certain the entry resource is open — no sleep, no race condition.
      _ <- entryAcquired.get

      // Cancel. The entry Resource is released with ExitCase.Cancelled.
      //   Without PR #156: IO.blocking(closeEntry()) reads from the InputStream backed by
      //     Stream.never → blocks forever → timeout fires.
      //   With PR #156: Async[F].unit → cancellation completes instantly.
      _ <- fiber.cancel.timeout(5.seconds)
    } yield ()
  }

  // TODO: Remove `.ignore` once PR #156 is merged (https://github.com/lhns/fs2-compress/pull/156).
  //
  // How the bug manifests (ZipArchiver):
  //   The zip-entry Resource finalizer unconditionally calls
  //   `IO.blocking(zipOutputStream.closeEntry())`. `closeEntry()` flushes the DEFLATE compressor
  //   output to the underlying OutputStream. If that OutputStream's pipe buffer is full and the
  //   consumer has stopped draining, the flush write blocks the JVM thread, preventing fiber
  //   cancellation from completing.
  //
  // Why this test is deterministic (no IO.sleep / timing):
  //   `chunkSize = 1` makes readOutputStream's internal PipedStreamBuffer hold exactly 1 byte.
  //   After the consumer takes that 1 byte (.take(1)), the archiver background fiber immediately
  //   refills the 1-byte buffer and blocks — the buffer is *always* full when closeEntry() runs,
  //   regardless of scheduling.  A `Deferred[IO, Unit]` (`entryStarted`) fires when the first
  //   compressed byte is produced, confirming putNextEntry() was called and the entry resource is
  //   open before we trigger cancellation.
  //
  // Without PR #156: `fiber.cancel` waits for `IO.blocking(closeEntry())`, which is blocked
  //   flushing to the full 1-byte buffer → the 5-second timeout assertion fires.
  // With PR #156: ExitCase.Cancelled → `Async[F].unit` → cancellation completes instantly.
  test("zip archiver can be interrupted mid-entry".ignore) {
    // chunkSize = 1 → readOutputStream's PipedStreamBuffer has capacity exactly 1 byte.
    // After the consumer takes the one buffered byte, the archiver immediately refills the
    // buffer and blocks — the buffer is deterministically full when closeEntry() runs.
    val tinyBufferArchiver = ZipArchiver.makeDeflated[IO](chunkSize = 1)
    for {
      random <- Random.scalaUtilRandom[IO]
      // Random data does not compress; the DEFLATED output is ~1 MiB, ensuring the archiver
      // has plenty of bytes to write and will fill the 1-byte buffer immediately.
      content <- random.nextBytes(1024 * 1024)

      // Fires when the first compressed byte is produced — putNextEntry() has been called.
      entryStarted <- Deferred[IO, Unit]

      fiber <- Stream
        .chunk[IO, Byte](Chunk.array(content))
        .through(ArchiveSingleFileCompressor.forName(tinyBufferArchiver, "test").compress)
        // Signal on first byte: entry resource is definitely open.
        .evalTap(_ => entryStarted.complete(()).void)
        // Take exactly 1 byte. The archiver background fiber then refills the
        // 1-byte buffer and blocks — the buffer is full when cleanup runs.
        .take(1)
        .compile
        .drain
        .start

      // Wait until we know the entry resource is open — no sleep.
      _ <- entryStarted.get

      // Cancel. The entry Resource is released with ExitCase.Cancelled.
      //   Without PR #156: IO.blocking(closeEntry()) flushes to the full 1-byte buffer
      //     → blocks forever → timeout fires.
      //   With PR #156: Async[F].unit → cancellation completes instantly.
      _ <- fiber.cancel.timeout(5.seconds)
    } yield ()
  }
}

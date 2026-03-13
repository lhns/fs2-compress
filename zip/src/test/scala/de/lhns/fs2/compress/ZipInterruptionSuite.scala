package de.lhns.fs2.compress

import cats.effect.IO
import cats.effect.std.Random
import fs2.{Chunk, Stream}
import munit.CatsEffectSuite

import java.io.{PipedInputStream, PipedOutputStream}
import scala.concurrent.duration._

/** Tests verifying that the zip archiver and unarchiver can be interrupted mid-entry.
  *
  * The underlying issue: `ZipInputStream.closeEntry()` and `ZipOutputStream.closeEntry()` are called via
  * `Async[F].blocking` in the zip entry resource finalizer. These methods block the JVM thread while draining
  * remaining entry bytes. When the source stream is stalled (e.g. a slow network), this prevents fiber
  * cancellation from completing, causing the application to hang on shutdown (see issue #113).
  *
  * TODO: Remove the `.ignore` tags once PR #156 is merged:
  *   https://github.com/lhns/fs2-compress/pull/156
  *
  * Without the fix in PR #156, these tests hang (blocking inside `IO.blocking(closeEntry())`) and therefore fail
  * via the timeout assertion. With the fix, `closeEntry()` is skipped on `ExitCase.Cancelled` /
  * `ExitCase.Errored`, so interruption completes promptly.
  */
class ZipInterruptionSuite extends CatsEffectSuite {

  private val zipUnarchiver: ZipUnarchiver[IO] = ZipUnarchiver.make()

  /** Builds a STORED-method zip file in memory.
    *
    * Using STORED (no deflate) gives a fully predictable byte layout:
    *   - local file header : 34 bytes  (30 fixed + 4 for filename "test")
    *   - entry content     : `contentSize` bytes  (raw, 1:1 with input)
    *   - total             : ≥ contentSize + 34 bytes
    *
    * This makes it trivial to reason about how many content bytes are available after
    * feeding only the first N bytes of the zip file to the unarchiver.
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
  //   When the consumer takes only a subset of an entry's bytes (e.g. via .take), fs2 releases the
  //   zip-entry Resource with ExitCase.Cancelled.  The current finalizer unconditionally calls
  //   `IO.blocking(zipInputStream.closeEntry())`.  `closeEntry()` must drain ALL remaining entry bytes
  //   from the underlying InputStream before returning.  If the InputStream is backed by a stalled
  //   source (e.g. a slow HTTP connection), `closeEntry()` blocks the JVM thread indefinitely.
  //   Because `IO.blocking` is not interruptible, `fiber.cancel` waits forever, and the application
  //   cannot shut down in response to a signal (Ctrl-C, k8s SIGTERM, …).
  //
  // How this test demonstrates the bug:
  //   A PipedInputStream is pre-loaded with only the first 10 000 bytes of a 1 MiB STORED zip file
  //   (34-byte header + 9 966 bytes of raw content).  The pipe output is left open but idle, so
  //   pipeIn.read() blocks at the JVM level once the initial bytes are exhausted — faithfully
  //   modelling a stalled HTTP stream.  The first readInputStream chunk delivers 9 966 bytes;
  //   .take(100) is satisfied, the Resource is released as Cancelled, and the finalizer calls
  //   IO.blocking(closeEntry()).  closeEntry() tries to drain the remaining ~1 038 610 bytes but
  //   blocks on the empty pipe, so fiber.cancel never returns, and the timeout assertion fires.
  test("zip unarchiver can be interrupted mid-entry".ignore) {
    for {
      random <- Random.scalaUtilRandom[IO]
      // Random (incompressible) content ensures the STORED zip is large — any compressible
      // pattern might let the JVM zip machinery skip draining in certain edge cases.
      content <- random.nextBytes(1024 * 1024)
      zipBytes <- buildStoredZipBytes(content)

      pipeOut <- IO(new PipedOutputStream())
      // Internal pipe buffer of 4096 bytes; once exhausted pipeIn.read() blocks at the JVM level.
      pipeIn <- IO(new PipedInputStream(pipeOut, 4096))
      _ <- IO.blocking {
        // Write enough bytes so that:
        //   • getNextEntry() can parse the full 34-byte local file header, and
        //   • the first readInputStream chunk returns 9 966 content bytes (> 100),
        //     satisfying .take(100) before the pipe blocks.
        pipeOut.write(zipBytes, 0, 10000)
        pipeOut.flush()
        // pipeOut intentionally NOT closed — keeps pipeIn.read() blocking (not EOF).
      }

      fiber <- fs2.io
        .readInputStream[IO](IO.pure(pipeIn), 64 * 1024, closeAfterUse = false)
        .through(zipUnarchiver.unarchive)
        .flatMap { case (_, entryStream) => entryStream }
        // .take(100) satisfies the consumer and triggers Resource release as Cancelled.
        // Without the fix the finalizer calls IO.blocking(closeEntry()) which deadlocks
        // on the now-empty pipe queue; fiber.cancel therefore never completes.
        .take(100)
        .compile
        .drain
        .start

      _ <- IO.sleep(200.millis) // allow the fiber to reach and block in closeEntry()

      // Without PR #156 this assertion fails with TimeoutException because fiber.cancel
      // is waiting for IO.blocking(closeEntry()) to return (which it never does).
      _ <- fiber.cancel.timeout(5.seconds)
      _ <- IO.blocking(pipeOut.close()) // cleanup: unblock any residual JVM thread
    } yield ()
  }

  // TODO: Remove `.ignore` once PR #156 is merged (https://github.com/lhns/fs2-compress/pull/156).
  //
  // How the bug manifests (ZipArchiver):
  //   When the consumer of the archiver's output stream stops mid-entry (e.g. via .take), the
  //   zip-entry Resource is released with ExitCase.Cancelled.  The current finalizer unconditionally
  //   calls `IO.blocking(zipOutputStream.closeEntry())`.  For DEFLATED data, `closeEntry()` must
  //   flush the remaining buffered data from the DEFLATE compressor to the underlying OutputStream.
  //   If that OutputStream's internal queue is already full (the consumer has stopped draining),
  //   the flush blocks the JVM thread, preventing fiber cancellation from completing.
  //
  // Note: The exact blocking behaviour depends on the internal buffer size of `readOutputStream`.
  // This test documents the expected cancellation contract; it should reliably demonstrate the bug
  // for large incompressible inputs where the output queue is likely to be full when .take fires.
  test("zip archiver can be interrupted mid-entry".ignore) {
    val deflatedArchiver = ZipArchiver.makeDeflated[IO]()
    for {
      random <- Random.scalaUtilRandom[IO]
      // Random data does not compress, so the DEFLATED output is approximately the same
      // size as the input (~1 MiB).  This makes it likely that readOutputStream's internal
      // queue is already full when .take(100) fires, causing closeEntry()'s flush to block.
      content <- random.nextBytes(1024 * 1024)
      // .take(100) stops the consumer after 100 bytes of zip output, releasing the zip-entry
      // Resource as Cancelled.  Without the fix, the finalizer calls
      // IO.blocking(zipOutputStream.closeEntry()) which flushes the DEFLATE compressor.
      // If the output queue is full (the consumer has stopped draining), this write blocks
      // the JVM thread, so the stream never terminates and the timeout assertion fires.
      _ <- Stream
        .chunk[IO, Byte](Chunk.array(content))
        .through(ArchiveSingleFileCompressor.forName(deflatedArchiver, "test").compress)
        .take(100)
        .compile
        .drain
        .timeout(5.seconds)
    } yield ()
  }
}

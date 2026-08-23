package de.lhns.fs2.compress

import cats.effect.{Deferred, IO}
import fs2.{Chunk, Stream}

/** The cancellation contract for a `Compressor` / `Decompressor` pair.
  *
  * Subclasses build a *fresh* instance for a given chunk size, because the write side tests need to shrink the
  * `fs2.io.readOutputStream` pipe (see `CancellationSuite.stalledChunkSize`). Modules that only decompress override
  * [[compressorSupported]] and supply [[compressedSample]] directly.
  */
abstract class CompressorCancellationSuite extends CancellationSuite {

  protected def compressor(chunkSize: Int): Option[Compressor[IO]]

  protected def decompressor(chunkSize: Int): Decompressor[IO]

  protected def compressorSupported: Boolean = true

  /** Whether the decompressor returns from `read` after a small amount of input.
    *
    * Some codecs (snappy, bzip2, lz4, brotli) need a whole internal block before a single `InputStream#read` can return
    * anything. `fs2.io.readInputStream` performs that read inside a non-interruptible `F.blocking`, so with a
    * deliberately slow source the cancellation cannot be delivered until the entire block has trickled in - no matter
    * how the finalizers behave. For those codecs the trickling scenario would assert something no fix can deliver, so
    * it is skipped; the remaining scenarios still cover them. Verified by thread dump: those decompressors park in
    * `XInputStream.read`, whereas `ZipUnarchiver` parks in `ZipInputStream.closeEntry()` inside a resource finalizer,
    * which is the bug this suite is about.
    */
  protected def decompressorReadsAreFineGrained: Boolean = true

  /** How much of the compressed sample is handed over before the source starts to trickle. A quarter is enough for any
    * of these formats to be well inside the payload, and leaves three quarters that a draining finalizer would have to
    * wait for.
    */
  protected def headSize(size: Int): Int = math.max(1, size / 4)

  protected def compressedSample: IO[Chunk[Byte]] =
    compressor(Defaults.defaultChunkSize) match {
      case Some(instance) =>
        randomBytes(payloadSize).flatMap { payload =>
          Stream.chunk(payload).through(instance.compress).compile.to(Chunk)
        }
      case None =>
        IO.raiseError(new IllegalStateException("decompress only modules must override compressedSample"))
    }

  if (compressorSupported) {

    test("compress: cancellation completes while downstream has stopped draining") {
      for {
        payload <- randomBytes(payloadSize)
        firstChunk <- Deferred[IO, Unit]
        instance = compressor(stalledChunkSize).get
        program = Stream
          .chunk(payload)
          .through(instance.compress)
          .through(stalledSink(firstChunk))
          .compile
          .drain
        _ <- assertCancelsPromptly(program, firstChunk.get)
      } yield ()
    }

    test("compress: early downstream termination terminates the stream") {
      for {
        payload <- randomBytes(payloadSize)
        instance = compressor(stalledChunkSize).get
        _ <- assertCompletesPromptly(
          Stream.chunk(payload).through(instance.compress).take(1).compile.drain
        )
      } yield ()
    }
  }

  if (decompressorReadsAreFineGrained) {
    test("decompress: cancellation completes while the source is still trickling") {
      for {
        sample <- compressedSample
        started <- Deferred[IO, Unit]
        program = slowSource(sample, headSize(sample.size))
          .through(decompressor(Defaults.defaultChunkSize).decompress)
          .through(signalFirstChunk(started))
          .compile
          .drain
        _ <- assertCancelsPromptly(program, started.get)
      } yield ()
    }
  }

  test("decompress: cancellation completes while downstream has stopped draining") {
    for {
      sample <- compressedSample
      firstChunk <- Deferred[IO, Unit]
      program = Stream
        .chunk(sample)
        .through(decompressor(Defaults.defaultChunkSize).decompress)
        .through(stalledSink(firstChunk))
        .compile
        .drain
      _ <- assertCancelsPromptly(program, firstChunk.get)
    } yield ()
  }

  test("decompress: early downstream termination terminates the stream") {
    for {
      sample <- compressedSample
      _ <- assertCompletesPromptly(
        Stream
          .chunk(sample)
          .through(decompressor(Defaults.defaultChunkSize).decompress)
          .take(1)
          .compile
          .drain
      )
    } yield ()
  }

  test("decompress: an upstream error is surfaced and terminates the stream") {
    for {
      sample <- compressedSample
      source = Stream.chunk(sample.take(headSize(sample.size))) ++ Stream.raiseError[IO](boom)
      result <- assertCompletesPromptly(
        source.through(decompressor(Defaults.defaultChunkSize).decompress).compile.drain.attempt
      )
      _ <- IO(assert(result.isLeft, "the upstream error was swallowed instead of being surfaced"))
    } yield ()
  }
}

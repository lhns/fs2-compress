package de.lhns.fs2.compress

import cats.effect.{Deferred, IO}
import fs2.{Chunk, Stream}

/** The cancellation contract for a `Compressor` and `Decompressor` pair.
  *
  * Subclasses build a new instance for a given chunk size, because the tests on the write side need to shrink the
  * `fs2.io.readOutputStream` pipe (see `CancellationSuite.stalledChunkSize`). Modules that can only decompress override
  * [[compressorSupported]] and provide [[compressedSample]] themselves.
  */
abstract class CompressorCancellationSuite extends CancellationSuite {

  protected def compressor(chunkSize: Int): Option[Compressor[IO]]

  protected def decompressor(chunkSize: Int): Decompressor[IO]

  protected def compressorSupported: Boolean = true

  /** Whether the decompressor returns from `read` once it has read a small amount of input.
    *
    * Snappy, bzip2, lz4 and brotli need a whole internal block before a single `InputStream#read` can return anything.
    * `fs2.io.readInputStream` performs that read inside an `F.blocking` that cannot be interrupted, so with a
    * deliberately slow source the cancellation is not delivered until the whole block has arrived, no matter what the
    * finalizers do. For those codecs the test with the slow source would ask for something that no fix can provide, so
    * it is skipped. The other tests still cover them.
    *
    * Thread dumps confirm the difference: those decompressors park in `XInputStream.read`, while `ZipUnarchiver` parks
    * in `ZipInputStream.closeEntry()` inside a resource finalizer, which is the bug this suite is about.
    */
  protected def decompressorReadsAreFineGrained: Boolean = true

  /** How much of the compressed sample is delivered before the source starts to trickle. A quarter puts every one of
    * these formats well inside the payload, and leaves three quarters that a finalizer which drains would have to wait
    * for.
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

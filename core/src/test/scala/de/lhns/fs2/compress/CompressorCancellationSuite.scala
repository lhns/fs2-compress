package de.lhns.fs2.compress

import cats.effect.{Deferred, IO}
import fs2.{Chunk, Stream}

/** The cancellation contract for a `Compressor` and `Decompressor` pair.
  *
  * Subclasses build a new instance for a given chunk size, because these tests need to control both the size of the
  * `fs2.io.readOutputStream` pipe and how much a decompressor reads before it produces output. Modules that can only
  * decompress override [[compressorSupported]] and provide [[compressedSample]] themselves.
  */
abstract class CompressorCancellationSuite extends CancellationSuite {

  protected def compressor(chunkSize: Int): Option[Compressor[IO]]

  protected def decompressor(chunkSize: Int): Decompressor[IO]

  protected def compressorSupported: Boolean = true

  protected def compressedSample: IO[Chunk[Byte]] =
    compressor(Defaults.defaultChunkSize) match {
      case Some(instance) =>
        randomBytes(payloadSize).flatMap { payload =>
          Stream.chunk(payload).through(instance.compress).compile.to(Chunk)
        }
      case None =>
        IO.raiseError(new IllegalStateException("modules that only decompress must override compressedSample"))
    }

  if (compressorSupported) {

    test("compress: cancelling finishes while downstream has stopped draining") {
      for {
        payload <- randomBytes(payloadSize)
        arrived <- Deferred[IO, Unit]
        instance = compressor(stalledChunkSize).get
        program = Stream
          .chunk(payload)
          .through(instance.compress)
          .through(pauseAfterFirstChunk(arrived))
          .compile
          .drain
        _ <- cancel(program, arrived.get)
      } yield ()
    }

    test("compress: stopping downstream early finishes the stream") {
      for {
        payload <- randomBytes(payloadSize)
        instance = compressor(stalledChunkSize).get
        _ <- finishes(Stream.chunk(payload).through(instance.compress).take(1).compile.drain)
      } yield ()
    }
  }

  test("decompress: cancelling stops reading from the source") {
    for {
      sample <- compressedSample
      arrived <- Deferred[IO, Unit]
      sourceAndCount <- countingSource(sample)
      (source, pulled) = sourceAndCount
      program = source
        .through(decompressor(readChunkSize).decompress)
        .through(pauseAfterFirstChunk(arrived))
        .compile
        .drain
      _ <- cancelStopsReading(program, arrived, pulled)
    } yield ()
  }

  test("decompress: stopping downstream early finishes the stream") {
    for {
      sample <- compressedSample
      _ <- finishes(Stream.chunk(sample).through(decompressor(readChunkSize).decompress).take(1).compile.drain)
    } yield ()
  }

  test("decompress: an error from the source is reported") {
    for {
      sample <- compressedSample
      source = Stream.chunk(sample.take(sample.size / 4)) ++ Stream.raiseError[IO](boom)
      result <- finishes(source.through(decompressor(readChunkSize).decompress).compile.drain.attempt)
      _ <- IO(assert(result.isLeft, "the error from the source was swallowed instead of being reported"))
    } yield ()
  }
}

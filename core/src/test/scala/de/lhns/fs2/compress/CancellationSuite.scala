package de.lhns.fs2.compress

import cats.effect.kernel.Outcome
import cats.effect.std.Random
import cats.effect.{Deferred, Fiber, IO, Ref}
import cats.syntax.all._
import fs2.{Chunk, Pipe, Stream}
import munit.{CatsEffectSuite, Location}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

/** Shared machinery for asserting that a compression or archiving stream can be cancelled.
  *
  * The problem these tests are about is that `Async[F].blocking` cannot be interrupted, and that a `Resource` can be
  * cancelled neither while it acquires nor while it releases. A stream that reads or writes from such a place carries
  * on after it has been cancelled. On the reading side it drains the rest of the entry before its finalizer returns,
  * and on the writing side it blocks forever trying to flush into a pipe that nobody is draining.
  *
  * Those two symptoms need different tests, because only one of them can be observed without waiting.
  *
  * Reading is checked by counting. The source is fully in memory, so nothing ever blocks, and the stream is stopped at
  * a point of the test's choosing by parking on a `Deferred` that is never completed. Parking like that can be
  * cancelled, so the cancellation takes effect immediately and the source stops being pulled. The test counts the bytes
  * taken from the source before cancelling and again afterwards: a finalizer that drains reads the rest of the entry in
  * between, one that does not reads nothing. Nothing waits, and nothing asserts how long anything took.
  *
  * Counting the difference rather than the total matters, because several decompressors read a whole internal block
  * before they produce any output at all. That read-ahead happens before the cancellation and says nothing about
  * whether cancelling works.
  *
  * Writing is checked by waiting, because the symptom is a stream that never finishes, and there is no way to observe
  * that other than to give it a deadline. [[liveBudget]] is that deadline. It is not used to synchronise anything, and
  * a working implementation finishes in milliseconds, so it can be generous.
  *
  * Note that `core` and `gzip` are also built for Scala.js, so this file must not use `java.io`, `fs2.io` or anything
  * else that only exists on the JVM.
  */
trait CancellationSuite extends CatsEffectSuite {

  /** How long a stream gets to finish before it counts as stuck. This detects a deadlock rather than measuring
    * performance, so it is deliberately much larger than anything a working implementation needs. A whole run forks a
    * JVM per module and runs them at the same time, so a stream that normally takes a few seconds can take considerably
    * longer on a loaded machine. Waiting longer costs nothing when the stream is not stuck, since the wait ends as soon
    * as it finishes.
    */
  protected def liveBudget: FiniteDuration = 1.minute

  /** Deliberately small. `fs2.io.readOutputStream` allocates a `PipedStreamBuffer(chunkSize)`, so the capacity of the
    * pipe is the chunk size. At 64 bytes, a consumer that stops draining makes the next write block, including the
    * trailer that `closeEntry()` and `OutputStream#close()` write.
    */
  protected def stalledChunkSize: Int = 64

  /** Small enough that a decompressor produces its first chunk of output after reading very little, which is what
    * leaves a large gap between draining the source and not draining it.
    */
  protected def readChunkSize: Int = 1024

  protected def payloadSize: Int = 128 * 1024

  /** Comfortably above [[liveBudget]], so that a stuck stream is reported by the helpers below, which say what is stuck
    * and why, rather than by munit cutting the test off first with a bare timeout.
    */
  override def munitIOTimeout: Duration = 3.minutes

  protected val boom: RuntimeException = new RuntimeException("boom")

  protected def randomBytes(size: Int): IO[Chunk[Byte]] =
    Random.scalaUtilRandom[IO].flatMap(_.nextBytes(size)).map(Chunk.array(_))

  /** A source that keeps count of how many bytes have been taken from it. Everything is already in memory, so reading
    * from it never blocks and never has to wait for anything.
    */
  protected def countingSource(bytes: Chunk[Byte]): IO[(Stream[IO, Byte], IO[Int])] =
    Ref[IO].of(0).map { counter =>
      val source = Stream
        .chunk[IO, Byte](bytes)
        .chunkLimit(readChunkSize)
        .evalTap(chunk => counter.update(_ + chunk.size))
        .unchunks
      (source, counter.get)
    }

  /** Emits the first chunk, announces that it got there, and then parks for good.
    *
    * Parking on a `Deferred` that is never completed can be cancelled, so the stream stops exactly here and stays
    * cancellable. Upstream is left in the middle of its work, which is the state these tests want to cancel from.
    */
  protected def pauseAfterFirstChunk[A](arrived: Deferred[IO, Unit]): Pipe[IO, A, A] =
    _.chunks.zipWithIndex.flatMap { case (chunk, index) =>
      Stream.chunk(chunk) ++ {
        if (index == 0) Stream.exec(arrived.complete(()).attempt *> IO.never[Unit])
        else Stream.empty
      }
    }

  /** Runs `program`, waits for `readyToCancel`, cancels it, and asserts that the cancellation is carried out.
    *
    * The way the cancellation is written below looks roundabout, but the shorter versions do not work.
    * `fiber.cancel.timeout(d)` hangs, because `cancel` is `IO.uncancelable` and `timeout` is `race(_, sleep)`, which
    * cancels the loser and then waits for that cancellation to finish. Waiting for an uncancelable region to be
    * cancelled is exactly the situation these tests are about. `fiber.cancel.background.use(...)` hangs for the same
    * reason, because releasing the `Resource` cancels the fiber that does the cancelling and waits for it.
    *
    * Starting the cancellation in its own fiber and waiting for it with `join.timeout(...)`, which can be cancelled, is
    * the only version that always finishes. When it times out, that fiber and one blocked thread are left behind. That
    * is the price of reporting a failure instead of hanging the whole suite, and it only happens on a run that has
    * already failed.
    */
  protected def cancel[A](program: IO[A], readyToCancel: IO[Unit])(implicit loc: Location): IO[Unit] =
    program.start.flatMap(fiber => readyToCancel *> cancelFiber(fiber))

  private def cancelFiber[A](fiber: Fiber[IO, Throwable, A])(implicit loc: Location): IO[Unit] =
    for {
      canceller <- fiber.cancel.start
      verdict <- canceller.join.timeout(liveBudget).attempt
      _ <- verdict match {
        case Right(Outcome.Succeeded(_)) => IO.unit
        case Right(Outcome.Errored(t)) => IO.raiseError(t)
        case Right(Outcome.Canceled()) => IO(fail("the fiber doing the cancelling was itself cancelled"))
        case Left(_: TimeoutException) =>
          IO(
            fail(
              s"cancelling did not finish within $liveBudget, which means a finalizer is stuck in an " +
                "Async[F].blocking call that cannot be interrupted"
            )
          )
        case Left(t) => IO.raiseError(t)
      }
    } yield ()

  /** Asserts that `program` finishes on its own within [[liveBudget]]. On timeout the fiber is deliberately not
    * cancelled, because that would hang for the reasons given above.
    */
  protected def finishes[A](program: IO[A])(implicit loc: Location): IO[A] =
    program.start.flatMap { fiber =>
      fiber.join.timeout(liveBudget).attempt.flatMap {
        case Right(Outcome.Succeeded(fa)) => fa
        case Right(Outcome.Errored(t)) => IO.raiseError(t)
        case Right(Outcome.Canceled()) => IO(fail("the stream was unexpectedly cancelled"))
        case Left(_: TimeoutException) =>
          IO(
            fail(
              s"the stream did not finish within $liveBudget, so it is stuck: either waiting on something that will " +
                "never happen, or inside an Async[F].blocking call that cannot be interrupted"
            )
          )
        case Left(t) => IO.raiseError(t)
      }
    }

  /** Runs `program`, waits for `arrived`, cancels it, and asserts that cancelling did not read any further from the
    * source. A chunk or two may still be in flight, so the bound is not zero, but it is far below the amount that
    * draining the rest of the entry would read.
    */
  protected def cancelStopsReading(
      program: IO[Unit],
      arrived: Deferred[IO, Unit],
      pulled: IO[Int]
  )(implicit loc: Location): IO[Unit] =
    for {
      fiber <- program.start
      _ <- arrived.get
      before <- pulled
      _ <- cancelFiber(fiber)
      after <- pulled
      _ <- IO(
        assert(
          after - before <= readChunkSize * 2,
          s"cancelling read another ${after - before} bytes from the source, so it drained the rest of the entry " +
            s"instead of stopping (it had read $before bytes before being cancelled)"
        )
      )
    } yield ()
}

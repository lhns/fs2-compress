package de.lhns.fs2.compress

import cats.effect.kernel.Outcome
import cats.effect.std.Random
import cats.effect.{Deferred, IO}
import cats.syntax.all._
import fs2.{Chunk, Pipe, Stream}
import munit.{CatsEffectSuite, Location}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

/** Shared machinery for asserting that cancelling a compression or archiving stream actually completes, rather than
  * parking forever inside an `Async[F].blocking` finalizer that cannot be interrupted (see issue #113).
  *
  * What matters most in these tests is where the fiber is parked when the cancellation arrives, because that decides
  * whether the test is asking for something that is possible at all.
  *
  * If it is parked in a read that will never return, such as a `Stream.never` tail or a gate that only the test cleanup
  * opens, then nothing can be done about it. `fs2.io.readInputStream` reads inside `F.blocking(is.read(...))`, so the
  * cancellation is not delivered until that read returns, and no change to the finalizers makes a difference.
  *
  * If it is parked in a finalizer that is draining or flushing bytes which are still arriving, then that is issue #113
  * itself, and it can be fixed. A download that is still running is the usual example: `closeEntry()` insists on
  * draining the rest of the entry before it returns.
  *
  * The read side therefore uses [[slowSource]], where the source keeps delivering so that each read returns quickly
  * while a lot of data is still left to drain. The write side uses [[stalledChunkSize]] with [[stalledSink]], which
  * fills the output pipe and keeps it full.
  *
  * Note that `core` and `gzip` are also built for Scala.js, so this file must not use `java.io`, `fs2.io` or anything
  * else that only exists on the JVM.
  *
  * Every duration below is an upper bound on something that a correct implementation does in milliseconds, or it widens
  * a window. No assertion ever requires an operation to be slow, so a badly chosen value can only make a test pass when
  * it should not, and never fail when it should not.
  */
trait CancellationSuite extends CatsEffectSuite {

  /** How long `Fiber#cancel` is allowed to take. A correct implementation needs a few milliseconds, so this detects a
    * hang rather than measuring performance.
    */
  protected def cancelBudget: FiniteDuration = 1500.millis

  /** How long a stream is allowed to take to terminate on its own after downstream stops pulling or upstream fails.
    */
  protected def completionBudget: FiniteDuration = 10.seconds

  /** Widens the window during which the code under test sits inside the blocking call we want to interrupt. This is not
    * used for synchronisation; the signal passed to [[assertCancelsPromptly]] does that. If this value is too short, a
    * test passes when it should not, but it never fails when it should not.
    */
  protected def settleDelay: FiniteDuration = 100.millis

  /** Deliberately small. `fs2.io.readOutputStream` allocates a `PipedStreamBuffer(chunkSize)`, so the capacity of the
    * pipe is the chunk size. At 64 bytes, a consumer that stops draining makes the next write block, including the
    * trailer that `closeEntry()` and `OutputStream#close()` write. That is what makes the tests on the write side
    * reliable without sleeping.
    */
  protected def stalledChunkSize: Int = 64

  protected def payloadSize: Int = 128 * 1024

  /** The size of the chunks that the withheld tail of a source is released in, and the delay between them. Together
    * they decide how long a full drain takes. A correct implementation never waits for it, a broken one waits for all
    * of it.
    */
  protected def tailChunkSize: Int = 1024

  protected def tailPeriod: FiniteDuration = 100.millis

  override def munitIOTimeout: Duration = 90.seconds

  protected val boom: RuntimeException = new RuntimeException("boom")

  protected def randomBytes(size: Int): IO[Chunk[Byte]] =
    Random.scalaUtilRandom[IO].flatMap(_.nextBytes(size)).map(Chunk.array(_))

  /** `bytes`, with the first `headSize` bytes delivered at once and the remainder trickled out in [[tailChunkSize]]
    * pieces, one per [[tailPeriod]].
    *
    * The source never stops, so every blocking read returns within one period and the cancellation can be delivered.
    * What it cannot do is finish quickly, because draining the rest takes about
    * `(bytes.size - headSize) / tailChunkSize * tailPeriod`. A finalizer that drains before it returns therefore
    * exceeds the cancellation budget by a wide margin, while one that does not is unaffected.
    */
  protected def slowSource(bytes: Chunk[Byte], headSize: Int): Stream[IO, Byte] = {
    val at = math.max(1, math.min(headSize, bytes.size - 1))
    val (head, tail) = bytes.splitAt(at)
    Stream.chunk[IO, Byte](head) ++ Stream.chunk[IO, Byte](tail).chunkLimit(tailChunkSize).metered(tailPeriod).unchunks
  }

  /** Pulls exactly one chunk and then never pulls again. Pulling once makes sure that the producer is running and that
    * the `readOutputStream` pipe is being filled. Together with [[stalledChunkSize]] this fills the pipe and keeps it
    * full.
    */
  protected def stalledSink(firstChunk: Deferred[IO, Unit]): Pipe[IO, Byte, Nothing] =
    _.chunks
      .evalTap(_ => firstChunk.complete(()).attempt.void)
      .evalMap(_ => IO.never[Unit])
      .drain

  protected def signalFirstChunk[A](signal: Deferred[IO, Unit]): Pipe[IO, A, A] =
    _.chunks.evalTap(_ => signal.complete(()).attempt.void).unchunks

  /** Runs `program` in a fiber, waits for `readyToCancel`, cancels it and asserts that the cancellation completes
    * within [[cancelBudget]].
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
  protected def assertCancelsPromptly[A](
      program: IO[A],
      readyToCancel: IO[Unit]
  )(implicit loc: Location): IO[Unit] =
    for {
      fiber <- program.start
      _ <- readyToCancel
      _ <- IO.sleep(settleDelay)
      canceller <- fiber.cancel.start
      verdict <- canceller.join.timeout(cancelBudget).attempt
      _ <- verdict match {
        case Right(Outcome.Succeeded(_)) =>
          fiber.join.timeout(cancelBudget).flatMap {
            case Outcome.Canceled() => IO.unit
            case other => IO(fail(s"expected the fiber to end as Canceled but it ended as $other"))
          }
        case Right(Outcome.Errored(t)) => IO.raiseError(t)
        case Right(Outcome.Canceled()) => IO(fail("the canceller fiber was itself cancelled"))
        case Left(_: TimeoutException) =>
          IO(
            fail(
              s"fiber.cancel did not complete within $cancelBudget: a finalizer is parked in a " +
                "non-interruptible Async[F].blocking call, so the cancellation can never be " +
                "delivered (issue #113)"
            )
          )
        case Left(t) => IO.raiseError(t)
      }
    } yield ()

  /** Asserts that `program` finishes on its own within [[completionBudget]]. This is for the cases where nothing is
    * cancelled explicitly but the stream can still deadlock in a finalizer. On timeout the fiber is deliberately not
    * cancelled, because that would hang for the reasons given above.
    */
  protected def assertCompletesPromptly[A](program: IO[A])(implicit loc: Location): IO[A] =
    program.start.flatMap { fiber =>
      fiber.join.timeout(completionBudget).attempt.flatMap {
        case Right(Outcome.Succeeded(fa)) => fa
        case Right(Outcome.Errored(t)) => IO.raiseError(t)
        case Right(Outcome.Canceled()) => IO(fail("the stream was unexpectedly cancelled"))
        case Left(_: TimeoutException) =>
          IO(
            fail(
              s"the stream did not terminate within $completionBudget: a finalizer is parked in a " +
                "non-interruptible Async[F].blocking call (issue #113)"
            )
          )
        case Left(t) => IO.raiseError(t)
      }
    }
}

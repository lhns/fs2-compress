package de.lhns.fs2.compress

import cats.effect.kernel.Outcome
import cats.effect.std.Random
import cats.effect.{Deferred, IO}
import cats.syntax.all._
import fs2.{Chunk, Pipe, Stream}
import munit.{CatsEffectSuite, Location}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

/** Shared machinery for asserting that cancelling a compression / archiving stream actually completes, instead of
  * parking forever inside a non-interruptible `Async[F].blocking` finalizer (see issue #113).
  *
  * The most important design constraint here is *where the fiber is parked* when the cancellation arrives, because that
  * decides whether a test is asserting something achievable at all:
  *
  *   - Parked in a mid-stream read that will never return (a `Stream.never` tail, or a gate that is only opened by the
  *     test cleanup): `fs2.io.readInputStream` reads via `F.blocking(is.read(...))`, so cancellation cannot be
  *     delivered until that read returns. No amount of finalizer fixing helps, and such a test stays red forever.
  *   - Parked in a *finalizer* that is draining or flushing bytes which are still moving: that is the real issue #113
  *     (a live download whose remaining entry bytes `closeEntry()` insists on draining before it returns), and that is
  *     what can actually be fixed.
  *
  * So the read side uses [[slowSource]] - the source keeps delivering, individual reads return promptly, but there is a
  * lot left to drain - while the write side uses [[stalledChunkSize]] together with [[stalledSink]], which makes the
  * output pipe full by construction.
  *
  * Cross-platform note: `core` and `gzip` also cross-build to Scala.js, so this file must not reference `java.io`,
  * `fs2.io` or anything else that is JVM-only.
  *
  * Timing policy: every duration below is an *upper* bound on something a correct implementation finishes in
  * milliseconds, or a window widener. No assertion ever requires an operation to be slow. A mistuned constant can
  * therefore only ever produce a false pass, never a false failure.
  */
trait CancellationSuite extends CatsEffectSuite {

  /** How long `Fiber#cancel` is allowed to take. A correct implementation needs single digit milliseconds; this is a
    * hang detector, not a benchmark.
    */
  protected def cancelBudget: FiniteDuration = 1500.millis

  /** How long a stream is allowed to take to terminate on its own after downstream stops pulling or upstream fails.
    */
  protected def completionBudget: FiniteDuration = 10.seconds

  /** Purely widens the window during which the subject sits inside the blocking call we want to interrupt. This is
    * *not* synchronisation - the signal handed to [[assertCancelsPromptly]] is the real one. If this were too short the
    * result would be a false pass, never a failure.
    */
  protected def settleDelay: FiniteDuration = 100.millis

  /** Deliberately tiny. `fs2.io.readOutputStream` allocates a `PipedStreamBuffer(chunkSize)`, so the pipe capacity *is*
    * the chunk size. At 64 bytes, a consumer that stops draining guarantees that the very next write - including the
    * trailer written by `closeEntry()` / `OutputStream#close()` \- blocks. That is what makes the write side tests
    * deterministic without sleeping.
    */
  protected def stalledChunkSize: Int = 64

  protected def payloadSize: Int = 128 * 1024

  /** Size of the chunks the withheld tail of a source is released in, and the delay between them. Together these decide
    * how long a full drain takes: a correct implementation never waits for it, a broken one waits for all of it.
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
    * The point is that the source never stops: every blocking read returns within one period, so cancellation *can* be
    * delivered. What it cannot do is finish quickly - draining the rest takes roughly
    * `(bytes.size - headSize) / tailChunkSize * tailPeriod`. A finalizer that insists on draining before it returns
    * therefore blows the cancellation budget by a wide margin, while one that does not is unaffected.
    */
  protected def slowSource(bytes: Chunk[Byte], headSize: Int): Stream[IO, Byte] = {
    val at = math.max(1, math.min(headSize, bytes.size - 1))
    val (head, tail) = bytes.splitAt(at)
    Stream.chunk[IO, Byte](head) ++ Stream.chunk[IO, Byte](tail).chunkLimit(tailChunkSize).metered(tailPeriod).unchunks
  }

  /** Pulls exactly one chunk - so the producer is definitely running and the `readOutputStream` pipe is definitely
    * being filled - and then never pulls again. Combined with [[stalledChunkSize]] this saturates the pipe by
    * construction.
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
    * Do not "simplify" the cancellation dance below:
    *   - `fiber.cancel.timeout(d)` hangs. `cancel` is `IO.uncancelable`, and `timeout` is `race(_, sleep)`, which
    *     cancels the loser and waits for that cancellation to finish. Cancelling an uncancelable region back-pressures
    *     until it exits - which is exactly the situation under test.
    *   - `fiber.cancel.background.use(...)` hangs for the same reason: the `Resource` release cancels the canceller
    *     fiber and waits for it.
    *
    * `.start` plus a cancelable `join.timeout(...)` is the only bounded formulation. On timeout the canceller fiber,
    * and one blocked platform thread, are abandoned; that is the price of reporting a failure instead of hanging the
    * suite, and it only happens on an already red run.
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

  /** Asserts that `program` terminates on its own within [[completionBudget]]. Used where nothing is cancelled
    * explicitly but the stream can still deadlock in a finalizer. Deliberately does not cancel the fiber on timeout -
    * that would hang, for the reasons above.
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

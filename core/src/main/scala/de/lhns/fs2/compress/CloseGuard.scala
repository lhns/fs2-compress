package de.lhns.fs2.compress

import cats.MonadThrow
import cats.effect.Resource
import cats.effect.Resource.ExitCase
import cats.syntax.applicativeError._

private[compress] object CloseGuard {

  /** Like `Resource.make`, but runs `abort` instead of `close` on any unsuccessful exit, discarding
    * whatever error `abort` may raise.
    *
    * Every archiver and compressor here writes into the `fs2.io.readOutputStream` pipe, which holds
    * exactly `chunkSize` bytes. Once the consumer stops draining it, any further write blocks - and
    * `Async[F].blocking` cannot be interrupted, so a write that happens in an uncancelable region
    * blocks forever and `fiber.cancel` never returns (issue #113).
    *
    * Both halves of a `Resource` are uncancelable regions, so the underlying stream is opened and
    * closed *inside* the stream instead, where `Async[F].interruptible` can actually be interrupted.
    * What is left for this guard is the abnormal path: when the stream did not get that far, the
    * `close()` still has to happen, and it has to happen without blocking. `abort` is therefore
    * expected to close the fs2 `OutputStream` first - `fs2.io.internal.PipedStreamBuffer` turns
    * writes to a closed pipe into non-blocking no-ops rather than errors, so `close()` still runs to
    * completion and still releases whatever native resources it owns (`Deflater.end()`,
    * `ZSTD_freeCStream`, brotli4j's `Encoder`). Only the trailer bytes are dropped, which is exactly
    * right for output nobody is reading anymore.
    *
    * Note that fs2 reports `ExitCase.Succeeded` for early downstream termination (`take`, `head`)
    * and only a genuine interruption as `ExitCase.Canceled`. That distinction does not matter here,
    * because terminating downstream of `readOutputStream` cancels the writing fiber, which reaches
    * this finalizer as a real cancellation.
    */
  def apply[F[_], A](acquire: F[A])(close: A => F[Unit], abort: A => F[Unit])(implicit
      F: MonadThrow[F]
  ): Resource[F, A] =
    Resource.makeCase(acquire) {
      case (a, ExitCase.Succeeded) => close(a)
      case (a, _) => abort(a).handleError(_ => ())
    }
}

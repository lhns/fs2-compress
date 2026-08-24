package de.lhns.fs2.compress

import cats.effect.{Async, Resource}
import fs2.io._
import fs2.{Pipe, Stream}

import java.io.OutputStream

/** Cancelable versions of `fs2.io.readOutputStream` for codecs that are `java.io.OutputStream`s.
  *
  * Cancellation is the reason these exist. `Async[F].blocking` cannot be interrupted, and a `Resource` can be cancelled
  * neither while it acquires nor while it releases. A write in one of those places therefore blocks forever once the
  * consumer stops draining the pipe, and the cancellation never arrives (issue #113).
  */
private[compress] object OutputStreams {

  /** Like `fs2.io.readOutputStream`, except that `write` is given `wrapOutputStream` applied to the pipe, and that
    * closing that stream can be cancelled.
    */
  def readWrappedOutputStream[F[_]: Async, A <: OutputStream](
      chunkSize: Int
  )(wrapOutputStream: OutputStream => A)(write: A => Stream[F, Nothing]): Stream[F, Byte] =
    readOutputStream[F](chunkSize) { pipe =>
      Resource
        .make(Async[F].blocking(wrapOutputStream(pipe))) { outputStream =>
          // This only runs if `write` was cancelled or failed before it closed the stream itself.
          // The pipe is closed first, because that is what stops this from blocking: a closed pipe
          // discards writes instead of backpressuring them, so `close()` returns even though nobody
          // is reading, and it still releases everything it holds. fs2 closes the pipe as well, but
          // not until this release has returned, which is too late to help here. An error raised at
          // this point only describes the truncation we just caused, so it is ignored.
          Async[F].void(Async[F].attempt(Async[F].blocking {
            pipe.close()
            outputStream.close()
          }))
        }
        .use { outputStream =>
          // The stream is closed here instead of in the release above, because a cancellation can
          // interrupt it here. If that happens, the release closes it instead. Either way the same
          // bytes are written.
          (write(outputStream) ++
            Stream.exec(Async[F].interruptible(outputStream.close()))).compile.drain
        }
    }

  /** [[readWrappedOutputStream]] as a `Pipe`, for codecs that simply write everything they are given. */
  def throughOutputStream[F[_]: Async](
      chunkSize: Int
  )(wrapOutputStream: OutputStream => OutputStream): Pipe[F, Byte, Byte] = { stream =>
    readWrappedOutputStream[F, OutputStream](chunkSize)(wrapOutputStream) { outputStream =>
      stream.through(writeOutputStream(Async[F].pure(outputStream), closeAfterUse = false))
    }
  }
}

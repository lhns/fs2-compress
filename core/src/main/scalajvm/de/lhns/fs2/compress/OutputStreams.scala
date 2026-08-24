package de.lhns.fs2.compress

import cats.effect.{Async, Resource}
import fs2.io._
import fs2.{Pipe, Stream}

import java.io.OutputStream

/** Cancelable variants of `fs2.io.readOutputStream` for codecs that are `java.io.OutputStream`s.
  *
  * `Async[F].blocking` cannot be interrupted, and a `Resource` acquires and releases uncancelably. So anything written
  * from there blocks forever once the consumer stops draining the pipe, and the cancellation is never delivered (issue
  * #113).
  */
private[compress] object OutputStreams {

  /** A byte stream of everything `write` writes into the `OutputStream` built by `mkOutputStream`. */
  def write[F[_]: Async, A <: OutputStream](
      chunkSize: Int
  )(mkOutputStream: OutputStream => A)(write: A => Stream[F, Nothing]): Stream[F, Byte] =
    readOutputStream[F](chunkSize) { pipe =>
      Resource
        .make(Async[F].blocking(mkOutputStream(pipe))) { outputStream =>
          // Only reached when `write` was cancelled or failed before closing the stream itself.
          // Closing the pipe first is what keeps this from blocking: writes to a closed pipe are
          // discarded rather than backpressured, so `close()` returns instead of waiting on a
          // consumer that is gone, and still frees what it owns. fs2 closes the pipe as well, but
          // only once this release has returned, which is too late to help. Errors here only ever
          // describe the truncation we just caused, so they are dropped.
          Async[F].void(Async[F].attempt(Async[F].blocking {
            pipe.close()
            outputStream.close()
          }))
        }
        .use { outputStream =>
          // Closed here rather than in the release above because here it is cancelable: an
          // interrupted close gives up and lets the release finish the job. Same bytes either way.
          (write(outputStream) ++
            Stream.exec(Async[F].interruptible(outputStream.close()))).compile.drain
        }
    }

  /** [[write]] for a `Compressor`: the input goes straight into the `OutputStream`. */
  def compress[F[_]: Async](
      chunkSize: Int
  )(mkOutputStream: OutputStream => OutputStream): Pipe[F, Byte, Byte] = { stream =>
    write[F, OutputStream](chunkSize)(mkOutputStream) { outputStream =>
      stream.through(writeOutputStream(Async[F].pure(outputStream), closeAfterUse = false))
    }
  }
}

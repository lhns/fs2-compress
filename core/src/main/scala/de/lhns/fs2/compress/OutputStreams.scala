package de.lhns.fs2.compress

import cats.effect.Sync

import java.io.OutputStream

private[compress] object OutputStreams {

  /** The release for a compression or archive stream layered on top of an `fs2.io.readOutputStream`
    * pipe, for the paths where the stream did not already close itself: cancellation, or an error.
    *
    * Closing `pipe` first is the whole point, and it is not redundant even though
    * `readOutputStream` closes it too. fs2 closes it in a `guaranteeCase` *around* the body, so on
    * cancellation the two closes deadlock: this release blocks writing the trailer into a pipe
    * nobody drains, and the close that would unblock it cannot run until this release returns.
    * Closing it here breaks that cycle. Writes to a closed `PipedStreamBuffer` are non-blocking
    * no-ops rather than errors, so `close()` still runs to completion and still frees whatever it
    * owns - `Deflater.end()`, `ZSTD_freeCStream`, brotli4j's `Encoder`. Only the trailer bytes are
    * dropped, which is right for output nobody is reading anymore.
    *
    * Errors are discarded: a stream cut off mid-entry makes `close()` complain about the truncation
    * it already knows about (`ZipException: invalid entry size`, tar's "unclosed entries"), and a
    * finalizer raising that during cancellation would mask the real outcome. Any genuine close error
    * on the happy path has already surfaced from the stream itself.
    *
    * Closing `pipe` on the happy path is harmless: `close()` is idempotent and never blocks, and
    * `PipedStreamBuffer`'s reader checks for buffered bytes before it checks the closed flag, so it
    * still drains what is left before reporting EOF.
    */
  def abandoning[F[_]: Sync](pipe: OutputStream)(stream: OutputStream): F[Unit] =
    Sync[F].void(Sync[F].attempt(Sync[F].blocking {
      pipe.close()
      stream.close()
    }))
}

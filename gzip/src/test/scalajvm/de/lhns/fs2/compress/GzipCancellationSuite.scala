package de.lhns.fs2.compress

import cats.effect.IO

/** The control group. Gzip is implemented with `fs2.compression.Compression`, so there is no `java.io` stream and no
  * `Async[F].blocking` finalizer anywhere on its path. It is therefore expected to pass every scenario in this suite
  * *before* the interruption fix as well as after, which is what shows the harness is not asserting something
  * universally impossible.
  */
class GzipCancellationSuite extends CompressorCancellationSuite {
  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(GzipCompressor.make[IO](chunkSize = chunkSize))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    GzipDecompressor.make[IO](chunkSize)
}

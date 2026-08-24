package de.lhns.fs2.compress

import cats.effect.IO

/** The control group. Gzip is built on `fs2.compression.Compression`, so there is no `java.io` stream and no
  * `Async[F].blocking` finalizer anywhere along its path. It should therefore pass every test in this suite both before
  * and after the fix, which is what shows that these tests are not asking for something impossible.
  */
class GzipCancellationSuite extends CompressorCancellationSuite {
  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] =
    Some(GzipCompressor.make[IO](chunkSize = chunkSize))

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    GzipDecompressor.make[IO](chunkSize)
}

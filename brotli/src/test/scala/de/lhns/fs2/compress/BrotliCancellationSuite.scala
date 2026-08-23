package de.lhns.fs2.compress

import cats.effect.IO
import fs2.Chunk

import java.io.{ByteArrayOutputStream, IOException}

/** Brotli only provides a decompressor here, so there is no compressor to exercise and the sample comes from a checked
  * in fixture (128 KiB of incompressible random data) rather than from a round trip. The size matters: a tiny sample
  * would make the trickling source test vacuous.
  */
class BrotliCancellationSuite extends CompressorCancellationSuite {
  // See CompressorCancellationSuite.decompressorReadsAreFineGrained: this decompressor needs a
  // whole internal block per read, so cancellation latency is bounded by the source, not by us.
  override protected def decompressorReadsAreFineGrained: Boolean = false

  override protected def compressorSupported: Boolean = false

  override protected def compressor(chunkSize: Int): Option[Compressor[IO]] = None

  override protected def decompressor(chunkSize: Int): Decompressor[IO] =
    BrotliDecompressor.make[IO](chunkSize)

  override protected def compressedSample: IO[Chunk[Byte]] =
    IO.blocking {
      val name = "/random-128k.br"
      Option(getClass.getResourceAsStream(name)) match {
        case None => throw new IOException(s"test fixture $name not found")
        case Some(in) =>
          try {
            val out = new ByteArrayOutputStream()
            val buf = new Array[Byte](8192)
            var read = in.read(buf)
            while (read >= 0) {
              out.write(buf, 0, read)
              read = in.read(buf)
            }
            Chunk.array(out.toByteArray)
          } finally in.close()
      }
    }
}

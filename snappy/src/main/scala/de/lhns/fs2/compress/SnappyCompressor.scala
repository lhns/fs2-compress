package de.lhns.fs2.compress

import cats.effect.Async
import fs2.Pipe
import fs2.io._
import org.xerial.snappy.{
  SnappyFramedInputStream,
  SnappyFramedOutputStream,
  SnappyHadoopCompatibleOutputStream,
  SnappyInputStream,
  SnappyOutputStream
}

import java.io.{BufferedInputStream, InputStream, OutputStream}

class SnappyCompressor[F[_]: Async] private (chunkSize: Int, mode: SnappyCompressor.WriteMode) extends Compressor[F] {
  override def compress: Pipe[F, Byte, Byte] = { stream =>
    readOutputStream[F](chunkSize) { outputStream =>
      stream
        .through(writeOutputStream(Async[F].blocking[OutputStream] {
          mode.fromOutputStream(outputStream)
        }))
        .compile
        .drain
    }
  }
}

object SnappyCompressor {
  sealed trait WriteMode
  object WriteMode {
    // https://github.com/xerial/snappy-java/blob/ec23d7c611563bedce536ca4d02ebdb9a690ea91/src/main/java/org/xerial/snappy/SnappyOutputStream.java#L64
    private val DefaultBasicBlockSize = 32 * 1024
    private val DefaultHadoopBlockSize = DefaultBasicBlockSize

    /** See
      * [[https://github.com/xerial/snappy-java/blob/ec23d7c611563bedce536ca4d02ebdb9a690ea91/src/main/java/org/xerial/snappy/SnappyOutputStream.java#L59]]
      */
    final case class Basic(blockSize: Int = DefaultBasicBlockSize) extends WriteMode

    /** See
      * [[https://github.com/xerial/snappy-java/blob/ec23d7c611563bedce536ca4d02ebdb9a690ea91/src/main/java/org/xerial/snappy/SnappyFramedOutputStream.java#L34]]
      */
    final case class Framed(
        blockSize: Int = SnappyFramedOutputStream.DEFAULT_BLOCK_SIZE,
        minCompressionRatio: Double = SnappyFramedOutputStream.DEFAULT_MIN_COMPRESSION_RATIO
    ) extends WriteMode

    /** Compression for use with Hadoop libraries: it does not emit a file header but write out the current block size
      * as a preamble to each block
      */
    final case class HadoopCompatible(blockSize: Int = DefaultHadoopBlockSize) extends WriteMode
  }

  private implicit class WriteModeOps(mode: WriteMode) {
    def fromOutputStream(o: OutputStream): OutputStream = mode match {
      case r: WriteMode.Basic => new SnappyOutputStream(o, r.blockSize)
      case f: WriteMode.Framed => new SnappyFramedOutputStream(o, f.blockSize, f.minCompressionRatio)
      case h: WriteMode.HadoopCompatible => new SnappyHadoopCompatibleOutputStream(o, h.blockSize)
    }
  }

  def apply[F[_]](implicit instance: SnappyCompressor[F]): SnappyCompressor[F] = instance

  def make[F[_]: Async](
      chunkSize: Int = Defaults.defaultChunkSize,
      mode: WriteMode
  ): SnappyCompressor[F] = new SnappyCompressor(chunkSize, mode)
}

class SnappyDecompressor[F[_]: Async] private (chunkSize: Int, decompressionType: SnappyDecompressor.ReadMode)
    extends Decompressor[F] {
  override def decompress: Pipe[F, Byte, Byte] = { stream =>
    stream
      .through(toInputStream[F])
      .map(new BufferedInputStream(_, chunkSize))
      .flatMap { inputStream =>
        readInputStream(
          Async[F].blocking(
            decompressionType.fromInputStream(inputStream)
          ),
          chunkSize
        )
      }
  }
}

object SnappyDecompressor {
  sealed trait ReadMode
  object ReadMode {

    /** See
      * [[https://github.com/xerial/snappy-java/blob/ec23d7c611563bedce536ca4d02ebdb9a690ea91/src/main/java/org/xerial/snappy/SnappyInputStream.java#L36]]
      */
    final case class Basic(maxChunkSize: Int = SnappyInputStream.MAX_CHUNK_SIZE) extends ReadMode

    /** See
      * [[https://github.com/xerial/snappy-java/blob/ec23d7c611563bedce536ca4d02ebdb9a690ea91/src/main/java/org/xerial/snappy/SnappyFramedInputStream.java#L39]]
      */
    final case class Framed(verifyChecksums: Boolean = true) extends ReadMode
  }

  private implicit class ReadModeOps(mode: ReadMode) {
    def fromInputStream(i: InputStream): InputStream = mode match {
      case r: ReadMode.Basic => new SnappyInputStream(i, r.maxChunkSize)
      case f: ReadMode.Framed => new SnappyFramedInputStream(i, f.verifyChecksums)
    }
  }

  def apply[F[_]](implicit instance: SnappyDecompressor[F]): SnappyDecompressor[F] = instance

  def make[F[_]: Async](chunkSize: Int = Defaults.defaultChunkSize, mode: ReadMode): SnappyDecompressor[F] =
    new SnappyDecompressor(chunkSize, mode)
}

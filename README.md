# fs2-compress

[![Typelevel Affiliate Project](https://img.shields.io/badge/typelevel-affiliate%20project-FFB4B5.svg)](https://typelevel.org/projects/)
[![build](https://github.com/lhns/fs2-compress/actions/workflows/build.yml/badge.svg)](https://github.com/lhns/fs2-compress/actions/workflows/build.yml)
[![Release Notes](https://img.shields.io/github/release/lhns/fs2-compress.svg?maxAge=3600)](https://github.com/lhns/fs2-compress/releases/latest)
[![Maven Central](https://img.shields.io/maven-central/v/de.lhns/fs2-compress_2.13)](https://search.maven.org/artifact/de.lhns/fs2-compress_2.13)
[![Apache License 2.0](https://img.shields.io/github/license/lhns/fs2-compress.svg?maxAge=3600)](https://www.apache.org/licenses/LICENSE-2.0)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

Integrations for several compression algorithms with [Fs2](https://github.com/typelevel/fs2).

## Usage

### build.sbt

```sbt
libraryDependencies += "de.lhns" %% "fs2-compress-gzip" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-zip" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-zip4j" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-tar" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-bzip2" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-zstd" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-brotli" % "2.1.0"
libraryDependencies += "de.lhns" %% "fs2-compress-lz4" % "2.1.0"
```

## Concepts
This library introduces the following abstractions in order to work with several different compression algorithms and
archive methods.

### Compression
#### Compressor
The `Compressor` typeclass abstracts the compression of a stream of bytes.
```scala
trait Compressor[F[_]] {
  def compress: Pipe[F, Byte, Byte]
}
```
Passing a stream of bytes through the `Compressor.compress` pipe will result in a compressed stream of bytes. :tada:

#### Decompressor
The `Decompressor` typeclass abstracts the decompression of a stream of bytes.
```scala
trait Decompressor[F[_]] {
  def decompress: Pipe[F, Byte, Byte]
}
```
Passing a stream of bytes through the `Decompressor.decompress` pipe will result in a decompressed stream of bytes. :tada:

### Archives
The library also provides abstractions for working with archive formats. An archive is a collection of files and directories
which may or may not also include compression depending on the archive format.
#### ArchiveEntry
An `ArchiveEntry` represents a file or directory in an archive. It has the following signature:
```scala
case class ArchiveEntry[+Size[A] <: Option[A], Underlying](name: String, uncompressedSize: Size[Long], underlying: Underlying, ...)
```
The `Size` type parameter is used to encode whether the size of the entry is known or not. For some archive formats the size
of an entry must be known in advance, and as such the relevant `Archiver` will require that the `Size` type parameter is `Some`.
#### Archiver
The `Archiver` typeclass abstracts the creation of an archive from a stream of `ArchiveEntry` paired with the relevant data.
```scala
trait Archiver[F[_], Size[A] <: Option[A]] {
  def archive: Pipe[F, (ArchiveEntry[Size, Any], Stream[F, Byte]), Byte]
}
```
#### Unarchiver
The `Unarchiver` typeclass abstracts the extraction of an archive into a stream of `ArchiveEntry` paired with the relevant data.
```scala
trait Unarchiver[F[_], Size[A] <: Option[A], Underlying] {
  def unarchive: Pipe[F, Byte, (ArchiveEntry[Size, Underlying], Stream[F, Byte])]
}
```

## Examples

### Gzip
```scala
import cats.effect.IO
import de.lhns.fs2.compress.{GzipCompressor, GzipDecompressor}
import fs2.io.compression._
import fs2.io.file.{Files, Path}

implicit val gzipCompressor: GzipCompressor[IO] = GzipCompressor.make()
implicit val gzipDecompressor: GzipDecompressor[IO] = GzipDecompressor.make()

for {
  _ <- Files[IO].readAll(Path("file"))
    .through(GzipCompressor[IO].compress)
    .through(Files[IO].writeAll(Path("file.gz")))
    .compile
    .drain
  _ <- Files[IO].readAll(Path("file.gz"))
    .through(GzipDecompressor[IO].decompress)
    .through(Files[IO].writeAll(Path("file")))
    .compile
    .drain
} yield ()
```

### Zip
```scala
/** Compress a directory into a .zip file
  * @param toCompress
  *   The directory to compress
  * @param output
  *   The where the .zip file should be written to
  * @tparam F
  *   The effect type to run in, e.g. cats.effect.IO
  * @return
  *   An effect in F which when run will compress the files in toCompress and write a .zip file to output
  * @note
  *   This example assumes that the toCompress is a directory and only contains files, no subdirectories
  */
def compressDirectory[F[_]: cats.effect.Async](toCompress: fs2.io.file.Path, output: fs2.io.file.Path): F[Unit] = {
  val zip = de.lhns.fs2.compress.ZipArchiver.make[F]()
  fs2.io.file
    .Files[F]
    .list(toCompress)
    .evalMap { path =>
      fs2.io.file
        .Files[F]
        .size(path)
        .map(size =>
          ArchiveEntry[Some, Unit](path.toString, uncompressedSize = Some(size)) -> fs2.io.file.Files[F].readAll(path)
        )
    }
    .through(zip.archive)
    .through(fs2.io.file.Files[F].writeAll(output))
    .compile
    .drain
}
```

## License

This project uses the Apache 2.0 License. See the file called LICENSE.

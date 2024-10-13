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
libraryDependencies += "de.lhns" %% "fs2-compress-gzip" % "2.2.1"
libraryDependencies += "de.lhns" %% "fs2-compress-zip" % "2.2.1"
libraryDependencies += "de.lhns" %% "fs2-compress-zip4j" % "2.2.1"
libraryDependencies += "de.lhns" %% "fs2-compress-tar" % "2.2.1"
libraryDependencies += "de.lhns" %% "fs2-compress-bzip2" % "2.2.1"
libraryDependencies += "de.lhns" %% "fs2-compress-zstd" % "2.2.1"
libraryDependencies += "de.lhns" %% "fs2-compress-brotli" % "2.2.1"
libraryDependencies += "de.lhns" %% "fs2-compress-brotli4j" % "2.2.1"
libraryDependencies += "de.lhns" %% "fs2-compress-lz4" % "2.2.1"
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

The following examples does not check that the paths used are valid. For real world applications you will probably want to
add some checks to that effect.

### Compression

Compression can be abstracted over using the `Compressor` typeclass. Adapt the following examples based on which compression algorithm you want to use.
```scala
import cats.effect.Async
import de.lhns.fs2.compress._
import fs2.io.file.{Files, Path}

// implicit def brotli4J[F[_]: Async]: Compressor[F] = Brotli4JCompressor.make()
// implicit def bzip2[F[_]: Async]: Compressor[F] = Bzip2Compressor.make()
// implicit def lz4[F[_]: Async]: Compressor[F] = Lz4Compressor.make()
// implicit def zstd[F[_]: Async]: Compressor[F] = ZstdCompressor.make()
implicit def gzip[F[_]: Async]: Compressor[F] = GzipCompressor.make()

def compressFile[F[_]: Async](toCompress: Path, writeTo: Path)(implicit compressor: Compressor[F]): F[Unit] =
  Files[F]
    .readAll(toCompress)
    .through(compressor.compress)
    .through(Files[F].writeAll(writeTo))
    .compile
    .drain
```

### Decompression

Similarly, decompression can be abstracted over using the `Decompressor` typeclass. Adapt the following examples based on which compression algorithm was used to write the source file.
```scala
import cats.effect.Async
import de.lhns.fs2.compress._
import fs2.io.file.{Files, Path}

// implicit def brotli[F[_]: Async]: Decompressor[F] = BrotliDecompressor.make()
// implicit def brotli4J[F[_]: Async]: Decompressor[F] = Brotli4JDecompressor.make()
// implicit def bzip2[F[_]: Async]: Decompressor[F] = Bzip2Decompressor.make()
// implicit def lz4[F[_]: Async]: Decompressor[F] = Lz4Decompressor.make()
// implicit def zstd[F[_]: Async]: Decompressor[F] = ZstdDecompressor.make()
implicit def gzip[F[_]: Async]: Decompressor[F] = GzipDecompressor.make()

def decompressFile[F[_]: Async](toDecompress: Path, writeTo: Path)(implicit decompressor: Decompressor[F]): F[Unit] =
  Files[F]
    .readAll(toCompress)
    .through(decompressor.decompress)
    .through(Files[F].writeAll(writeTo))
    .compile
    .drain
```

### Archiving

The library supports both `.zip` and `.tar` archives, with support for `.zip` through both the native Java implementation and the [zip4j](https://github.com/srikanth-lingala/zip4j) library.

```scala
import cats.effect.Async
import de.lhns.fs2.compress._
import fs2.io.file.{Files, Path}

// implicit def tar[F[_]: Async]: Archiver[F, Some] = TarArchiver.make()
// implicit def zip4j[F[_]: Async]: Archiver[F, Some] = Zip4JArchiver.make()
implicit def zip[F[_]: Async]: Archiver[F, Option] = ZipArchiver.makeDeflated()

def archiveDirectory[F[_]](directory: Path, writeTo: Path)(implicit archiver: Archiver[F, Option]): F[Unit] =
  Files[F]
    .list(directory)
    .evalMap { path =>
      Files[F]
        .size(path)
        .map { size =>
          // Name the entry based on the relative path between the source directory and the file
          val name = directory.relativize(path).toString
          ArchiveEntry[Some, Unit](name, uncompressedSize = Some(size)) -> Files[F].readAll(path)
        }
    }
    .through(archiver.archive)
    .through(Files[F].writeAll(writeTo))
    .compile
    .drain
```
Note that `.tar` doesn't compress the archive, so to create a `.tar.gz` file you will have to combine the archiver with
the `GzipCompressor`

```scala
import cats.effect.Async
import de.lhns.fs2.compress._
import fs2.io.file.{Files, Path}

implicit def gzip[F[_]: Async]: Compressor[F] = GzipCompressor.make()
implicit def tar[F[_]: Async]: Archiver[F, Some] = TarArchiver.make()

def tarAndGzip[F[_]: Async](directory: Path, writeTo: Path)(implicit archiver: Archiver[F, Some], compressor: Compressor[F]): F[Unit] =
  Files[F]
    .list(directory)
    .evalMap { path =>
      Files[F]
        .size(path)
        .map { size =>
          // Name the entry based on the relative path between the source directory and the file
          val name = directory.relativize(path).toString
          ArchiveEntry[Some, Unit](name, uncompressedSize = Some(size)) -> Files[F].readAll(path)
        }
    }
    .through(archiver.archive)
    .through(compressor.compress)
    .through(Files[F].writeAll(writeTo))
    .compile
    .drain
```

### Unarchiving

To unarchive we use the `Unarchiver` typeclass matching our archive format.

```scala
import cats.effect.Async
import de.lhns.fs2.compress._
import fs2.io.file.{Files, Path}

// implicit def tar[F[_]: Async]: Unarchiver[F, Option] = TarUnarchiver.make()
// implicit def zip4j[F[_]: Async]: Unarchiver[F, Option] = Zip4JUnarchiver.make()
implicit def zip[F[_]: Async]: Unarchiver[F, Option] = ZipUnarchiver.make()

def unArchive[F[_]](archive: Path, writeTo: Path)(implicit archiver: Unarchiver[F, Option]): F[Unit] =
  Files[F]
    .readAll(archive)
    .through(archiver.unarchive)
    .flatMap { case (entry, data) =>
      data.through(Files[F].writeAll(writeTo.resolve(entry.name)))
    }
    .compile
    .drain
```
Once again if you have a `.tar.gz` file you will have to combine the `Unarchiver` with the `GzipDecompressor`

```scala
import cats.effect.Async
import de.lhns.fs2.compress._
import fs2.io.file.{Files, Path}

implicit def gzip[F[_]: Async]: Decompressor[F] = GzipDecompressor.make()
implicit def tar[F[_]: Async]: Unarchiver[F, Option] = TarUnarchiver.make()

def unArchive[F[_]](archive: Path, writeTo: Path)(implicit archiver: Unarchiver[F, Option], decompressor: Decompress[F]): F[Unit] =
  Files[F]
    .readAll(archive)
    .through(decompressor.decompress)
    .through(archiver.unarchive)
    .flatMap { case (entry, data) =>
      data.through(Files[F].writeAll(writeTo.resolve(entry.name)))
    }
    .compile
    .drain
```

## License

This project uses the Apache 2.0 License. See the file called LICENSE.

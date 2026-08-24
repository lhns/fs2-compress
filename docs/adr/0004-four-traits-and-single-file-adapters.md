# 4. Model the four operations as traits, with single-file adapters

Date: 2023-01-13

## Status

Accepted

## Context

The library covers two different things that are easy to conflate. Compression turns bytes
into fewer bytes — gzip, zstd, bzip2. Archiving collects named entries into one container —
zip, tar. Some formats are one, some are the other, and `.tar.gz` is both composed.

A user also frequently wants the degenerate case: put a single file into a zip, or read the
one file back out, without dealing with entries at all.

## Decision

Four traits, each a single `Pipe`:

```scala
trait Compressor[F[_]]   { def compress:   Pipe[F, Byte, Byte] }
trait Decompressor[F[_]] { def decompress: Pipe[F, Byte, Byte] }

trait Archiver[F[_], Size[A] <: Option[A]] {
  def archive: Pipe[F, (ArchiveEntry[Size, Any], Stream[F, Byte]), Byte]
}
trait Unarchiver[F[_], Size[A] <: Option[A], Underlying] {
  def unarchive: Pipe[F, Byte, (ArchiveEntry[Size, Underlying], Stream[F, Byte])]
}
```

Because each is just a pipe, composition is ordinary fs2: `.through(archiver.archive)
.through(compressor.compress)` is how you write a `.tar.gz`, and the library needs no
concept of a combined format.

For the single-file case, `ArchiveSingleFileCompressor` and `ArchiveSingleFileDecompressor`
adapt an `Archiver`/`Unarchiver` down to the `Compressor`/`Decompressor` interface. That is
what makes a one-file zip usable anywhere a plain compressor is expected.

`Compressor` and `Decompressor` date from the first commit and are unchanged since.
`Archiver`, `Unarchiver` and the single-file adapters arrived with tar support.

## Consequences

- The entry stream in `Archiver`/`Unarchiver` is nested inside the outer stream. That nesting
  is the source of the hardest problems in this library: what happens when the consumer does
  not read an entry (ADR 0020), and when it cancels mid-entry (ADR 0018).
- `Archiver` carries a `Size` parameter that `Compressor` does not need, because archive
  formats may require a declared size — see ADR 0008 and ADR 0009.
- `ArchiveSingleFileDecompressor` ends in `.head`, taking the first entry and ignoring the
  rest. This turns out to matter for cancellation semantics, since early termination is
  reported differently from a real cancellation.

## References

- `9ed3d84` (2023-01-11) — `Compressor`, `Decompressor`, and `ZipSingleFile`
- `5198a98` "tar and archivers" (2023-01-13) — `Archiver`, `Unarchiver`, and the generalised
  `ArchiveSingleFile`
- `core/src/main/scala/de/lhns/fs2/compress/{Compressor,Decompressor,Archiver,Unarchiver,ArchiveSingleFile}.scala`

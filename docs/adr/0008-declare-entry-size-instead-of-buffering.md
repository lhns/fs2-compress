# 8. Stream archive entries by declaring the size up front

Date: 2024-04-10

## Status

Accepted

## Context

Tar writes a header before each entry's payload, and that header contains the uncompressed
size. Zip's STORED method likewise needs the size and CRC in the local header. The size must
therefore be known before the first byte of payload is written.

The original archivers obtained it the only way they could without asking: by buffering the
whole entry with `chunkAll` and measuring it. Issue #77 reported the consequence:

> The use of `chunkAll` in `TarArchiver#archive` and in `ZipArchiver#archive` causes all the
> bytes for each entry to be read into memory, which can lead to out-of-memory errors for
> large entries.

So the library was not really streaming archives at all — it was assembling them in memory,
one entry at a time. For a streaming library that is a defect, not a trade-off.

## Decision

Stop deriving the size and require the caller to declare it, then encode in the type whether
a given archiver requires it, using a higher-kinded parameter bounded by `Option`:

```scala
trait Archiver[F[_], Size[A] <: Option[A]] {
  def archive: Pipe[F, (ArchiveEntry[Size], Stream[F, Byte]), Byte]
}
```

Instantiated at `Some`, the size is mandatory and the compiler enforces it, because
`Some[Long]` cannot be `None`. Instantiated at `Option`, it is optional. `chunkAll`
disappears and entries stream through.

`ArchiveSingleFileCompressor.forName` gained two overloads so the distinction is visible at
the call site — one that takes a size, one that does not.

## Consequences

- Entries of any size can be archived in constant memory.
- The caller has to know the size in advance. For a file on disk that is a `stat`; for a
  computed stream it may mean restructuring, and that is the real cost of this decision.
- A declared size is now the only source of truth, and nothing checks it — which is exactly
  the gap ADR 0010 closes.
- The higher-kinded `Size` parameter appears in every archiver signature from here on. Six
  months later it needed refining: see ADR 0009.

## References

- Issue #77 "Tar and zip archivers read entries fully into memory"
- `b5d234f` "require size for an archive entry" (2024-04-10), PR #104
- `core/src/main/scala/de/lhns/fs2/compress/{ArchiveEntry,Archiver,ArchiveSingleFile}.scala`

# 10. Verify the declared entry size while archiving

Date: 2024-04-12

## Status

Accepted

## Context

After ADR 0008 the caller declares each entry's size and the archiver writes it into the
header before the payload. Nothing checked that the payload then matched.

For tar this was already handled: commons-compress validates against the declared header
size and fails. For zip in DEFLATED mode it was not. `java.util.zip.ZipOutputStream` happily
writes a structurally valid archive whose central directory records a size that never
occurred — silent corruption, discovered by whoever tries to read the file later, possibly
in a different program on a different day.

## Decision

Count the bytes actually streamed and fail if they do not match what was declared. A shared
pipe in the `Archiver` companion does it:

```scala
def checkUncompressedSize[F[_]: Async, Size[A] <: Option[A]]: Pipe[F, ...] =
  _.map { case (entry, bytes) =>
    val newBytes = (entry.uncompressedSize: Option[Long]) match {
      case None => bytes
      case Some(expectedSize) =>
        Stream.eval(Ref[F].of(0L)).flatMap { sizeRef =>
          bytes.chunks.evalTap(chunk => sizeRef.update(_ + chunk.size)).unchunks ++
            Stream.exec(sizeRef.get.map { size =>
              if (size != expectedSize) throw new IllegalStateException(...)
            })
        }
    }
    (entry, newBytes)
  }
```

`ZipArchiver` and `Zip4JArchiver` run their entry stream through it. `TarArchiver` does not,
because commons-compress already does the equivalent.

It is a no-op when no size was declared, so the `Size = Option` path costs nothing.

## Consequences

- A mismatch fails loudly at the point of archiving, rather than producing a file that is
  wrong somewhere else later.
- The check is per chunk and adds a `Ref` update to every chunk written. Negligible against
  the compression itself.
- This established the pattern the library reaches for whenever a caller can be wrong in a
  way that would otherwise corrupt output silently: count, compare, raise
  `IllegalStateException`. ADR 0020 follows the same shape on the reading side.

## References

- `2edaff6` "added test for wrong body size" (2024-04-12) — the failing tests first
- `62a6fe1` "check entry size in ZipArchiver" (2024-04-12)
- `core/src/main/scala/de/lhns/fs2/compress/Archiver.scala`

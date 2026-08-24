# 7. Ship a second zip implementation for encryption

Date: 2023-11-07

## Status

Accepted

## Context

The `zip` module wraps `java.util.zip`, which is in the JDK, has no extra dependencies, and
cannot read or write encrypted archives. Password-protected zips are common enough that
"this library cannot open that file" is a real gap, and the JDK offers nothing to close it.

## Decision

Add a separate `zip4j` module wrapping the zip4j library, and keep the JDK-based `zip`
module alongside it. Users who need encryption take the extra dependency; users who do not,
do not.

The archiver takes the password by name, so a secret need not be held in memory for the
lifetime of the instance:

```scala
def make[F[_]: Async](password: => Option[String] = None, chunkSize: Int = Defaults.defaultChunkSize)
```

The module sat in a rough state for five months — the first version had a literal
`"password"` hardcoded and a `method: Int` parameter whose effect was commented out — and was
finished in `849bec8`, which also moved it onto the `ArchiveEntry` encoding of ADR 0011.

## Consequences

- Two modules read and write the same format, and they are **not** interchangeable.
  `Zip4JArchiver` is `Archiver[F, Some]` and always needs a declared entry size, while the
  JDK `ZipArchiver` is parameterised and does not (ADR 0009). Switching between them is a
  type-level change, not a drop-in swap.
- zip4j needs two different native types for one format — `ZipParameters` when writing,
  `LocalFileHeader` when reading. The old single-`Entry` `Archiver` could not express that,
  which is part of what motivated ADR 0011.
- Both must be maintained. Every change to the shared entry machinery lands in three
  archivers rather than two.

## References

- Issue #106; `781a64c` "zip4j module" (2023-11-07)
- `849bec8` "fixed zip4j Archiver" (2024-04-12) — real password parameter, new entry encoding
- `zip4j/src/main/scala/de/lhns/fs2/compress/Zip4J.scala`

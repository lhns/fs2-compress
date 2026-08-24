# 14. Codec modules follow a fixed shape

Date: 2024-10-02

## Status

Accepted

## Context

New codecs mostly arrive as outside contributions — lz4 and snappy both did, from different
contributors three months apart. A contributor adding one has to work out the module layout,
the class and companion conventions, the stream plumbing and the test style, none of which
were written down.

They are all derivable from the existing modules, and in practice both contributions were
structurally identical to what was already there, which is the point: the shape is uniform
enough to copy.

## Decision

Accept new codecs as modules that follow the established shape, rather than special-casing
each one:

1. `build.sbt`: a version in the `V` block, an `.aggregate(...)` line on `root`, and a
   `projectMatrix` module depending on `core % "compile->compile;test->test"`, named
   `fs2-compress-<name>`, JVM-only.
2. One source file with a `Compressor` and a `Decompressor`, constructors private.
3. Compression through `readOutputStream` + `writeOutputStream`, decompression through
   `toInputStream` + `readInputStream` (ADR 0001).
4. A companion with `apply` summoning and `make` constructing, `chunkSize` defaulted to
   `Defaults.defaultChunkSize` (ADR 0005).
5. A round-trip suite: 1 MiB of random bytes, compress, decompress, compare.
6. A line in the README dependency block.

Codec-specific configuration is exposed where the format has genuinely distinct modes rather
than being flattened away — snappy takes a `WriteMode`/`ReadMode` ADT covering basic, framed
and Hadoop-compatible, because those produce incompatible bytes.

## Consequences

- Reviewing a new codec is mostly checking it matches the shape.
- The uniformity is what makes the sweeping changes in ADR 0018 and ADR 0019 tractable: the
  same edit applied to every compressor because every compressor was the same code.
- Steps 3 and 5 have since moved on. Compression now goes through
  `OutputStreams.throughOutputStream` (ADR 0019), and a codec is also expected to pass the
  shared cancellation suite. A new module copied from an old one will not automatically be
  cancellable.
- The library carries whatever compatibility quirks the contributors needed. Lz4 uses frame
  encoding and is explicitly not Spark-compatible; snappy's Hadoop mode exists precisely to
  be.

## References

- `de033f0` "Support LZ4" (2024-10-02), PR #145 — Erik van Oosten
- `8c44ac2` "Support snappy compression." (2025-01-08), PR #191 — Rodrigo Molina
- `12d1c83` "Add Brotli4J submodule" (2024-10-11), PR #152 — Jakob Merrild

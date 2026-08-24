# 15. Ship a second brotli implementation for compression

Date: 2024-10-11

## Status

Accepted

## Context

The `brotli` module is built on `org.brotli:dec` — Google's reference JVM port, whose
artifact name says what it is. It decodes only. There has never been a `BrotliCompressor` in
that module, and issue #105 asked for one:

> The Brotli module only has a decompressor.

There is no compressor to add: the dependency does not contain one. Adding brotli compression
means a different library, and brotli4j is the usual JVM choice. It also bundles native
binaries for each supported platform, which is a meaningful weight to put on users who only
ever decompress.

## Decision

Add a separate `brotli4j` module providing both `Brotli4JCompressor` and
`Brotli4JDecompressor`, and keep the existing `brotli` module unchanged.

The first proposal replaced the backing library in place. That PR was closed and resubmitted
as a new module instead, which is the decision worth recording: `org.brotli:dec` was kept
deliberately, not by omission.

## Considered options

- **Swap `brotli` onto brotli4j.** One brotli module, compression for everyone. Rejected: it
  forces brotli4j's native binaries on existing decompress-only users, changing the
  dependency footprint of a module they already depend on.
- **Add a second module.** Chosen, following the precedent already set for zip (ADR 0007).

## Consequences

- Two brotli modules with different capabilities: `brotli` decodes, `brotli4j` does both.
  Users choosing brotli have to know which they want.
- Existing users' dependencies are unchanged.
- `brotli4j` exposes `Encoder.Parameters`, so quality, window size and mode are
  configurable — the wider surface that comes with a fuller implementation.
- The README's decompression example still lists `BrotliDecompressor`, and the compression
  examples do not mention brotli at all. Worth fixing.

## References

- Issue #105 "Add BrotliCompressor API"
- PR #151 "Add brotli compressor" — closed, replaced by PR #152
- `12d1c83` "Add Brotli4J submodule" (2024-10-11)
- `90d7b48` "publish brotli4j module" — adds the missing `.aggregate` line

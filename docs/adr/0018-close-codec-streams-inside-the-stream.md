# 18. Close codec streams inside the stream, not in a `Resource`

Date: 2026-08-24

## Status

Accepted

## Context

Issue #113 reported that a program decompressing a zip could not be stopped with Ctrl-C. The
fiber was cancelled and never finished.

The cause is narrower than it first looks, and it is not only `closeEntry`. `Async[F].blocking`
cannot be interrupted, and **both halves of a `Resource` are uncancelable regions**. Any write
or read performed in one of them therefore runs to completion no matter what, and once the
consumer has stopped draining the `fs2.io.readOutputStream` pipe, "to completion" means never.

Thread dumps put the blocked frame in three genuinely different places, which is why fixing
one site would not have been enough:

| Where | Frame |
|---|---|
| Entry finalizer, draining the rest of the entry from a live upstream | `ZipInputStream.closeEntry()` |
| Resource **acquire**, writing a 512-byte header into a full pipe | `TarArchiveOutputStream.putArchiveEntry()` |
| Outer finalizer, flushing the final block into a pipe nobody reads | `BZip2CompressorOutputStream.close()` |

## Decision

Stop performing these writes inside uncancelable regions.

- The entry header and trailer, and the close of the underlying stream, move out of
  `Resource` and into the stream itself as `Async[F].interruptible`. There an interrupt lands.
  On the happy path the bytes and their order are unchanged.
- The `Resource` release becomes a uniform safety net for the paths where the stream did not
  get that far. It closes the fs2 `OutputStream` **first**, which is what makes it
  non-blocking: writes to a closed `PipedStreamBuffer` are discarded rather than
  backpressured, so `close()` returns even with nobody reading, and still releases what it
  owns — `Deflater.end()`, `ZSTD_freeCStream`, brotli4j's encoder.
- `ZipUnarchiver` drops its per-entry `closeEntry()` finalizer entirely. `getNextEntry()`
  calls `closeEntry()` itself before advancing, so it was redundant on the happy path and
  pure liability on every other one.

Closing the pipe first is not redundant with what fs2 already does. fs2 closes it in a
`guaranteeCase` *around* the body, so on cancellation the two closes deadlock: the release
blocks writing the trailer, and the close that would unblock it cannot run until the release
returns. Removing that one line locally brought back ten failures.

## Considered options

- **Skip `close()` on cancellation.** Avoids the hang, but leaks whatever the stream owns,
  including native memory.
- **Use `Async[F].interruptible` in the release.** Does not work: finalizers run in an
  uncancelable region, so the interrupt is never delivered. Tried and measured.
- **Branch on `ExitCase` and only close on success.** Insufficient. fs2 reports
  `ExitCase.Succeeded` for early downstream termination, so `.take(n)` and
  `ArchiveSingleFileDecompressor`'s `.head` — the path in the issue — would still drain the
  entry. It also leaves the outer `close()` in place, which blocks on its own.
- **Move the close into the stream, with the release as a fallback.** Chosen.

## Consequences

- Cancelling a compression or archiving stream completes, across every module.
- On cancellation the output is truncated: no central directory, no EOF records, no frame
  epilogue. That is correct — the only way to reach this path is that whoever was reading
  those bytes has already gone.
- **Cancellation latency is bounded, not zero.** `fs2.io.readInputStream` still reads inside
  `F.blocking`, so a cancellation waits for the read in flight. Codecs that need a large
  contiguous block per read can take a while on a slow source. Fixing that needs fs2's
  `readInputStreamCancelable`, which is `private[io]`.
- The success path is byte-for-byte identical, which the pre-existing round-trip suites pin.

## References

- Issue #113 "zip decompressor is not cancellable"
- PRs #339 and #341, commit `a5a9bed` "Make archivers and compressors cancelable"
- ADR 0019 for the shared helper, ADR 0020 for how this is tested

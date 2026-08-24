# 21. Let entries be skipped, and fail when reading data the archive has passed

Date: 2026-08-24

## Status

Proposed — PR #342 is open.

## Context

Issue #295: pulling archive entries without reading their data never terminated the stream.
Inspecting the names in a downloaded zip — an ordinary thing to want — simply hung.

All three unarchivers shared a handshake that put `Stream.exec(deferred.get)` in the outer
stream between one entry and the next, where the `Deferred` was only completed by running
that entry's data stream **to its end**. Pull the next entry without finishing the current
one's data and it waited forever. It was also broader than the issue title suggested: reading
only *part* of an entry hung just as surely as skipping it.

The handshake was never needed to keep the archive positioned correctly. All three underlying
libraries already read past the rest of the current entry while looking for the next header —
confirmed in the bytecode, not assumed:

| | |
|---|---|
| `java.util.zip.ZipInputStream.getNextEntry()` | calls `closeEntry()`, which loops `read()` to EOF |
| `TarArchiveInputStream.getNextTarEntry()` | `IOUtils.skip(this, Long.MAX_VALUE)` + `skipRecordPadding()` |
| zip4j's `getNextEntry()` | calls `readUntilEndOfEntry()` |

So it bought nothing except the deadlock. What it did prevent, by making it impossible, is
reading an entry's data *after* the archive has moved past it — where the bytes are gone and
a read would quietly return whatever the archive is positioned at now.

## Decision

Two mistakes, two different answers:

| Consumer does | Result |
|---|---|
| Moves to the next entry with data unread | the data is skipped, the stream continues |
| Reads data the archive has moved past | `IllegalStateException` |

Skipping is allowed because an archive is read in one pass and there is no alternative;
refusing would make listing entry names an error. The stale read fails because there is no
correct answer to return, and silence there would trade a hang for corruption.

A `Ref[F, Long]` records which entry the archive is positioned at, set *before* the archive
advances so a stale data stream fails the moment it commits to moving on. The check runs per
chunk, so data streams read concurrently with the entries are caught too, not just the
sequential case.

The entry loop was identical in all three unarchivers and now lives in `Unarchiver.entries`,
beside the trait, in the same way the shared size check lives in `Archiver` (ADR 0010).

## Considered options

- **Leave it stuck.** A hang is the worst of the three: silent, no clue where to look, and it
  fires on partial reads as well as skips.
- **Throw when advancing past unread data.** Turns the hang into an error, but makes the
  reported use case — listing entry names — an error rather than working.
- **Skip silently, no exception.** Fixes the issue with the smallest change, but a stale read
  then returns the next entry's bytes.
- **Skip on advance, throw on stale read.** Chosen.

## Consequences

- Listing entries, reading part of an entry, and skipping entries all terminate.
- Misuse fails loudly and immediately instead of returning wrong bytes.
- Skipping costs the I/O of reading past the skipped data. Unavoidable in a one-pass format.
- That skip happens inside an uninterruptible `Async[F].blocking` call, so skipping a large
  entry lengthens cancellation latency — the bounded-latency caveat from ADR 0018.
- Nothing in the repo built a multi-entry archive before this, which is why the issue had no
  coverage. The shared suite now does.

## References

- Issue #295 "Not pulling zip entry data stream causes a non-terminating stream"
- PR #296 — the same diagnosis and conclusion, from Hugo van Rijswijk
- PR #342, `core/src/main/scala/de/lhns/fs2/compress/Unarchiver.scala`

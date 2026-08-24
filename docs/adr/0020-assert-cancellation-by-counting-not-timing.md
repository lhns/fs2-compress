# 20. Assert cancellation by counting bytes, not by elapsed time

Date: 2026-08-24

## Status

Accepted

## Context

The bug in ADR 0018 had gone unfixed for a long time partly because nobody could write a
convincing test for it. The contributor who diagnosed it said as much: *"Not sure how to
easily test this in an automated test."*

An earlier attempt used `Stream.never` as a stalled source. That looks reasonable and is
wrong: it parks the fiber inside an uninterruptible `IO.blocking(read)`, where cancellation
can never be delivered no matter how good the fix is. The test would hang with a correct
implementation, so it proves nothing.

That is the trap these tests have to avoid. Whether a cancellation test asserts something
achievable depends entirely on **where the fiber is parked**:

| Parked in | Can cancellation be delivered? |
|---|---|
| A mid-stream read that will not return | **No.** Nothing the library does can help. |
| A finalizer draining or flushing bytes | **Yes.** That is the bug. |

Both cases were confirmed by thread dump rather than argument — zip parks in
`ZipInputStream.closeEntry()` inside a finalizer, snappy parks in `SnappyInputStream.read`
mid-stream.

## Decision

Two different techniques, because the two symptoms are observable in different ways.

**Reading is checked by counting.** The source is entirely in memory, so nothing blocks. The
stream is stopped where the test wants by parking on a `Deferred` that is never completed —
parking like that *can* be cancelled, so the cancellation takes effect at once and the source
stops being pulled. The test counts bytes taken from the source before cancelling and again
afterwards. A finalizer that drains reads the rest of the entry in between; one that does not
reads nothing. No sleeping, no timing assertion, nothing to be flaky.

Counting the **difference** rather than the total is what makes this work for every codec.
Snappy, bzip2, lz4 and brotli read a whole internal block before producing any output, and
that read-ahead happens before the cancellation — an absolute bound flags them wrongly.

**Writing is checked by waiting**, because its symptom is a stream that never finishes and a
deadline is the only way to see that. The budget is a deadlock detector, not a
synchronisation point: ten seconds against milliseconds of real work. The pipe is shrunk to
64 bytes so that a consumer which stops draining makes the next write block by construction
rather than by timing.

Cancellation is observed with `fiber.cancel.start` plus a cancelable `join.timeout`.
`fiber.cancel.timeout(...)` would hang, because `cancel` is `IO.uncancelable` and `timeout`
is `race`, which waits for the loser's cancellation — exactly the situation under test.

## Consequences

- The read-side tests run in about 0.06s each with no timing assumptions.
- Every module runs every test. An earlier timing-based version needed a per-codec opt-out
  for the block-reading decompressors; the counting version does not.
- Gzip is included deliberately as a control. It is pure fs2 with no blocking finalizer, so
  it passes both before and after the fix, which shows the harness is not asserting something
  universally impossible.
- The write-side budget is still a timeout. It cannot be removed without a way to observe
  "never finishes" that does not involve waiting.
- A failing write-side test leaks a blocked thread. That is the price of reporting a failure
  instead of hanging the suite, and it only happens on an already-red run.

## References

- PR #335, commit `8bedd40` "Add cancellation tests for all archivers and compressors"
- `core/src/test/scala/de/lhns/fs2/compress/CancellationSuite.scala`
- ADR 0018 for the fix these tests pin

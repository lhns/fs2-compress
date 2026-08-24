# 13. Test with munit-cats-effect

Date: 2024-06-07

## Status

Accepted

## Context

Tests ran on munit plus `munit-tagless-final`, a small library by the same author, through a
hand-written base class in `core`:

```scala
abstract class IOSuite extends TaglessFinalSuite[IO] {
  override protected def toFuture[A](f: IO[A]): Future[A] =
    f.unsafeToFuture()(unsafe.IORuntime.global)
}
```

Every suite ran against the global `IORuntime` via `unsafeToFuture`. That works for a
round-trip test, but it means no per-test runtime, no timeout supervision from the framework,
and no effectful assertion helpers.

## Decision

Replace both test dependencies with `org.typelevel::munit-cats-effect`, delete `IOSuite`, and
have every suite extend `CatsEffectSuite`.

## Consequences

- Tests are pinned to `IO` rather than abstract over an effect type. In practice every suite
  already used `IO`, so nothing was lost.
- One fewer bespoke dependency, and the standard Typelevel testing setup that contributors
  will recognise.
- This turned out to be a prerequisite for everything in ADR 0018 and ADR 0020. The
  cancellation suites depend on the cats-effect test-runtime integration — `munitIOTimeout`
  supervision in particular — which the old `IOSuite` did not provide. A suite that has to
  cancel fibers and detect deadlocks cannot be built on `unsafeToFuture` against a global
  runtime.

## References

- `8c9db92` "use munit-cats-effect" (2024-06-07)
- `core/src/test/scala/de/lhns/fs2/compress/CancellationSuite.scala` — the current suites
  built on `CatsEffectSuite`

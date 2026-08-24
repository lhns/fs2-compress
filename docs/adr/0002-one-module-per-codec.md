# 2. Give each codec its own module

Date: 2023-01-11

## Status

Accepted

## Context

The library began as a single `core` artifact containing gzip, zip, zstd and bzip2 together,
which meant one dependency list for all of them:

```scala
libraryDependencies ++= Seq(
  "co.fs2" %% "fs2-io" % V.fs2,
  "com.github.luben" % "zstd-jni" % V.zstdJni,
  "org.apache.commons" % "commons-compress" % V.commonsCompress,
  "org.typelevel" %% "cats-effect" % V.catsEffect
)
```

Anyone who wanted gzip also got zstd-jni, a multi-megabyte jar carrying native binaries for
every supported platform, plus commons-compress.

The problem surfaced within the hour. Commit `5a7c1ac`, twenty-four minutes after the code
landed, demoted zstd-jni to test scope:

```diff
-      "com.github.luben" % "zstd-jni" % V.zstdJni,
+      "com.github.luben" % "zstd-jni" % V.zstdJni % Test,
```

That shrank the published artifact and broke it at the same time: `ZstdCompressor` was still
in the jar, but no longer compilable by anyone downstream.

## Decision

Split into `core` plus one module per codec, using sbt-projectmatrix. `core` keeps only
fs2-core and cats-effect. Each codec module depends on `core` and owns its native
dependency, and is published as its own artifact (`fs2-compress-gzip`, `fs2-compress-zstd`,
and so on).

The split commit puts zstd-jni straight back into compile scope — undoing the workaround is
precisely what it was for.

## Considered options

- **One artifact.** What was there. Rejected: it forces every dependency on every user.
- **One artifact with optional dependencies at test scope.** Tried for twenty-four minutes
  in `5a7c1ac`. Rejected: it does not actually make the code usable, it just hides it.
- **One artifact per codec.** Chosen.

## Consequences

- Users depend on exactly the codecs they use. This is the reason the README lists ten
  separate coordinates rather than one.
- Adding a codec means touching `build.sbt` in three places (version, aggregate, module),
  which ADR 0014 turns into a repeatable shape.
- Modules are wired as `core % "compile->compile;test->test"` so they can reuse the shared
  test scaffolding in `core`. That wiring is what later lets the abstract cancellation and
  unarchive suites live in `core` and be subclassed per codec.
- Eleven modules times three Scala versions makes for a large build matrix, which is why CI
  runs with a raised heap.

## References

- `9ed3d84` "added files", `5a7c1ac` "change zstd-jni scope to test" (2023-01-11)
- `1099bb0` "modules" (2023-01-11), merged as PR #1
- `94c0360` "updated build.sbt" — adds the `test->test` wiring

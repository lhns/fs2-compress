# 16. Track the Scala 3 LTS line

Date: 2025-01-23

## Status

Accepted

## Context

Automated dependency updates had been carrying the build forward along the Scala 3 "Next"
series through 2024 — 3.4.1, 3.4.2, 3.4.3, 3.5.0, 3.5.1, 3.5.2, 3.6.2 — because from a bot's
point of view a newer version is simply an update.

For a library that is the wrong direction. Scala 3 minor releases are not forward binary
compatible, so an artifact built on 3.6.2 cannot be consumed by an application on 3.3.x,
while an artifact built on the LTS line can be consumed by everyone.

## Decision

Pin the Scala 3 axis to the 3.3.x LTS line, and take the downgrade required to get there:

```diff
-lazy val scalaVersions = Seq("3.6.2", "2.13.15", "2.12.20")
+lazy val scalaVersions = Seq("3.3.4", "2.13.15", "2.12.20")
```

Updates within the LTS line are still accepted; the build has moved 3.3.4 → 3.3.8 since.

Scala 2.13 and 2.12 remain supported.

## Consequences

- Applications on any supported Scala 3 version can use the published artifacts.
- The library cannot use language features newer than the LTS line.
- Dependency-update PRs that propose a Next-series version have to be declined rather than
  merged, which is a standing bit of maintenance attention.
- Three Scala versions across eleven modules, two of them also cross-built to Scala.js, is a
  large matrix to keep green.

## References

- `08519ee` "scala 3 lts" (2025-01-23)
- `build.sbt` — `scalaVersions`

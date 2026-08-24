# 17. Publish to Sonatype Central under early-semver

Date: 2023-01-11

## Status

Accepted

## Context

The library publishes eleven artifacts across three Scala versions and two platforms, so
releasing has to be automated and the versioning has to tell users what is safe to upgrade.

## Decision

**Version scheme.** `versionScheme := Some("early-semver")`, set in the initial commit and
never changed. It tells coursier and sbt how to judge compatibility: within 1.x and above,
`2.2.0` and `2.3.0` are compatible; in 0.x, a minor bump is breaking.

**Version source.** The version comes from the CI tag rather than being committed:

```scala
version := {
  val Tag = "refs/tags/v?([0-9]+(?:\\.[0-9]+)+(?:[+-].*)?)".r
  sys.env.get("CI_VERSION").collect { case Tag(tag) => tag }.getOrElse("0.0.1-SNAPSHOT")
}
```

Publishing a GitHub release runs `sonatypeBundleClean; publishSigned; sonatypeBundleRelease`
with `CI_VERSION` set, so the tag is the single source of truth and a local build is always a
snapshot.

**Host.** Originally OSSRH on `s01.oss.sonatype.org`, migrated to Sonatype Central when OSSRH
was retired.

A follow-up the same evening lifted `version` to `ThisBuild` scope, because
`sonatypeBundleRelease` names the staging bundle from the build-level version and that scope
was still reporting a stale default.

## Consequences

- Releasing is tagging. There is no version to bump in a commit and no chance of a mismatch
  between tag and artifact.
- Early-semver is what makes deprecation the right answer rather than removal: the
  deprecated `ZipArchiver.make` (ADR 0012) must stay for the whole 2.x line.
- The published surface is eleven artifacts per release, so any breaking change is expensive
  to roll out and expensive to undo.

## References

- `9ed3d84` (2023-01-11) — `versionScheme` and the `CI_VERSION` regex, from day one
- `df0629e` "sonatype central" (2025-05-13) — host migration
- `b449d3a` "set ThisBuild / version" (2025-05-13)
- `build.sbt`, `.github/workflows/build.yml`

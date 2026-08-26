# Releasing Akka Platform API

The `akka-platform-api-java-client` artifact is published to Akka's
[Cloudsmith](https://cloudsmith.io/~lightbend/repos/akka/) maven repository
(`lightbend/akka`). Consumers resolve it through Akka's secured, tokenized
repository — they generate a token and get the repository configuration from
https://account.akka.io/token (see the Installation section in the README).

Publishing is automated by the [`Publish`](.github/workflows/publish.yml) GitHub
workflow. The published version is derived from the git tag by
[sbt-dynver](https://github.com/sbt/sbt-dynver).

## Cutting a release

1. Make sure `main` is green and contains everything you want in the release.
2. Tag the commit with a `v`-prefixed [semantic version](https://semver.org/)
   and push the tag:

   ```shell
   git tag v1.0.0
   git push origin v1.0.0
   ```

3. Pushing the tag triggers the `Publish` workflow, which runs
   `sbt java-client/publishSigned`. Tag `v1.0.0` publishes version `1.0.0` to the
   `lightbend/akka` release repository. Watch the run under the repository's
   **Actions** tab.

That's it — there is no separate version file to bump; the tag *is* the version.

## Snapshots

Any build from an untagged commit produces a `-SNAPSHOT` version and, when
published, goes to the `lightbend/akka-snapshots` repository instead of the
release repository.

To publish a snapshot (or to smoke-test the pipeline before tagging), run the
`Publish` workflow manually: **Actions → Publish → Run workflow**, selecting the
branch to build.

## What gets published

- `io.akka:akka-platform-api-java-client` — the jar, plus a sources jar and pom.
  There is no Scala version suffix (`crossPaths := false`) as this is a Java
  library, and no javadoc jar (the client sources are generated).
- The schema-only root project does not publish anything (`publish / skip := true`).

Artifacts are GPG-signed as part of `publishSigned`.

## Required configuration

The workflow relies on these repository/organization secrets:

| Secret | Purpose |
| --- | --- |
| `PUBLISH_USER` / `PUBLISH_PASSWORD` | Cloudsmith write credentials |
| `PGP_SECRET` / `PGP_PASSPHRASE` | GPG key (base64) and passphrase for signing |

These are shared Akka organization secrets; `akka/platform-api` must be included
in each secret's repository-access list.

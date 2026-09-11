# Getting started

## What you'll have at the end of this page

- The offline probe passing locally, with no AWS account and no Docker.
- A real `dist/bootstrap` and `dist/lambda.zip`, built from source on Amazon Linux 2023.
- A live Lambda function deployed to your own AWS account, invoked once, and torn down again.

## Prerequisites

| Tool | Why | Install |
|---|---|---|
| **[jolt](https://github.com/jolt-lang/jolt)** | `jolt probe` runs the interpreted runtime loop locally, and `jolt <task>` reads this project's `bb.edn` directly, so it's the command every example on this page uses | `brew install jolt-lang/jolt/jolt` |
| **[babashka](https://babashka.org)** | jolt's task runner reads `bb.edn`, but several tasks (`test`, `deploy`, `invoke`, `teardown`, `bench`, the `site:*` tasks) shell out to a real `bb` binary internally, since they need `clojure.test` or `cheshire`, neither of which jolt bundles. You still need it installed even though you'll mostly type `jolt`, not `bb` | `brew install borkdude/brew/babashka` |
| **Docker** | Builds the deployable `bootstrap` binary on Amazon Linux 2023 (the glibc version has to match the real Lambda environment, see [Building on Amazon Linux 2023](al2023-build.md)) | [docker.com](https://www.docker.com) |
| **AWS CLI v2** | Every AWS-touching task shells out to it | `brew install awscli` |
| **An AWS account** | To deploy, invoke, and benchmark against | Credentials set up via `AWS_PROFILE`/`AWS_REGION` env vars or `aws configure`. Nothing in this repo hardcodes a profile, account, or region. |

## Clone and probe

```sh
git clone git@github.com:b12n-oss/lambda-mvp-jlt.git
cd lambda-mvp-jlt
jolt probe
```

`jolt probe` runs the actual runtime loop (`joltc run`, interpreted, not the compiled `bootstrap`) against an offline mock of the Lambda Runtime API on an OS-assigned local port. No AWS account, no Docker, and it finishes in about two seconds. If this doesn't pass, nothing later in this page will either: it's the fastest signal that something about your `joltc` install or the runtime loop itself is wrong before you spend five minutes on a Docker build.

Run the unit suite the same way:

```sh
jolt test
```

## Build the real binary

```sh
jolt image
```

This builds Chez Scheme and jolt from source inside an Amazon Linux 2023 container (the same OS Lambda's `provided.al2023` execution environment runs), compiles the handler and runtime loop into one self-contained executable, and audits every non-glibc shared library it links. It takes several minutes the first time; Docker's own layer cache makes later builds faster. When it finishes, `dist/bootstrap` and `dist/lambda.zip` exist.

## Deploy, invoke, tear down

```sh
jolt deploy
jolt invoke
jolt teardown
```

`jolt deploy` is idempotent: it creates the IAM role and Lambda function the first time, and updates them on every later call. `jolt invoke` runs a single ad-hoc invocation and prints the response body plus the CloudWatch `REPORT` line, the same line [Cold vs. warm boot](cold-warm-boot.md) explains how to read. `jolt teardown` deletes both the function and the role, so nothing keeps running in your account.

## Measure cold vs. warm boot time

```sh
jolt deploy
jolt bench
jolt teardown
```

`jolt bench` is the reason this project exists: it deploys at a few different memory tiers, measures one cold invocation and several warm ones at each, and prints a comparison table. See [Cold vs. warm boot](cold-warm-boot.md) for what the numbers mean and how to reproduce a jolt-version comparison.

## Next steps

Run `jolt tasks` to see every available command, including the documentation site tasks (`jolt site:build`/`jolt site:serve`). [Architecture](architecture.md) covers how the runtime loop, the build, and the bench tool fit together.

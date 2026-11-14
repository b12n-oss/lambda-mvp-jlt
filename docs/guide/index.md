# lambda-mvp-jlt, User guide

`lambda-mvp-jlt` runs [Jolt](https://github.com/jolt-lang/jolt) (native Clojure on Chez Scheme, no JVM) on AWS Lambda as a **custom runtime**, the same `provided.al2023` contract that [awslabs/aws-lambda-cpp](https://github.com/awslabs/aws-lambda-cpp) implements for C++. The runtime loop itself is about 60 lines of Clojure. On top of it sit a demo handler and a `jolt bench` tool that answers one question: how much wall-clock time does a Lambda invocation cost, cold versus warm, at different memory tiers, on your own AWS account.

Every AWS-touching command in this repo relies on the caller's own `aws` CLI configuration (`AWS_PROFILE`/`AWS_REGION`, or `aws configure`). Nothing here hardcodes a profile, an account, or a region: clone it, point it at your account, and the same commands that produced this project's own measured numbers will produce yours.

## How to read this guide

**New to the project?** Start with [Getting started](getting-started.md) for the offline probe, the Docker build, and your first live deploy. Then read [Architecture](architecture.md) for how the Runtime API loop, the AL2023 build, and the bench tool fit together.

**Want the Runtime API loop's own design notes?** [The Runtime API loop](runtime-api-loop.md) covers the poll/execute/respond contract, why the loop needs no JSON dependency, and the offline mock that turns a runtime-loop change into a two-second local iteration.

**Building on Amazon Linux 2023?** [Building on Amazon Linux 2023](al2023-build.md) covers the glibc constraint that forces a from-source build, the recipe, and the AL2023 packaging gotchas that each cost a build round.

**Want to understand or reproduce the cold/warm boot-time story?** [Cold vs. warm boot](cold-warm-boot.md) is why this project exists: what `jolt bench` measures, how to read the table it prints, and the recipe for reproducing a jolt-version comparison against your own account.

**Contributing a change?** [Contributing](contributing.md) covers the build, the test commands, and this project's conventions.

## Guide map

| Page | What you'll learn |
|---|---|
| [Getting started](getting-started.md) | Install, offline probe, Docker build, first live deploy |
| [Architecture](architecture.md) | How the runtime loop, the build, and the bench tool fit together |
| [The Runtime API loop](runtime-api-loop.md) | The Lambda custom-runtime contract and this project's implementation |
| [Building on Amazon Linux 2023](al2023-build.md) | The glibc constraint, the from-source build recipe, arm64 |
| [Cold vs. warm boot](cold-warm-boot.md) | What `jolt bench` measures and how to reproduce the comparison |
| [Running against LocalStack](localstack.md) | Deploy/invoke/teardown with no AWS account, via `LAMBDA_ENDPOINT_URL` |
| [Contributing](contributing.md) | Build, test, and PR conventions |

## Find your scenario

| Scenario | Pages to read |
|---|---|
| "I want to try this out" | Getting started |
| "I want to understand how it works" | Architecture, then The Runtime API loop and Building on Amazon Linux 2023 |
| "I want to measure cold/warm boot time on my own account" | Getting started, then Cold vs. warm boot |
| "I want the full lifecycle without an AWS account" | Running against LocalStack |
| "I want to contribute a change" | Contributing |

## See also

- [Project README](https://github.com/b12n-oss/lambda-mvp-jlt/blob/main/README.md): the same quickstart in prose, plus the restricted-networks workarounds.
- [`CHANGELOG.md`](https://github.com/b12n-oss/lambda-mvp-jlt/blob/main/CHANGELOG.md): version history.

# Contributing

## Build and test

```sh
jolt probe        # offline e2e: mock Runtime API + demo handler (no AWS, no Docker), ~2s
jolt test         # unit tests for script/bench.clj's pure functions (no AWS, no Docker)
jolt image        # AL2023 Docker build -> dist/bootstrap + dist/lambda.zip (several minutes)
```

`jolt probe` and `jolt test` are the fast, offline loop: run them after every change to `src/`, `script/bench.clj`, or `tools/mock_runtime_api.py`. `jolt image` is the slow, real one: run it after any `Dockerfile` change, and check the `ldd` audit in the build log for `not found` lines.

Live-testing against a real AWS account (`jolt deploy`/`jolt invoke`/`jolt bench`/`jolt teardown`) isn't part of the fast loop, but it's the check that actually matters for anything touching `script/aws_lifecycle.clj` or `script/bench_run.clj`. This project's own development found three real bugs this way that no offline test could have (a memory default too low for jolt's own heap ceiling, and a silent-failure gap in how `jolt bench` reported a crashing function): see [Cold vs. warm boot](cold-warm-boot.md) for the detail. If you're changing either of those files, deploy to a throwaway function in your own account, exercise the change for real, and tear it down again before opening a PR.

## Conventions

- **No hardcoded AWS profile, account, or region, anywhere.** Every AWS-touching command relies on the caller's own `aws` CLI configuration. Grep for `--profile` and a 12-digit account ID before committing anything that touches `script/aws_lifecycle.clj` or `script/bench_run.clj`.
- **`bb.edn` task bodies stay EDN-reader-safe.** No `#"regex"`, `@deref`, or `#(...)` anonymous-fn literals in a task's `:task` form. Real logic lives in `script/*.clj`, invoked as a babashka subprocess from a thin `bb.edn` wrapper.
- **Every AWS-mutating call checks its own exit code.** A `sh` call that can fail (`create-function`, `delete-role`, `wait function-updated`) dies with the AWS CLI's own error text rather than silently continuing past a failure.
- **Namespace root is `net.b12n.lambda-mvp`.** Directory `src/net/b12n/lambda_mvp/`.

## Docs site

```sh
jolt site:build   # into _site/ (needs a docs-engine checkout, see below)
jolt site:serve   # build and serve locally
```

Both need a local checkout of [`b12n-oss/docs-engine`](https://github.com/b12n-oss/docs-engine), found via `$DOCS_ENGINE`, a sibling `../docs-engine` directory, or `~/dev/b12n-oss/docs-engine`. CI checks the engine out itself, so this is for local preview only. New guide pages go in `docs/guide/*.md`, no frontmatter: title and description come from `docs/site.edn`.

## Pull requests

Open against `main`. Describe what changed and why, not just what. If the change touches `script/aws_lifecycle.clj` or `script/bench_run.clj`, say what you live-tested and against what memory tier.

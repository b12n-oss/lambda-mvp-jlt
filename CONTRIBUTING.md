# Contributing to lambda-mvp-jlt

Issues and pull requests are welcome. This is the short version of what a
change needs to pass. The longer, narrative version lives at
[`docs/guide/contributing.md`](docs/guide/contributing.md) and renders
through [docs-engine](https://github.com/b12n-oss/docs-engine).

## Status

This is an early release. Jolt itself, and the ecosystem around it, are
still evolving. Task names and defaults may change without a deprecation
period. Say so in your PR if a change depends on current behavior staying
exactly as it is.

## The gate

```sh
jolt probe   # offline e2e: mock Runtime API + jolt loop (no AWS, no Docker), ~2s
jolt test    # unit tests for script/bench.clj's pure functions (no AWS, no Docker)
jolt image   # AL2023 Docker build -> dist/bootstrap + dist/lambda.zip (several minutes)
```

`jolt probe` and `jolt test` are the fast loop: run them after every
change to `src/`, `script/bench.clj`, or `tools/mock_runtime_api.py`.
`jolt image` is the slow, real one: run it after any `Dockerfile` change,
and check the `ldd` audit in the build log for `not found` lines.

Live-testing against a real AWS account (`jolt deploy`/`jolt invoke`/
`jolt bench`/`jolt teardown`) is the check that actually matters for
anything touching `script/aws_lifecycle.clj` or `script/bench_run.clj`.
This project's own development found three real bugs this way that no
offline test could have: see
[Cold vs. warm boot](docs/guide/cold-warm-boot.md) for the detail. If
you're changing either file, deploy to a throwaway function in your own
account, exercise the change for real, and tear it down again before
opening a PR.

## Traps

**`jolt` runs `bb.edn` tasks directly, but it isn't a full babashka
replacement.** Several tasks (`test`, `deploy`, `invoke`, `teardown`,
`bench`, the `site:*` tasks) shell out to a real `bb` binary internally,
because they need `clojure.test` or `cheshire`, neither of which jolt
bundles. Running a `script/*.clj` file directly with `jolt run FILE`
instead of `bb FILE` will fail on the first `:require` jolt doesn't
recognize. Both binaries need to be installed.

**No hardcoded AWS profile, account, or region, anywhere.** Every
AWS-touching command relies on the caller's own `aws` CLI configuration.
Grep for `--profile` and a 12-digit account ID before committing anything
that touches `script/aws_lifecycle.clj` or `script/bench_run.clj`.

**`bb.edn` task bodies stay EDN-reader-safe.** No `#"regex"`, `@deref`, or
`#(...)` anonymous-fn literals in a task's `:task` form. Real logic lives
in `script/*.clj`, invoked as a subprocess from a thin `bb.edn` wrapper.

**Every AWS-mutating call checks its own exit code.** A `sh` call that can
fail (`create-function`, `delete-role`, `wait function-updated`) dies with
the AWS CLI's own error text rather than silently continuing past a
failure. An early version of this project's `teardown!` didn't, and
silently reported success on a failed delete.

## Docs

```sh
jolt site:build   # into _site/ (needs a docs-engine checkout, see docs/guide/contributing.md)
jolt site:serve   # build and serve locally
```

**Prose style: no em-dashes anywhere**, headings and table cells included.
Simple connectives, varied sentence length. This applies to documentation,
commit messages, and code comments alike.

## Pull requests

- Branch off `main`.
- Keep the change and its tests in the same commit where that is natural.
- Explain **why** in the commit message. The diff already shows what.
- Run `jolt probe` and `jolt test` before you open it.
- Stage files by explicit path. Please do not `git add -A`.
- If the change touches `script/aws_lifecycle.clj` or
  `script/bench_run.clj`, say what you live-tested and against what
  memory tier, and on which architecture (`arm64`/`x86_64`) if the change
  could plausibly affect the `LAMBDA_ARCH`/`--architectures` path.

## AI-assisted contributions

Use whatever helps. What matters is that you have read what you are
submitting, that you can explain why each part is there, and that any test
you add is a real test rather than an assertion shaped to pass. Do not
paste generated documentation you have not verified: several claims in
this repository's own docs were wrong on the first pass and were caught
only by running the command rather than by reading it back.

## Security

Do not open a public issue for a vulnerability. Use GitHub's private
security advisory reporting for this repository instead.

## License

Eclipse Public License 2.0. By contributing you agree your contribution is
licensed under it.

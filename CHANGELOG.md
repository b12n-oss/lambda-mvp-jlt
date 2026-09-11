# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Added

- Initial public release preparation.
- x86_64 builds: `LAMBDA_ARCH=x86_64 jolt image` (default stays `arm64`); `jolt deploy` reads the architecture from `dist/bootstrap`'s ELF header and passes it on both create and update.
- `jolt demo`: `image` + `deploy` + `invoke` in one command, with every prerequisite (tools, AWS credentials and region, Docker access, emulation for `LAMBDA_ARCH`) checked before the build starts.

## [0.1.0]

- Extracted the custom-runtime core (Runtime API loop, demo handler, offline probe) from the private `b12n-lambda-jolt` project, generalized: no hardcoded AWS profile, account, or region anywhere.
- AL2023 Docker build (`jolt image`): Chez Scheme and jolt from source, `arm64`-pinned, with `JOLT_VERSION`/`CHEZ_VERSION` as overridable build args.
- Generic, idempotent AWS lifecycle tool (`jolt deploy`/`jolt invoke`/`jolt teardown`), driven entirely by the caller's own `aws` CLI configuration.
- `jolt bench`: the centerpiece. Measures and compares cold-vs-warm Lambda boot time across memory tiers, with a `FunctionError`-aware invoke path so a crashing function never silently reports bogus timing data.
- Live-verified against a real AWS account during development, which caught three real bugs no offline test could have: a deploy memory default below jolt's own heap ceiling, `jolt bench` default tiers that hit the same ceiling and then a real AWS account quota limit, and the `FunctionError` detection gap above.

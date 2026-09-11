# Building on Amazon Linux 2023

## The glibc constraint

Two facts collide:

- Lambda's `provided.al2023` execution environment is based on the AL2023
  **minimal** container image: < 40 MB, no language runtime, **glibc 2.34**.
- Jolt's **prebuilt** Linux x86_64 binary requires **glibc ≥ 2.35**
  (Ubuntu 22.04+, jolt README).

glibc is backward-compatible only: a binary built against an older glibc runs on
a newer one, never the reverse. So neither the Homebrew joltc (macOS) nor the
release Linux joltc can produce a deployable binary. The `bootstrap` must be
built by a **from-source jolt running on AL2023 itself**, so it links 2.34.

## The recipe (Dockerfile)

Mirrors jolt's own CI (`.github/workflows/tests.yml`) transplanted to dnf:

1. **Chez Scheme from source** (`CHEZ_VERSION` build arg, default `10.4.1`),
   `./configure --installprefix=/opt/chez --threads --disable-x11`. From
   source because distro chez packages ship no **kernel dev files**
   (`libkernel.a`, `scheme.h`), which `joltc build` needs to link the
   self-contained executable. A `chez` wrapper execing `/opt/chez/bin/scheme`
   goes next to `scheme` so jolt's build derives the kernel-file location
   from it.
2. **Jolt from a fresh clone** (`JOLT_VERSION` build arg, default `0.8.7`,
   `--recurse-submodules`). Its bootstrap seed is checked in: clone and run,
   no build step.
3. `joltc build -m net.b12n.lambda-mvp.main -o bootstrap`: fetches the
   `deps.edn` git deps, compiles app + runtime + stdlib, links one executable.
4. **`ldd` audit → `lib/`**: every non-glibc `.so` the binary links is copied
   into `lib/`, plus the http-client's dlopened trio (`libz.so.1`,
   `libssl.so.3`, `libcrypto.so.3`) which `ldd` cannot see. Lambda's
   `LD_LIBRARY_PATH` includes `/var/task/lib`, so the zip is self-sufficient.
   glibc itself is **excluded**: that must come from the execution environment.
5. `zip -r lambda.zip bootstrap lib` and a `FROM scratch` export stage so
   `docker build --target export --output dist .` drops `dist/bootstrap` +
   `dist/lambda.zip` on the host.

Chez statically links its vendored zlib and lz4, which keeps the `lib/` bundle
short.

## `JOLT_VERSION`/`CHEZ_VERSION` are build args, not constants

Both are overridable: `JOLT_VERSION=0.7.14 jolt image` rebuilds against an
older jolt release without editing the Dockerfile. This is what makes
`docs/guide/cold-warm-boot.md`'s jolt-version comparison reproducible rather
than a one-time historical claim.

## AL2023 packaging gotchas (each cost one build round in the source project)

- **`which` is not installed.** `bin/joltc` locates chez via `which` → install
  the `which` package.
- **`xxd` is not installed.** jolt's self-contained link embeds the boot image
  via `xxd -i` → `xxd` ships in the **`vim-common`** package.
- These live in a **separate `RUN dnf install` layer placed after the Chez
  build** so fixing them doesn't invalidate the ~4-minute Chez layer.

## arm64 and x86_64

By default the build targets **linux/arm64** (native on Apple Silicon Docker,
no qemu) and deploys to Lambda `--architectures arm64` (Graviton, cheaper per
GB-second). Chez v10 supports aarch64le Linux; jolt's release binaries don't
cover aarch64 Linux, but the from-source build works.

`LAMBDA_ARCH=x86_64 jolt image` (`amd64` also accepted) builds the identical
Dockerfile under `--platform linux/amd64` instead. `jolt deploy` reads the
architecture from `dist/bootstrap`'s ELF header, so it always matches the last
build, including when it switches an existing function's architecture.

Building for the architecture your machine isn't needs qemu. Docker Desktop
ships it; on a plain Linux Docker Engine, a missing emulator fails the first
`RUN` with `exec /bin/sh: exec format error`. Either build natively (on an
x86_64 Linux host, `LAMBDA_ARCH=x86_64`) or register the emulator, e.g.
`docker run --privileged --rm tonistiigi/binfmt --install arm64`. Expect an
emulated Chez build to be several times slower.

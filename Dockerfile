# Build the jolt Lambda bootstrap on Amazon Linux 2023 -- the same OS as the
# provided.al2023 execution environment, so the binary links its glibc (2.34).
# Jolt's prebuilt Linux binary needs glibc >= 2.35, hence the from-source build
# (Chez recipe mirrored from jolt's own CI, .github/workflows/tests.yml).
#
#   docker build --target export --output dist .
#
# emits dist/bootstrap and dist/lambda.zip for `aws lambda create-function
# --runtime provided.al2023`.

# Overridable for restricted networks: pre-load an image (e.g. via `crane pull`
# + `docker load` + `docker tag ... my-local/amazonlinux:2023`) and pass
# --build-arg BASE_IMAGE=my-local/amazonlinux:2023.
ARG BASE_IMAGE=public.ecr.aws/amazonlinux/amazonlinux:2023
FROM ${BASE_IMAGE} AS build

# Overridable so a collaborator can reproduce a jolt-version cold/warm
# boot-time comparison -- see docs/guide/cold-warm-boot.md:
#   JOLT_VERSION=0.7.14 bb build && bb deploy && bb bench
#   JOLT_VERSION=0.8.6  bb build && bb deploy && bb bench
ARG JOLT_VERSION=0.8.6
ARG CHEZ_VERSION=10.4.1

RUN dnf install -y gcc gcc-c++ make git zip tar gzip findutils \
      ncurses-devel libuuid-devel zlib-devel lz4-devel openssl-libs \
    && dnf clean all

# Chez Scheme from source: the distro-package route ships no kernel dev files
# (libkernel.a, scheme.h), which `joltc build` needs to link a binary.
RUN git clone --depth 1 --branch v${CHEZ_VERSION} https://github.com/cisco/ChezScheme.git /tmp/chez-src \
    && cd /tmp/chez-src \
    && ./configure --installprefix=/opt/chez --threads --disable-x11 \
    && make -j"$(nproc)" \
    && make install \
    && printf '#!/bin/sh\nexec /opt/chez/bin/scheme "$@"\n' > /opt/chez/bin/chez \
    && chmod +x /opt/chez/bin/chez \
    && rm -rf /tmp/chez-src
ENV PATH=/opt/chez/bin:$PATH

# Jolt from source (bootstrap seed is checked in -- no build step, clone and
# run). PINNED to a tag, not a floating clone of main, so `bb build` is
# reproducible.
RUN git clone --recurse-submodules --depth 1 --branch v${JOLT_VERSION} https://github.com/jolt-lang/jolt.git /opt/jolt
ENV PATH=/opt/jolt/bin:$PATH

# joltc's build tooling shells out to `which` (locate chez) and `xxd` (embed
# the boot image; ships in vim-common) -- neither is in AL2023 by default.
# Separate layer (not in the main dnf install) to keep the Chez build cached.
RUN dnf install -y which vim-common && dnf clean all

# WORKDIR is /var/task, matching a deployed custom-runtime zip's own cwd on
# Lambda -- not an arbitrary build-only name.
WORKDIR /var/task
COPY deps.edn ./
COPY src ./src

# The Lambda entry point: a self-contained native executable named bootstrap.
RUN joltc build -m net.b12n.lambda-mvp.main -o bootstrap

# Bundle every non-glibc shared library the binary links (ldd) or dlopens at
# startup (the http-client's :jolt/native list: libz, libssl, libcrypto) into
# lib/ -- Lambda's LD_LIBRARY_PATH includes /var/task/lib. glibc itself must
# come from the execution environment, never the zip.
RUN mkdir -p lib \
    && for f in $(ldd bootstrap | awk '/=> \// {print $3}' \
                  | grep -Ev 'libc\.so|libm\.so|libpthread|libdl|librt|ld-linux|libresolv|libnss'); do \
         cp -v "$f" lib/; done \
    && for name in libz.so.1 libssl.so.3 libcrypto.so.3; do \
         [ -e "lib/$name" ] || cp -v "/usr/lib64/$name" lib/; done \
    && zip -r lambda.zip bootstrap lib \
    && ldd bootstrap && ls -la lib/ \
    && ls -l lambda.zip

FROM scratch AS export
COPY --from=build /var/task/bootstrap /bootstrap
COPY --from=build /var/task/lambda.zip /lambda.zip

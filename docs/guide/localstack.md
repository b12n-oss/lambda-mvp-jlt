# Running against LocalStack

Everything in this repo that touches AWS (`jolt deploy`, `jolt invoke`,
`jolt teardown`, `jolt bench`, `jolt demo`) can run against
[LocalStack](https://localstack.cloud) instead of a real account — useful for
trying the full deploy/invoke lifecycle with zero AWS credentials, zero cost,
and zero risk to a real account. The Runtime API loop, the handler, and the
zip are exactly the same artifacts real AWS gets; only the control plane
(`lambda create-function`, `invoke`, IAM) is emulated.

## Quickstart

```sh
jolt image              # unchanged: build dist/lambda.zip first
jolt localstack:demo    # start LocalStack + deploy + invoke, no AWS account
jolt localstack:stop    # stop + remove the container when done
```

`jolt localstack:demo` starts a detached `localstack/localstack` container
(`lambda-mvp-jlt-localstack`, edge on `http://localhost:4566`, host docker
socket mounted), waits for the edge to answer, then runs this repo's own
`deploy` and `invoke` tasks unchanged. `jolt localstack:status` reports
whether the container is up and the edge is answering.

The Lambda function containers LocalStack runs are created through the host's
docker daemon (that's why the socket is mounted), so the function executes
real `bootstrap` + `lib/` from your `dist/lambda.zip`.

## The switch: `LAMBDA_ENDPOINT_URL`

Nothing about the default behavior changes until you set
`LAMBDA_ENDPOINT_URL`. Unset (or blank) means real AWS, exactly as before.
Set, it does two things to every `aws` CLI call the scripts make:

- `--endpoint-url <value>` is injected as a global option, and
- LocalStack's documented dummy credentials (`test`/`test`, region
  `us-east-1`) are exported — unless you've set `AWS_ACCESS_KEY_ID` /
  `AWS_SECRET_ACCESS_KEY` / `AWS_DEFAULT_REGION` yourself, which then win.

The real-AWS credential/region preflight in `demo`/`deploy`/`invoke`/`bench`
is skipped when the endpoint is set, so no aws CLI configuration is needed at
all for the LocalStack path.

You can drive it by hand, without the wrapper tasks:

```sh
export LAMBDA_ENDPOINT_URL=http://localhost:4566
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
aws --endpoint-url $LAMBDA_ENDPOINT_URL lambda list-functions   # anything, really
bb script/aws_lifecycle.clj deploy   # or jolt deploy -- same code path
bb script/aws_lifecycle.clj invoke
bb script/aws_lifecycle.clj teardown
```

(Or start your own LocalStack: `docker run -d -p 4566:4566
-v /var/run/docker.sock:/var/run/docker.sock localstack/localstack`.)

Note on versions: LocalStack's March 2026 licensing change means
`localstack/localstack:latest` (and anything newer) refuses to start without
`LOCALSTACK_AUTH_TOKEN`, even for community features. This repo therefore
pins `localstack/localstack:4.13.1`, the last tag that runs without an
account. If you have a token (the free tier works), export
`LOCALSTACK_AUTH_TOKEN` and optionally `LOCALSTACK_IMAGE` (e.g.
`localstack/localstack:latest`) and the wrapper tasks will pass both to the
container.

## What LocalStack is good for here, and what it isn't

Good:

- End-to-end confidence in the deploy/invoke/teardown lifecycle (packaging,
  `provided.al2023`, handler wiring, response/log plumbing) without an
  account.
- Fast iteration on `aws_lifecycle.clj` itself.
- CI smoke tests.

Not good:

- **Cold/warm boot numbers.** `jolt bench` runs against LocalStack if the
  endpoint is set, but LocalStack executes your function in a plain docker
  container on your machine — no Firecracker microVM, no Lambda memory-tier
  CPU credits, and on Apple Silicon possibly under emulation. The numbers are
  meaningless for the cold-vs-warm question; use a real account for that
  (that's what `jolt bench` exists for).
- IAM fidelity. LocalStack's IAM is permissive; a trust policy that works
  here may still be rejected by real AWS.

## Requirements

- Docker (running), for both the LocalStack container and the function
  containers it spawns.
- The `aws` CLI on PATH (version 2), as for the real-AWS path.

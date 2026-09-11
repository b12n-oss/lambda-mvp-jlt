# Cold vs. warm boot on a Jolt custom runtime

## Three numbers, one log line

Every Lambda invocation ends with a `REPORT` line in CloudWatch:

```
REPORT RequestId: ... Duration: 4.60 ms Billed Duration: 5 ms Memory Size: 512 MB Max Memory Used: 237 MB [Init Duration: 322.20 ms]
```

- **Init Duration** (cold invokes only) — time to start the execution
  environment (launch `bootstrap`, jolt runtime init) before the handler's
  first request is served.
- **Duration** — the handler's own execution time (the Runtime API loop's
  `next-invocation` → `handler` → `post-response` round trip). Present on
  every invocation, cold or warm.
- **Billed Duration** — what you're actually charged for. For a custom
  runtime this *includes* Init Duration on a cold invoke.

`bb invoke` prints this line directly. `script/bench.clj`'s
`parse-report-line` turns it into a Clojure map; `Init Duration`'s absence is
exactly how a warm sample is told apart from a cold one.

## What `bb bench` does

For each memory tier in `BENCH_MEMORY_TIERS` (default `2048,3072,4096`):

1. `aws lambda update-function-configuration --memory-size <tier>` — any
   configuration update invalidates the function's existing execution
   environments, so the very next invoke runs in a fresh one. No separate
   "force cold start" trick needed.
2. Waits for the update to finish applying, then invokes once — the cold
   sample.
3. Invokes `BENCH_WARM_SAMPLES` more times back-to-back (no further config
   change) — the warm samples, landing on the same execution environment.
4. Prints a table: Cold Init Duration, Cold Duration, Warm Duration
   (min/median/max), Max Memory Used — one column per tier.

The default tiers start at 2048 MB because jolt v0.8.5+ caps its heap at
25% of the configured Lambda memory, and this runtime's baseline live
heap (~250 MB, regardless of app size) needs at least that much
headroom — below it, every invocation fails at boot with
`JOLT_MAX_HEAP is smaller than the runtime's own live heap`, not a
timing problem `bb bench` can usefully measure. If you lower
`BENCH_MEMORY_TIERS` below ~2048 MB yourself, expect to see exactly
that error rather than a number.

Run it:

```sh
bb deploy
bb bench
bb teardown   # when you're done -- nothing should keep running in your account
```

If a "cold" sample shows no Init Duration, `bb bench` prints a warning
rather than silently reporting incomplete data — that would mean the
execution environment wasn't actually fresh, worth investigating rather than
trusting the number.

## What the source research project found

Measured in the private project this repo was extracted from (`us-east-1`,
`provided.al2023`, arm64, 2026-07-18 — **illustrative, not a live
guarantee**; your numbers will differ by account, region, and the hardware
allocation AWS happens to give you):

| Metric | 256 MB | 512 MB |
|---|---|---|
| Cold `Init Duration` | 920 ms | 1339 ms |
| Cold handler `Duration` | 16.1 ms | 5.1 ms |
| Warm `Duration` | 19.9 → 3.8 → 4.6 ms | 5.0 ms |
| `Max Memory Used` | 234 MB | 237 MB |

Takeaways that held in that project: warm invocations are consistently in
the low single-digit milliseconds once a sandbox has served an event; cold
init is dominated by process/runtime startup, not the handler; max memory is
roughly constant (the Chez heap) regardless of the configured limit, as long
as the limit is above that floor.

## Reproducing the jolt-version finding

The same project later bumped jolt `v0.7.14 → v0.8.6` and found a **mixed**
result: warm invokes got materially faster (jolt's own vfasl boot-image work
and general runtime tightening), but cold `Init Duration` got slower for
that project's specific binary, because the binary itself grew substantially
between the two pins (unrelated dependency growth, not jolt's own boot
format). That's a finding specific to what was linked into that binary, not
a general claim about the jolt version bump — which is exactly why it's
worth reproducing against *this* repo's much smaller, dependency-free
binary rather than taking the number on faith:

```sh
JOLT_VERSION=0.7.14 bb build && bb deploy && bb bench   # note the table
JOLT_VERSION=0.8.6  bb build && bb deploy && bb bench   # compare
bb teardown
```

Diff the two `bb bench` tables. If you try other jolt releases, the same
recipe applies — `JOLT_VERSION` is a plain Dockerfile build arg.

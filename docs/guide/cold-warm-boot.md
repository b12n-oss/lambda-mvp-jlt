# Cold vs. warm boot on a Jolt custom runtime

## Three numbers, one log line

Every Lambda invocation ends with a `REPORT` line in CloudWatch:

```
REPORT RequestId: ... Duration: 4.60 ms Billed Duration: 5 ms Memory Size: 512 MB Max Memory Used: 237 MB [Init Duration: 322.20 ms]
```

- **Init Duration** (cold invokes only): time to start the execution
  environment (launch `bootstrap`, jolt runtime init) before the handler's
  first request is served.
- **Duration**: the handler's own execution time (the Runtime API loop's
  `next-invocation` → `handler` → `post-response` round trip). Present on
  every invocation, cold or warm.
- **Billed Duration**: what you're actually charged for. For a custom
  runtime this *includes* Init Duration on a cold invoke.

`jolt invoke` prints this line directly. `script/bench.clj`'s
`parse-report-line` turns it into a Clojure map; `Init Duration`'s absence is
exactly how a warm sample is told apart from a cold one.

## What `jolt bench` does

For each memory tier in `BENCH_MEMORY_TIERS` (default `2048,3008`):

1. `aws lambda update-function-configuration --memory-size <tier>`: any
   configuration update invalidates the function's existing execution
   environments, so the very next invoke runs in a fresh one. No separate
   "force cold start" trick needed.
2. Waits for the update to finish applying, then invokes once, the cold
   sample.
3. Invokes `BENCH_WARM_SAMPLES` more times back-to-back (no further config
   change), the warm samples, landing on the same execution environment.
4. Prints a table: Cold Init Duration, Cold Duration, Warm Duration
   (min/median/max), Max Memory Used, one column per tier.

The default tiers start at 2048 MB because jolt v0.8.5+ caps its heap at
25% of the configured Lambda memory, and this runtime's baseline live
heap (~250 MB, regardless of app size) needs at least that much
headroom. Below it, every invocation fails at boot with
`JOLT_MAX_HEAP is smaller than the runtime's own live heap`, not a
timing problem `jolt bench` can usefully measure. If you lower
`BENCH_MEMORY_TIERS` below ~2048 MB yourself, expect to see exactly
that error rather than a number.

Run it:

```sh
jolt deploy
jolt bench
jolt teardown   # when you're done -- nothing should keep running in your account
```

Two things worth knowing before you run it. `jolt bench` leaves the function
configured at its last tier's memory size (3008 MB by default): a later
`jolt deploy` resets it back to 2048 MB, but if you only ever run `jolt bench`
you're left on the higher-cost tier. And `jolt deploy`/`jolt bench`/`jolt teardown`
all act on whatever function/role name is configured
(`LAMBDA_MVP_FUNCTION_NAME`, default `lambda-mvp-jlt`), so don't point it at
an existing unrelated resource, since `jolt deploy` will overwrite its code
and `jolt teardown` will delete it.

If a "cold" sample shows no Init Duration, `jolt bench` prints a warning
rather than silently reporting incomplete data. That would mean the
execution environment wasn't actually fresh, worth investigating rather than
trusting the number.

## This repo's own baseline

Measured against this repo's own binary (not the source project's), jolt
v0.8.7, arm64, `provided.al2023`, 2026-09-12, two regions back to back:

| Metric | us-west-2, 2048 MB | us-west-2, 3008 MB | ap-southeast-2, 2048 MB | ap-southeast-2, 3008 MB |
|---|---|---|---|---|
| Cold Init Duration | 325.3 ms | 306.5 ms | 288.3 ms | 401.9 ms |
| Cold Duration | 2.4 ms | 2.2 ms | 2.1 ms | 3.0 ms |
| Warm Duration (min/median/max) | 1.8 / 1.9 / 2.0 ms | 2.0 / 2.2 / 2.3 ms | 1.7 / 1.8 / 2.0 ms | 2.1 / 2.2 / 2.6 ms |
| Max Memory Used | 164 MB | 164 MB | 164 MB | 164 MB |

Same as **illustrative, not a live guarantee**: a single run each, and the
spread between tiers and regions above (down to ~2 ms warm, Cold Init
anywhere from ~290 to ~400 ms) is itself the point, not a precise number to
target.

That table stays here as the record of what v0.8.7 did, and it was measured
while v0.8.7 was this repo's default. The default is now v0.8.9, so for
current-default numbers read
[v0.8.7 vs v0.8.9](#v087-vs-v089-three-runs-each) below, which measures both
versions on the same function on the same day.

### v0.8.6 vs v0.8.7, confirmed

The 164 MB above is notably below the ~250 MB baseline the section below
describes for jolt v0.8.6 and earlier, and v0.8.7's own changelog credits
binary-size and resident-memory cuts (a hello-world example there went from
225 MB to 95 MB resident). Confirmed against this repo's own binary, not
just plausible: same function, same region (ap-southeast-2), same memory
tiers, redeployed with `JOLT_VERSION=0.8.6` then `JOLT_VERSION=0.8.7`
back to back, 2026-09-12.

| Metric | 0.8.6, 2048 MB | 0.8.6, 3008 MB | 0.8.7, 2048 MB | 0.8.7, 3008 MB |
|---|---|---|---|---|
| Cold Init Duration | 587.5 ms | 590.3 ms | 399.1 ms | 317.9 ms |
| Cold Duration | 1.9 ms | 2.2 ms | 2.4 ms | 2.3 ms |
| Warm Duration (min/median/max) | 1.7 / 1.8 / 1.9 ms | 1.8 / 1.9 / 2.1 ms | 2.1 / 2.2 / 2.5 ms | 1.8 / 2.0 / 3.0 ms |
| Max Memory Used | 317 MB | 317 MB | 164 MB | 164 MB |

`dist/bootstrap` itself shrank the same way: 34.4 MB under 0.8.6 down to
15.1 MB under 0.8.7. Max Memory Used roughly halved and Cold Init Duration
dropped by 30-46% at both tiers, in the direction the changelog claims and
by a margin too large to be tier-to-tier noise. Warm Duration moved the
other way slightly, still low single-digit milliseconds either version, not
a meaningful difference at this sample size (one warm-sample run per tier
per version).

### v0.8.7 vs v0.8.9, three runs each

jolt v0.8.9 landed on 2026-09-18. Its changelog is mostly about `jolt.loader`,
a real `java.util.zip` on the zlib every binary already links, and lock-free
atoms that let parallel Clojure scale again, so nothing in it promises a faster
Lambda cold start directly. Measured anyway, against this repo's own binary,
same function, same region (`ap-southeast-2`), redeployed with
`JOLT_VERSION=0.8.7` then `JOLT_VERSION=0.8.9` back to back on 2026-09-19.

Cold Init Duration is the noisiest number `jolt bench` produces, and one sample
per tier cannot tell a version change apart from whatever hardware AWS handed
out that minute. So this table reports the median of **three** `jolt bench` runs
per version per tier, with the Cold Init range beside it:

| Metric | 0.8.7, 2048 MB | 0.8.9, 2048 MB | 0.8.7, 3008 MB | 0.8.9, 3008 MB |
|---|---|---|---|---|
| Cold Init Duration (median of 3) | 407.4 ms | 361.6 ms | 311.0 ms | 346.1 ms |
| Cold Init Duration (range of 3) | 326-446 ms | 358-438 ms | 310-311 ms | 334-437 ms |
| Cold Duration (median of 3) | 2.3 ms | 1.8 ms | 2.1 ms | 1.9 ms |
| Warm Duration (median of the 3 medians) | 2.1 ms | 1.7 ms | 1.8 ms | 1.7 ms |
| Max Memory Used | 164 MB | 182 MB | 164 MB | 182 MB |

Three things fall out of it, and only one is the speed-up the release suggests.

**The handler itself got faster, at both tiers.** Cold Duration dropped from
2.3 to 1.8 ms at 2048 MB and from 2.1 to 1.9 ms at 3008 MB. Across all six
cold samples per version the two distributions barely touch: 0.8.9's slowest
was 2.1 ms and 0.8.7's fastest was 2.0 ms. Warm Duration moved the same way,
2.1 down to 1.7 ms and 1.8 down to 1.7 ms. The absolute numbers are tiny, so
read this as a consistent direction rather than a headline.

**Cold Init did not measurably improve.** The 2048 MB median looks better and
the 3008 MB median looks worse, while the two versions' ranges overlap heavily
at both tiers. On this binary and this account, moving v0.8.7 to v0.8.9 buys
nothing at boot that three runs per tier can separate from noise.

**Memory went up.** Max Memory Used moved from 164 MB to 182 MB, roughly 11%
more, and it held steady across every run either way. `dist/bootstrap` grew
with it, from 15,099,280 bytes under 0.8.7 to 16,895,672 under 0.8.9, roughly
12%. That direction matters here because the v0.8.5 heap ceiling ties the
usable memory tiers to the runtime's own live heap, so a runtime carrying more
of it eats into the headroom the 2048 MB floor exists to provide.

One more check worth recording: rebuilding v0.8.7 today produced a
`dist/bootstrap` of 15,099,280 bytes, which is the same 15.1 MB the
v0.8.6-vs-v0.8.7 section above recorded a week earlier, and its bench numbers
landed within a few percent of that run. The build is reproducible, so the
0.8.9 deltas above are a property of the version rather than of the day.

## What the source research project found

Measured in the private project this repo was extracted from (`us-east-1`,
`provided.al2023`, arm64, 2026-07-18, **illustrative, not a live
guarantee**; your numbers will differ by account, region, and the hardware
allocation AWS happens to give you). They were measured against that project's
then-current jolt v0.7.14 pin, before the v0.8.5 heap ceiling existed, so the
256 and 512 MB tiers shown below are not reproducible against this repo's own
jolt v0.8.9 default, for the reason the heap-ceiling note above describes:

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
a general claim about the jolt version bump, which is exactly why it's
worth reproducing against *this* repo's much smaller, dependency-free
binary rather than taking the number on faith:

```sh
JOLT_VERSION=0.7.14 jolt image && jolt deploy && jolt bench   # note the table
JOLT_VERSION=0.8.9  jolt image && jolt deploy && jolt bench   # compare
jolt teardown
```

Diff the two `jolt bench` tables. If you try other jolt releases, the same
recipe applies: `JOLT_VERSION` is a plain Dockerfile build arg.

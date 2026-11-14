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
- **Billed Duration**: what you're charged for. For a custom runtime this
  includes Init Duration on a cold invoke.

`jolt invoke` prints this line directly. `script/bench.clj`'s
`parse-report-line` turns it into a Clojure map, and a missing `Init Duration`
is how a warm sample is told apart from a cold one.

## What `jolt bench` does

For each memory tier in `BENCH_MEMORY_TIERS` (default `2048,3008`):

1. `aws lambda update-function-configuration --memory-size <tier>`: any
   configuration update invalidates the function's existing execution
   environments, so the very next invoke runs in a fresh one. No separate
   "force cold start" trick is needed.
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
`JOLT_MAX_HEAP is smaller than the runtime's own live heap`, which
`jolt bench` can't turn into a timing. If you lower `BENCH_MEMORY_TIERS`
below ~2048 MB yourself, expect that error instead of a number.

Run it:

```sh
jolt deploy
jolt bench
jolt teardown   # when you're done -- nothing should keep running in your account
```

Two things to know before you run it. `jolt bench` leaves the function
configured at its last tier's memory size (3008 MB by default): a later
`jolt deploy` resets it back to 2048 MB, but if you only ever run `jolt bench`
you're left on the higher-cost tier. And `jolt deploy`/`jolt bench`/`jolt teardown`
all act on whatever function/role name is configured
(`LAMBDA_MVP_FUNCTION_NAME`, default `lambda-mvp-jlt`), so don't point it at
an existing unrelated resource: `jolt deploy` will overwrite its code
and `jolt teardown` will delete it.

If a "cold" sample shows no Init Duration, `jolt bench` prints a warning
instead of reporting incomplete data. The execution environment wasn't
fresh, so investigate before trusting the number.

## This repo's own baseline

Measured against this repo's own binary (not the source project's), jolt
v0.8.7, arm64, `provided.al2023`, 2026-09-12, two regions back to back:

| Metric | us-west-2, 2048 MB | us-west-2, 3008 MB | ap-southeast-2, 2048 MB | ap-southeast-2, 3008 MB |
|---|---|---|---|---|
| Cold Init Duration | 325.3 ms | 306.5 ms | 288.3 ms | 401.9 ms |
| Cold Duration | 2.4 ms | 2.2 ms | 2.1 ms | 3.0 ms |
| Warm Duration (min/median/max) | 1.8 / 1.9 / 2.0 ms | 2.0 / 2.2 / 2.3 ms | 1.7 / 1.8 / 2.0 ms | 2.1 / 2.2 / 2.6 ms |
| Max Memory Used | 164 MB | 164 MB | 164 MB | 164 MB |

These are **illustrative, not a live guarantee**: a single run each. The
spread between tiers and regions above (down to ~2 ms warm, Cold Init
anywhere from ~290 to ~400 ms) is the takeaway, not a precise number to
target.

That table records what v0.8.7 did, measured while it was this repo's
default. The default is now v0.8.27 built on jolt's patched Chez, so for
current-default numbers read
[v0.8.9 vs v0.8.27](#v089-vs-v0827-and-the-patched-chez) below.

### v0.8.6 vs v0.8.7, confirmed

The 164 MB above is well below the ~250 MB baseline the section below
describes for jolt v0.8.6 and earlier, and v0.8.7's own changelog credits
binary-size and resident-memory cuts (a hello-world example there went from
225 MB to 95 MB resident). This repo's own binary confirms it: same function, same region (ap-southeast-2), same memory
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
dropped by 30-46% at both tiers, as the changelog claims and by a margin too
large to be tier-to-tier noise. Warm Duration moved the other way slightly,
but it stays in low single-digit milliseconds for either version, so the
difference means little at this sample size (one warm-sample run per tier
per version).

### v0.8.7 vs v0.8.9, three runs each

jolt v0.8.9 landed on 2026-09-18. Its changelog is mostly about `jolt.loader`,
a real `java.util.zip` on the zlib every binary already links, and lock-free
atoms that let parallel Clojure scale again, so nothing in it promises a faster
Lambda cold start. Measured anyway, against this repo's own binary,
same function, same region (`ap-southeast-2`), redeployed with
`JOLT_VERSION=0.8.7` then `JOLT_VERSION=0.8.9` back to back on 2026-09-19.

Cold Init Duration is the noisiest number `jolt bench` produces, and one sample
per tier can't separate a version change from whatever hardware AWS handed
out that minute. So this table reports the median of **three** `jolt bench` runs
per version per tier, with the Cold Init range beside it:

| Metric | 0.8.7, 2048 MB | 0.8.9, 2048 MB | 0.8.7, 3008 MB | 0.8.9, 3008 MB |
|---|---|---|---|---|
| Cold Init Duration (median of 3) | 407.4 ms | 361.6 ms | 311.0 ms | 346.1 ms |
| Cold Init Duration (range of 3) | 326-446 ms | 358-438 ms | 310-311 ms | 334-437 ms |
| Cold Duration (median of 3) | 2.3 ms | 1.8 ms | 2.1 ms | 1.9 ms |
| Warm Duration (median of the 3 medians) | 2.1 ms | 1.7 ms | 1.8 ms | 1.7 ms |
| Max Memory Used | 164 MB | 182 MB | 164 MB | 182 MB |

Three results, and only one is a speed-up.

**The handler itself got faster, at both tiers.** Cold Duration dropped from
2.3 to 1.8 ms at 2048 MB and from 2.1 to 1.9 ms at 3008 MB. Across all six
cold samples per version the two distributions barely touch: 0.8.9's slowest
was 2.1 ms and 0.8.7's fastest was 2.0 ms. Warm Duration moved the same way,
2.1 down to 1.7 ms and 1.8 down to 1.7 ms. The absolute numbers are tiny, so
read this as a consistent direction, not a headline.

**Cold Init did not measurably improve.** The 2048 MB median looks better and
the 3008 MB median looks worse, while the two versions' ranges overlap heavily
at both tiers. On this binary and this account, moving v0.8.7 to v0.8.9 buys
nothing at boot that three runs per tier can separate from noise.

**Memory went up.** Max Memory Used moved from 164 MB to 182 MB, roughly 11%
more, and it held steady across every run either way. `dist/bootstrap` grew
with it, from 15,099,280 bytes under 0.8.7 to 16,895,672 under 0.8.9, roughly
12%. That matters because the v0.8.5 heap ceiling ties the usable memory tiers to
the runtime's own live heap, so a bigger heap eats into the headroom the
2048 MB floor exists to provide.

As a check, rebuilding v0.8.7 produced a `dist/bootstrap` of 15,099,280
bytes, the same 15.1 MB the v0.8.6-vs-v0.8.7 section above recorded a week
earlier, and its bench numbers landed within a few percent of that run. The
build is reproducible, so the 0.8.9 deltas above belong to the version, not
to the day.

### v0.8.9 vs v0.8.27, and the patched Chez

jolt v0.8.27 landed on 2026-11-12, eighteen releases after v0.8.9. Two things
changed for this repo's build besides the version. From v0.8.10 on, jolt's CI
and releases build Chez with seven of jolt's own kernel patches
(`host/chez/chez-patches/`: lock-free thread activation, loading the boot
image in place, sharing vfasl strings, and four smaller fixes), applied by
jolt's `ci/apply-chez-patches.sh`. Stock Chez still works, so the Dockerfile
now has `CHEZ_PATCHES` (default `1`) and both were measured.

Three builds, the same function, `ap-southeast-2`, arm64, 2026-11-14, three
`jolt bench` runs each. Stock 0.8.27 ran twice, once before and once after the
patched build, so its column is the median of six runs. The second round
checked whether deploy order mattered, and it didn't by more than the noise.

| Metric | 0.8.9, 2048 MB | 0.8.27 stock, 2048 MB | 0.8.27 patched, 2048 MB | 0.8.9, 3008 MB | 0.8.27 stock, 3008 MB | 0.8.27 patched, 3008 MB |
|---|---|---|---|---|---|---|
| Cold Init Duration (median) | 319.4 ms | 392.6 ms | 477.1 ms | 425.7 ms | 484.9 ms | 349.3 ms |
| Cold Init Duration (range) | 311-496 ms | 385-518 ms | 374-480 ms | 416-432 ms | 359-504 ms | 348-460 ms |
| Cold Duration (median) | 1.6 ms | 2.0 ms | 2.2 ms | 2.1 ms | 2.1 ms | 1.9 ms |
| Warm Duration (median of medians) | 1.7 ms | 1.8 ms | 2.0 ms | 1.9 ms | 1.9 ms | 1.7 ms |
| Max Memory Used | 178 MB | 150-157 MB | 128-129 MB | 178 MB | 151-156 MB | 128-129 MB |

| Artifact | 0.8.9 | 0.8.27 stock | 0.8.27 patched |
|---|---|---|---|
| `dist/bootstrap` | 16,895,728 bytes | 20,578,056 bytes | 20,054,232 bytes |
| `dist/lambda.zip` | 12,741,724 bytes | 15,626,168 bytes | 15,250,138 bytes |

**Memory is the main result.** Max Memory Used fell from 178 MB to about
152 MB on stock Chez, and to 128 MB on the patched one, 28% below v0.8.9. It
held within a few megabytes across every run. The patches account for about
24 MB of that by themselves, the strongest reason to build with them.
This reverses the direction v0.8.9 moved in (164 to 182 MB) and widens the
headroom the 2048 MB floor exists to protect.

**Cold Init didn't measurably change.** Medians moved in both directions, and
every column's range overlaps every other column's at the same tier, with
samples anywhere from about 310 to 520 ms. The best single median today was
v0.8.9 at 2048 MB and the patched v0.8.27 at 3008 MB. Three runs per tier can't
tell the three builds apart at boot, the same conclusion the v0.8.7 vs
v0.8.9 round reached. The binary grew by 3.2 MB (19%) patched and 3.7 MB
(22%) stock between the versions, which would push Init up if anything.

**Handler time is flat.** Cold and warm durations all sit between 1.6 and
2.2 ms, a spread inside run-to-run noise.

v0.8.9 measured 178 MB today against 182 MB in September, so that number is
stable within a few megabytes across two months. Cold Init for v0.8.9 held
less well: today's medians were 319 and 426 ms against September's 362 and
346 ms, one lower and one higher, so that number moves a lot from day to day.

## What the source research project found

Measured in the private project this repo was extracted from (`us-east-1`,
`provided.al2023`, arm64, 2026-07-18, **illustrative, not a live
guarantee**; your numbers will differ by account, region, and the hardware
AWS allocates you). They were measured against that project's then-current
jolt v0.7.14 pin, before the v0.8.5 heap ceiling existed, so the 256 and
512 MB tiers below can't be reproduced against this repo's jolt v0.8.27
default, for the reason the heap-ceiling note above describes:

| Metric | 256 MB | 512 MB |
|---|---|---|
| Cold `Init Duration` | 920 ms | 1339 ms |
| Cold handler `Duration` | 16.1 ms | 5.1 ms |
| Warm `Duration` | 19.9 → 3.8 → 4.6 ms | 5.0 ms |
| `Max Memory Used` | 234 MB | 237 MB |

Takeaways that held in that project: warm invocations stay in the low
single-digit milliseconds once a sandbox has served an event; cold init is
dominated by process/runtime startup, not the handler; max memory is roughly
constant (the Chez heap) regardless of the configured limit, as long as the
limit is above that floor.

## Reproducing the jolt-version finding

The same project later bumped jolt `v0.7.14 → v0.8.6` and found a **mixed**
result: warm invokes got materially faster (jolt's own vfasl boot-image work
and general runtime tightening), but cold `Init Duration` got slower for
that project's specific binary, because the binary itself grew substantially
between the two pins (unrelated dependency growth, not jolt's own boot
format). That finding is specific to what was linked into that binary, not
a general claim about the jolt version bump, so it's worth reproducing
against this repo's much smaller, dependency-free binary instead of taking
the number on faith:

```sh
JOLT_VERSION=0.7.14 jolt image && jolt deploy && jolt bench   # note the table
JOLT_VERSION=0.8.27 jolt image && jolt deploy && jolt bench   # compare
CHEZ_PATCHES=0      jolt image && jolt deploy && jolt bench   # 0.8.27 on stock Chez
jolt teardown
```

Diff the two `jolt bench` tables. If you try other jolt releases, the same
recipe applies: `JOLT_VERSION` is a plain Dockerfile build arg.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A study of the **0-1 knapsack problem** (see `README.md`): `n` items, each with
a weight and a value, one bag with a fixed capacity, and the question of which
subset is worth the most. The repository packs the same bag ten ways — three
greedy rules, simulated annealing, a genetic algorithm under three selection
schemes, and three exact solvers — and makes them check each other.

The whole program is `Rucksack.java`, one file in the default package. `Item`
and `Packing` are the data; `greedy()` is the fast wrong answer; `annealing()`
and `genetic()` are the heuristics; `dynamicProgramming()` (indexed by weight),
`dynamicProgrammingByValue()` and `branchAndBound()` are three independent
exact solvers; `main()` wires up a hand-rolled CLI. There is no package
structure, no test suite and no build step — `java Rucksack.java` compiles and
runs it in one go ([JEP 330](https://openjdk.org/jeps/330), single-file source
launch).

`tools/MakeBanner.java` and `tools/banner.sh` compile against `Rucksack.java`
and draw `docs/banner-{dark,light}.svg` from real solver output. They are
**local-only and gitignored** — the generated banners are committed, the
generator is not — so they may be missing from a fresh clone. Do not add them
back to git.

## Running

```
java Rucksack.java                        # 100 items, 750 kg, every method
java Rucksack.java -n 30                  # small enough that everything is instant
java Rucksack.java --correlated           # the hard instance class
java Rucksack.java -m genetic -v          # just the GA, with a per-generation trace
java Rucksack.java -m anneal --steps 5000000
java Rucksack.java --seed 7 --quiet       # a different catalogue, no item dump
tools/banner.sh                           # regenerate both banners (local-only)
```

**Java 17 or newer.** macOS's `/usr/bin/java` is a stub; the working JDK on this
machine is Homebrew's `openjdk@21`, and it is keg-only, so it is not on the
default `PATH`:

```
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
```

Nothing outside `java.base` is imported. There is no `timeout` on this machine
(no GNU coreutils) — background a run and kill it by pid if you need one.

## Conventions worth preserving

- **Weights are integers, in hectograms (0.1 kg), everywhere inside the
  program.** `Item.weight` is an `int`; kilograms exist only in formatting and
  at the CLI boundary. This is not fussiness — the original stored weights as
  `double` and then used `(int) weight` as a memo key, which is what made its
  dynamic-programming solver return overweight bags (27 times in 200 random
  18-item instances; see the History section of `README.md`). Do not
  reintroduce a floating-point capacity comparison.
- The packings go to **stdout**; the summary, the timings and the `-v` trace go
  to **stderr**, so the results stay redirectable.
- Exit codes carry the verdict: `0` every packing fits and no heuristic beat
  the proven optimum, `1` a cross-check failed or every requested method was
  skipped, `2` bad arguments.
- **Every run is reproducible, and each method independently so.** `stream(seed,
  name)` gives the catalogue and each stochastic solver its own derived
  generator, so adding `--steps` to a command line cannot change what the
  genetic algorithm found. Never call `new Random()` without a seed, and never
  construct one inside a loop — the original did both, in five places.
- The three exact solvers are independent implementations of the same answer
  and must agree. `main()` asserts it on every run; that is the cheapest
  regression test there is.
- `branchAndBound` is exact but has a node budget (`MAX_SEARCH_NODES`). When it
  runs out it must report `branch and bound, unproven` and must **not** be
  counted as exact — a strongly correlated instance at `-n 200` really does
  exhaust it. Never let a solver claim a proof it did not finish.
- Both dynamic programs refuse to allocate more than `MAX_TABLE_CELLS` and
  return `null` instead; callers report the skip rather than crashing.
- The genetic algorithm is the point of the original program, so all three
  selection schemes stay — linear, roulette and tournament — even though they
  all lose to a sort. They are there to be *compared* against a known optimum,
  which is the one thing the original could never do. Simulated annealing is
  the control that shows *why* they plateau; keep the two next to each other.
- Anything a solver reports must be a legal bag. `Packing.fits()` is the
  gatekeeper; fitness returns 0 for an overweight individual rather than
  clamping or repairing it.
- The mathematics, the alternative approaches and the literature live in
  `README.md` — if an algorithm changes, that is what has to stay true. Verify
  every number in it by running it before committing.
- The banner must stay derived from a real solver run. If the artwork stops
  matching what `Rucksack.java` prints, that is a bug, not a style choice.
  `SEED` and `ITEMS` are fixed on purpose: re-rendering must produce
  byte-identical SVGs.

## Checking correctness

The three exact solvers must agree, and no heuristic may ever exceed them. A
non-zero exit means a packing was overweight, the exact methods disagreed, or a
heuristic out-scored the optimum:

```sh
export PATH="/opt/homebrew/opt/openjdk@21/bin:$PATH"
javac -d /tmp/kb Rucksack.java
for seed in $(seq 1 25); do for n in 3 12 31 60; do for extra in "" "--correlated"; do
  java -cp /tmp/kb Rucksack -n $n -s $seed -q $extra --generations 40 --steps 15000 \
    >/dev/null 2>&1 || echo "FAIL n=$n seed=$seed $extra"
done; done; done
```

Known answers, all reproducible from the default seed `2026`:

| Run | Optimum |
| --- | --- |
| `java Rucksack.java` (100 items, 750.0 kg) | `7,935` in 59 items at 749.5 kg |
| `java Rucksack.java -n 30` (225.0 kg) | `2,619` |
| `java Rucksack.java -n 40 --correlated` (300.0 kg) | `3,810` |

Branch-and-bound node counts, which are the sharpest signal that something has
changed in the bound or the ordering:

| Instance | Nodes | Proved |
| --- | --- | --- |
| `-n 200` | `2,077` | yes |
| `-n 2000` | `3,783` | yes |
| `-n 100 --correlated` | `189,401,756` | yes |
| `-n 200 --correlated` | budget exhausted | no |
| `-n 400 --correlated` | `19,371` | yes |

## Branches

Work happens on `feature/refactor_2026`; `master` is the default/PR target.
The remote is `git@github.com:oskar-j/knapsack-problem.git` — SSH, because
`gh` on this machine is authenticated for SSH only and HTTPS pushes fail.

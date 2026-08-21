import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The 0-1 knapsack problem, packed seven ways, with the answers checking each other.
 *
 * <p>Given {@code n} items that each have a weight and a value, and one bag that can
 * carry a fixed total weight, which subset of the items is worth the most? Every item
 * is taken whole or left behind — there is no cutting things in half, which is exactly
 * what makes the problem hard.
 *
 * <p>This program generates a random catalogue of items and then packs the same bag
 * with every method in the file:
 *
 * <ul>
 *   <li>{@link #greedy} — sort by one criterion, take what fits. Instant, and wrong.</li>
 *   <li>{@link #annealing} — one bag, wandering downhill and occasionally up, cooling
 *       as it goes.</li>
 *   <li>{@link #genetic} — a population of bags that mutate, cross over and compete,
 *       under three different selection schemes.</li>
 *   <li>{@link #dynamicProgramming} — the textbook table indexed by weight. Exact.</li>
 *   <li>{@link #dynamicProgrammingByValue} — the same idea stood on its head and
 *       indexed by value instead. Exact, and cheap when values are small even if the
 *       capacity is enormous.</li>
 *   <li>{@link #branchAndBound} — depth-first search under a fractional bound. Exact
 *       again, by a route with no table at all, so all three keep each other honest.</li>
 * </ul>
 *
 * <p>The exact solvers exist so the heuristics can be graded: the genetic algorithm
 * has no way of knowing how good its answer is, and the whole point of running it next
 * to a proven optimum is to find out.
 *
 * <p>Run it straight from source, no build step required (JEP 330):
 *
 * <pre>
 *   java Rucksack.java              # 100 items, 750 kg, every method
 *   java Rucksack.java -n 30 -v     # smaller catalogue, with a training trace
 *   java Rucksack.java --help
 * </pre>
 *
 * <p>Packings go to stdout, the summary and timings to stderr. The exit code is 0 when
 * every bag fits and no heuristic out-scored the optimum, 1 when a cross-check failed,
 * and 2 on a bad command line.
 *
 * <p>Requires Java 17 or newer. Nothing outside {@code java.base} is used.
 */
public final class Rucksack {

    /** Weights live in hectograms — 0.1 kg — so that every comparison is exact. */
    static final int HECTOGRAMS_PER_KG = 10;

    /** Item weights are drawn uniformly from 0.5 kg to 30.0 kg. */
    static final int MIN_WEIGHT = 5;
    static final int MAX_WEIGHT = 300;

    /** Item values are drawn uniformly from 10 to 199. */
    static final int MIN_VALUE = 10;
    static final int MAX_VALUE = 199;

    static final int DEFAULT_ITEMS = 100;
    static final long DEFAULT_SEED = 2026L;

    /**
     * The default bag holds a quarter of what the catalogue could weigh at its heaviest,
     * which comes out at a little under half the catalogue's actual weight. That is the
     * awkward size: far too small to take everything, far too large for the answer to be
     * obvious.
     */
    static final int CAPACITY_DIVISOR = 4;

    /** Refuse to allocate a dynamic-programming table larger than this many cells. */
    static final long MAX_TABLE_CELLS = 64_000_000L;

    /**
     * How many nodes {@link #branchAndBound} may open before it gives up on proving
     * anything. Uncorrelated instances never come close — a thousand items is settled in
     * a few thousand nodes — but a strongly correlated one can want more nodes than there
     * is time in the day, and a solver that hangs is worse than one that says so.
     */
    static final long MAX_SEARCH_NODES = 500_000_000L;

    private Rucksack() {
        // Static entry point only; nothing here is worth instantiating.
    }

    // ------------------------------------------------------------------ the data

    /**
     * One thing that could go in the bag.
     *
     * @param index  position in the catalogue, so a sorted copy can still be traced back
     * @param weight in hectograms (0.1 kg)
     * @param value  in whatever the thief is counting
     */
    record Item(int index, int weight, int value) {

        /** Value per kilogram — the ratio greedy packing lives or dies by. */
        double density() {
            return (double) value * HECTOGRAMS_PER_KG / weight;
        }
    }

    /**
     * A filled bag: which items were taken, and what that came to.
     *
     * <p>Immutable, and the totals are computed once at construction, so
     * {@link #fitness} is free. The original version of this program recomputed the
     * weight of the bag inside the inner loop of every algorithm, which is how an
     * {@code O(n)} idea turned into an {@code O(n^3)} one.
     */
    static final class Packing {

        final Item[] catalogue;
        final boolean[] taken;
        final int weight;
        final int value;
        final int count;

        /** An empty bag over the given catalogue. */
        Packing(Item[] catalogue) {
            this(catalogue, new boolean[catalogue.length]);
        }

        /** A bag holding exactly the items flagged in {@code taken}. Takes ownership. */
        Packing(Item[] catalogue, boolean[] taken) {
            this.catalogue = catalogue;
            this.taken = taken;
            int w = 0;
            int v = 0;
            int c = 0;
            for (int i = 0; i < taken.length; i++) {
                if (taken[i]) {
                    w += catalogue[i].weight();
                    v += catalogue[i].value();
                    c++;
                }
            }
            this.weight = w;
            this.value = v;
            this.count = c;
        }

        boolean fits(int capacity) {
            return weight <= capacity;
        }

        /**
         * What the genetic algorithm optimises: an overweight bag is worth nothing.
         *
         * <p>Scoring it zero rather than repairing it is deliberate. It lets crossover
         * produce illegal children — which it will, constantly — without any of them
         * surviving selection, and it keeps the search space a plain bit string.
         */
        int fitness(int capacity) {
            return fits(capacity) ? value : 0;
        }

        /** A copy with one item taken out or put back — a single point mutation. */
        Packing flip(int index) {
            boolean[] copy = taken.clone();
            copy[index] = !copy[index];
            return new Packing(catalogue, copy);
        }

        /** A child taking items {@code [0, cut)} from this bag and the rest from {@code other}. */
        Packing crossover(Packing other, int cut) {
            boolean[] child = new boolean[taken.length];
            System.arraycopy(taken, 0, child, 0, cut);
            System.arraycopy(other.taken, cut, child, cut, taken.length - cut);
            return new Packing(catalogue, child);
        }

        /** The bag as a bit string, one character per catalogue entry. */
        String bits() {
            StringBuilder out = new StringBuilder(taken.length);
            for (boolean t : taken) {
                out.append(t ? '1' : '0');
            }
            return out.toString();
        }

        @Override
        public String toString() {
            return String.format(
                    Locale.ROOT, "%d items, %s, value %,d", count, kilograms(weight), value);
        }
    }

    /** One algorithm's answer, with the wall-clock time it took to find it. */
    record Result(String label, Packing packing, long nanos, boolean exact) {}

    // ----------------------------------------------------------------- generation

    /**
     * A private random stream for one named consumer, derived from the run's seed.
     *
     * <p>Everything stochastic draws from its own stream rather than from one shared
     * generator, so a method's answer depends only on {@code --seed} and never on which
     * other methods happened to run first. Without this, adding {@code --steps} to a
     * command line would silently change what the genetic algorithm found.
     */
    static Random stream(long seed, String name) {
        return new Random(seed * 1_000_003L + name.hashCode());
    }

    /** What kind of catalogue to invent. The two behave nothing alike. */
    enum Instance {

        /**
         * Weight and value drawn independently. The easy case, and the one the original
         * program used: value per kilogram varies wildly, so the greedy order is very
         * informative and the search prunes almost everything.
         */
        UNCORRELATED("weights and values drawn independently"),

        /**
         * The textbook hard case: {@code value = weight + MAX_WEIGHT/10}, so every item is
         * worth almost exactly what it weighs. Value per kilogram is then nearly constant,
         * the fractional bound stops telling the search anything, and the difference
         * between good packings comes down to arithmetic on the last few grams.
         */
        STRONGLY_CORRELATED("value = weight + 3.0 kg, so nothing is a bargain");

        final String description;

        Instance(String description) {
            this.description = description;
        }
    }

    /**
     * Draw a random catalogue.
     *
     * @param count how many items to invent
     * @param kind  independent draws, or the correlated instance that makes it hard
     * @param rng   the seeded source of randomness for the catalogue
     */
    static Item[] catalogue(int count, Instance kind, Random rng) {
        Item[] items = new Item[count];
        for (int i = 0; i < count; i++) {
            int weight = MIN_WEIGHT + rng.nextInt(MAX_WEIGHT - MIN_WEIGHT + 1);
            int value = switch (kind) {
                case UNCORRELATED -> MIN_VALUE + rng.nextInt(MAX_VALUE - MIN_VALUE + 1);
                case STRONGLY_CORRELATED -> weight + MAX_WEIGHT / 10;
            };
            items[i] = new Item(i, weight, value);
        }
        return items;
    }

    // --------------------------------------------------------------- greedy rules

    /** The three ways of being greedy, in order of how well they work. */
    enum Ordering {
        WEIGHT("lightest first", Comparator.comparingInt(Item::weight)),
        VALUE("most valuable first", Comparator.comparingInt(Item::value).reversed()),
        DENSITY("best value per kilo", Comparator.comparingDouble(Item::density).reversed());

        final String description;
        final Comparator<Item> comparator;

        Ordering(String description, Comparator<Item> comparator) {
            // Ties broken by catalogue position, so the answer never depends on sort stability.
            this.description = description;
            this.comparator = comparator.thenComparingInt(Item::index);
        }
    }

    /**
     * Sort the catalogue once, then walk it taking anything that still fits.
     *
     * <p>{@code O(n log n)}, and never optimal in general — {@link Ordering#DENSITY}
     * comes close because it is the integer shadow of the fractional relaxation, but
     * "close" is doing real work in that sentence. See the README for how close.
     */
    static Packing greedy(Item[] items, int capacity, Ordering ordering) {
        Item[] sorted = items.clone();
        Arrays.sort(sorted, ordering.comparator);

        boolean[] taken = new boolean[items.length];
        int weight = 0;
        for (Item item : sorted) {
            if (weight + item.weight() <= capacity) {
                taken[item.index()] = true;
                weight += item.weight();
            }
        }
        return new Packing(items, taken);
    }

    // ----------------------------------------------------- exact solver #1: the table

    /**
     * The textbook dynamic program: {@code best[w]} is the most that fits in {@code w}.
     *
     * <p>Items are folded in one at a time, sweeping the capacity axis downwards so that
     * each item is offered to the bag exactly once. That is the whole 0-1 constraint,
     * expressed as a loop direction. Runs in {@code O(n * capacity)} time — pseudo-
     * polynomial, because the capacity is a number in the input, not a count of things.
     *
     * <p>Reconstruction needs to know which cells an item actually improved, which is
     * the {@code chosen} table and the reason this method is the memory-hungry one.
     *
     * @return the optimal packing, or {@code null} if the table would be too large
     */
    static Packing dynamicProgramming(Item[] items, int capacity) {
        long cells = (long) items.length * (capacity + 1);
        if (cells > MAX_TABLE_CELLS) {
            return null;
        }

        int[] best = new int[capacity + 1];
        boolean[][] chosen = new boolean[items.length][capacity + 1];

        for (int i = 0; i < items.length; i++) {
            int weight = items[i].weight();
            int value = items[i].value();
            for (int w = capacity; w >= weight; w--) {
                int candidate = best[w - weight] + value;
                if (candidate > best[w]) {
                    best[w] = candidate;
                    chosen[i][w] = true;
                }
            }
        }

        // Walk the decisions back out. best[] is non-decreasing in w, so the full
        // capacity is always as good a starting point as any.
        boolean[] taken = new boolean[items.length];
        int w = capacity;
        for (int i = items.length - 1; i >= 0; i--) {
            if (chosen[i][w]) {
                taken[i] = true;
                w -= items[i].weight();
            }
        }
        return new Packing(items, taken);
    }

    // --------------------------------------------- exact solver #2: the same table, rotated

    /**
     * The dynamic program with its axes swapped: {@code lightest[v]} is the least weight
     * that buys a value of exactly {@code v}.
     *
     * <p>Same recurrence, same sweep, same 0-1 constraint expressed as a loop direction —
     * but the table is now as long as the total value of the catalogue rather than as long
     * as the bag. Which of the two is cheaper depends entirely on the numbers: a bag
     * measured in grams and items priced in whole units want this one, a small bag full of
     * expensive items wants {@link #dynamicProgramming}. Neither is polynomial, and the
     * fact that both exist is why "pseudo-polynomial" is the careful word for either.
     *
     * <p>The answer is the largest value whose cheapest packing still fits.
     *
     * @return the optimal packing, or {@code null} if the table would be too large
     */
    static Packing dynamicProgrammingByValue(Item[] items, int capacity) {
        int totalValue = 0;
        for (Item item : items) {
            totalValue += item.value();
        }
        long cells = (long) items.length * (totalValue + 1);
        if (cells > MAX_TABLE_CELLS) {
            return null;
        }

        final int unreachable = Integer.MAX_VALUE;
        int[] lightest = new int[totalValue + 1];
        Arrays.fill(lightest, unreachable);
        lightest[0] = 0;
        boolean[][] chosen = new boolean[items.length][totalValue + 1];

        for (int i = 0; i < items.length; i++) {
            int weight = items[i].weight();
            int value = items[i].value();
            for (int v = totalValue; v >= value; v--) {
                if (lightest[v - value] == unreachable) {
                    continue;
                }
                int candidate = lightest[v - value] + weight;
                if (candidate < lightest[v]) {
                    lightest[v] = candidate;
                    chosen[i][v] = true;
                }
            }
        }

        int bestValue = 0;
        for (int v = totalValue; v >= 0; v--) {
            if (lightest[v] <= capacity) {
                bestValue = v;
                break;
            }
        }

        boolean[] taken = new boolean[items.length];
        int v = bestValue;
        for (int i = items.length - 1; i >= 0; i--) {
            if (chosen[i][v]) {
                taken[i] = true;
                v -= items[i].value();
            }
        }
        return new Packing(items, taken);
    }

    // ------------------------------------------------ exact solver #3: the search

    /**
     * What a bounded search came back with.
     *
     * @param packing the best bag it found
     * @param proved  whether it exhausted the tree — if false, {@code packing} is a good
     *                answer with no claim to being the best one
     * @param nodes   how many nodes it opened
     */
    record Proof(Packing packing, boolean proved, long nodes) {}

    /**
     * Depth-first search over "take it or leave it", pruned by the fractional optimum.
     *
     * <p>Sorting by value density first means the greedy prefix is already excellent, so
     * a good incumbent appears immediately and most of the tree never gets built. The
     * bound at each node is the answer to the *fractional* knapsack — fill greedily and
     * allow the last item to be sliced — which is the cheapest upper bound there is and
     * still tight enough to make a hundred items instant.
     *
     * <p>Exact, like both dynamic programs, but with no dependence on the capacity, no
     * dependence on the values and no table at all. Three independent methods arriving
     * at the same number is this repository's regression test.
     */
    static Proof branchAndBound(Item[] items, int capacity, long nodeLimit) {
        Item[] byDensity = items.clone();
        Arrays.sort(byDensity, Ordering.DENSITY.comparator);

        var search = new BranchAndBound(byDensity, capacity, nodeLimit);
        search.explore(0, 0, 0);

        boolean[] taken = new boolean[items.length];
        for (int i = 0; i < byDensity.length; i++) {
            if (search.best[i]) {
                taken[byDensity[i].index()] = true;
            }
        }
        return new Proof(new Packing(items, taken), !search.gaveUp, search.nodes);
    }

    /** The search with no budget at all, for callers that know it terminates. */
    static Packing branchAndBound(Item[] items, int capacity) {
        return branchAndBound(items, capacity, Long.MAX_VALUE).packing();
    }

    /** Mutable state for one {@link #branchAndBound} run, kept out of the signature. */
    private static final class BranchAndBound {

        final Item[] items;
        final int capacity;
        final boolean[] current;
        final boolean[] best;
        final long nodeLimit;
        int bestValue;
        long nodes;
        boolean gaveUp;

        BranchAndBound(Item[] items, int capacity, long nodeLimit) {
            this.items = items;
            this.capacity = capacity;
            this.nodeLimit = nodeLimit;
            this.current = new boolean[items.length];
            this.best = new boolean[items.length];
        }

        /**
         * @param depth  how many items have been decided
         * @param weight weight committed so far
         * @param value  value committed so far
         */
        void explore(int depth, int weight, int value) {
            if (gaveUp) {
                return;
            }
            if (++nodes > nodeLimit) {
                gaveUp = true;
                return;
            }
            if (value > bestValue) {
                bestValue = value;
                System.arraycopy(current, 0, best, 0, current.length);
            }
            if (depth == items.length || bound(depth, weight, value) <= bestValue) {
                return;
            }

            // Take it first: the density order means this branch usually holds the answer,
            // and a strong incumbent is what makes the bound bite on the other one.
            if (weight + items[depth].weight() <= capacity) {
                current[depth] = true;
                explore(depth + 1, weight + items[depth].weight(), value + items[depth].value());
                current[depth] = false;
            }
            explore(depth + 1, weight, value);
        }

        /** Fractional-knapsack upper bound on anything reachable below this node. */
        double bound(int depth, int weight, int value) {
            double limit = value;
            int room = capacity - weight;
            for (int i = depth; i < items.length; i++) {
                int itemWeight = items[i].weight();
                if (itemWeight <= room) {
                    room -= itemWeight;
                    limit += items[i].value();
                } else {
                    // The one item that gets sliced, and then there is no room for more.
                    limit += (double) items[i].value() * room / itemWeight;
                    break;
                }
            }
            return limit;
        }
    }

    // ------------------------------------------------------- the genetic algorithm

    /** How the survivors of each generation are picked. */
    enum Selection {
        LINEAR("linear selection", "the fittest few, taken straight off the top"),
        ROULETTE("roulette selection", "sampled with probability proportional to fitness"),
        TOURNAMENT("tournament selection", "the pool split into groups, each group's best wins");

        final String label;
        final String description;

        Selection(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    /**
     * Knobs for {@link #genetic}.
     *
     * @param pool        individuals per generation
     * @param elite       how many survive to breed
     * @param generations how many rounds to run
     * @param mutationRate share of offspring made by mutation rather than crossover
     */
    record Evolution(int pool, int elite, int generations, double mutationRate) {

        static Evolution defaults(int items) {
            return new Evolution(100, 20, items * 20, 0.7);
        }
    }

    /**
     * Evolve a population of bags and return the best one ever seen.
     *
     * <p>Each generation: pick {@code elite} survivors by {@code selection}, then refill
     * the pool with their mutated and recombined offspring. Survivors are chosen from a
     * snapshot of the previous generation, never from the array being rewritten — reading
     * and writing the same pool in one pass is subtle enough to have been the original
     * program's most damaging bug.
     *
     * <p>The best individual found is remembered outside the pool, so a lucky bag can
     * never be lost to an unlucky round of selection.
     *
     * @param trace where to report progress, or {@code null} to stay quiet
     */
    static Packing genetic(
            Item[] items,
            int capacity,
            Selection selection,
            Evolution settings,
            Random rng,
            PrintStream trace) {

        Packing[] pool = new Packing[settings.pool()];
        Packing empty = new Packing(items);
        Arrays.fill(pool, empty);
        // Start from empty bags rather than from a greedy solution: the point of the
        // exercise is what evolution finds on its own.
        for (int i = 0; i < pool.length; i++) {
            pool[i] = mutate(empty, rng);
        }

        Packing champion = empty;
        int report = Math.max(1, settings.generations() / 20);

        for (int generation = 0; generation < settings.generations(); generation++) {
            for (Packing individual : pool) {
                if (individual.fitness(capacity) > champion.fitness(capacity)) {
                    champion = individual;
                }
            }

            Packing[] survivors = select(pool, capacity, selection, settings.elite(), rng);

            Packing[] next = new Packing[settings.pool()];
            System.arraycopy(survivors, 0, next, 0, survivors.length);
            for (int i = survivors.length; i < next.length; i++) {
                Packing parent = survivors[i % survivors.length];
                next[i] = rng.nextDouble() < settings.mutationRate()
                        ? mutate(parent, rng)
                        : parent.crossover(survivors[rng.nextInt(survivors.length)],
                                rng.nextInt(items.length));
            }
            pool = next;

            if (trace != null && (generation % report == 0 || generation == settings.generations() - 1)) {
                trace.printf(
                        Locale.ROOT,
                        "  gen %5d  best %,7d  champion %,7d  mean %,7.0f%n",
                        generation,
                        bestFitness(pool, capacity),
                        champion.value,
                        meanFitness(pool, capacity));
            }
        }

        for (Packing individual : pool) {
            if (individual.fitness(capacity) > champion.fitness(capacity)) {
                champion = individual;
            }
        }
        return champion;
    }

    /** One point mutation. Kept as a helper so every caller uses the same shared {@code rng}. */
    private static Packing mutate(Packing packing, Random rng) {
        return packing.flip(rng.nextInt(packing.taken.length));
    }

    /** Pick {@code elite} individuals out of {@code pool}, by the requested scheme. */
    private static Packing[] select(
            Packing[] pool, int capacity, Selection selection, int elite, Random rng) {

        Packing[] survivors = new Packing[Math.min(elite, pool.length)];
        switch (selection) {
            case LINEAR -> {
                Packing[] ranked = pool.clone();
                Arrays.sort(ranked, Comparator.comparingInt((Packing p) -> p.fitness(capacity)).reversed());
                System.arraycopy(ranked, 0, survivors, 0, survivors.length);
            }
            case ROULETTE -> {
                // A cumulative fitness axis; a dart thrown at it lands on an individual
                // with probability proportional to its share. Zero-fitness bags occupy no
                // width at all and so are never drawn.
                long[] wheel = new long[pool.length];
                long total = 0;
                for (int i = 0; i < pool.length; i++) {
                    total += pool[i].fitness(capacity);
                    wheel[i] = total;
                }
                for (int i = 0; i < survivors.length; i++) {
                    if (total == 0) {
                        // Nothing in the pool fits the bag; fall back to a uniform draw.
                        survivors[i] = pool[rng.nextInt(pool.length)];
                        continue;
                    }
                    long dart = Math.floorMod(rng.nextLong(), total);
                    int hit = Arrays.binarySearch(wheel, dart);
                    survivors[i] = pool[hit >= 0 ? hit + 1 : -hit - 1];
                }
            }
            case TOURNAMENT -> {
                // Deal the pool round-robin into as many groups as there are places, then
                // let each group send its best. Every individual competes exactly once.
                List<List<Packing>> groups = new ArrayList<>(survivors.length);
                for (int i = 0; i < survivors.length; i++) {
                    groups.add(new ArrayList<>());
                }
                for (int i = 0; i < pool.length; i++) {
                    groups.get(i % survivors.length).add(pool[i]);
                }
                for (int i = 0; i < survivors.length; i++) {
                    Packing winner = groups.get(i).get(0);
                    for (Packing challenger : groups.get(i)) {
                        if (challenger.fitness(capacity) > winner.fitness(capacity)) {
                            winner = challenger;
                        }
                    }
                    survivors[i] = winner;
                }
            }
        }
        return survivors;
    }

    private static int bestFitness(Packing[] pool, int capacity) {
        int best = 0;
        for (Packing p : pool) {
            best = Math.max(best, p.fitness(capacity));
        }
        return best;
    }

    private static double meanFitness(Packing[] pool, int capacity) {
        long total = 0;
        for (Packing p : pool) {
            total += p.fitness(capacity);
        }
        return (double) total / pool.length;
    }

    // ------------------------------------------------------- simulated annealing

    /**
     * Settings for {@link #annealing}.
     *
     * @param steps      how many single-bit moves to attempt
     * @param startTemp  temperature at the first step, in units of item value
     * @param endTemp    temperature at the last step
     */
    record Annealing(int steps, double startTemp, double endTemp) {

        static Annealing defaults(Evolution evolution, Item[] items) {
            // Ten single-item moves for every whole bag the genetic algorithm builds. The
            // genetic algorithm rebuilds all n items per candidate where annealing changes
            // one, so at this ratio the two cost about the same wall-clock — which is the
            // comparison worth making, rather than one counted in candidates.
            int hottest = MIN_VALUE;
            for (Item item : items) {
                hottest = Math.max(hottest, item.value());
            }
            return new Annealing(evolution.generations() * evolution.pool() * 10, hottest, 0.05);
        }
    }

    /**
     * One bag, wandering. Flip a random item in or out; keep the move if it improves
     * things, and keep it anyway with probability {@code exp(delta / T)} if it does not.
     * Lower {@code T} as the run goes on, so early moves roam and late moves polish.
     *
     * <p>Overweight bags are allowed *during* the walk, priced at the value of the best
     * item per kilo in the catalogue. That is the cheapest possible exchange rate, so an
     * illegal bag is never worth more than a legal one carrying the same weight — the
     * search can cut across the boundary without ever being rewarded for landing outside
     * it. Only feasible states are remembered as candidates for the answer.
     *
     * <p>This is the genetic algorithm's opposite number: no population, no crossover, one
     * tunable that actually matters. Both are here to be measured against the exact
     * solvers rather than against folklore.
     */
    static Packing annealing(
            Item[] items, int capacity, Annealing settings, Random rng, PrintStream trace) {

        double penalty = 0;
        for (Item item : items) {
            penalty = Math.max(penalty, (double) item.value() / item.weight());
        }

        boolean[] state = new boolean[items.length];
        int weight = 0;
        int value = 0;
        double score = 0;

        boolean[] best = state.clone();
        int bestValue = 0;

        double cooling = Math.pow(settings.endTemp() / settings.startTemp(),
                1.0 / Math.max(1, settings.steps() - 1));
        double temperature = settings.startTemp();
        int report = Math.max(1, settings.steps() / 20);

        for (int step = 0; step < settings.steps(); step++) {
            int i = rng.nextInt(items.length);
            int sign = state[i] ? -1 : 1;
            int nextWeight = weight + sign * items[i].weight();
            int nextValue = value + sign * items[i].value();
            double nextScore = nextValue - penalty * Math.max(0, nextWeight - capacity);

            double delta = nextScore - score;
            if (delta >= 0 || rng.nextDouble() < Math.exp(delta / temperature)) {
                state[i] = !state[i];
                weight = nextWeight;
                value = nextValue;
                score = nextScore;
                if (weight <= capacity && value > bestValue) {
                    bestValue = value;
                    System.arraycopy(state, 0, best, 0, state.length);
                }
            }

            if (trace != null && (step % report == 0 || step == settings.steps() - 1)) {
                trace.printf(Locale.ROOT,
                        "  step %8d  T %8.3f  current %,7d  best %,7d%n",
                        step, temperature, weight <= capacity ? value : 0, bestValue);
            }
            temperature *= cooling;
        }
        return new Packing(items, best);
    }

    // ------------------------------------------------------------------ formatting

    /** "1 item", "100 items" — the sort of thing that shows up in every screenshot. */
    static String plural(long count, String noun) {
        return String.format(Locale.ROOT, "%,d %s%s", count, noun, count == 1 ? "" : "s");
    }

    /** Hectograms as kilograms, which is the only place kilograms exist. */
    static String kilograms(int hectograms) {
        return String.format(Locale.ROOT, "%,.1f kg", (double) hectograms / HECTOGRAMS_PER_KG);
    }

    /** The catalogue, six items to a line, so a hundred of them stay readable. */
    static String catalogueTable(Item[] items) {
        StringBuilder out = new StringBuilder();
        int perLine = 6;
        for (int i = 0; i < items.length; i++) {
            out.append(String.format(
                    Locale.ROOT, "%4d: %6.1f kg %4d ",
                    items[i].index(),
                    (double) items[i].weight() / HECTOGRAMS_PER_KG,
                    items[i].value()));
            if (i % perLine == perLine - 1 || i == items.length - 1) {
                out.append(System.lineSeparator());
            }
        }
        return out.toString();
    }

    /** The results, one row per method, gaps measured against the proven optimum. */
    static String resultsTable(List<Result> results, int optimum) {
        int width = 4;
        for (Result r : results) {
            width = Math.max(width, r.label().length());
        }

        StringBuilder out = new StringBuilder();
        out.append(String.format(
                Locale.ROOT, "%-" + width + "s %7s %12s %10s %9s %10s%n",
                "method", "items", "weight", "value", "gap", "time"));
        out.append("-".repeat(width + 52)).append(System.lineSeparator());

        for (Result r : results) {
            String gap = "";
            if (r.exact()) {
                gap = "optimal";
            } else if (optimum > 0) {
                gap = String.format(Locale.ROOT, "%.2f%%",
                        100.0 * (r.packing().value - optimum) / optimum);
            }
            out.append(String.format(
                    Locale.ROOT, "%-" + width + "s %7d %12s %10s %9s %9.1fms%n",
                    r.label(),
                    r.packing().count,
                    kilograms(r.packing().weight),
                    String.format(Locale.ROOT, "%,d", r.packing().value),
                    gap,
                    r.nanos() / 1e6));
        }
        return out.toString();
    }

    // ----------------------------------------------------------------- the options

    /** Everything the command line can say. */
    static final class Options {
        int items = DEFAULT_ITEMS;
        Instance instance = Instance.UNCORRELATED;
        int capacity = -1;
        long seed = DEFAULT_SEED;
        String method = "all";
        String selection = "all";
        Evolution evolution;
        Integer steps;
        boolean quiet;
        boolean verbose;
    }

    static final String USAGE = """
            usage: java Rucksack.java [options]

            Pack a knapsack of randomly generated items, every which way, and grade the
            heuristics against a proven optimum.

              -n, --items N        how many items to generate (default %d)
              -c, --capacity KG    bag capacity in kilograms (default: items * 30 / %d)
              -s, --seed S         seed for the whole run (default %d)
                  --correlated     make every item worth what it weighs (the hard case)
              -m, --method M       all, greedy, anneal, genetic, dp, branch (default all)
                  --selection S    all, linear, roulette, tournament (default all)
                  --generations G  genetic algorithm rounds (default: 20 * items)
                  --pool P         individuals per generation (default 100)
                  --elite E        survivors per generation (default 20)
                  --mutation R     share of offspring made by mutation (default 0.7)
                  --steps N        annealing moves (default: 10 * generations * pool)
              -q, --quiet          do not print the item catalogue
              -v, --verbose        trace the genetic algorithm on stderr
              -h, --help           show this message

            Packings go to stdout, the summary to stderr. Exit code 0 means every bag
            fits and no heuristic beat the optimum, 1 means a cross-check failed, 2
            means the command line was wrong.
            """;

    /**
     * Parse the command line.
     *
     * @throws IllegalArgumentException with a message meant for the user
     */
    static Options parse(String[] args) {
        Options options = new Options();
        Integer generations = null;
        Integer pool = null;
        Integer elite = null;
        Integer steps = null;
        Double mutation = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> throw new HelpRequested();
                case "-q", "--quiet" -> options.quiet = true;
                case "--correlated" -> options.instance = Instance.STRONGLY_CORRELATED;
                case "-v", "--verbose" -> options.verbose = true;
                case "-n", "--items" -> options.items = positive(arg, value(args, ++i, arg));
                case "-s", "--seed" -> options.seed = Long.parseLong(value(args, ++i, arg));
                case "-m", "--method" -> options.method = value(args, ++i, arg).toLowerCase(Locale.ROOT);
                case "--selection" -> options.selection = value(args, ++i, arg).toLowerCase(Locale.ROOT);
                case "--generations" -> generations = positive(arg, value(args, ++i, arg));
                case "--pool" -> pool = positive(arg, value(args, ++i, arg));
                case "--elite" -> elite = positive(arg, value(args, ++i, arg));
                case "--steps" -> steps = positive(arg, value(args, ++i, arg));
                case "--mutation" -> mutation = Double.parseDouble(value(args, ++i, arg));
                case "-c", "--capacity" -> {
                    double kg = Double.parseDouble(value(args, ++i, arg));
                    if (kg < 0) {
                        throw new IllegalArgumentException("capacity cannot be negative");
                    }
                    options.capacity = (int) Math.round(kg * HECTOGRAMS_PER_KG);
                }
                default -> throw new IllegalArgumentException("unknown option: " + arg);
            }
        }

        // The original took a bare item count as its only argument. Keep that working.
        if (options.capacity < 0) {
            options.capacity = options.items * MAX_WEIGHT / CAPACITY_DIVISOR;
        }

        Evolution base = Evolution.defaults(options.items);
        options.evolution = new Evolution(
                pool == null ? base.pool() : pool,
                elite == null ? base.elite() : elite,
                generations == null ? base.generations() : generations,
                mutation == null ? base.mutationRate() : mutation);

        options.steps = steps;

        if (options.evolution.elite() > options.evolution.pool()) {
            throw new IllegalArgumentException("--elite cannot exceed --pool");
        }
        if (options.evolution.mutationRate() < 0 || options.evolution.mutationRate() > 1) {
            throw new IllegalArgumentException("--mutation must be between 0 and 1");
        }
        if (!List.of("all", "greedy", "anneal", "genetic", "dp", "branch")
                .contains(options.method)) {
            throw new IllegalArgumentException("unknown method: " + options.method);
        }
        if (!List.of("all", "linear", "roulette", "tournament").contains(options.selection)) {
            throw new IllegalArgumentException("unknown selection: " + options.selection);
        }
        return options;
    }

    /** Signals {@code --help}, which is a request rather than a mistake. */
    private static final class HelpRequested extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static int positive(String option, String raw) {
        int parsed = Integer.parseInt(raw);
        if (parsed <= 0) {
            throw new IllegalArgumentException(option + " must be positive, got " + parsed);
        }
        return parsed;
    }

    // ------------------------------------------------------------------------ main

    public static void main(String[] args) {
        Options options;
        try {
            options = parse(args);
        } catch (HelpRequested help) {
            System.out.print(String.format(USAGE, DEFAULT_ITEMS, CAPACITY_DIVISOR, DEFAULT_SEED));
            return;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException bad) {
            System.err.println("rucksack: " + bad.getMessage());
            System.err.println();
            System.err.print(String.format(USAGE, DEFAULT_ITEMS, CAPACITY_DIVISOR, DEFAULT_SEED));
            System.exit(2);
            return;
        }

        Item[] items = catalogue(options.items, options.instance,
                stream(options.seed, "catalogue"));
        Annealing defaults = Annealing.defaults(options.evolution, items);
        final Annealing schedule = options.steps == null
                ? defaults
                : new Annealing(options.steps, defaults.startTemp(), defaults.endTemp());
        int capacity = options.capacity;

        int totalWeight = 0;
        int totalValue = 0;
        for (Item item : items) {
            totalWeight += item.weight();
            totalValue += item.value();
        }

        System.err.printf(
                Locale.ROOT,
                "%s weighing %s and worth %,d in total; the bag holds %s (%.0f%% of it), "
                        + "seed %d, %s.%n",
                plural(items.length, "item"),
                kilograms(totalWeight),
                totalValue,
                kilograms(capacity),
                100.0 * capacity / totalWeight,
                options.seed,
                options.instance.description);

        if (!options.quiet) {
            System.out.println("Catalogue — index: weight, value");
            System.out.print(catalogueTable(items));
            System.out.println();
        }

        List<Result> results = new ArrayList<>();
        boolean wantAll = options.method.equals("all");

        if (wantAll || options.method.equals("greedy")) {
            for (Ordering ordering : Ordering.values()) {
                results.add(time("greedy, " + ordering.description,
                        false, () -> greedy(items, capacity, ordering)));
            }
        }
        if (wantAll || options.method.equals("anneal")) {
            if (options.verbose) {
                System.err.println("simulated annealing — one bag, cooling from "
                        + (int) schedule.startTemp() + " over "
                        + String.format(Locale.ROOT, "%,d", schedule.steps()) + " moves");
            }
            results.add(time("simulated annealing", false,
                    () -> annealing(items, capacity, schedule,
                            stream(options.seed, "annealing"),
                            options.verbose ? System.err : null)));
        }
        if (wantAll || options.method.equals("genetic")) {
            for (Selection selection : Selection.values()) {
                if (!options.selection.equals("all")
                        && !selection.name().equalsIgnoreCase(options.selection)) {
                    continue;
                }
                if (options.verbose) {
                    System.err.println("genetic, " + selection.label + " — "
                            + selection.description);
                }
                results.add(time("genetic, " + selection.label, false,
                        () -> genetic(items, capacity, selection, options.evolution,
                                stream(options.seed, "genetic-" + selection.name()),
                                options.verbose ? System.err : null)));
            }
        }

        int optimum = 0;
        List<Result> exact = new ArrayList<>();
        if (wantAll || options.method.equals("dp")) {
            Result byWeight = time("dynamic programming, by weight", true,
                    () -> dynamicProgramming(items, capacity));
            if (byWeight.packing() == null) {
                System.err.printf(
                        "dynamic programming by weight skipped: a %,d x %,d table is too large.%n",
                        items.length, capacity + 1);
            } else {
                results.add(byWeight);
                exact.add(byWeight);
            }

            Result byValue = time("dynamic programming, by value", true,
                    () -> dynamicProgrammingByValue(items, capacity));
            if (byValue.packing() == null) {
                System.err.println("dynamic programming by value skipped: the table is too large.");
            } else {
                results.add(byValue);
                exact.add(byValue);
            }
        }
        if (wantAll || options.method.equals("branch")) {
            long started = System.nanoTime();
            Proof proof = branchAndBound(items, capacity, MAX_SEARCH_NODES);
            Result bb = new Result(
                    proof.proved() ? "branch and bound" : "branch and bound, unproven",
                    proof.packing(), System.nanoTime() - started, proof.proved());
            results.add(bb);
            if (proof.proved()) {
                exact.add(bb);
            } else {
                System.err.printf(
                        "branch and bound opened %,d nodes without exhausting the tree; "
                                + "its %,d is a lower bound, not a proof.%n",
                        proof.nodes(), proof.packing().value);
            }
        }
        for (Result r : exact) {
            optimum = Math.max(optimum, r.packing().value);
        }

        if (results.isEmpty()) {
            System.err.println("nothing to solve: every method you asked for was skipped. "
                    + "Try -m branch, which needs no table.");
            System.exit(1);
            return;
        }

        System.out.print(resultsTable(results, optimum));

        Result best = results.stream()
                .max(Comparator.comparingInt(r -> r.packing().value))
                .orElseThrow();
        if (!options.quiet) {
            System.out.println();
            System.out.println("Best packing found — " + best.label() + ":");
            System.out.println(wrap(best.packing().bits(), 72));
        }

        int status = verdict(results, exact, capacity, optimum);
        System.out.flush();
        System.err.flush();
        System.exit(status);
    }

    /**
     * Grade the run.
     *
     * <p>Two things can only be true if something is broken: a bag that does not fit, and
     * a heuristic that scored higher than an algorithm claiming to be exact. Either one
     * is a bug in this file, not an interesting result, so it earns a non-zero exit.
     */
    private static int verdict(
            List<Result> results, List<Result> exact, int capacity, int optimum) {

        int problems = 0;
        for (Result r : results) {
            if (!r.packing().fits(capacity)) {
                System.err.printf("FAIL: %s produced a bag of %s, over the %s limit.%n",
                        r.label(), kilograms(r.packing().weight), kilograms(capacity));
                problems++;
            }
            if (optimum > 0 && r.packing().value > optimum) {
                System.err.printf("FAIL: %s scored %,d, above the supposed optimum of %,d.%n",
                        r.label(), r.packing().value, optimum);
                problems++;
            }
        }
        for (Result r : exact) {
            if (r.packing().value != optimum) {
                System.err.printf("FAIL: %s says %,d, but another exact method says %,d.%n",
                        r.label(), r.packing().value, optimum);
                problems++;
            }
        }

        if (optimum > 0) {
            String proof = exact.size() > 1
                    ? exact.size() + " independent exact methods agree on it"
                    : "found by " + exact.get(0).label();
            System.err.printf("optimum %,d — %s.%n", optimum, proof);

            results.stream()
                    .filter(r -> !r.exact())
                    .max(Comparator.comparingInt(r -> r.packing().value))
                    .ifPresent(r -> System.err.printf(
                            "best heuristic: %s at %,d, %.2f%% short of it.%n",
                            r.label(), r.packing().value,
                            100.0 * (optimum - r.packing().value) / optimum));
        }

        if (problems == 0) {
            System.err.println(results.size() == 1
                    ? "the packing fits inside " + kilograms(capacity) + "."
                    : "all " + plural(results.size(), "packing") + " fit inside "
                            + kilograms(capacity) + ".");
        }
        return problems == 0 ? 0 : 1;
    }

    /** Run one solver, timing it. */
    private static Result time(String label, boolean exact, java.util.function.Supplier<Packing> solver) {
        long started = System.nanoTime();
        Packing packing = solver.get();
        return new Result(label, packing, System.nanoTime() - started, exact);
    }

    /** Break a long bit string into readable rows. */
    private static String wrap(String text, int width) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i += width) {
            out.append(text, i, Math.min(text.length(), i + width)).append(System.lineSeparator());
        }
        return out.toString().stripTrailing();
    }
}

package sgdc.dc.evidenceset.build.sampling;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.javasoft.bitset.IBitSet;
import sgdc.dc.evidenceset.IEvidenceSet;
import sgdc.dc.evidenceset.TroveEvidenceSet;
import sgdc.dc.evidenceset.build.EvidenceSetBuilder;
import sgdc.dc.input.ColumnPair;
import sgdc.dc.input.Input;
import sgdc.dc.predicates.Predicate;
import sgdc.dc.predicates.PredicateBuilder;
import sgdc.dc.predicates.sets.PredicateBitSet;

/**
 * Random sampling (paper Section V-A).
 *
 * <p>(1) Initial light sampling: for every tuple t, n random partners s != t are drawn.
 * (2) Adaptive batch sampling: batches of uniformly random ordered pairs (t != s); the batch
 * size doubles after every batch that fails the convergence test. Sampling stops as soon as
 * halfWidth(p) = z * sqrt(s^(p)(1 - s^(p)) / trials[p]) <= eta holds for all predicates, or when
 * the adaptive stage has used Nmax pairs. The test is evaluated after stage (1) and after every
 * batch (the original code skipped stage (2) and the test whenever n*|r| >= Nmax).
 *
 * <p>Evidence multiplicities are kept (the original stored a set per block, so repeated
 * evidences were counted once per block, which biased the selectivity estimation used by the
 * verification). The work is split into fixed-size chunks, each with its own random stream
 * derived from (seed, stage, chunk), and chunk results are merged in chunk order. The output is
 * therefore identical for every thread count.
 *
 * <p>Properties: rs.n (5), rs.Nmax (100000), rs.eta (0.03), rs.z (1.96), rs.batch0 (2000),
 * rs.chunk (1000 tuples / pairs per task), rs.threads (max(1, cpus - 1)).
 */
public class RandomSampling extends EvidenceSetBuilder {

    private final long seed;
    private final int n;
    private final long nMax;
    private final double eta;
    private final double z;
    private final int batch0;
    private final int chunk;
    private final int threads;

    /* results of the last run */
    private double[] selectivity = new double[0];
    private long trials = 0;
    private boolean converged = false;
    private long initialPairs = 0;
    private long adaptivePairs = 0;
    private int batches = 0;

    public RandomSampling(PredicateBuilder predicates, long seed) {
        super(predicates);
        this.seed = seed;
        this.n = Math.max(0, Integer.getInteger("rs.n", 5));
        this.nMax = Math.max(0L, Long.getLong("rs.Nmax", 100_000L));
        this.eta = Double.parseDouble(System.getProperty("rs.eta", "0.03"));
        this.z = Double.parseDouble(System.getProperty("rs.z", "1.96"));
        this.batch0 = Math.max(1, Integer.getInteger("rs.batch0", 2000));
        this.chunk = Math.max(1, Integer.getInteger("rs.chunk", 1000));
        this.threads = Math.max(1, Integer.getInteger("rs.threads",
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1)));
    }

    public IEvidenceSet buildEvidenceSet(Input input) {
        final Collection<ColumnPair> pairs = predicates.getColumnPairs();
        createSets(pairs);

        int maxIdx = -1;
        for (Predicate p : predicates.getPredicates()) maxIdx = Math.max(maxIdx, PredicateBitSet.getIndex(p));
        final long[] succ = new long[maxIdx + 1];
        final int[] predIdx = predicates.getPredicates().stream().mapToInt(PredicateBitSet::getIndex).toArray();

        final TroveEvidenceSet evidence = new TroveEvidenceSet();
        final int lines = input.getLineCount();
        trials = 0;
        initialPairs = 0;
        adaptivePairs = 0;
        batches = 0;
        converged = false;

        if (lines < 2) {
            selectivity = new double[maxIdx + 1];
            log.warn("random sampling skipped: relation has {} tuple(s)", lines);
            return evidence;
        }

        ExecutorService pool = threads > 1 ? Executors.newFixedThreadPool(threads) : null;
        try {
            // ---------------- (1) initial light sampling: n partners per tuple
            if (n > 0) {
                List<Chunk> tasks = new ArrayList<>();
                int c = 0;
                for (int start = 0; start < lines; start += chunk, c++) {
                    final int s = start, e = Math.min(lines, start + chunk);
                    final long cs = mix(seed, 1, c);
                    tasks.add(() -> {
                        SplittableRandom r = new SplittableRandom(cs);
                        Map<PredicateBitSet, Long> local = new HashMap<>();
                        for (int i = s; i < e; i++) {
                            PredicateBitSet st = getStatic(pairs, i);
                            for (int k = 0; k < n; k++) {
                                int j = r.nextInt(lines - 1);
                                if (j >= i) j++;
                                local.merge(getPredicateSet(st, pairs, i, j), 1L, Long::sum);
                            }
                        }
                        return local;
                    });
                }
                initialPairs = run(pool, tasks, evidence, succ);
                trials += initialPairs;
            }
            converged = hasConverged(succ, predIdx);

            // ---------------- (2) adaptive batches until convergence or Nmax
            long batch = batch0;
            while (!converged && adaptivePairs < nMax) {
                long size = Math.min(batch, nMax - adaptivePairs);
                List<Chunk> tasks = new ArrayList<>();
                int c = 0;
                for (long done = 0; done < size; done += chunk, c++) {
                    final int cnt = (int) Math.min(chunk, size - done);
                    final long cs = mix(seed, 2 + batches, c);
                    tasks.add(() -> {
                        SplittableRandom r = new SplittableRandom(cs);
                        Map<PredicateBitSet, Long> local = new HashMap<>();
                        for (int q = 0; q < cnt; q++) {
                            int i = r.nextInt(lines);
                            int j = r.nextInt(lines - 1);
                            if (j >= i) j++;
                            local.merge(getPredicateSet(getStatic(pairs, i), pairs, i, j), 1L, Long::sum);
                        }
                        return local;
                    });
                }
                long got = run(pool, tasks, evidence, succ);
                adaptivePairs += got;
                trials += got;
                batches++;
                converged = hasConverged(succ, predIdx);
                batch = batch * 2;
            }
        } finally {
            if (pool != null) pool.shutdownNow();
        }

        selectivity = new double[maxIdx + 1];
        for (int i = 0; i <= maxIdx; i++) selectivity[i] = trials == 0 ? 0.0 : (double) succ[i] / trials;
        log.info("random sampling: initialPairs={} adaptivePairs={} batches={} trials={} converged={} "
                        + "maxHalfWidth={} evidences={} (n={}, Nmax={}, eta={}, z={}, seed={})",
                initialPairs, adaptivePairs, batches, trials, converged,
                String.format("%.4f", maxHalfWidth(succ, predIdx)), evidence.size(), n, nMax, eta, z, seed);
        return evidence;
    }

    private interface Chunk extends java.util.concurrent.Callable<Map<PredicateBitSet, Long>> {
    }

    /** Runs the chunks (in parallel if a pool is given) and merges them in chunk order. */
    private long run(ExecutorService pool, List<Chunk> tasks, IEvidenceSet evidence, long[] succ) {
        List<Map<PredicateBitSet, Long>> results = new ArrayList<>(tasks.size());
        try {
            if (pool == null) {
                for (Chunk t : tasks) results.add(t.call());
            } else {
                List<Future<Map<PredicateBitSet, Long>>> fs = new ArrayList<>();
                for (Chunk t : tasks) fs.add(pool.submit(t));
                for (Future<Map<PredicateBitSet, Long>> f : fs) results.add(f.get());
            }
        } catch (ExecutionException e) {
            throw new IllegalStateException("random sampling failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("random sampling interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("random sampling failed", e);
        }
        long pairsDone = 0;
        for (Map<PredicateBitSet, Long> local : results) {
            for (Map.Entry<PredicateBitSet, Long> en : local.entrySet()) {
                long cnt = en.getValue();
                evidence.add(en.getKey(), cnt);
                pairsDone += cnt;
                IBitSet b = en.getKey().getBitset();
                for (int i = b.nextSetBit(0); i >= 0; i = b.nextSetBit(i + 1)) {
                    if (i < succ.length) succ[i] += cnt;
                }
            }
        }
        return pairsDone;
    }

    private double halfWidth(long s, long t) {
        if (t == 0) return Double.POSITIVE_INFINITY;
        double p = (double) s / t;
        return z * Math.sqrt(p * (1.0 - p) / t);
    }

    private boolean hasConverged(long[] succ, int[] predIdx) {
        return maxHalfWidth(succ, predIdx) <= eta;
    }

    private double maxHalfWidth(long[] succ, int[] predIdx) {
        double mx = 0.0;
        for (int i : predIdx) mx = Math.max(mx, halfWidth(succ[i], trials));
        return mx;
    }

    /** Deterministic seed for (stage, chunk). */
    private static long mix(long seed, long stage, long chunk) {
        long x = seed * 0x9E3779B97F4A7C15L + stage * 0xBF58476D1CE4E5B9L + chunk * 0x94D049BB133111EBL;
        x ^= (x >>> 31);
        x *= 0x7FB5D329728EA185L;
        x ^= (x >>> 27);
        return x;
    }

    /** s^(p) of the last run, indexed by predicate bit index (PredicateBitSet.getIndex). */
    public double[] getSelectivity() {
        return selectivity.clone();
    }

    public long getTrials() {
        return trials;
    }

    public boolean isConverged() {
        return converged;
    }

    private static final Logger log = LoggerFactory.getLogger(RandomSampling.class);
}

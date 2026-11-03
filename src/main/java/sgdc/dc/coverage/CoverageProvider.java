package sgdc.dc.coverage;

import ch.javasoft.bitset.IBitSet;
import ch.javasoft.bitset.LongBitSet;
import sgdc.dc.denialcontraints.DenialConstraint;
import sgdc.dc.input.Input;
import sgdc.dc.predicates.Predicate;
import sgdc.dc.predicates.PredicateBuilder;
import sgdc.dc.predicates.sets.PredicateBitSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import sgdc.dc.helpers.BitSetTable;

/**
 * Sampling-based Coverage estimator and Coverage upper bound, following the paper
 * (Sec. III "Coverage", Sec. IV-B "Sampling-based Coverage estimation", Sec. VI-A
 * "Score upper bounds").
 *
 * <p>For phi = not(p1 and ... and pm), let K(t,s) be the number of predicates of phi satisfied
 * by the ordered pair (t,s) and w(k) = (k+1)/m. Then
 * Coverage(phi) = sum_{k<m} |kE| w(k) / sum_{k<m} |kE| = E[w(K) | K <= m-1].
 *
 * <p>Estimator (rejection sampling): walk a fixed seeded stream of uniform ordered pairs with
 * t != s, keep a pair iff K <= m-1 (i.e. it does not violate phi), stop after n accepted
 * pairs and return the mean of (K+1)/m over them. With n = 10,000 Hoeffding gives
 * |error| <= sqrt(ln(40)/20000) ~ 0.0136 with probability >= 0.95. The guarantee needs all n accepted
 * samples; if the stream hits sgdc.cov.maxPairs first, fewer are used (counted in {@link #cappedEstimates()}).
 *
 * <p>Upper bounds: {@link #scoreUpperSafe} is the sound bound used by the search (-Dsgdc.upper=safe, default).
 * {@link #coverageUpper} is the CovUpper of the paper (Sec. VI-A),
 * CovUpper(S) = min{1, Cov^(phi(S)) + sum_{r=m}^{mmax-1} smax_r/(r+1)}; it is NOT admissible
 * (airport: bound 0.5703, Coverage of a superset 0.9972) and only kept for -Dsgdc.upper=paper.
 *
 * <p>Not thread-safe for concurrent searches: estimates and the beta cache are updated together under the
 * instance lock, the statistics counters are not synchronized.
 */
public class CoverageProvider {

    /** Accepted samples per DC (paper: n = 10,000). */
    public static final int DEFAULT_SAMPLE_SIZE = 10_000;

    private final PredicateBuilder predicates;
    private final int sampleSize;
    private final PairSample pairs;

    /**
     * Cache of the estimates keyed by the predicate bit set S: value 0 = Coverage estimate of phi(S),
     * value 1 = beta(S), the fraction of sampled pairs violating phi(S) from the same scan (NaN for |S| <= 1, where
     * beta comes from the selectivity). Compact table (see BitSetTable); accessed by the search thread only.
     */
    private BitSetTable cache;
    private double lastBeta = 1.0;

    /* lazily initialised (predicate bit indices are assigned by evidence building) */
    private volatile boolean selectivityReady = false;
    private double[] selectivity;               // by predicate bit index
    private List<IBitSet> groupBits;            // one bit set per predicate group
    private double[] groupMaxSelectivity;       // max selectivity per group

    /* statistics */
    private long estimates = 0;
    private long drawnPairs = 0;
    private long acceptedPairs = 0;
    private long minAccepted = Long.MAX_VALUE;  // smallest number of accepted samples of one estimate
    private long capped = 0;                    // estimates that stopped at maxPairs with fewer than n accepted samples

    public CoverageProvider(Input input, PredicateBuilder predicates, int sampleSize) {
        this(input, predicates, sampleSize, Long.getLong("sgdc.cov.seed", 1L));
    }

    public CoverageProvider(Input input, PredicateBuilder predicates, int sampleSize, long seed) {
        this.predicates = predicates;
        this.sampleSize = Math.max(1, sampleSize);
        int maxPairs = Integer.getInteger("sgdc.cov.maxPairs", 64 * this.sampleSize);
        this.pairs = new PairSample(input, seed, this.sampleSize, maxPairs);
    }

    // ------------------------------------------------------------------ coverage

    /** Estimated Coverage of dc (cached). */
    public double calculate(DenialConstraint dc) {
        return calculate(dc.getPredicateSet().getBitset());
    }

    private BitSetTable cache() {
        if (cache == null) cache = new BitSetTable(BitSetTable.wordsFor(PredicateBitSet.indexProvider.size() - 1), 2);
        return cache;
    }

    /** Estimated Coverage of phi(S) (cached). */
    public double calculate(IBitSet s) {
        double v = cache().get(s, 0, Double.NaN);
        if (!Double.isNaN(v)) return v;
        synchronized (this) {                               // estimate and lastBeta must belong to the same S
            double est = estimate(s);
            cache().put(s, est, s.cardinality() >= 2 ? lastBeta : Double.NaN);   // |S| <= 1: beta from the selectivity
            return est;
        }
    }

    /** Estimated fraction of ordered pairs (t != s) that violate phi(S). */
    public double violationFraction(IBitSet s) {
        int m = s.cardinality();
        if (m == 0) return 1.0;
        if (m == 1) return selectivity(PredicateBitSet.indexProvider.getObject(s.nextSetBit(0)));
        double b = cache().get(s, 1, Double.NaN);
        if (!Double.isNaN(b)) return b;
        calculate(s);
        b = cache().get(s, 1, Double.NaN);
        return Double.isNaN(b) ? 1.0 : b;
    }

    /**
     * Rejection-sampling estimate of Coverage(phi(S)); not cached.
     * The empty predicate set has no coverage; by convention 0 is returned.
     */
    public synchronized double estimate(IBitSet s) {
        int m = s.cardinality();
        lastBeta = 1.0;                                     // no stale value from the previous estimate
        if (m == 0 || pairs.isEmpty()) return 0.0;
        if (m == 1) return 1.0;                             // only K = 0 is accepted and w(0) = 1/1
        estimates++;

        int[] idx = new int[m];
        int q = 0;
        for (int b = s.nextSetBit(0); b >= 0; b = s.nextSetBit(b + 1)) idx[q++] = b;

        long accepted = 0;
        long sumK = 0;
        long viol = 0;
        int w = 0;
        while (true) {
            long[][] bm = new long[m][];
            for (int i = 0; i < m; i++) bm[i] = pairs.bitmap(idx[i]);
            int words = pairs.words();
            for (; w < words; w++) {
                long all = -1L;
                for (int i = 0; i < m; i++) all &= bm[i][w];
                long accept = ~all;                        // K <= m-1  <=>  not all predicates hold
                int c = Long.bitCount(accept);
                if (accepted + c > sampleSize) {
                    accept = lowestBits(accept, (int) (sampleSize - accepted));
                    c = Long.bitCount(accept);
                }
                if (accepted + c >= sampleSize && accept != 0) {   // only pairs up to the last accepted one were drawn
                    long hi = Long.highestOneBit(accept);
                    viol += Long.bitCount(all & (hi | (hi - 1)));
                } else {
                    viol += Long.bitCount(all);
                }
                for (int i = 0; i < m; i++) sumK += Long.bitCount(bm[i][w] & accept);
                accepted += c;
                if (accepted >= sampleSize) {
                    drawnPairs += 64L * (w + 1);
                    lastBeta = (double) viol / (viol + accepted);
                    return finish(sumK, accepted, m);
                }
            }
            if (!pairs.extend()) {                          // stream exhausted (cap reached)
                drawnPairs += 64L * w;
                capped++;
                if (accepted == 0) { lastBeta = 1.0; minAccepted = 0; return 0.0; }   // phi violated by every sampled pair
                if (capped == 1) log.info("coverage sample stream capped at {} pairs: {} uses only {} accepted samples "
                        + "(Hoeffding guarantee does not hold, see RESULT_COV_CAPPED)", pairs.size(), s, accepted);
                lastBeta = (double) viol / (viol + accepted);
                return finish(sumK, accepted, m);
            }
        }
    }

    private double finish(long sumK, long accepted, int m) {
        acceptedPairs += accepted;
        minAccepted = Math.min(minAccepted, accepted);
        // mean of (K+1)/m over the accepted pairs
        return (double) (sumK + accepted) / ((double) m * accepted);
    }

    private static long lowestBits(long x, int k) {
        long r = 0;
        for (int i = 0; i < k && x != 0; i++) {
            long low = x & -x;
            r |= low;
            x ^= low;
        }
        return r;
    }

    // ------------------------------------------------------------------ selectivity

    /** Selectivity estimate s^(p): fraction of sampled ordered pairs (t != s) that satisfy p. */
    public double selectivity(Predicate p) {
        ensureSelectivity();
        int i = PredicateBitSet.getIndex(p);
        return i < selectivity.length ? selectivity[i] : pairSelectivity(i);
    }

    private synchronized double pairSelectivity(int index) {
        long[] bm = pairs.bitmap(index);
        long c = 0;
        for (long x : bm) c += Long.bitCount(x);
        return pairs.size() == 0 ? 0.0 : (double) c / pairs.size();
    }

    private synchronized void ensureSelectivity() {
        if (selectivityReady) return;
        int maxIdx = 0;
        for (Predicate p : predicates.getPredicates()) maxIdx = Math.max(maxIdx, PredicateBitSet.getIndex(p));
        selectivity = new double[maxIdx + 1];
        for (Predicate p : predicates.getPredicates()) {
            int i = PredicateBitSet.getIndex(p);
            selectivity[i] = pairSelectivity(i);
        }
        groupBits = new ArrayList<>();
        List<Double> gmax = new ArrayList<>();
        for (Collection<Predicate> group : predicates.getPredicateGroups()) {
            IBitSet bs = LongBitSet.FACTORY.create();
            double mx = 0.0;
            for (Predicate p : group) {
                int i = PredicateBitSet.getIndex(p);
                bs.set(i);
                mx = Math.max(mx, selectivity[i]);
            }
            groupBits.add(bs);
            gmax.add(mx);
        }
        groupMaxSelectivity = gmax.stream().mapToDouble(Double::doubleValue).toArray();
        selectivityReady = true;
    }

    /** Number of predicate groups = maximal number of predicates in a DC (m_max). */
    public int groupCount() {
        ensureSelectivity();
        return groupBits.size();
    }

    // ------------------------------------------------------------------ upper bound

    /**
     * CovUpper(S) = min{1, Cov^(phi(S)) + sum_{r=m}^{mmax-1} smax_r/(r+1)}.
     * For S = {} every one-predicate extension has Coverage exactly 1, so the bound is 1.
     *
     * @param mmax maximal DC size considered by the search (<= number of predicate groups)
     */
    public double coverageUpper(IBitSet s, int mmax) {
        int m = s.cardinality();
        if (m == 0) return 1.0;
        ensureSelectivity();

        double[] free = new double[groupBits.size()];
        int f = 0;
        for (int g = 0; g < groupBits.size(); g++) {
            if (!intersects(s, groupBits.get(g))) free[f++] = groupMaxSelectivity[g];
        }
        Arrays.sort(free, 0, f);                             // ascending; read from the end

        int steps = Math.min(f, Math.max(0, mmax - m));
        double tail = 0.0;
        for (int i = 0; i < steps && tail < 1.0; i++) {
            int r = m + i;
            tail += free[f - 1 - i] / (r + 1);
        }
        if (tail >= 1.0) return 1.0;                       // bound saturated, no estimate needed
        double bound = calculate(s) + tail;
        return Math.min(1.0, bound);
    }

    /**
     * Sound ScoreUpper(S): an upper bound on Score(phi(T)) for every T with S subset-or-equal T (one predicate per group).
     *
     * Coverage part. Let A = pairs with K_S <= m-1 (they do not violate phi(S)) and B = pairs with K_S = m,
     * beta = |B| / (|A|+|B|). For T = S + R, |R| = r >= 1: every pair of A is counted by Coverage(phi(T)) with
     * weight (K_S + K_R + 1)/(m+r) <= (K_S + r + 1)/(m+r) <= 1, and a pair of B is counted only if it fails some
     * predicate of R, with weight <= 1. The ratio is largest when all of B is counted with weight 1, hence
     *   Coverage(phi(T)) <= beta + (1-beta) * (E[K_S | A] + r + 1)/(m+r) = beta + (1-beta) * (m*Cov(S) + r)/(m+r).
     * Succinctness part. Succ(T) = 4/(2+|ops(T) u cols(T)|); each added predicate comes from an unused group and
     * adds at least minNewCols(g) >= 0 columns not in S, so Succ(T) <= 4/(2 + u + sum of the r smallest minNewCols).
     * ScoreUpper(S) = max over r = 0..R of a*SuccUpper_r + b*CovUpper_r (r = 0 is S itself).
     */
    // ---- precomputed structure for the sound bound
    private int[] predC1, predC2, predOp;       // by predicate bit index: column ids / operator id
    private int[][] groupPreds;                 // predicate bit indices per group
    private int[] groupOf;                      // group of each predicate bit index
    private int nCols, nOps;
    private int[] colStamp, opStamp, grpStamp;
    private int stamp = 0;
    public long ubCalls = 0, ubCheapPruned = 0, ubExact = 0, ubHint = 0;
    private IBitSet hintKey; private int hintM; private double hintCov, hintBeta;
    /* -Dsgdc.parentLoop (default true): also look up every cached parent S - {q} when the hint does not prune */
    static final boolean PARENT_LOOP = Boolean.parseBoolean(System.getProperty("sgdc.parentLoop", "true"));

    /** The search is about to generate the children of S: use S as the parent hint (its estimate is cached). */
    public void setParentHint(IBitSet s) {
        int m = s.cardinality();
        if (m == 0) { hintKey = null; return; }
        ensureSafe();
        double[] k = known(s);
        if (k == null) { hintKey = null; return; }
        hintKey = s; hintM = m; hintCov = k[0]; hintBeta = k[1];
    }

    private void ensureSafe() {
        if (predC1 != null) return;
        ensureSelectivity();
        java.util.Map<String, Integer> cid = new java.util.HashMap<>(), oid = new java.util.HashMap<>();
        int n = selectivity.length;
        predC1 = new int[n]; predC2 = new int[n]; predOp = new int[n]; groupOf = new int[n];
        List<Collection<Predicate>> groups = new ArrayList<>(predicates.getPredicateGroups());
        groupPreds = new int[groups.size()][];
        for (int g = 0; g < groups.size(); g++) {
            int[] a = new int[groups.get(g).size()]; int q = 0;
            for (Predicate p : groups.get(g)) {
                int i = PredicateBitSet.getIndex(p);
                a[q++] = i; groupOf[i] = g;
                predC1[i] = cid.computeIfAbsent(p.getOperand1().getColumn().getName(), k -> cid.size());
                predC2[i] = cid.computeIfAbsent(p.getOperand2().getColumn().getName(), k -> cid.size());
                predOp[i] = oid.computeIfAbsent(p.getOperator().getShortString(), k -> oid.size());
            }
            groupPreds[g] = a;
        }
        nCols = cid.size(); nOps = oid.size();
        colStamp = new int[nCols]; opStamp = new int[nOps]; grpStamp = new int[groups.size()];
    }

    /** Coverage and violation fraction of phi(P) if already known without sampling, else null. */
    private double[] known(IBitSet p) {
        int m = p.cardinality();
        if (m == 1) return new double[]{1.0, selectivity[p.nextSetBit(0)]};
        double c = cache().get(p, 0, Double.NaN);
        if (Double.isNaN(c)) return null;
        double b = cache().get(p, 1, Double.NaN);
        return Double.isNaN(b) ? null : new double[]{c, b};
    }

    /**
     * Sound ScoreUpper(S): an upper bound on Score(phi(T)) for every T with S subset-or-equal T (one predicate per group).
     *
     * Coverage part (theorem). Let A = pairs with K_P <= |P|-1, B = pairs with K_P = |P| (they violate phi(P)),
     * beta_P = |B|/(|A|+|B|). For every T superset of P with |T| = |P| + r:
     *     Coverage(phi(T)) <= beta_P + (1-beta_P) * (|P|*Cov(P) + r)/(|P| + r)      (r >= 0).
     * It is applied with P = S (needs a coverage estimate of S) and with every P = S - {q} whose estimate is cached
     * (the parent in the search tree always is); each gives a valid bound, the minimum is used.
     * Succinctness part. Succ(T) = 4/(2+|ops(T) u cols(T)|); each added predicate comes from an unused group and
     * adds at least minNewCols(g) >= 0 columns not in S, so Succ(T) <= 4/(2 + u + sum of the r smallest minNewCols).
     * ScoreUpper(S) = max over r = 0..R of a*SuccUpper_r + b*CovUpper_r.
     * Two stages: the bound from the cached parents only (no sampling); if it is already < tau, S is pruned;
     * otherwise Cov(S) is estimated (needed for the queue priority anyway) and the tighter bound is returned.
     */
    public double scoreUpperSafe(IBitSet s, sgdc.dc.util.PruningFactors f) {
        int m = s.cardinality();
        if (m == 0) return f.a + f.b;
        ensureSafe();
        ubCalls++;
        stamp++;
        int u = 0;
        for (int b = s.nextSetBit(0); b >= 0; b = s.nextSetBit(b + 1)) {
            if (colStamp[predC1[b]] != stamp) { colStamp[predC1[b]] = stamp; u++; }
            if (colStamp[predC2[b]] != stamp) { colStamp[predC2[b]] = stamp; u++; }
            if (opStamp[predOp[b]] != stamp) { opStamp[predOp[b]] = stamp; u++; }
            grpStamp[groupOf[b]] = stamp;
        }
        int G = groupPreds.length;
        int[] add = new int[G];
        int R = 0;
        for (int g = 0; g < G; g++) {
            if (grpStamp[g] == stamp) continue;
            int best = 2;
            for (int p : groupPreds[g]) {
                int c1 = predC1[p], c2 = predC2[p];
                int c = (colStamp[c1] == stamp ? 0 : 1) + (c2 != c1 && colStamp[c2] != stamp ? 1 : 0);
                if (c < best) best = c;
            }
            add[R++] = best;
        }
        Arrays.sort(add, 0, R);
        double[] succ = new double[R + 1];
        int extra = 0;
        succ[0] = 4.0 / (2 + u);
        for (int r = 1; r <= R; r++) { extra += add[r - 1]; succ[r] = 4.0 / (2 + u + extra); }
        if (f.a * succ[0] + f.b < f.threshold) { ubCheapPruned++; return f.a * succ[0] + f.b; }   // Coverage <= 1

        // covUb[r] >= Coverage of every T superset of S with |T| = m + r
        double[] covUb = new double[R + 1];
        Arrays.fill(covUb, 1.0);
        boolean any = false;
        // stage 1: parent hint (the node being expanded; children are generated consecutively), no map lookup
        if (m >= 2 && hintKey != null && hintM == m - 1) {
            IBitSet t = hintKey.clone();
            t.andNot(s);
            if (t.isEmpty()) {
                tighten(covUb, hintCov, hintBeta, m - 1, 1);
                double ub = combine(succ, covUb, R, f);
                if (ub < f.threshold) { ubCheapPruned++; ubHint++; return ub; }
                any = true;
            }
        }
        double[] kS = known(s);
        if (PARENT_LOOP && m >= 2 && kS == null) {
            for (int q = s.nextSetBit(0); q >= 0; q = s.nextSetBit(q + 1)) {
                IBitSet p = s.clone();
                p.clear(q);
                double[] kp = known(p);
                if (kp == null) continue;
                any = true;
                tighten(covUb, kp[0], kp[1], m - 1, 1);
                hintKey = p; hintM = m - 1; hintCov = kp[0]; hintBeta = kp[1];
                                                                           // every cached parent gives a valid bound
            }
        }
        if (kS == null && any) {
            double ub = combine(succ, covUb, R, f);
            if (ub < f.threshold) { ubCheapPruned++; return ub; }
        }
        ubExact++;
        double cov = calculate(s);
        double beta = violationFraction(s);
        // the returned value uses S's own estimate only (identical to the one-stage bound), so the priority-queue
        // tie-break on ub and hence the search order are unchanged; parents are used only to prune early
        Arrays.fill(covUb, 1.0);
        tighten(covUb, cov, beta, m, 0);
        covUb[0] = Math.min(covUb[0], cov);                                  // r = 0: phi(S) itself
        return combine(succ, covUb, R, f);
    }

    /** covUb[r] = min(covUb[r], bound from P with |P| = mp, where T has |P| + off + r predicates). */
    private static void tighten(double[] covUb, double cov, double beta, int mp, int off) {
        for (int r = 0; r < covUb.length; r++) {
            int k = r + off;
            double v = k == 0 ? cov : beta + (1 - beta) * (mp * cov + k) / (mp + k);
            if (v < covUb[r]) covUb[r] = v;
        }
    }

    private static double combine(double[] succ, double[] covUb, int R, sgdc.dc.util.PruningFactors f) {
        double best = -1;
        for (int r = 0; r <= R; r++) {
            if (f.a * succ[r] + f.b <= best) break;                           // Succ only decreases, Coverage <= 1
            best = Math.max(best, f.a * succ[r] + f.b * Math.min(1.0, covUb[r]));
        }
        return best;
    }

    private static boolean intersects(IBitSet a, IBitSet b) {
        IBitSet t = a.clone();
        t.and(b);
        return !t.isEmpty();
    }

    // ------------------------------------------------------------------ misc

    public int getSampleSize() {
        return sampleSize;
    }

    public void clearCache() {
        cache().clear();
    }

    public String stats() {
        return String.format("ubCalls=%d cheapPruned=%d (hint %d) exact=%d ".formatted(ubCalls, ubCheapPruned, ubHint, ubExact) + "coverage estimates=%d cached=%d pairStream=%d avgDrawn=%.1f avgAccepted=%.1f minAccepted=%d capped=%d",
                estimates, cache().size(), pairs.size(),
                estimates == 0 ? 0.0 : (double) drawnPairs / estimates,
                estimates == 0 ? 0.0 : (double) acceptedPairs / estimates,
                minAccepted(), capped);
    }

    /** Smallest number of accepted samples used by one estimate (n if no estimate was capped, 0 if none was made). */
    public long minAccepted() {
        return estimates == 0 ? 0 : minAccepted;
    }

    /** Number of estimates that hit sgdc.cov.maxPairs before collecting n accepted samples. */
    public long cappedEstimates() {
        return capped;
    }

    private static final Logger log = LoggerFactory.getLogger(CoverageProvider.class);
}

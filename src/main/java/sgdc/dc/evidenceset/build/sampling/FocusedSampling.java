package sgdc.dc.evidenceset.build.sampling;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gnu.trove.list.array.TIntArrayList;
import sgdc.dc.evidenceset.IEvidenceSet;
import sgdc.dc.evidenceset.build.EvidenceSetBuilder;
import sgdc.dc.input.ColumnPair;
import sgdc.dc.input.Input;
import sgdc.dc.input.ParsedColumn;
import sgdc.dc.predicates.PredicateBuilder;
import sgdc.dc.predicates.sets.PredicateBitSet;

/**
 * Focused sampling (paper Section V-B).
 *
 * <p>For every attribute A the tuples are grouped into value clusters C_0..C_{k-1}; for sortable
 * attributes (Long / Double columns; string columns are treated as unordered) the clusters are
 * sorted by value. A method is m = (A, type) with type in {WITHIN, OTHER, BACK, FORTH}:
 * WITHIN pairs two distinct tuples of one cluster, OTHER pairs tuples of two different clusters,
 * BACK / FORTH restrict the target cluster to the ones preceding / succeeding the source cluster
 * (sortable attributes only). A method is only created when it is applicable.
 *
 * <p>Source cluster: C_i with probability proportional to |C_i| (a uniformly random tuple is the
 * source tuple). Target cluster: p_ij proportional to rho(i,j) * |C_j|, rho = 1 for unordered
 * attributes. For sortable attributes, with d = |i - j| and d0 (fs.nearMax, default 3):
 * rho = p_near * (1/d) / sum_near(1/d) for 1 <= d <= d0 and (1 - p_near) * d / sum_far(d) for d > d0.
 * The distribution is sampled exactly with prefix sums (O(log k) per draw).
 *
 * <p>Scheduler: every method is warmed up with one batch, g^(m) is initialised with the warm-up yield
 * g = #new evidences / #attempts, and methods with g^(m) > gamma enter a priority queue. The best
 * method runs one batch of b attempts, g^(m) <- (1-lambda) g^(m) + lambda g, and it is re-inserted
 * while g^(m) > gamma. Sampling stops when the attempt budget B is used or the queue is empty.
 * A new evidence is one that is not yet in E (exact test on the evidence set).
 * p_near is adapted per method after every batch: p_near <- clip(p_near + eta (g^near - g^far)).
 *
 * <p>Every method has its own random stream derived from the seed and the scheduler is sequential, so the
 * output is deterministic. Within a batch the evidences are computed in parallel (fs.threads) and inserted
 * in draw order, which gives the same result as the sequential procedure.
 *
 * <p>Properties: fs.batch (50000), fs.warmupBatch (20000), fs.nearMax (3), fs.pNear (0.65),
 * fs.dynamicPNear (true), fs.pNearEta (0.2), fs.pNearMin (0.05), fs.pNearMax (0.95),
 * fs.smooth (lambda, 0.3), fs.sortableOther (true: also run OTHER on sortable attributes).
 */
public class FocusedSampling extends EvidenceSetBuilder {

    enum Type { WITHIN, OTHER, BACK, FORTH }

    private final long seed;
    private final int batch;
    private final int warmupBatch;
    private final int d0;
    private final double pNear0;
    private final boolean dynamicPNear;
    private final double pNearEta;
    private final double pNearMin;
    private final double pNearMax;
    private final double lambda;
    private final boolean sortableOther;

    private Collection<ColumnPair> pairs;
    private Stats lastStats;
    /* -Dfs.threads (default CPU count - 1): threads computing the evidences of a batch; 1 = sequential */
    private final int threads = Math.max(1, Integer.getInteger("fs.threads", Runtime.getRuntime().availableProcessors() - 1));
    private static final int PAR_MIN = 2048;           // smaller batches are computed sequentially
    private java.util.concurrent.ForkJoinPool pool;

    /** Evidences of the pairs (t1s[q], t2s[q]), q < n; read-only on the shared data, so safe to compute in parallel. */
    private PredicateBitSet[] evidences(int[] t1s, int[] t2s, int n) {
        PredicateBitSet[] out = new PredicateBitSet[n];
        if (pool == null || n < PAR_MIN) {
            for (int q = 0; q < n; q++) out[q] = getPredicateSet(getStatic(pairs, t1s[q]), pairs, t1s[q], t2s[q]);
            return out;
        }
        try {
            pool.submit(() -> java.util.stream.IntStream.range(0, n).parallel()
                    .forEach(q -> out[q] = getPredicateSet(getStatic(pairs, t1s[q]), pairs, t1s[q], t2s[q]))).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
        return out;
    }

    public FocusedSampling(PredicateBuilder predicates, long seed) {
        super(predicates);
        this.seed = seed;
        this.batch = Math.max(1, Integer.getInteger("fs.batch", Integer.getInteger("fs.chunk", 50_000)));
        this.warmupBatch = Math.max(1, Integer.getInteger("fs.warmupBatch", Math.min(20_000, batch)));
        this.d0 = Math.max(1, Integer.getInteger("fs.nearMax", 3));
        this.pNear0 = Double.parseDouble(System.getProperty("fs.pNear", "0.65"));
        this.dynamicPNear = Boolean.parseBoolean(System.getProperty("fs.dynamicPNear", "true"));
        this.pNearEta = Double.parseDouble(System.getProperty("fs.pNearEta", "0.2"));
        this.pNearMin = Double.parseDouble(System.getProperty("fs.pNearMin", "0.05"));
        this.pNearMax = Double.parseDouble(System.getProperty("fs.pNearMax", "0.95"));
        this.lambda = Double.parseDouble(System.getProperty("fs.smooth", "0.3"));
        this.sortableOther = Boolean.parseBoolean(System.getProperty("fs.sortableOther", "true"));
        if (!(pNearMin >= 0 && pNearMin <= pNearMax && pNearMax <= 1))
            throw new IllegalArgumentException("need 0 <= fs.pNearMin <= fs.pNearMax <= 1");
        if (!(lambda > 0 && lambda <= 1))
            throw new IllegalArgumentException("fs.smooth must be in (0,1]");
    }

    // ================================================================== clusters

    /** Value clusters of one attribute, stored contiguously: cluster c is order[start[c] .. start[c+1]). */
    private static final class Clusters {
        final String name;
        final boolean sortable;
        final int k;              // number of clusters
        final int n;              // number of tuples
        final int[] order;        // tuple ids grouped by cluster
        final long[] start;       // prefix sums of the sizes (length k+1)
        final long[] idxWeighted; // prefix sums of c * |C_c| (length k+1), sortable only
        final int[] within;       // ids of clusters with size >= 2
        final long[] withinStart; // prefix sums of their sizes

        Clusters(ParsedColumn<?> col, int lines) {
            this.name = col.getName();
            this.sortable = col.isComparableType();
            Map<Object, TIntArrayList> byValue = new LinkedHashMap<>();
            for (int t = 0; t < lines; t++) {
                byValue.computeIfAbsent(col.getValue(t), v -> new TIntArrayList()).add(t);
            }
            List<Object> keys = new ArrayList<>(byValue.keySet());
            if (sortable) {
                keys.sort(FocusedSampling::compareValues);
            }
            this.k = keys.size();
            this.n = lines;
            this.order = new int[lines];
            this.start = new long[k + 1];
            this.idxWeighted = sortable ? new long[k + 1] : null;
            int pos = 0;
            TIntArrayList w = new TIntArrayList();
            for (int c = 0; c < k; c++) {
                TIntArrayList l = byValue.get(keys.get(c));
                start[c] = pos;
                for (int q = 0; q < l.size(); q++) order[pos++] = l.get(q);
                if (sortable) idxWeighted[c + 1] = idxWeighted[c] + (long) c * l.size();
                if (l.size() >= 2) w.add(c);
            }
            start[k] = pos;
            this.within = w.toArray();
            this.withinStart = new long[within.length + 1];
            for (int q = 0; q < within.length; q++) withinStart[q + 1] = withinStart[q] + size(within[q]);
        }

        long size(int c) {
            return start[c + 1] - start[c];
        }

        /** Cluster that contains position pos of order. */
        int clusterOfPos(long pos) {
            return upper(start, 0, k, pos);
        }

        int tupleIn(int c, SplittableRandom r) {
            return order[(int) (start[c] + r.nextLong(size(c)))];
        }
    }

    /** Largest index x in [lo, hi) with arr[x] <= v (arr is non-decreasing). */
    private static int upper(long[] arr, int lo, int hi, long v) {
        int a = lo, b = hi - 1;
        while (a < b) {
            int mid = (a + b + 1) >>> 1;
            if (arr[mid] <= v) a = mid; else b = mid - 1;
        }
        return a;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int compareValues(Object a, Object b) {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        return ((Comparable) a).compareTo(b);
    }

    // ================================================================== methods

    private final class Method {
        final int id;
        final Clusters cl;
        final Type type;
        final SplittableRandom rnd;

        double gHat;
        double pNear = pNear0;
        double gNear = Double.NaN, gFar = Double.NaN;
        long attempts, news, nearA, nearN, farA, farN, batches;

        /* outputs of pickTarget */
        private boolean lastNear;

        Method(int id, Clusters cl, Type type) {
            this.id = id;
            this.cl = cl;
            this.type = type;
            this.rnd = new SplittableRandom(mix(seed, id));
        }

        /**
         * Runs up to b attempts; returns the batch yield g. Three steps with the same result as drawing and inserting
         * one pair at a time: (1) draw the pairs in order from this method's random stream (the draws do not depend on
         * the evidence set within a batch), (2) compute their evidences in parallel, (3) insert them in draw order.
         */
        double runBatch(IEvidenceSet ev, long b) {
            long nw = 0, na = 0, nn = 0, fa = 0, fn = 0;
            int a = 0;
            int cap = (int) Math.min(b, Integer.MAX_VALUE - 8);
            int[] t1s = new int[cap], t2s = new int[cap];
            byte[] kind = new byte[cap];                    // 0: no near/far statistics, 1: near, 2: far
            long skips = 0, maxSkips = 20 * b + 100;
            while (a < b && skips <= maxSkips) {
                int t1, t2;
                boolean near = false, cross = false;
                if (type == Type.WITHIN) {
                    long u = rnd.nextLong(cl.withinStart[cl.within.length]);
                    int q = upper(cl.withinStart, 0, cl.within.length, u);
                    int c = cl.within[q];
                    long off1 = u - cl.withinStart[q];
                    long sz = cl.size(c);
                    long off2 = rnd.nextLong(sz - 1);
                    if (off2 >= off1) off2++;
                    t1 = cl.order[(int) (cl.start[c] + off1)];
                    t2 = cl.order[(int) (cl.start[c] + off2)];
                } else {
                    // source tuple uniform (cluster prob. proportional to |C_i|) among the clusters
                    // that have a target: BACK needs a predecessor, FORTH a successor
                    long lo = type == Type.BACK ? cl.start[1] : 0;
                    long hi = type == Type.FORTH ? cl.start[cl.k - 1] : cl.n;
                    long pos = lo + rnd.nextLong(hi - lo);
                    int i = cl.clusterOfPos(pos);
                    t1 = cl.order[(int) pos];
                    if (!cl.sortable) {
                        long rest = cl.n - cl.size(i);
                        if (rest <= 0) { skips++; continue; }
                        long u = rnd.nextLong(rest);
                        if (u >= cl.start[i]) u += cl.size(i);
                        t2 = cl.order[(int) u];
                    } else {
                        int j = pickSortedTarget(i);
                        if (j < 0) { skips++; continue; }
                        t2 = cl.tupleIn(j, rnd);
                        near = lastNear;
                        cross = true;
                    }
                }
                t1s[a] = t1; t2s[a] = t2; kind[a] = (byte) (cross ? (near ? 1 : 2) : 0);
                a++;
            }
            PredicateBitSet[] ps = evidences(t1s, t2s, a);
            for (int q = 0; q < a; q++) {
                boolean isNew = ev.add(ps[q]);
                if (isNew) nw++;
                if (kind[q] == 1) { na++; if (isNew) nn++; } else if (kind[q] == 2) { fa++; if (isNew) fn++; }
            }
            attempts += a;
            news += nw;
            nearA += na; nearN += nn; farA += fa; farN += fn;
            batches++;
            if (cl.sortable && type != Type.WITHIN) {
                if (na > 0) gNear = Double.isNaN(gNear) ? (double) nn / na : (1 - lambda) * gNear + lambda * nn / na;
                if (fa > 0) gFar = Double.isNaN(gFar) ? (double) fn / fa : (1 - lambda) * gFar + lambda * fn / fa;
                if (dynamicPNear && !Double.isNaN(gNear) && !Double.isNaN(gFar)) {
                    pNear = Math.min(pNearMax, Math.max(pNearMin, pNear + pNearEta * (gNear - gFar)));
                }
            }
            if (a == 0) return 0.0;
            return (double) nw / a;
        }

        /** Target cluster for source cluster i (sortable attributes), -1 if there is none. */
        private int pickSortedTarget(int i) {
            boolean back = type != Type.FORTH, forth = type != Type.BACK;
            int k = cl.k;
            // near candidates: 1 <= d <= d0 on the allowed sides
            double nearRaw = 0, invD = 0;
            int bLo = Math.max(0, i - d0), fHi = Math.min(k - 1, i + d0);
            if (back) for (int j = bLo; j < i; j++) { nearRaw += cl.size(j) / (double) (i - j); invD += 1.0 / (i - j); }
            if (forth) for (int j = i + 1; j <= fHi; j++) { nearRaw += cl.size(j) / (double) (j - i); invD += 1.0 / (j - i); }
            // far candidates: d > d0
            double farBack = 0, farForth = 0, sumD = 0;
            int bFarHi = i - d0 - 1;            // back far range [0, bFarHi]
            int fFarLo = i + d0 + 1;            // forth far range [fFarLo, k-1]
            if (back && bFarHi >= 0) {
                farBack = (double) i * (cl.start[bFarHi + 1] - cl.start[0]) - (cl.idxWeighted[bFarHi + 1] - cl.idxWeighted[0]);
                long cnt = bFarHi + 1;           // d runs from d0+1 to i
                sumD += (double) cnt * (d0 + 1 + i) / 2.0;
            }
            if (forth && fFarLo <= k - 1) {
                farForth = (double) (cl.idxWeighted[k] - cl.idxWeighted[fFarLo]) - (double) i * (cl.start[k] - cl.start[fFarLo]);
                long cnt = k - fFarLo;           // d runs from d0+1 to k-1-i
                sumD += (double) cnt * (d0 + 1 + (k - 1 - i)) / 2.0;
            }
            double mNear = invD > 0 ? pNear * nearRaw / invD : 0.0;
            double mFar = sumD > 0 ? (1 - pNear) * (farBack + farForth) / sumD : 0.0;
            if (mNear <= 0 && mFar <= 0) {
                // only possible if p_near is 0 or 1 and the other range is empty: fall back to the existing range
                mNear = nearRaw;
                mFar = farBack + farForth;
                if (mNear <= 0 && mFar <= 0) return -1;
            }
            if (rnd.nextDouble() * (mNear + mFar) < mNear) {
                lastNear = true;
                double u = rnd.nextDouble() * nearRaw;
                int last = -1;
                if (back) for (int j = bLo; j < i; j++) { last = j; u -= cl.size(j) / (double) (i - j); if (u < 0) return j; }
                if (forth) for (int j = i + 1; j <= fHi; j++) { last = j; u -= cl.size(j) / (double) (j - i); if (u < 0) return j; }
                return last;
            }
            lastNear = false;
            boolean useBack = rnd.nextDouble() * (farBack + farForth) < farBack;
            if (useBack) {
                // weight (i - l)|C_l| on [0, bFarHi]; G(x) = i*(S[x]-S[0]) - (L[x]-L[0]) for x in [1, bFarHi+1]
                long total = (long) i * cl.start[bFarHi + 1] - cl.idxWeighted[bFarHi + 1];
                long u = rnd.nextLong(total);
                int a = 1, b = bFarHi + 1;
                while (a < b) {
                    int mid = (a + b) >>> 1;
                    long g = (long) i * cl.start[mid] - cl.idxWeighted[mid];
                    if (g > u) b = mid; else a = mid + 1;
                }
                return a - 1;
            } else {
                // weight (l - i)|C_l| on [fFarLo, k-1]; G(x) = (L[x]-L[lo]) - i*(S[x]-S[lo]) for x in [lo+1, k]
                long base = cl.idxWeighted[fFarLo] - (long) i * cl.start[fFarLo];
                long total = cl.idxWeighted[k] - (long) i * cl.start[k] - base;
                long u = rnd.nextLong(total);
                int a = fFarLo + 1, b = k;
                while (a < b) {
                    int mid = (a + b) >>> 1;
                    long g = cl.idxWeighted[mid] - (long) i * cl.start[mid] - base;
                    if (g > u) b = mid; else a = mid + 1;
                }
                return a - 1;
            }
        }

        @Override
        public String toString() {
            return cl.name + "/" + type;
        }
    }

    private static long mix(long seed, long id) {
        long x = seed * 0x9E3779B97F4A7C15L + (id + 1) * 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 31);
        x *= 0x7FB5D329728EA185L;
        x ^= (x >>> 27);
        return x;
    }

    // ================================================================== driver

    /** Statistics of the last run. */
    public static final class Stats {
        public long budget, attempts, news, evidenceBefore, evidenceAfter;
        public int methods, warmedUp, queued, dropped;
        public long nearAttempts, nearNew, farAttempts, farNew;
        public double avgPNear = Double.NaN;
        public final Map<Type, long[]> byType = new EnumMap<>(Type.class);

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("budget=%d attempts=%d new=%d yield=%.4f evidences %d -> %d methods=%d queued=%d dropped=%d",
                    budget, attempts, news, attempts == 0 ? 0.0 : (double) news / attempts,
                    evidenceBefore, evidenceAfter, methods, queued, dropped));
            for (Map.Entry<Type, long[]> e : byType.entrySet())
                sb.append(' ').append(e.getKey()).append("(a=").append(e.getValue()[0]).append(",new=").append(e.getValue()[1]).append(')');
            long cross = nearAttempts + farAttempts;
            sb.append(String.format(" sortedCross: near=%d(new %d) far=%d(new %d) nearRatio=%.3f avgFinalPNear=%.3f",
                    nearAttempts, nearNew, farAttempts, farNew, cross == 0 ? 0.0 : (double) nearAttempts / cross, avgPNear));
            return sb.toString();
        }
    }

    public Stats getStats() {
        return lastStats;
    }

    /**
     * Expands evidenceSet in place (and returns it).
     *
     * @param gamma  yield threshold: a method is kept while its smoothed yield is > gamma
     * @param budget maximal number of sampled pairs (attempts)
     */
    public IEvidenceSet buildEvidenceSet(IEvidenceSet evidenceSet, Input input, double gamma, long budget) {
        pool = threads > 1 ? new java.util.concurrent.ForkJoinPool(threads) : null;
        try {
            return build(evidenceSet, input, gamma, budget);
        } finally {
            if (pool != null) pool.shutdown();
            pool = null;
        }
    }

    private IEvidenceSet build(IEvidenceSet evidenceSet, Input input, double gamma, long budget) {
        pairs = predicates.getColumnPairs();
        createSets(pairs);
        Stats st = new Stats();
        st.budget = Math.max(0, budget);
        st.evidenceBefore = evidenceSet.size();

        int lines = input.getLineCount();
        List<Method> methods = new ArrayList<>();
        if (lines >= 2) {
            int id = 0;
            for (ParsedColumn<?> c : input.getColumns()) {
                Clusters cl = new Clusters(c, lines);
                if (cl.within.length > 0) methods.add(new Method(id, cl, Type.WITHIN));
                id++;
                if (cl.k >= 2) {
                    if (cl.sortable) {
                        methods.add(new Method(id++, cl, Type.BACK));
                        methods.add(new Method(id++, cl, Type.FORTH));
                        if (sortableOther) methods.add(new Method(id, cl, Type.OTHER));
                        id++;
                    } else {
                        methods.add(new Method(id + 2, cl, Type.OTHER));
                        id += 3;
                    }
                } else {
                    id += 3;
                }
            }
        }
        st.methods = methods.size();

        long remaining = st.budget;
        PriorityQueue<Method> pq = new PriorityQueue<>((a, b) -> {
            int c = Double.compare(b.gHat, a.gHat);
            return c != 0 ? c : Integer.compare(a.id, b.id);
        });
        // warm-up: one batch per method initialises g^(m)
        for (Method m : methods) {
            if (remaining <= 0) break;
            long before = m.attempts;
            m.gHat = m.runBatch(evidenceSet, Math.min(warmupBatch, remaining));
            remaining -= m.attempts - before;
            st.warmedUp++;
            if (m.gHat > gamma) pq.add(m); else st.dropped++;
        }
        st.queued = pq.size();
        // main loop
        while (remaining > 0 && !pq.isEmpty()) {
            Method m = pq.poll();
            long before = m.attempts;
            double g = m.runBatch(evidenceSet, Math.min(batch, remaining));
            long used = m.attempts - before;
            remaining -= used;
            m.gHat = (1 - lambda) * m.gHat + lambda * g;
            if (used == 0) { st.dropped++; continue; }
            if (m.gHat > gamma) pq.add(m); else st.dropped++;
        }

        double pSum = 0;
        int pCnt = 0;
        for (Method m : methods) {
            st.attempts += m.attempts;
            st.news += m.news;
            st.nearAttempts += m.nearA;
            st.nearNew += m.nearN;
            st.farAttempts += m.farA;
            st.farNew += m.farN;
            long[] bt = st.byType.computeIfAbsent(m.type, t -> new long[2]);
            bt[0] += m.attempts;
            bt[1] += m.news;
            if (m.cl.sortable && m.type != Type.WITHIN && m.batches > 0) { pSum += m.pNear; pCnt++; }
            if (log.isDebugEnabled())
                log.debug("method {}: batches={} attempts={} new={} gHat={} pNear={}", m, m.batches, m.attempts, m.news,
                        String.format("%.4f", m.gHat), String.format("%.3f", m.pNear));
        }
        st.avgPNear = pCnt == 0 ? Double.NaN : pSum / pCnt;
        st.evidenceAfter = evidenceSet.size();
        lastStats = st;
        log.info("focused sampling: {} (gamma={}, b={}, warmup={}, d0={}, pNear0={}, dynamicPNear={}, seed={}, threads={})",
                st, gamma, batch, warmupBatch, d0, pNear0, dynamicPNear, seed, threads);
        return evidenceSet;
    }

    private static final Logger log = LoggerFactory.getLogger(FocusedSampling.class);
}

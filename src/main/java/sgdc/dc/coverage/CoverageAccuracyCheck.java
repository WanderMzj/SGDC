package sgdc.dc.coverage;

import ch.javasoft.bitset.IBitSet;
import ch.javasoft.bitset.LongBitSet;
import sgdc.dc.denialcontraints.DenialConstraint;
import sgdc.dc.input.Input;
import sgdc.dc.input.RelationalInput;
import sgdc.dc.predicates.Predicate;
import sgdc.dc.predicates.PredicateBuilder;
import sgdc.dc.predicates.sets.PredicateBitSet;
import sgdc.dc.predicates.sets.PredicateSetFactory;

import java.io.File;
import java.util.*;

/**
 * Stand-alone accuracy check for the refactored coverage module (not part of the algorithm).
 *
 * On the first N rows of a dataset it enumerates ALL ordered pairs (t,s), t != s, and computes
 * the exact Coverage of the paper for random DCs. It compares
 *   - the refactored rejection-sampling estimator (CoverageProvider),
 *   - the original SGDC implementation (verbatim logic of the old CoverageHandler/Provider,
 *     serial variant), and
 * checks Theorem 1 and the admissibility of CovUpper(S) against exact values.
 *
 * usage: java -Ddataset=file.csv -Drows=2000 -Ddcs=300 sgdc.dc.coverage.CoverageAccuracyCheck
 */
public class CoverageAccuracyCheck {

    static int rows;
    static long nPairs;
    static int words;
    static long[][] exactBits;          // by predicate index, over all ordered pairs t != s

    public static void main(String[] args) throws Exception {
        String ds = System.getProperty("dataset");
        rows = Integer.getInteger("rows", 2000);
        int nDcs = Integer.getInteger("dcs", 300);
        long seed = Long.getLong("seed", 7L);

        Input input = new Input(new RelationalInput(new File(ds)), rows, -1);
        rows = input.getLineCount();
        PredicateBuilder pb = new PredicateBuilder(input, true, 0.15d);
        List<List<Predicate>> groups = new ArrayList<>();
        for (Collection<Predicate> g : pb.getPredicateGroups()) {
            List<Predicate> l = new ArrayList<>(g);
            l.sort(Comparator.comparing(Predicate::toString));
            groups.add(l);
            for (Predicate p : l) PredicateBitSet.getIndex(p);
        }
        int P = PredicateBitSet.indexProvider.size();
        int mmax = groups.size();

        // exact bitmaps
        nPairs = (long) rows * (rows - 1);
        words = (int) ((nPairs + 63) >>> 6);
        exactBits = new long[P][];
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < P; i++) {
            Predicate p = PredicateBitSet.getPredicate(i);
            long[] bm = new long[words];
            long j = 0;
            for (int t = 0; t < rows; t++)
                for (int s = 0; s < rows; s++) {
                    if (t == s) continue;
                    if (p.satisfies(t, s)) bm[(int) (j >>> 6)] |= 1L << (j & 63);
                    j++;
                }
            exactBits[i] = bm;
        }
        System.out.printf("dataset=%s rows=%d pairs=%d |P|=%d groups=%d exactPrep=%dms%n",
                new File(ds).getName(), rows, nPairs, P, mmax, System.currentTimeMillis() - t0);

        CoverageProvider provider = new CoverageProvider(input, pb, CoverageProvider.DEFAULT_SAMPLE_SIZE);
        LegacyCoverage legacy = new LegacyCoverage(input, 1, 10000);

        Random rnd = new Random(seed);
        int[] sizeHist = new int[mmax + 1];
        double sumErrNew = 0, sumErrOld = 0, maxErrNew = 0, maxErrOld = 0;
        int overNew = 0, overOld = 0, cnt = 0;
        double[] errNewBySize = new double[mmax + 1], errOldBySize = new double[mmax + 1];
        long tNew = 0, tOld = 0;
        StringBuilder examples = new StringBuilder();
        for (int d = 0; d < nDcs; d++) {
            int m = 1 + rnd.nextInt(Math.min(6, mmax));
            IBitSet S = randomSet(groups, m, rnd);
            double exact = exactCoverage(S);
            if (Double.isNaN(exact)) { d--; continue; }       // every pair violates phi: undefined
            long a = System.nanoTime();
            double est = provider.estimate(S);
            long b = System.nanoTime();
            double old = legacy.calculate(new DenialConstraint(PredicateSetFactory.create(S)));
            long c = System.nanoTime();
            tNew += b - a; tOld += c - b;
            double eN = Math.abs(est - exact), eO = Math.abs(old - exact);
            sumErrNew += eN; sumErrOld += eO; maxErrNew = Math.max(maxErrNew, eN); maxErrOld = Math.max(maxErrOld, eO);
            if (eN > 0.02) overNew++;
            if (eO > 0.02) overOld++;
            errNewBySize[m] += eN; errOldBySize[m] += eO; sizeHist[m]++;
            cnt++;
            if (d < 6 && m >= 2)
                examples.append(String.format(Locale.ROOT, "  m=%d exact=%.4f new=%.4f old=%.4f  %s%n", m, exact, est, old,
                        new DenialConstraint(PredicateSetFactory.create(S))));
        }
        System.out.printf(Locale.ROOT, "ESTIMATOR dcs=%d  new: meanAbsErr=%.4f maxAbsErr=%.4f frac(|err|>0.02)=%.3f time=%.1fms | old: meanAbsErr=%.4f maxAbsErr=%.4f frac(|err|>0.02)=%.3f time=%.1fms%n",
                cnt, sumErrNew / cnt, maxErrNew, (double) overNew / cnt, tNew / 1e6,
                sumErrOld / cnt, maxErrOld, (double) overOld / cnt, tOld / 1e6);
        for (int m = 1; m <= mmax; m++)
            if (sizeHist[m] > 0)
                System.out.printf(Locale.ROOT, "  size=%d n=%d meanAbsErr new=%.4f old=%.4f%n", m, sizeHist[m],
                        errNewBySize[m] / sizeHist[m], errOldBySize[m] / sizeHist[m]);
        System.out.print(examples);

        // ---- Theorem 1 (exact values) and CovUpper admissibility
        int thChecks = 0, thViol = 0; double thMaxExcess = 0; String thEx = "";
        int ubChecks = 0, ubViol = 0; double ubMaxExcess = 0;
        int nUb = Integer.getInteger("ubsets", 60);
        for (int d = 0; d < nUb; d++) {
            int m = 2 + rnd.nextInt(Math.max(1, Math.min(4, mmax - 2)));
            if (m >= mmax) m = Math.max(2, mmax - 1);
            IBitSet S = randomSet(groups, m, rnd);
            double cS = exactCoverage(S);
            if (Double.isNaN(cS)) continue;
            double upper = provider.coverageUpper(S, mmax);
            double best = cS;
            // one-step extensions: Theorem 1
            for (List<Predicate> g : groups) {
                if (usesGroup(S, g)) continue;
                for (Predicate p : g) {
                    IBitSet S2 = S.clone(); S2.set(PredicateBitSet.getIndex(p));
                    double c2 = exactCoverage(S2);
                    if (Double.isNaN(c2)) continue;
                    double sel = exactSelectivity(PredicateBitSet.getIndex(p));
                    double bound = sel * (m - 1) / ((double) m * (m + 1));
                    thChecks++;
                    if (c2 - cS > bound + 1e-12) { thViol++; if (c2 - cS - bound > thMaxExcess) { thMaxExcess = c2 - cS - bound;
                        thEx = String.format(Locale.ROOT, "S=%s cov(S)=%.4f + p=%s sel=%.4f -> cov=%.4f bound=%.4f", new DenialConstraint(PredicateSetFactory.create(S)), cS, p, sel, c2, bound); } }
                    best = Math.max(best, c2);
                }
            }
            // random deeper extensions up to mmax
            for (int k = 0; k < 40; k++) {
                IBitSet S2 = S.clone();
                int add = 1 + rnd.nextInt(Math.max(1, mmax - m));
                List<Integer> free = new ArrayList<>();
                for (int gi = 0; gi < groups.size(); gi++) if (!usesGroup(S, groups.get(gi))) free.add(gi);
                Collections.shuffle(free, rnd);
                for (int q = 0; q < add && q < free.size(); q++) {
                    List<Predicate> g = groups.get(free.get(q));
                    S2.set(PredicateBitSet.getIndex(g.get(rnd.nextInt(g.size()))));
                }
                double c2 = exactCoverage(S2);
                if (!Double.isNaN(c2)) best = Math.max(best, c2);
            }
            ubChecks++;
            if (best > upper + 1e-9) { ubViol++; ubMaxExcess = Math.max(ubMaxExcess, best - upper); }
        }
        System.out.printf(Locale.ROOT, "THEOREM1 checks=%d violations=%d maxExcess=%.4f%n  worst: %s%n", thChecks, thViol, thMaxExcess, thEx);
        // ---- stress: S made of high-selectivity predicates, i.e. phi(S) violated by many pairs
        int sChecks = 0, sViol = 0; double sMax = 0; String sEx = "";
        int uChecks = 0, uViol = 0; double uMax = 0;
        for (int d = 0; d < nUb; d++) {
            int m = 2 + rnd.nextInt(2);
            List<Integer> gi = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) gi.add(i);
            Collections.shuffle(gi, rnd);
            IBitSet S = LongBitSet.FACTORY.create();
            for (int i = 0; i < m; i++) {
                Predicate best = null; double bs = -1;
                for (Predicate p : groups.get(gi.get(i))) { double v = exactSelectivity(PredicateBitSet.getIndex(p)); if (v > bs) { bs = v; best = p; } }
                S.set(PredicateBitSet.getIndex(best));
            }
            double cS = exactCoverage(S);
            if (Double.isNaN(cS)) continue;
            double upper = provider.coverageUpper(S, mmax);
            double bestC = cS;
            for (List<Predicate> g : groups) {
                if (usesGroup(S, g)) continue;
                for (Predicate p : g) {
                    IBitSet S2 = S.clone(); S2.set(PredicateBitSet.getIndex(p));
                    double c2 = exactCoverage(S2);
                    if (Double.isNaN(c2)) continue;
                    bestC = Math.max(bestC, c2);
                    double sel = exactSelectivity(PredicateBitSet.getIndex(p));
                    double bound = sel * (m - 1) / ((double) m * (m + 1));
                    sChecks++;
                    if (c2 - cS > bound + 1e-12) { sViol++; if (c2 - cS - bound > sMax) { sMax = c2 - cS - bound;
                        sEx = String.format(Locale.ROOT, "S=%s cov(S)=%.4f + p=%s sel=%.4f -> cov=%.4f bound=%.4f", new DenialConstraint(PredicateSetFactory.create(S)), cS, p, sel, c2, bound); } }
                }
            }
            uChecks++;
            if (bestC > upper + 1e-9) { uViol++; uMax = Math.max(uMax, bestC - upper); }
        }
        System.out.printf(Locale.ROOT, "THEOREM1-STRESS checks=%d violations=%d maxExcess=%.4f%n  worst: %s%n", sChecks, sViol, sMax, sEx);
        System.out.printf(Locale.ROOT, "COVUPPER-STRESS sets=%d violations(one-step)=%d maxExcess=%.4f%n", uChecks, uViol, uMax);
        System.out.printf(Locale.ROOT, "COVUPPER sets=%d violations=%d maxExcess=%.4f%n", ubChecks, ubViol, ubMaxExcess);
    }

    static boolean usesGroup(IBitSet S, List<Predicate> g) {
        for (Predicate p : g) if (S.get(PredicateBitSet.getIndex(p))) return true;
        return false;
    }

    static IBitSet randomSet(List<List<Predicate>> groups, int m, Random rnd) {
        List<Integer> gi = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) gi.add(i);
        Collections.shuffle(gi, rnd);
        IBitSet S = LongBitSet.FACTORY.create();
        for (int i = 0; i < m; i++) {
            List<Predicate> g = groups.get(gi.get(i));
            S.set(PredicateBitSet.getIndex(g.get(rnd.nextInt(g.size()))));
        }
        return S;
    }

    static double exactSelectivity(int idx) {
        long c = 0;
        for (long x : exactBits[idx]) c += Long.bitCount(x);
        return (double) c / nPairs;
    }

    /** Exact Coverage = sum_{k<m} |kE| (k+1)/m / sum_{k<m} |kE|; NaN if no pair has K < m. */
    static double exactCoverage(IBitSet S) {
        int m = S.cardinality();
        int[] idx = new int[m]; int q = 0;
        for (int b = S.nextSetBit(0); b >= 0; b = S.nextSetBit(b + 1)) idx[q++] = b;
        long accepted = 0, sumK = 0;
        int tail = (int) (nPairs & 63);
        for (int w = 0; w < words; w++) {
            long valid = (w == words - 1 && tail != 0) ? (1L << tail) - 1 : -1L;
            long all = valid;
            for (int i = 0; i < m; i++) all &= exactBits[idx[i]][w];
            long acc = valid & ~all;
            accepted += Long.bitCount(acc);
            for (int i = 0; i < m; i++) sumK += Long.bitCount(exactBits[idx[i]][w] & acc);
        }
        if (accepted == 0) return Double.NaN;
        return (double) (sumK + accepted) / ((double) m * accepted);
    }

    /** Verbatim logic of the ORIGINAL SGDC CoverageHandler + CoverageProvider (serial variant). */
    static final class LegacyCoverage {
        final int[] l, r; final int N; final BitSet inv;
        LegacyCoverage(Input input, long seed, int samplePairs) {
            N = samplePairs; l = new int[N]; r = new int[N]; inv = new BitSet(N);
            Random random = new Random(seed); int n = input.getLineCount();
            for (int i = 0; i < N; i++) { int t1, t2; do { t1 = random.nextInt(n); t2 = random.nextInt(n); } while (t1 == t2); l[i] = t1; r[i] = t2; }
        }
        double calculate(DenialConstraint dc) {
            inv.clear();
            int m = dc.size();
            List<DenialConstraint> list = dc.generateSubset().getConstraints().stream()
                    .sorted(Comparator.comparing(DenialConstraint::getPredicateCount).reversed()).toList();
            double v = 0;
            for (DenialConstraint sub : list) {
                long cnt = 0;
                for (int i = 0; i < N; i++) {
                    if (inv.get(i)) continue;
                    boolean all = true;
                    for (Predicate p : sub.getPredicateSet()) if (!p.satisfies(l[i], r[i])) { all = false; break; }
                    if (all) { inv.set(i); cnt++; }
                }
                v += (double) cnt * ((double) sub.size() / m) / N;
            }
            v += (double) (N - inv.cardinality()) / N / m;
            return v;
        }
    }
}

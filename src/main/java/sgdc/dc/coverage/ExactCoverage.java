package sgdc.dc.coverage;

import sgdc.dc.denialcontraints.DenialConstraint;
import sgdc.dc.input.Input;
import sgdc.dc.input.ParsedColumn;
import sgdc.dc.predicates.Predicate;
import sgdc.dc.predicates.sets.PredicateBitSet;
import de.metanome.algorithm_integration.Operator;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Exact Coverage of VALID denial constraints over the full relation (all ordered pairs t != s).
 *
 * <p>Coverage(phi) = sum_{k<m} |kE| (k+1)/m / sum_{k<m} |kE|, K(t,s) = number of predicates of phi satisfied
 * by (t,s). If phi is valid, no pair satisfies all m predicates (|mE| = 0), hence the denominator is
 * N = n(n-1) and, by linearity of sum_{pairs} K = sum_{p in phi} cnt(p),
 * <pre>  Coverage(phi) = (1 + sum_{p in phi} sel(p)) / m,   sel(p) = cnt(p) / N.</pre>
 * sel(p) is computed exactly: for a same-column predicate t0.A op t1.A from the value frequencies of A (one hash
 * pass per column): the equality count is sum_v c_v (c_v - 1) and the order operators follow by symmetry
 * (#{t.A < s.A} = #{t.A > s.A}); cross-column predicates are counted by binary search in a sorted copy.
 *
 * <p>The formula is exact only for valid DCs (every DC output by SGDC has been verified on the full data).
 */
public final class ExactCoverage {

    private final Input input;
    private final int n;
    private final double pairs;
    private final Map<Integer, Comparable[]> sortedCols = new HashMap<>();
    private final Map<Predicate, Double> sel = new HashMap<>();
    private final Map<Integer, Double> eqCache = new HashMap<>();
    private long nsTotal = 0;

    public ExactCoverage(Input input) {
        this.input = input;
        this.n = input.getLineCount();
        this.pairs = (double) n * (double) (n - 1);
    }

    /** Exact Coverage of a valid DC. */
    public synchronized double coverage(DenialConstraint dc) {
        long t0 = System.nanoTime();
        int m = 0;
        double s = 0;
        for (Predicate p : dc.getPredicateSet()) {
            s += selectivity(p);
            m++;
        }
        nsTotal += System.nanoTime() - t0;
        if (m == 0) return 0.0;
        return (1.0 + s) / m;
    }

    /** Exact fraction of ordered pairs (t, s), t != s, satisfying p. */
    public synchronized double selectivity(Predicate p) {
        Double v = sel.get(p);
        if (v != null) return v;
        double r = n < 2 ? 0.0 : count(p) / pairs;
        sel.put(p, r);
        return r;
    }

    public long millis() {
        return nsTotal / 1_000_000;
    }

    public int cachedPredicates() {
        return sel.size();
    }

    // ------------------------------------------------------------------

    @SuppressWarnings({"unchecked", "rawtypes"})
    private double count(Predicate p) {
        ParsedColumn<?> c1 = p.getOperand1().getColumn();
        ParsedColumn<?> c2 = p.getOperand2().getColumn();
        int i1 = p.getOperand1().getIndex(), i2 = p.getOperand2().getIndex();
        Operator op = p.getOperator();
        if (i1 == i2) {                                      // single-tuple predicate: independent of the other tuple
            long c = 0;
            for (int l = 0; l < n; l++) if (op.eval((Comparable) c1.getValue(l), c2.getValue(l))) c++;
            return (double) c * (n - 1);
        }
        // i1 != i2: #{(u,v): u != v, u.C1 op v.C2} (the t0/t1 roles are symmetric over ordered pairs)
        if (c1 == c2 || c1.getIndex() == c2.getIndex()) {
            // #{u != v: u.A == v.A} = sum_v c_v (c_v - 1) (value equality as in Operator.eval: equals);
            // the strict order relations split the remaining pairs evenly (#{u.A < v.A} = #{u.A > v.A})
            double eq = eqPairs(c1);
            double lt = (pairs - eq) / 2.0;
            switch (op) {
                case EQUAL: return eq;
                case UNEQUAL: return pairs - eq;
                case LESS: case GREATER: return lt;
                case LESS_EQUAL: case GREATER_EQUAL: return lt + eq;
                default: throw new IllegalStateException("operator " + op);
            }
        }
        Comparable[] s2 = sorted(c2);
        double c = 0;
        for (int u = 0; u < n; u++) {
            Comparable a = (Comparable) c1.getValue(u);
            int lo = lowerBound(s2, a), hi = upperBound(s2, a);   // [lo,hi): v.C2 == a
            long less = lo, eq = hi - lo, greater = s2.length - hi; // counts of v.C2 <, ==, > a
            long k;
            switch (op) {
                case EQUAL: k = eq; break;
                case UNEQUAL: k = less + greater; break;
                case LESS: k = greater; break;                  // a < v.C2
                case LESS_EQUAL: k = greater + eq; break;
                case GREATER: k = less; break;
                case GREATER_EQUAL: k = less + eq; break;
                default: throw new IllegalStateException("operator " + op);
            }
            if (op.eval(a, c2.getValue(u))) k--;                 // exclude v = u
            c += k;
        }
        return c;
    }

    private double eqPairs(ParsedColumn<?> col) {
        return eqCache.computeIfAbsent(col.getIndex(), k -> {
            HashMap<Object, int[]> cnt = new HashMap<>();
            for (int l = 0; l < n; l++) cnt.computeIfAbsent(col.getValue(l), x -> new int[1])[0]++;
            double eq = 0;
            for (int[] c : cnt.values()) eq += (double) c[0] * (c[0] - 1);
            return eq;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Comparable[] sorted(ParsedColumn<?> col) {
        return sortedCols.computeIfAbsent(col.getIndex(), k -> {
            Comparable[] a = new Comparable[n];
            for (int l = 0; l < n; l++) a[l] = (Comparable) col.getValue(l);
            Arrays.sort(a);
            return a;
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int lowerBound(Comparable[] a, Comparable x) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (a[mid].compareTo(x) < 0) lo = mid + 1; else hi = mid; }
        return lo;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int upperBound(Comparable[] a, Comparable x) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (a[mid].compareTo(x) <= 0) lo = mid + 1; else hi = mid; }
        return lo;
    }

    /**
     * Brute force over all ordered pairs (small inputs only): exact Coverage by the definition and the number
     * of violating pairs. Used to check the closed form.
     */
    public double[] bruteForce(DenialConstraint dc) {
        Predicate[] ps = new Predicate[dc.size()];
        int q = 0;
        for (Predicate p : dc.getPredicateSet()) ps[q++] = p;
        int m = ps.length;
        long[] cnt = new long[m + 1];
        for (int t = 0; t < n; t++)
            for (int s = 0; s < n; s++) {
                if (t == s) continue;
                int k = 0;
                for (Predicate p : ps) if (p.satisfies(t, s)) k++;
                cnt[k]++;
            }
        double num = 0, den = 0;
        for (int k = 0; k < m; k++) { num += cnt[k] * (k + 1.0) / m; den += cnt[k]; }
        return new double[]{den == 0 ? 0 : num / den, cnt[m]};
    }

    static {
        // PredicateBitSet is referenced so that the class is initialised consistently with the search
        PredicateBitSet.indexProvider.size();
    }
}

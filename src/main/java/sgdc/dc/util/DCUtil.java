package sgdc.dc.util;

import ch.javasoft.bitset.IBitSet;
import sgdc.dc.coverage.CoverageProvider;
import sgdc.dc.denialcontraints.DenialConstraint;
import sgdc.dc.predicates.Predicate;
import sgdc.dc.predicates.sets.PredicateSetFactory;

import java.util.HashSet;
import java.util.Set;

/** Stateless score helpers (the former static weights a/b now live in {@link PruningFactors}). */
public final class DCUtil {

    private DCUtil() {
    }

    /** Succ(phi) = 4 / (2 + |ops(phi) ∪ attrs(phi)|). */
    public static double getSuccinctness(DenialConstraint dc) {
        Set<String> set = new HashSet<>();
        for (Predicate predicate : dc.getPredicateSet()) {
            set.add(predicate.getOperator().getShortString());
            set.add(predicate.getOperand1().getColumn().getName());
            set.add(predicate.getOperand2().getColumn().getName());
        }
        return 4.0d / (2 + set.size());
    }

    /**
     * Upper-bound mode (-Dsgdc.upper=...):
     * "safe" (default) - sound bound of {@link CoverageProvider#scoreUpperSafe}; used by all experiments;
     * "legacy"         - the original heuristic bound of SGDC; not admissible (misses DCs with >= 5 predicates);
     * "paper"          - CovUpper of Sec. VI-A. Not admissible either (counter-example on airport: bound 0.5703,
     *                    true Coverage of a superset 0.9972), and it does not finish on adult / tax500k.
     */
    public static final String UPPER_MODE = System.getProperty("sgdc.upper", "safe");

    /**
     * ScoreUpper(S) = a*SuccUpper(S) + b*CovUpper(S) with SuccUpper(S) = Succ(phi(S)) and CovUpper from
     * {@link CoverageProvider#coverageUpper(IBitSet, int)} (paper Sec. VI-A), or the legacy heuristic.
     *
     * @param mmax maximal DC size (number of predicate groups)
     */
    public static double scoreUpper(IBitSet S, int mmax, CoverageProvider coverageProvider, PruningFactors f) {
        if ("legacy".equalsIgnoreCase(UPPER_MODE)) {
            return scoreUpperLegacy(S, mmax, f);
        }
        if ("safe".equalsIgnoreCase(UPPER_MODE)) {         // sound bound, see CoverageProvider#scoreUpperSafe
            return coverageProvider.scoreUpperSafe(S, f);
        }
        if ("one".equalsIgnoreCase(UPPER_MODE)) {          // experiment: CovUpper(S) = 1 for every node
            if (S.isEmpty()) return f.a + f.b;
            return f.score(new DenialConstraint(PredicateSetFactory.create(S)), 1.0);
        }
        if (S.isEmpty()) {
            return f.a + f.b;              // Succ of any one-predicate DC is 1, Coverage <= 1
        }
        DenialConstraint dc = new DenialConstraint(PredicateSetFactory.create(S));
        return f.score(dc, coverageProvider.coverageUpper(S, mmax));
    }

    /**
     * Original heuristic: Coverage upper = 1 for |S| <= 4, otherwise an ad-hoc series.
     * Not admissible in the sense of the paper; available as -Dsgdc.upper=legacy for comparison only.
     */
    public static double scoreUpperLegacy(IBitSet S, int m, PruningFactors f) {
        if (S.isEmpty()) return f.a + f.b;
        DenialConstraint dc = new DenialConstraint(PredicateSetFactory.create(S));
        int n = dc.size();
        double coverage = 0;
        if (n <= 4) {
            coverage = 1;
        } else {
            for (int i = n + 1; i <= m / 2; i++) {
                coverage += 1.0d / (i * (i + 1)) - 4.0d / ((i + 3) * (i + 5));
            }
        }
        return f.score(dc, coverage);
    }
}

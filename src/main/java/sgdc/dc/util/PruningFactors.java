package sgdc.dc.util;

import sgdc.dc.denialcontraints.DenialConstraint;

/**
 * Score parameters of one run: Score(phi) = a*Succ(phi) + b*Coverage(phi), accepted iff Score >= threshold.
 * a = alpha (weight of Succinctness), b = 1 - alpha. Immutable; created from {@link RunConfig#factors()}.
 */
public final class PruningFactors {
    public final double a;
    public final double b;
    public final double threshold;

    public PruningFactors(double a, double b, double threshold) {
        if (a < 0 || b < 0) throw new IllegalArgumentException("weights must be >= 0: a=" + a + " b=" + b);
        this.a = a;
        this.b = b;
        this.threshold = threshold;
    }

    /** Score of a DC given its (estimated) coverage; negative coverage values are clamped to 0. */
    public double score(DenialConstraint dc, double coverage) {
        return a * DCUtil.getSuccinctness(dc) + b * Math.max(0.0, coverage);
    }

    /** Paper: D_tau = {phi : Score(phi) >= tau}. */
    public boolean accepts(double score) {
        return score >= threshold;
    }

    @Override
    public String toString() {
        return "PruningFactors{a=" + a + ", b=" + b + ", tau=" + threshold + "}";
    }
}

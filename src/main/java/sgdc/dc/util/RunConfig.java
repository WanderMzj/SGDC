package sgdc.dc.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable per-run configuration (replaces the former static {@code Parameters} class and the static
 * fields of SgdcRunner/DCUtil/Verification).
 *
 * <p>Sources, lowest to highest precedence: built-in defaults, JVM system properties ({@code -Dkey=value}),
 * command line ({@code --key=value}). A bare command line argument is taken as the dataset path.
 * Keys starting with {@code fs.}, {@code rs.}, {@code sgdc.}, {@code input.} or {@code inject.} that are not
 * fields of this class are forwarded to system properties so that component-level tuning knobs can also be
 * passed on the command line.</p>
 */
public final class RunConfig {

    public final String dataset;
    /** number of rows to read, -1 = all */
    public final int rows;
    /** weight of Succinctness: Score = alpha*Succ + (1-alpha)*Coverage */
    public final double alpha;
    public final double tau;
    /** batch size K of Algorithm 1 */
    public final int batchK;
    public final boolean noCrossColumn;
    public final double minimumSharedValue;
    /** master seed for all random components (sampling, coverage) */
    public final long seed;
    /** accepted pair samples of the coverage estimator */
    public final int coverageSamples;
    /** gamma of focused sampling: a method is kept while its smoothed yield is > gamma */
    public final double efficiencyThreshold;
    /** optional TSV dump of the result (null = none) */
    public final String dump;

    private static final String[] KEYS = {"dataset", "rows", "sgdc.alpha", "sgdc.tau", "sgdc.K", "noCrossColumn",
            "minimumSharedValue", "sgdc.seed", "sgdc.cov.n", "fs.gammaStop", "sgdc.dump"};

    private RunConfig(Map<String, String> m) {
        this.dataset = m.getOrDefault("dataset", "");
        this.rows = Integer.parseInt(m.getOrDefault("rows", "-1"));
        this.alpha = Double.parseDouble(m.getOrDefault("sgdc.alpha", "0.6"));
        this.tau = Double.parseDouble(m.getOrDefault("sgdc.tau", "0.55"));
        this.batchK = Integer.parseInt(m.getOrDefault("sgdc.K", "300"));
        this.noCrossColumn = Boolean.parseBoolean(m.getOrDefault("noCrossColumn", "true"));
        this.minimumSharedValue = Double.parseDouble(m.getOrDefault("minimumSharedValue", "0.15"));
        this.seed = Long.parseLong(m.getOrDefault("sgdc.seed", "1"));
        this.coverageSamples = Integer.parseInt(m.getOrDefault("sgdc.cov.n", "10000"));
        this.efficiencyThreshold = Double.parseDouble(m.getOrDefault("fs.gammaStop", "0.04"));
        String d = m.get("sgdc.dump");
        this.dump = (d == null || d.isEmpty()) ? null : d;
        if (alpha < 0 || alpha > 1) throw new IllegalArgumentException("sgdc.alpha must be in [0,1]: " + alpha);
        if (batchK < 1) throw new IllegalArgumentException("sgdc.K must be >= 1: " + batchK);
        if (coverageSamples < 1) throw new IllegalArgumentException("sgdc.cov.n must be >= 1: " + coverageSamples);
    }

    public static RunConfig defaults() {
        return new RunConfig(new LinkedHashMap<>());
    }

    public static RunConfig from(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : KEYS) {
            String v = System.getProperty(k);
            if (v != null) m.put(k, v);
        }
        if (args != null) {
            for (String a : args) {
                if (a.startsWith("--")) {
                    int eq = a.indexOf('=');
                    if (eq < 0) throw new IllegalArgumentException("expected --key=value, got: " + a);
                    String k = a.substring(2, eq), v = a.substring(eq + 1);
                    boolean known = false;
                    for (String kk : KEYS) known |= kk.equals(k);
                    if (known) {
                        m.put(k, v);
                    } else if (k.startsWith("fs.") || k.startsWith("rs.") || k.startsWith("sgdc.")
                            || k.startsWith("input.") || k.startsWith("inject.")) {
                        System.setProperty(k, v);
                    } else {
                        throw new IllegalArgumentException("unknown option: " + a);
                    }
                } else if (!a.isEmpty()) {
                    if (m.containsKey("dataset") && !m.get("dataset").isEmpty() && !a.equals(m.get("dataset")))
                        throw new IllegalArgumentException("more than one dataset given: " + m.get("dataset") + ", " + a);
                    m.put("dataset", a);
                }
            }
        }
        return new RunConfig(m);
    }

    public PruningFactors factors() {
        return new PruningFactors(alpha, 1 - alpha, tau);
    }

    @Override
    public String toString() {
        return "RunConfig{dataset=" + dataset + ", rows=" + rows + ", alpha=" + alpha + ", tau=" + tau
                + ", K=" + batchK + ", noCrossColumn=" + noCrossColumn + ", minimumSharedValue=" + minimumSharedValue
                + ", seed=" + seed + ", covSamples=" + coverageSamples + ", gammaStop=" + efficiencyThreshold
                + ", upper=" + DCUtil.UPPER_MODE + ", dump=" + dump + "}";
    }
}

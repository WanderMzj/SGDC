import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sgdc.dc.algorithms.sgdc.SgdcRunner;
import sgdc.dc.coverage.CoverageProvider;
import sgdc.dc.denialcontraints.DenialConstraint;
import sgdc.dc.denialcontraints.DenialConstraintSet;
import sgdc.dc.input.Input;
import sgdc.dc.input.RelationalInput;
import sgdc.dc.predicates.PredicateBuilder;
import sgdc.dc.util.DCUtil;
import sgdc.dc.util.PruningFactors;
import sgdc.dc.util.RunConfig;

/**
 * Command line entry point.
 *
 * <pre>
 * java -cp ... Main [--dataset=]file.csv [--rows=N] [--sgdc.alpha=0.6] [--sgdc.tau=0.55] [--sgdc.K=300]
 *      [--sgdc.seed=1] [--sgdc.cov.n=10000] [--fs.gammaStop=0.04] [--sgdc.dump=out.tsv]
 *      [--noCrossColumn=true] [--minimumSharedValue=0.15] [--fs.*=..] [--rs.*=..] [--input.*=..]
 * </pre>
 * Every option can also be given as a JVM system property (-Dkey=value); the command line wins.
 */
public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws IOException {
        sgdc.dc.util.HeapWatch.start();
        RunConfig cfg;
        try {
            cfg = RunConfig.from(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
            return;
        }
        if (cfg.dataset.isEmpty()) {
            System.err.println("usage: Main [--dataset=]<file.csv> [--key=value ...]");
            System.exit(2);
        }
        log.info("{}", cfg);

        Input input = new Input(new RelationalInput(new File(cfg.dataset)), cfg.rows, -1);
        PredicateBuilder predicates = new PredicateBuilder(input, cfg.noCrossColumn, cfg.minimumSharedValue);
        log.info("predicate space size: {}", predicates.getPredicates().size());

        long t0 = System.currentTimeMillis();
        SgdcRunner runner = new SgdcRunner(cfg);
        DenialConstraintSet dcs = runner.run(input, predicates);
        long ms = System.currentTimeMillis() - t0;
        log.info("total runtime: {}ms ({})", ms, runner.timings());

        System.out.println("RESULT_DISCOVERY_MS " + ms);
        System.out.println("RESULT_P " + predicates.getPredicates().size());
        System.out.println("RESULT_DCS " + dcs.size());
        System.out.println("RESULT_TIMINGS " + runner.timings());
        CoverageProvider cov = runner.getCoverageProvider();
        System.out.println("RESULT_COV_CAPPED " + cov.cappedEstimates() + " minAccepted=" + cov.minAccepted());
        boolean incomplete = runner.getSearchStats() != null && runner.getSearchStats().incomplete;
        System.out.println("RESULT_INCOMPLETE " + incomplete);
        System.out.println("RESULT_HEAP " + sgdc.dc.util.HeapWatch.report());

        if (cfg.dump != null) {
            PruningFactors f = cfg.factors();
            CoverageProvider cp = runner.getCoverageProvider();
            // sorted by the DC string: the output set is a HashSet, this makes the file byte-identical across runs
            java.util.List<DenialConstraint> sorted = new java.util.ArrayList<>(dcs.getConstraints());
            sorted.sort(java.util.Comparator.comparing(DenialConstraint::toString));
            try (PrintWriter w = new PrintWriter(cfg.dump, StandardCharsets.UTF_8.name())) {
                w.println("size\tsucc\tcoverage\tscore\tdc");
                for (DenialConstraint dc : sorted) {
                    double cov1 = runner.getExactCoverage() != null ? runner.getExactCoverage().coverage(dc) : cp.calculate(dc);
                    // full double precision (round-trip), so that Score >= tau can be re-checked from the file
                    w.printf(Locale.ROOT, "%d\t%s\t%s\t%s\t%s%n", dc.size(), DCUtil.getSuccinctness(dc), cov1,
                            f.score(dc, cov1), dc);
                }
            }
        }
        if (incomplete) {
            log.warn("verification budget exhausted: the output is incomplete (exit code 3)");
            System.exit(3);
        }
    }
}

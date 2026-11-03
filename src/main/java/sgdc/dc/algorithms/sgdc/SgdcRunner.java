package sgdc.dc.algorithms.sgdc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sgdc.dc.coverage.CoverageProvider;
import sgdc.dc.coverage.ExactCoverage;
import sgdc.dc.denialcontraints.DenialConstraint;
import sgdc.dc.util.PruningFactors;
import sgdc.dc.denialcontraints.DenialConstraintSet;
import sgdc.dc.evidenceset.HashEvidenceSet;
import sgdc.dc.evidenceset.IEvidenceSet;
import sgdc.dc.evidenceset.build.sampling.FocusedSampling;
import sgdc.dc.evidenceset.build.sampling.RandomSampling;
import sgdc.dc.input.Input;
import sgdc.dc.predicates.PredicateBuilder;
import sgdc.dc.util.RunConfig;

/**
 * One SGDC run: random sampling -> focused sampling -> Algorithm 1.
 *
 * <p>Everything is per instance (the original kept the dataset, the coverage provider and a sample
 * counter in static fields, hard-coded K = 300 and ran an extra minimize() on the already minimal
 * output). All random components are seeded from {@link RunConfig#seed}.
 *
 * <p>Focused sampling budget B: fs.maxAttempts if > 0, otherwise
 * clamp(fs.budgetScale * |r| * |R|, fs.amin, fs.amax) with fs.budgetScale (alias fs.gamma, default 0.5),
 * fs.amin (1,000,000) and fs.amax (20,000,000).
 */
public class SgdcRunner {

    private final RunConfig cfg;
    private CoverageProvider coverageProvider;
    private SGDC.Stats searchStats;
    private long msRandom, msFocused, msSearch, msExact = -1;
    private int exactCandidates = -1;
    private ExactCoverage exactCoverage;
    // 默认关闭：阈值附近的精确重算不属于原论文算法（论文的输出完全由估计分数决定），实验统一在关闭状态下进行
    public static final boolean EXACT = Boolean.parseBoolean(System.getProperty("sgdc.exact", "false"));
    /** score margin of the search when the exact post-step is on (search threshold = tau - margin) */
    public static final double MARGIN = Double.parseDouble(System.getProperty("sgdc.exact.margin", "0.01"));

    public SgdcRunner(RunConfig cfg) {
        this.cfg = cfg;
    }

    public DenialConstraintSet run(Input input, PredicateBuilder predicates) {
        String fixMode = System.getProperty("sgdc.fixIndex", "true");
        if (!"false".equals(fixMode)) {
            // predicate bit indices are otherwise assigned in first-seen order by the (parallel) sampling,
            // which makes the child order and hence the queue tie-break differ between runs
            // Predicate/ParsedColumn/Operator hash by identity, so the HashSet order differs between JVM runs:
            // assign indices in the order of the predicate strings
            java.util.List<sgdc.dc.predicates.Predicate> ps = new java.util.ArrayList<>(predicates.getPredicates());
            ps.sort(java.util.Comparator.comparing(Object::toString));
            // ablation: -Dsgdc.fixIndex=reverse | shuffle<seed> | opfirst (operator-major order)
            if ("reverse".equals(fixMode)) java.util.Collections.reverse(ps);
            else if (fixMode.startsWith("shuffle")) java.util.Collections.shuffle(ps, new java.util.Random(Long.parseLong(fixMode.substring(7))));
            else if ("opfirst".equals(fixMode)) ps.sort(java.util.Comparator.comparing((sgdc.dc.predicates.Predicate p) -> p.getOperator().toString()).thenComparing(Object::toString));
            for (sgdc.dc.predicates.Predicate p : ps) sgdc.dc.predicates.sets.PredicateBitSet.getIndex(p);
        }
        coverageProvider = new CoverageProvider(input, predicates, cfg.coverageSamples, cfg.seed);

        long t0 = System.currentTimeMillis();
        RandomSampling rs = new RandomSampling(predicates, cfg.seed);
        IEvidenceSet sampleEvidence = rs.buildEvidenceSet(input);
        msRandom = System.currentTimeMillis() - t0;
        log.info("random sampling time: {}ms", msRandom);

        HashEvidenceSet evidence = new HashEvidenceSet();
        for (sgdc.dc.predicates.sets.PredicateBitSet ps : sampleEvidence) evidence.add(ps);

        long t1 = System.currentTimeMillis();
        long budget = focusedBudget(input);
        new FocusedSampling(predicates, cfg.seed).buildEvidenceSet(evidence, input, cfg.efficiencyThreshold, budget);
        msFocused = System.currentTimeMillis() - t1;
        log.info("focused sampling time: {}ms", msFocused);

        long t2 = System.currentTimeMillis();
        // exact post-step (-Dsgdc.exact=true): the search runs with threshold tau - margin, every output DC is
        // then re-scored with its exact Coverage on the full relation and kept iff Score >= tau
        PruningFactors f = cfg.factors();
        PruningFactors searchF = EXACT ? new PruningFactors(f.a, f.b, f.threshold - MARGIN) : f;
        SGDC sgdc = new SGDC(predicates, coverageProvider, new Verification(input, predicates), searchF);
        DenialConstraintSet dcs = sgdc.discoverDCs(sampleEvidence, evidence, cfg.batchK);
        searchStats = sgdc.getStats();
        msSearch = System.currentTimeMillis() - t2;
        log.info("SGDC search time: {}ms", msSearch);
        log.info("{}", coverageProvider.stats());
        if (!EXACT) return dcs;

        long t3 = System.currentTimeMillis();
        exactCoverage = new ExactCoverage(input);
        DenialConstraintSet out = new DenialConstraintSet();
        int before = 0, dropped = 0;
        for (DenialConstraint dc : dcs) {
            before++;
            if (f.accepts(f.score(dc, exactCoverage.coverage(dc)))) out.add(dc); else dropped++;
        }
        msExact = System.currentTimeMillis() - t3;
        exactCandidates = before;
        log.info("exact rescoring: margin={} candidates={} kept={} dropped={} predicates={} time={}ms",
                MARGIN, before, out.size(), dropped, exactCoverage.cachedPredicates(), msExact);
        return out;
    }

    static long focusedBudget(Input input) {
        long fixed = Long.getLong("fs.maxAttempts", -1L);
        if (fixed > 0) return fixed;
        double scale = Double.parseDouble(System.getProperty("fs.budgetScale", System.getProperty("fs.gamma", "0.5")));
        long amin = Long.getLong("fs.amin", 1_000_000L);
        long amax = Long.getLong("fs.amax", 20_000_000L);
        if (amin > amax) throw new IllegalArgumentException("fs.amin > fs.amax");
        long auto = (long) Math.ceil(scale * (double) input.getLineCount() * (double) input.getColumns().length);
        return Math.max(amin, Math.min(amax, auto));
    }

    /** exact coverage of the post-step, null if it is off */
    public ExactCoverage getExactCoverage() {
        return exactCoverage;
    }

    public CoverageProvider getCoverageProvider() {
        return coverageProvider;
    }

    public SGDC.Stats getSearchStats() {
        return searchStats;
    }

    public String timings() {
        return "randomSampling=" + msRandom + "ms focusedSampling=" + msFocused + "ms search=" + msSearch + "ms" + (msExact >= 0 ? " exact=" + msExact + "ms (candidates=" + exactCandidates + ")" : "");
    }

    private static final Logger log = LoggerFactory.getLogger(SgdcRunner.class);
}

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
import sgdc.dc.util.DCUtil;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Reference scoring of an arbitrary DC list with a high-precision SAMPLED coverage estimate (independent seed,
 * ref.n accepted samples, default 200,000, Hoeffding half-width about 0.003 at 95%).
 * Historical: the ground truth D_tau of the experiments is now built by {@link ExactRef} with the exact
 * Coverage over all ordered pairs; this class is kept to reproduce the earlier sampled reference and the
 * coverage_old column.
 *
 * input lines: "t0.A(Type) op t1.A(Type) ^ ..." (a leading "¬(" / trailing ")" and a table-name
 * prefix such as "airport.csv." are tolerated).
 * The column coverage_old is the value the ORIGINAL SGDC code computes, evaluated on the same
 * number of uniform pairs without rejection: a pair satisfying k predicates counts k/m
 * (k = 1..m-1), 1/m if k = 0 and (m-1)/m if k = m. It is used to test which Coverage definition
 * the paper's "exact-score" ground truth corresponds to.
 *
 * usage: java -Ddataset=x.csv -Din=dcs.txt -Dout=scored.tsv [-Dref.n=200000] RefScore
 */
public class RefScore {
    public static void main(String[] args) throws Exception {
        String ds = System.getProperty("dataset");
        Input input = new Input(new RelationalInput(new File(ds)), Integer.getInteger("rows", -1), -1);
        PredicateBuilder pb = new PredicateBuilder(input, true, 0.15d);
        Map<String, Predicate> byName = new HashMap<>();
        for (Predicate p : pb.getPredicates()) {
            byName.put(p.toString(), p);
            PredicateBitSet.getIndex(p);
        }
        String table = new File(ds).getName() + ".";
        int n = Integer.getInteger("ref.n", 200_000);
        CoverageProvider ref = new CoverageProvider(input, pb, n, Long.getLong("ref.seed", 20260924L));

        try (PrintWriter w = new PrintWriter(System.getProperty("out"), "UTF-8")) {
            w.println("size\tsucc\tcoverage\tcoverage_old\tdc");
            PairSample all = new PairSample(input, Long.getLong("ref.seed", 20260924L) + 1, n, n);
            for (String line : Files.readAllLines(new File(System.getProperty("in")).toPath(), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("size\t")) continue;
                if (line.contains("\t")) line = line.substring(line.lastIndexOf('\t') + 1);
                if (line.startsWith("¬(")) line = line.substring(2, line.length() - 1);
                line = line.replace(table, "");
                IBitSet S = LongBitSet.FACTORY.create();
                boolean ok = true;
                for (String part : line.split(" \\^ ")) {
                    Predicate p = byName.get(part.trim());
                    if (p == null) { ok = false; System.err.println("unknown predicate: " + part); break; }
                    S.set(PredicateBitSet.getIndex(p));
                }
                if (!ok) continue;
                DenialConstraint dc = new DenialConstraint(PredicateSetFactory.create(S));
                w.printf(Locale.ROOT, "%d\t%.5f\t%.5f\t%.5f\t%s%n", dc.size(), DCUtil.getSuccinctness(dc), ref.calculate(S), legacyCoverage(all, S), dc);
            }
        }
        System.out.println("RefScore done: " + ref.stats());
    }

    /** Original-code Coverage (see class comment) over all pairs of the sample. */
    static double legacyCoverage(PairSample all, IBitSet S) {
        int m = S.cardinality();
        if (m <= 1) return 1.0;
        int[] idx = new int[m]; int q = 0;
        for (int b = S.nextSetBit(0); b >= 0; b = S.nextSetBit(b + 1)) idx[q++] = b;
        long[][] bm = new long[m][];
        for (int i = 0; i < m; i++) bm[i] = all.bitmap(idx[i]);
        long[] cntK = new long[m + 1];
        for (int w = 0; w < all.words(); w++) {
            for (int bit = 0; bit < 64; bit++) {
                int k = 0;
                for (int i = 0; i < m; i++) k += (int) ((bm[i][w] >>> bit) & 1L);
                cntK[k]++;
            }
        }
        double v = 0;
        for (int k = 0; k <= m; k++) v += cntK[k] * (k == 0 ? 1.0 : (k == m ? m - 1.0 : k)) / m;
        return v / all.size();
    }
}

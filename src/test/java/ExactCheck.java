import sgdc.dc.coverage.ExactCoverage;
import sgdc.dc.denialcontraints.DenialConstraint;
import sgdc.dc.input.Input;
import sgdc.dc.input.RelationalInput;
import sgdc.dc.predicates.Predicate;
import sgdc.dc.predicates.PredicateBuilder;
import sgdc.dc.predicates.sets.PredicateBitSet;
import sgdc.dc.predicates.sets.PredicateSetFactory;
import ch.javasoft.bitset.IBitSet;
import ch.javasoft.bitset.LongBitSet;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/** usage: ExactCheck data.csv ref.tsv rows  (rows>0: brute-force check on the first rows; rows<=0: full data vs ref column) */
public class ExactCheck {
    public static void main(String[] a) throws Exception {
        int rows = Integer.parseInt(a[2]);
        Input input = new Input(new RelationalInput(new File(a[0])), rows, -1);
        PredicateBuilder pb = new PredicateBuilder(input, true, 0.15d);
        Map<String, Predicate> byName = new HashMap<>();
        for (Predicate p : pb.getPredicates()) byName.put(p.toString(), p);
        String table = new File(a[0]).getName() + ".";
        ExactCoverage ex = new ExactCoverage(input);
        double maxErr = 0, maxRefDiff = 0, sumRefDiff = 0; int n = 0, viol = 0, skipped = 0;
        long t0 = System.nanoTime();
        for (String line : Files.readAllLines(new File(a[1]).toPath())) {
            if (line.startsWith("size\t")) continue;
            String[] f = line.split("\t");
            String dcs = f[f.length - 1];
            if (dcs.startsWith("¬(")) dcs = dcs.substring(2, dcs.length() - 1);
            dcs = dcs.replace(table, "");
            IBitSet S = LongBitSet.FACTORY.create(); boolean ok = true;
            for (String part : dcs.split(" \\^ ")) { Predicate p = byName.get(part.trim()); if (p == null) { ok = false; break; } S.set(PredicateBitSet.getIndex(p)); }
            if (!ok) { skipped++; continue; }
            DenialConstraint dc = new DenialConstraint(PredicateSetFactory.create(S));
            double c = ex.coverage(dc);
            if (rows > 0) {
                double[] b = ex.bruteForce(dc);
                if (b[1] > 0) { viol++; continue; }
                maxErr = Math.max(maxErr, Math.abs(b[0] - c));
            } else {
                double d = Math.abs(c - Double.parseDouble(f[2])); maxRefDiff = Math.max(maxRefDiff, d); sumRefDiff += d;
            }
            n++;
            if (rows <= 0 && n >= 3000) break;
        }
        System.out.printf(Locale.ROOT, "%s rows=%d checked=%d skipped=%d violatedOnSubset=%d maxAbsErr(closed vs brute)=%.3g maxDiff(exact vs ref-sample)=%.4f meanDiff=%.4f time=%dms%n",
                new File(a[0]).getName(), input.getLineCount(), n, skipped, viol, maxErr, maxRefDiff, n > 0 ? sumRefDiff / n : 0, (System.nanoTime() - t0) / 1_000_000);
    }
}

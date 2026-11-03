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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Exact ground truth: re-scores a list of VALID DCs (e.g. all minimal valid DCs found by Hydra) with the exact
 * Coverage over all ordered pairs of the relation (see {@link ExactCoverage}).
 * usage: java -Ddataset=x.csv -Din=hydra_ref.tsv -Dout=exact_ref.tsv sgdc.dc.coverage.ExactRef
 */
public class ExactRef {
    public static void main(String[] args) throws Exception {
        String ds = System.getProperty("dataset");
        Input input = new Input(new RelationalInput(new File(ds)), -1, -1);
        PredicateBuilder pb = new PredicateBuilder(input, true, 0.15d);
        Map<String, Predicate> byName = new HashMap<>();
        for (Predicate p : pb.getPredicates()) byName.put(p.toString(), p);
        String table = new File(ds).getName() + ".";
        ExactCoverage ex = new ExactCoverage(input);
        int n = 0, bad = 0;
        try (PrintWriter w = new PrintWriter(System.getProperty("out"), "UTF-8")) {
            w.println("size\tsucc\tcoverage\tdc");
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
                if (!ok) { bad++; continue; }
                DenialConstraint dc = new DenialConstraint(PredicateSetFactory.create(S));
                w.printf(Locale.ROOT, "%d\t%.5f\t%.8f\t%s%n", dc.size(), DCUtil.getSuccinctness(dc), ex.coverage(dc), dc);
                n++;
            }
        }
        System.out.println("ExactRef done: " + n + " DCs, " + bad + " unparsed, " + ex.millis() + " ms");
    }
}

package sgdc.dc.algorithms.sgdc;

import ch.javasoft.bitset.IBitSet;
import ch.javasoft.bitset.LongBitSet;
import ch.javasoft.bitset.search.NTreeSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sgdc.dc.coverage.CoverageProvider;
import sgdc.dc.denialcontraints.DenialConstraint;
import sgdc.dc.denialcontraints.DenialConstraintSet;
import sgdc.dc.evidenceset.IEvidenceSet;
import sgdc.dc.helpers.BitSetTable;
import sgdc.dc.predicates.Predicate;
import sgdc.dc.predicates.PredicateBuilder;
import sgdc.dc.predicates.sets.PredicateBitSet;
import sgdc.dc.predicates.sets.PredicateSetFactory;
import sgdc.dc.util.DCUtil;
import sgdc.dc.util.PruningFactors;

import java.util.*;

/**
 * Score-guided DC search (paper Algorithm 1).
 *
 * <p>Search nodes are (S, f): S is a predicate set, f = true iff S descends from a candidate whose batch
 * validation failed <em>completely</em>. After such a validation, E contains the evidence of every tuple
 * pair violating that candidate, hence for all supersets T: U_E(T) = ∅ ⇔ T is valid, so they need no
 * further validation.</p>
 *
 * <pre>
 * PQ ← {(∅, false)}   ordered by Score(φ(S)) desc
 * while PQ ≠ ∅ or Φpend ≠ ∅:
 *   if PQ = ∅: VALIDATE(Φpend)                      (final flush, SFALSE are re-queued as well)
 *   (S, f) ← pop; skip if ∃ M ∈ MinSet: M ⊆ S
 *   U ← {e ∈ E : S ⊆ e}
 *   if U = ∅:
 *     if Score(φ(S)) ≥ τ:  f ? D ∪= {S} : Φpend ∪= {S};   if |Φpend| ≥ K: VALIDATE(Φpend)
 *   else: pick e ∈ U; for each free group g, p ∈ g \ e: push (S ∪ {p}, f) if ScoreUpper(S ∪ {p}) ≥ τ
 * VALIDATE: exact check on the full relation → E_new, STRUE, SFALSE;
 *   E ∪= E_new; for S ∈ STRUE by |S|: if no subset in MinSet: remove supersets from MinSet/D, add S;
 *   for S ∈ SFALSE: push (S, true) if ScoreUpper(S) ≥ τ
 * </pre>
 *
 * Fixes of the original implementation (defect numbers of the audit):
 * #1 queue ordered by Score (ub only prunes), #2 f flag, #3 failed candidates are re-queued,
 * #4 MinSet only holds validated sets, #5 only validated DCs are output, #6 Score ≥ τ,
 * #7 no stdout debug output, separate timings, #8 no NPE in the timing report, #9 the visited set only
 * holds pushed nodes, #10 minimize runs once at the end.
 */
public class SGDC {

    private static final Logger log = LoggerFactory.getLogger(SGDC.class);

    private static final class Node {
        final IBitSet S;
        final boolean f;
        final double score;
        final double ub;
        final long seq;
        final int card;

        Node(IBitSet s, boolean f, double score, double ub, long seq) {
            this.S = s;
            this.f = f;
            this.score = score;
            this.ub = ub;
            this.seq = seq;
            this.card = s.cardinality();
        }
    }

    private static final Comparator<Node> ORDER = (a, b) -> {
        int c = Double.compare(b.score, a.score);           // Score desc (paper)
        if (c != 0) return c;
        c = Integer.compare(a.card, b.card);                  // smaller first
        if (c != 0) return c;
        c = Double.compare(b.ub, a.ub);
        if (c != 0) return c;
        return Long.compare(a.seq, b.seq);                    // FIFO, deterministic
    };

    /** Counters and timings of one run (ns). */
    public static final class Stats {
        public long popped, pushed, prunedByUb, prunedByMinSet, complete, belowTau, directAccepted, lowValidated, lowBlockers;
        public long batches, validated, deferred, strue, sfalse, requeued, unverified, redundant, newEvidence, droppedQueued;
        /** true iff the verification budget ran out: the output is then neither complete nor guaranteed minimal */
        public boolean incomplete;
        public long nsScore, nsValidate, nsBranch, nsTotal;
        public int initialEvidence, finalEvidence, output;

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "popped=%d pushed=%d prunedUb=%d prunedMinSet=%d complete=%d belowTau=%d directAccepted=%d lowValidated=%d lowBlockers=%d "
                            + "batches=%d validated=%d deferred=%d STRUE=%d SFALSE=%d requeued=%d unverified=%d droppedQueued=%d redundant=%d "
                            + "evidence %d->%d (+%d) output=%d incomplete=%b | total=%.1fms score=%.1fms validate=%.1fms branch=%.1fms",
                    popped, pushed, prunedByUb, prunedByMinSet, complete, belowTau, directAccepted, lowValidated, lowBlockers, batches, validated, deferred,
                    strue, sfalse, requeued, unverified, droppedQueued, redundant, initialEvidence, finalEvidence, newEvidence, output, incomplete,
                    nsTotal / 1e6, nsScore / 1e6, nsValidate / 1e6, nsBranch / 1e6);
        }
    }

    private final PredicateBuilder predicates;
    private final CoverageProvider coverageProvider;
    private final Verification verifier;
    private final PruningFactors factors;
    private final List<IBitSet> groupBits;

    private Stats stats = new Stats();

    // per-run state
    private PriorityQueue<Node> pq;
    private BitSetTable visited;                         // compact: keys stored inline (see BitSetTable)
    /* nodes already pruned by a sound (monotone) upper bound: once ScoreUpper(S) < tau it stays < tau, since the bound
       only uses S's own cached estimate or cached parents (their set only grows); re-evaluation is skipped.
       -Dsgdc.prunedMemo=false restores the original behaviour. */
    private static final boolean PRUNED_MEMO = Boolean.parseBoolean(System.getProperty("sgdc.prunedMemo", "true"))
            && "safe".equalsIgnoreCase(DCUtil.UPPER_MODE);
    private BitSetTable pruned;
    /* -Dsgdc.detEvidence (default true): evidences are indexed in a fixed order (search independent of hash/thread order) */
    private static final boolean DET_EVIDENCE = Boolean.parseBoolean(System.getProperty("sgdc.detEvidence", "true"));
    /* -Dsgdc.branchScan=k (default 1 = original: first uncovered evidence) */
    private static final boolean EXPLICIT_HINT = Boolean.parseBoolean(System.getProperty("sgdc.explicitHint", "false"));
    private static final int BRANCH_SCAN = Integer.getInteger("sgdc.branchScan", 1);
    /* -Dsgdc.validateLow (default true): a complete node with Score < tau is validated as well (not output). If valid it
       enters MinSet only as a blocker (its supersets are non-minimal); if invalid it is re-queued with f = true, so its
       valid supersets with Score >= tau stay reachable. false = original behaviour (the node is dropped). */
    private static final boolean VALIDATE_LOW = Boolean.parseBoolean(System.getProperty("sgdc.validateLow", "true"));
    private Set<IBitSet> blockOnly;
    private static final Comparator<IBitSet> BITS_ORDER = (a, b) -> {
        int x = a.nextSetBit(0), y = b.nextSetBit(0);
        while (x >= 0 && y >= 0) {
            if (x != y) return x < y ? -1 : 1;
            x = a.nextSetBit(x + 1); y = b.nextSetBit(y + 1);
        }
        return x == y ? 0 : (x < 0 ? -1 : 1);
    };
    public long prunedMemoHits = 0;
    private EvidenceIndex index;
    private NTreeSearch minSet;
    private Map<IBitSet, DenialConstraint> dcs;          // D (validated, minimal w.r.t. MinSet)
    private LinkedHashMap<IBitSet, DenialConstraint> pend;
    private int m;
    private long seq;

    public SGDC(PredicateBuilder predicates, CoverageProvider coverageProvider, Verification verifier,
                PruningFactors factors) {
        this.predicates = predicates;
        this.coverageProvider = coverageProvider;
        this.verifier = verifier;
        this.factors = factors;
        this.groupBits = buildPredicateGroupBitsets(predicates);
    }

    public Stats getStats() {
        return stats;
    }

    /**
     * @param sampleEvidence  random-sample evidence with counts (selectivities for validation planning)
     * @param initialEvidence evidence set E after focused sampling
     * @param batchK          validation batch size K
     */
    public DenialConstraintSet discoverDCs(IEvidenceSet sampleEvidence, IEvidenceSet initialEvidence, int batchK) {
        final long start = System.nanoTime();
        stats = new Stats();
        index = new EvidenceIndex(PredicateBitSet.indexProvider.size());
        if (DET_EVIDENCE) {
            List<IBitSet> ev = new ArrayList<>();
            Map<IBitSet, Long> cnt = new HashMap<>();
            for (PredicateBitSet ps : initialEvidence) { ev.add(ps.getBitset()); cnt.put(ps.getBitset(), initialEvidence.getCount(ps)); }
            ev.sort(BITS_ORDER);
            // ablation of the branching evidence: -Dsgdc.evOrder=bits (default) | freq | rfreq | card | rcard | shuffle<seed>
            String eo = System.getProperty("sgdc.evOrder", "sfreq");
            if (eo.equals("sfreq") || eo.equals("rsfreq")) {
                // frequency in the random pair sample (true pair counts; independent of predicate numbering)
                Map<IBitSet, Long> sc = new HashMap<>();
                for (PredicateBitSet ps : sampleEvidence) sc.merge(ps.getBitset(), sampleEvidence.getCount(ps), Long::sum);
                long hit = ev.stream().filter(sc::containsKey).count();
                log.info("sfreq: {} of {} initial evidences occur in the random sample", hit, ev.size());
                int sign = eo.equals("sfreq") ? -1 : 1;
                ev.sort(Comparator.comparingLong((IBitSet e) -> sign * sc.getOrDefault(e, 0L)).thenComparing(BITS_ORDER));
            } else if ("freq".equals(eo)) ev.sort(Comparator.comparingLong((IBitSet e) -> -cnt.get(e)).thenComparing(BITS_ORDER));
            else if ("rfreq".equals(eo)) ev.sort(Comparator.comparingLong((IBitSet e) -> cnt.get(e)).thenComparing(BITS_ORDER));
            else if ("card".equals(eo)) ev.sort(Comparator.comparingInt((IBitSet e) -> e.cardinality()).thenComparing(BITS_ORDER));
            else if ("rcard".equals(eo)) ev.sort(Comparator.comparingInt((IBitSet e) -> -e.cardinality()).thenComparing(BITS_ORDER));
            else if (eo.startsWith("shuffle")) Collections.shuffle(ev, new Random(Long.parseLong(eo.substring(7))));
            log.info("evidence order {}: {} initial evidences", eo, ev.size());
            for (IBitSet e : ev) index.addEvidence(e);
        } else {
            index.addAll(initialEvidence);
        }
        stats.initialEvidence = index.size();
        m = predicates.getPredicateGroups().size();
        pq = new PriorityQueue<>(ORDER);
        int words = BitSetTable.wordsFor(PredicateBitSet.indexProvider.size() - 1);   // indices are fixed before the search
        visited = new BitSetTable(words, 0);
        pruned = new BitSetTable(words, 0);
        blockOnly = new HashSet<>();
        minSet = new NTreeSearch();
        dcs = new LinkedHashMap<>();
        pend = new LinkedHashMap<>();
        seq = 0;
        final double tau = factors.threshold;

        IBitSet empty = LongBitSet.FACTORY.create();
        pq.add(new Node(empty, false, Double.POSITIVE_INFINITY, ub(empty), seq++));

        while (!pq.isEmpty() || !pend.isEmpty()) {
            if (verifier.isExhausted()) {
                log.warn("verification time budget exhausted: search stopped, {} queued and {} pending candidates dropped",
                        pq.size(), pend.size());
                stats.unverified += pend.size();
                stats.droppedQueued += pq.size();
                stats.incomplete = true;
                pend.clear();
                break;
            }
            if (pq.isEmpty()) {                               // final flush (may re-queue SFALSE)
                validateBatch(sampleEvidence);
                continue;
            }
            Node cur = pq.poll();
            stats.popped++;
            IBitSet S = cur.S;

            // a validated DC M ⊆ S makes S and all its supersets non-minimal
            if (!S.isEmpty() && minSet.containsSubset(S)) {
                stats.prunedByMinSet++;
                continue;
            }

            if (index.isComplete(S) && !S.isEmpty()) {
                stats.complete++;
                double sc = cur.score;
                if (!factors.accepts(sc)) {
                    stats.belowTau++;
                    if (!VALIDATE_LOW) continue;
                    if (cur.f) {                              // exactly valid (f-semantics): blocker only
                        accept(S, null, false);
                    } else if (!pend.containsKey(S)) {
                        stats.lowValidated++;
                        IBitSet key = S.clone();
                        pend.put(key, new DenialConstraint(PredicateSetFactory.create(S)));
                        blockOnly.add(key);
                        if (pend.size() >= batchK) validateBatch(sampleEvidence);
                    }
                    continue;
                }
                if (cur.f) {                                  // exact: all violations of an ancestor are in E
                    stats.directAccepted++;
                    accept(S, new DenialConstraint(PredicateSetFactory.create(S)), true);
                } else if (!pend.containsKey(S)) {
                    pend.put(S.clone(), new DenialConstraint(PredicateSetFactory.create(S)));
                    if (pend.size() >= batchK) validateBatch(sampleEvidence);
                }
                continue;
            }

            long t0 = System.nanoTime();
            IBitSet U = index.computeUncovered(S);
            int eId = U.nextSetBit(0);
            if (BRANCH_SCAN > 1 && eId >= 0) {
                // any uncovered evidence must be hit by every valid superset of S: pick the one with the fewest
                // candidate predicates (predicates of free groups NOT contained in the evidence) among the first ones
                IBitSet free = LongBitSet.FACTORY.create();
                for (IBitSet g : groupBits) if (S.getAndCardinality(g) == 0) free.or(g);
                int freeCard = free.cardinality();
                int best = -1, bestCnt = Integer.MAX_VALUE, seen = 0;
                for (int e = eId; e >= 0 && seen < BRANCH_SCAN; e = U.nextSetBit(e + 1), seen++) {
                    int cnt = freeCard - index.evidenceAndCardinality(e, free);
                    if (cnt < bestCnt) { bestCnt = cnt; best = e; if (cnt <= 1) break; }
                }
                eId = best;
            }
            // S = ∅ and E = ∅: every predicate is a candidate
            IBitSet eBits = eId >= 0 ? index.getEvidenceBits(eId) : LongBitSet.FACTORY.create();
            if (EXPLICIT_HINT) coverageProvider.setParentHint(S);
            for (IBitSet g : groupBits) {
                if (S.getAndCardinality(g) > 0) continue;       // group already used
                IBitSet cand = g.clone();
                cand.andNot(eBits);                             // predicates of g violated by e
                for (int p = cand.nextSetBit(0); p >= 0; p = cand.nextSetBit(p + 1)) {
                    IBitSet S2 = S.clone();
                    S2.set(p);
                    push(S2, cur.f, tau);
                }
            }
            stats.nsBranch += System.nanoTime() - t0;
        }

        // ---- output: validated minimal DCs only
        DenialConstraintSet out = new DenialConstraintSet();
        for (DenialConstraint dc : dcs.values()) out.add(dc);
        out.minimize();                                         // implication / symmetry (t0 <-> t1)
        out = out.pruneWithProvider(factors, coverageProvider); // Score >= tau (no-op safety net)
        stats.finalEvidence = index.size();
        stats.output = out.size();
        stats.nsTotal = System.nanoTime() - start;
        log.info("SGDC search: {}", stats);
        log.info("Coverage: {} upper={} prunedMemo={} detEvidence={} branchScan={} hits={} size={}", coverageProvider.stats(), DCUtil.UPPER_MODE, PRUNED_MEMO, DET_EVIDENCE, BRANCH_SCAN, prunedMemoHits, pruned.size());
        log.info("Verification: {}", verifier.parallelStats());
        pruned = null;
        visited = null;
        pq = null;
        return out;
    }

    private double ub(IBitSet S) {
        return DCUtil.scoreUpper(S, m, coverageProvider, factors);
    }

    private double score(IBitSet S) {
        long t0 = System.nanoTime();
        double s = factors.score(new DenialConstraint(PredicateSetFactory.create(S)), coverageProvider.calculate(S));
        stats.nsScore += System.nanoTime() - t0;
        return s;
    }

    private void push(IBitSet S, boolean f, double tau) {
        if (visited.contains(S)) return;
        if (PRUNED_MEMO && pruned.contains(S)) { stats.prunedByUb++; prunedMemoHits++; return; }
        long t0 = System.nanoTime();
        double ub = ub(S);
        stats.nsScore += System.nanoTime() - t0;
        if (ub < tau) {                                         // not stored in visited (#9)
            stats.prunedByUb++;
            if (PRUNED_MEMO) pruned.add(S);                     // S is a fresh clone owned by push
            return;
        }
        visited.add(S);
        stats.pushed++;
        pq.add(new Node(S, f, score(S), ub, seq++));
    }

    /** D ∪= {S}; MinSet keeps only minimal validated sets. */
    private void accept(IBitSet S, DenialConstraint dc, boolean output) {
        if (minSet.containsSubset(S)) {
            stats.redundant++;
            return;
        }
        List<IBitSet> supers = new ArrayList<>();
        minSet.forEachSuperSet(S, supers::add);
        for (IBitSet sup : supers) {
            minSet.remove(sup);
            dcs.remove(sup);
        }
        IBitSet key = S.clone();
        minSet.add(key);
        if (output) dcs.put(key, dc);
        else stats.lowBlockers++;
    }

    private void validateBatch(IEvidenceSet sampleEvidence) {
        if (pend.isEmpty()) return;
        long t0 = System.nanoTime();
        stats.batches++;
        Map<IBitSet, DenialConstraint> batchMap = pend;
        pend = new LinkedHashMap<>();
        // Candidates with a proper subset in the same batch are deferred: once that subset is validated they are
        // either non-minimal (subset in MinSet) or exact (all violations of the subset are in E, f-semantics).
        NTreeSearch pendTree = new NTreeSearch();
        for (IBitSet S : batchMap.keySet()) pendTree.add(S);
        List<IBitSet> keys = new ArrayList<>();
        List<IBitSet> deferred = new ArrayList<>();
        for (IBitSet S : batchMap.keySet()) {
            boolean[] hasSub = {false};
            pendTree.forEachSubSet(S, T -> { if (!T.equals(S)) hasSub[0] = true; });
            (hasSub[0] ? deferred : keys).add(S);
        }
        Collection<DenialConstraint> batch = new ArrayList<>(keys.size());
        for (IBitSet S : keys) batch.add(batchMap.get(S));
        stats.validated += keys.size();
        stats.deferred += deferred.size();

        log.debug("batch {}: validating {} candidates (sizes {})", stats.batches + 0, keys.size(),
                keys.stream().mapToInt(IBitSet::cardinality).summaryStatistics());
        if (Boolean.getBoolean("sgdc.dbgSingle")) {
            for (IBitSet S : keys) {
                long t1 = System.nanoTime();
                Verification.Result r1 = verifier.verify(java.util.List.of(batchMap.get(S)), sampleEvidence);
                log.info("DBG single |S|={} complete={} violEv={} refinements={} {} ms  {}", S.cardinality(), r1.complete,
                        r1.violationEvidence.size(), r1.refinements, (System.nanoTime() - t1) / 1_000_000, batchMap.get(S));
            }
        }
        Verification.Result r = verifier.verify(batch, sampleEvidence);
        List<IBitSet> newEv = new ArrayList<>();
        for (PredicateBitSet ev : r.violationEvidence) newEv.add(ev.getBitset());
        if (DET_EVIDENCE) newEv.sort(BITS_ORDER);
        for (IBitSet ev : newEv) {
            if (index.addEvidence(ev)) stats.newEvidence++;
        }
        if (!r.complete) {
            // time budget exhausted: the batch is not validated, none of it is output; the main loop stops next
            stats.unverified += keys.size() + deferred.size();
            stats.incomplete = true;
            for (IBitSet S : batchMap.keySet()) blockOnly.remove(S);
            stats.nsValidate += System.nanoTime() - t0;
            return;
        }
        List<IBitSet> strue = new ArrayList<>();
        final double tau = factors.threshold;
        for (IBitSet S : keys) {
            if (index.isComplete(S)) {
                strue.add(S);
            } else {
                stats.sfalse++;
                double ub = ub(S);
                if (ub >= tau) {                                 // re-queue with f = true (#3)
                    stats.requeued++;
                    pq.add(new Node(S, true, score(S), ub, seq++));
                }
            }
        }
        stats.strue += strue.size();
        strue.sort(Comparator.comparingInt((IBitSet e) -> e.cardinality()));
        for (IBitSet S : strue) accept(S, batchMap.get(S), !blockOnly.contains(S));
        // deferred candidates: every pending subset was validated completely in this batch
        deferred.sort(Comparator.comparingInt((IBitSet e) -> e.cardinality()));
        for (IBitSet S : deferred) {
            if (minSet.containsSubset(S)) {
                stats.redundant++;
            } else if (index.isComplete(S)) {
                stats.directAccepted++;
                accept(S, batchMap.get(S), !blockOnly.contains(S));
            } else if (ub(S) >= tau) {
                stats.requeued++;
                pq.add(new Node(S, true, score(S), ub(S), seq++));
            }
        }
        for (IBitSet S : batchMap.keySet()) blockOnly.remove(S);
        stats.nsValidate += System.nanoTime() - t0;
        log.debug("batch {}: {} candidates (+{} deferred), {} true, E={}, refinements={}, {} ms", stats.batches, keys.size(), deferred.size(), strue.size(),
                index.size(), r.refinements, (System.nanoTime() - t0) / 1_000_000);
    }

    private static List<IBitSet> buildPredicateGroupBitsets(PredicateBuilder pb) {
        List<IBitSet> res = new ArrayList<>();
        for (Collection<Predicate> group : pb.getPredicateGroups()) {
            IBitSet bs = LongBitSet.FACTORY.create();
            for (Predicate p : group) bs.set(PredicateBitSet.getIndex(p));
            res.add(bs);
        }
        res.sort(Comparator.comparingInt(b -> b.nextSetBit(0)));        // deterministic group order
        return res;
    }
}

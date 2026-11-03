package sgdc.dc.algorithms.sgdc;

import ch.javasoft.bitset.IBitSet;
import ch.javasoft.bitset.LongBitSet;
import sgdc.dc.predicates.sets.PredicateBitSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inverted index over the current evidence set E: postings[p] = ids of evidences that satisfy predicate p.
 * U(S) = {e in E : S ⊆ e} is the set of evidences NOT covered by the DC ¬(S); S is complete (a DC w.r.t. E)
 * iff U(S) is empty. Evidences are deduplicated; the internal lists are private, callers only get copies.
 */
public final class EvidenceIndex {

    private final List<IBitSet> evidences = new ArrayList<>();
    private final Map<IBitSet, Integer> ids = new HashMap<>();
    private IBitSet[] postings;
    private final IBitSet allEvidenceIds = LongBitSet.FACTORY.create();

    public EvidenceIndex(int predCount) {
        this.postings = new IBitSet[Math.max(1, predCount)];
    }

    public int size() {
        return evidences.size();
    }

    /** @return a copy of the evidence bitset with the given id */
    public IBitSet getEvidenceBits(int evidenceId) {
        return evidences.get(evidenceId).clone();
    }

    public int evidenceAndCardinality(int evidenceId, IBitSet with) {
        return evidences.get(evidenceId).getAndCardinality(with);
    }

    public boolean contains(IBitSet eBits) {
        return ids.containsKey(eBits);
    }

    /** Adds all evidences that are not yet indexed. @return the newly added evidences */
    public List<IBitSet> addAll(Iterable<PredicateBitSet> evidenceSet) {
        List<IBitSet> added = new ArrayList<>();
        for (PredicateBitSet ps : evidenceSet) {
            if (addEvidence(ps.getBitset())) added.add(ps.getBitset());
        }
        return added;
    }

    /** @return true iff the evidence was new */
    public boolean addEvidence(IBitSet eBits) {
        if (ids.containsKey(eBits)) return false;
        IBitSet copy = eBits.clone();
        int id = evidences.size();
        evidences.add(copy);
        ids.put(copy, id);
        allEvidenceIds.set(id);
        for (int p = copy.nextSetBit(0); p >= 0; p = copy.nextSetBit(p + 1)) {
            if (p >= postings.length) postings = Arrays.copyOf(postings, Math.max(p + 1, postings.length * 2));
            if (postings[p] == null) postings[p] = LongBitSet.FACTORY.create();
            postings[p].set(id);
        }
        return true;
    }

    /** U(S) = { e | S ⊆ e }: intersection of the postings of all predicates in S. */
    public IBitSet computeUncovered(IBitSet S) {
        IBitSet u = allEvidenceIds.clone();
        for (int p = S.nextSetBit(0); p >= 0; p = S.nextSetBit(p + 1)) {
            if (p >= postings.length || postings[p] == null) return LongBitSet.FACTORY.create();
            u.and(postings[p]);
            if (u.isEmpty()) break;
        }
        return u;
    }

    /** S is a DC w.r.t. the current evidence set iff no evidence contains all of S. */
    public boolean isComplete(IBitSet S) {
        return computeUncovered(S).isEmpty();
    }
}

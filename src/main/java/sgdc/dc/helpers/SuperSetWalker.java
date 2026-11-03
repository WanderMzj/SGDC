package sgdc.dc.helpers;

import java.util.*;
import java.util.function.Consumer;

import ch.javasoft.bitset.IBitSet;
import ch.javasoft.bitset.LongBitSet;
import com.google.common.util.concurrent.AtomicLongMap;
import sgdc.dc.paritions.ClusterPair;
import sgdc.dc.predicates.PartitionRefiner;

public class SuperSetWalker {
	private final double totalPairs;

	public static class InterResult {
		public int newRefiner;
		public IBitSet currentBits;
		public Consumer<ClusterPair> nextRefiner;
		public ClusterPair clusterPair;
		public double upperBound;
	}

	private final List<IBitSet> sortedList;
	private final BitSetTranslator translator;
	private final AtomicLongMap<PartitionRefiner> selectivityCount;
	private final IndexProvider<PartitionRefiner> indexProvider;

	public SuperSetWalker(Collection<IBitSet> keySet,
						  int[] counts,
						  AtomicLongMap<PartitionRefiner> selectivityCount,
						  IndexProvider<PartitionRefiner> indexProvider,
						  double pairCount) {
		ArrayIndexComparator comparator = new ArrayIndexComparator(counts, ArrayIndexComparator.Order.ASCENDING);
		this.translator = new BitSetTranslator(comparator.createIndexArray());
		this.selectivityCount = selectivityCount;
		this.sortedList = new ArrayList<>(translator.transform(keySet));
		this.indexProvider = indexProvider;
		this.totalPairs = pairCount;

		Collections.sort(this.sortedList, (o1, o2) -> o2.compareTo(o1));
	}

	/** Depth-first walk over the refiner prefix tree. Child refinements are triggered by the caller through InterResult.nextRefiner. */
	public void walk(Consumer<InterResult> f) {
		walkChildrenSync(0, LongBitSet.FACTORY.create(), null, -1, f);
	}

	private void walkChildrenSync(int next, IBitSet parent, ClusterPair parentRes, int lastBit, Consumer<InterResult> f) {
		while (next < sortedList.size() && parent.isSubSetOf(sortedList.get(next))) {
			int nextBit = sortedList.get(next).nextSetBit(lastBit + 1);
			if (nextBit < 0) {
				++next;
			} else {
				IBitSet toCheck = parent.clone();
				toCheck.set(nextBit);
				final int nextF = next;

				Consumer<ClusterPair> refineFurther = (clusterPair) ->
						walkChildrenSync(nextF, toCheck, clusterPair, nextBit, f);

				InterResult inter = new InterResult();
				inter.clusterPair = parentRes;
				inter.newRefiner = translator.retransform(nextBit);
				inter.nextRefiner = refineFurther;
				inter.upperBound = calculateUpper(next, nextBit);
				inter.currentBits = translator.bitsetRetransform(toCheck);
				f.accept(inter);

				while (next < sortedList.size() && toCheck.isSubSetOf(sortedList.get(next))) ++next;
			}
		}
	}

	/** Product of the selectivities of the refiners that remain after nextBit (upper bound of the surviving pair fraction). */
	private double calculateUpper(int next, int nextBit) {
		double product = 1.0d;
		IBitSet sup = sortedList.get(next);
		int pos = sup.nextSetBit(nextBit + 1);
		while (pos >= 0) {
			PartitionRefiner ref = indexProvider.getObject(translator.retransform(pos));
			long cnt = selectivityCount.get(ref);
			if (cnt <= 0L || totalPairs <= 0.0) return 0.0;
			double sel = cnt / totalPairs;
			if (sel <= 0.0) return 0.0;
			product *= sel;
			pos = sup.nextSetBit(pos + 1);
		}
		return Math.min(product, 1.0);
	}
}

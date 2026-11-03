package sgdc.dc.predicates.sets;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import ch.javasoft.bitset.BitSetFactory;
import ch.javasoft.bitset.IBitSet;
import ch.javasoft.bitset.LongBitSet.LongBitSetFactory;
import sgdc.dc.helpers.IndexProvider;
import sgdc.dc.predicates.Predicate;

public class PredicateBitSet implements Iterable<Predicate> {

	private IBitSet bitset;

	public PredicateBitSet() {
		this.bitset = bf.create();
	}

	public PredicateBitSet(IBitSet bitset) {
		this.bitset = bitset.clone();
	}

	public PredicateBitSet(PredicateBitSet pS) {
		this.bitset = pS.getBitset().clone();
	}

	public PredicateBitSet(Predicate p) {
		this.bitset = getBitSet(p);
	}
	
	public void remove(Predicate predicate) {
		this.bitset.clear(indexProvider.getIndex(predicate).intValue());
	}

	public boolean containsPredicate(Predicate predicate) {
		return this.bitset.get(indexProvider.getIndex(predicate).intValue());
	}

	public boolean isSubsetOf(PredicateBitSet superset) {
		return this.bitset.isSubSetOf(superset.getBitset());
	}


	public IBitSet getBitset() {
		return bitset;
	}


	/**
	 * Process-wide predicate <-> bit index dictionary (inherited from Hydra). It is intentionally global:
	 * every PredicateBitSet of the JVM uses the same bit layout, and PredicateProvider interns predicates, so
	 * one index per predicate is stable for the lifetime of the JVM. Consequence: a JVM runs one relation;
	 * running several relations in one JVM only grows the dictionary (bit indices of different relations do
	 * not collide, so results stay correct, but bit sets get wider). It is not thread-safe for concurrent
	 * registration; predicates are registered single-threaded while the predicate space is built.
	 */
	public static final IndexProvider<Predicate> indexProvider = new IndexProvider<>();
	private static final BitSetFactory bf = new LongBitSetFactory();

	static public Predicate getPredicate(int index) {
		return indexProvider.getObject(index);
	}

	static public IBitSet getBitSet(Predicate p) {
		int index = indexProvider.getIndex(p).intValue();
		IBitSet bitset = bf.create();
		bitset.set(index);
		return bitset;
	}

	public void addAll(PredicateBitSet PredicateBitSet) {
		this.bitset.or(PredicateBitSet.getBitset());
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((bitset == null) ? 0 : bitset.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		PredicateBitSet other = (PredicateBitSet) obj;
		if (bitset == null) {
			if (other.bitset != null)
				return false;
		} else if (!bitset.equals(other.bitset))
			return false;
		return true;
	}

	public static int getIndex(Predicate add) {
		return indexProvider.getIndex(add).intValue();
	}

	public int size() {
		return this.bitset.cardinality();
	}

	@Override
	public Iterator<Predicate> iterator() {
		return new Iterator<Predicate>() {
			private int currentIndex = bitset.nextSetBit(0);
			
			@Override
			public Predicate next() {
				int lastIndex = currentIndex;
				currentIndex = bitset.nextSetBit(currentIndex + 1);
				return indexProvider.getObject(lastIndex);
			}
			
			@Override
			public boolean hasNext() {
				return currentIndex >= 0;
			}
		};
	}
	
	
	
	public boolean add(Predicate predicate) {
		int index = getIndex(predicate);
		boolean newAdded = !bitset.get(index);
		this.bitset.set(index);
		return newAdded;
	}

	public int getSingleSetBitIndex(PredicateBitSet pSet) {
		// 只要这个 PredicateBitSet 里只有一个 bit 是 1，返回那个 bit 的索引
		return pSet.getBitset().nextSetBit(0);
	}


	/**
	 * Membership test. The original implementation returned {@code getIndex(p) != -1}, which is always true
	 * (IndexProvider assigns an index on first use), so every caller - notably the success counters of
	 * RandomSampling - saw every predicate as contained. Now identical to {@link #containsPredicate(Predicate)}.
	 */
	public boolean contains(Predicate p) {
		return containsPredicate(p);
	}

	public List<PredicateBitSet> getProperNonEmptySubsets() {
		List<PredicateBitSet> result = new ArrayList<>();
		generateProperNonEmptySubsets(subset -> result.add(subset));
		return result;
	}

	/**
	 * 使用回调的方式枚举所有非空、非全集子集，避免一次性装进 List 里爆内存。
	 */
	public void generateProperNonEmptySubsets(Consumer<PredicateBitSet> consumer) {
		IBitSet originalBits = this.bitset;
		int originalSize = originalBits.cardinality();

		// 收集当前集合中所有为 1 的 bit 的索引
		List<Integer> indices = new ArrayList<>();
		for (int idx = originalBits.nextSetBit(0); idx >= 0; idx = originalBits.nextSetBit(idx + 1)) {
			indices.add(idx);
		}

		// current 一开始是空集：先 clone，再把这些位置都清掉
		IBitSet current = originalBits.clone();
		for (int idx : indices) {
			current.clear(idx);
		}

		// 回溯枚举
		dfsSubsets(indices, 0, current, 0, originalSize, consumer);
	}

	private static void dfsSubsets(List<Integer> indices,
								   int pos,
								   IBitSet current,
								   int currentSize,
								   int originalSize,
								   Consumer<PredicateBitSet> consumer) {
		if (pos == indices.size()) {
			// 到达一个完整的选择结果：current 是某个子集
			if (currentSize > 0 && currentSize < originalSize) {
				// 注意：构造函数里会 clone bitset，所以可以直接传 current
				consumer.accept(new PredicateBitSet(current));
			}
			return;
		}

		int bitIndex = indices.get(pos);

		// 1) 不选这个元素
		dfsSubsets(indices, pos + 1, current, currentSize, originalSize, consumer);

		// 2) 选这个元素
		current.set(bitIndex);
		dfsSubsets(indices, pos + 1, current, currentSize + 1, originalSize, consumer);
		current.clear(bitIndex); // 回溯
	}
}

package ch.javasoft.bitset.search;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.Consumer;

import ch.javasoft.bitset.IBitSet;

public class NTreeSearch implements ISubsetBackend, ITreeSearch {

	//subtrees: 表示子树的映射，每个子节点由某个 位的位置（bit index） 决定。
	//
	//bitset: 当前节点是否为一个有效的集合终点（叶子节点），即是否存储了某个 IBitSet。

	private HashMap<Integer, NTreeSearch> subtrees = new HashMap<>();
	private IBitSet bitset;
	private boolean checked = false;


	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * ch.javasoft.bitset.search.ITreeSearch#add(ch.javasoft.bitset.IBitSet)
	 */

	//递归插入 bs 到树中。类似前缀树中逐位插入，只插入为 1 的位。

	@Override
	public boolean add(IBitSet bs) {
		add(bs, 0);
		return true;
	}
	public boolean add(IBitSet bs,boolean checked) {
		add(bs, 0, checked);  // 默认不设置checked
		return true;
	}

	//判断是否已经存在 add 的某个子集。
	private void add(IBitSet bs, int next) {
		int nextBit = bs.nextSetBit(next);
		if (nextBit < 0) {
			bitset = bs;
			checked = false;
			return;
		}
		NTreeSearch nextTree = subtrees.get(Integer.valueOf(nextBit));
		if (nextTree == null) {
			nextTree = new NTreeSearch();
			subtrees.put(Integer.valueOf(nextBit), nextTree);
		}
		nextTree.add(bs, nextBit + 1);
	}

	// 递归核心方法，支持checked设置
	private void add(IBitSet bs, int next, boolean checked) {
		int nextBit = bs.nextSetBit(next);
		if (nextBit < 0) {
			this.bitset = bs;
			this.checked = checked;  // 设置checked状态
			return;
		}
		NTreeSearch nextTree = subtrees.get(Integer.valueOf(nextBit));
		if (nextTree == null) {
			nextTree = new NTreeSearch();
			subtrees.put(Integer.valueOf(nextBit), nextTree);
		}
		nextTree.add(bs, nextBit + 1, checked);
	}
	//找到 invalidFD 所泛化的集合（子集），从树中移除这些子集。
	@Override
	public Set<CheckedBitSet> getAndRemoveGeneralizations(IBitSet invalidFD) {
		HashSet<CheckedBitSet> removed = new HashSet<>();
		getAndRemoveGeneralizations(invalidFD, 0, removed);
		return removed;
	}
//	@Override
//	public Set<IBitSet> getAndRemoveGeneralizations(IBitSet invalidFD) {
//		HashSet<IBitSet> removed = new HashSet<>();
//		getAndRemoveGeneralizations(invalidFD, 0, removed);
//		return removed;
//	}
	//枚举所有是给定集合 bitset 的超集的集合。
	//
	//思路：只有当前子节点的 bit index 在 bitset 中存在，才继续递归。
	private boolean getAndRemoveGeneralizations(IBitSet invalidFD, int next, Set<CheckedBitSet> removed) {
		if (bitset != null) {
			removed.add(new CheckedBitSet(bitset,checked));
			bitset = null;
		}

		int nextBit = invalidFD.nextSetBit(next);
		while (nextBit >= 0) {
			NTreeSearch subTree = subtrees.get(Integer.valueOf(nextBit));
			if (subTree != null)
				if (subTree.getAndRemoveGeneralizations(invalidFD, nextBit + 1, removed))
					subtrees.remove(Integer.valueOf(nextBit));
			nextBit = invalidFD.nextSetBit(nextBit + 1);
		}
		return subtrees.isEmpty();
	}
//	private boolean getAndRemoveGeneralizations(IBitSet invalidFD, int next, Set<IBitSet> removed) {
//		if (bitset != null) {
//			removed.add(bitset);
//			bitset = null;
//		}
//
//		int nextBit = invalidFD.nextSetBit(next);
//		while (nextBit >= 0) {
//			NTreeSearch subTree = subtrees.get(Integer.valueOf(nextBit));
//			if (subTree != null)
//				if (subTree.getAndRemoveGeneralizations(invalidFD, nextBit + 1, removed))
//					subtrees.remove(Integer.valueOf(nextBit));
//			nextBit = invalidFD.nextSetBit(nextBit + 1);
//		}
//		return subtrees.isEmpty();
//	}


	@Override
	public boolean containsSubset(IBitSet add) {
		return getSubset(add, 0) != null;
	}

	public IBitSet getSubset(IBitSet add) {
		return getSubset(add, 0);
	}

	private IBitSet getSubset(IBitSet add, int next) {
		if (bitset != null)
			return bitset;

		int nextBit = add.nextSetBit(next);
		while (nextBit >= 0) {
			NTreeSearch subTree = subtrees.get(Integer.valueOf(nextBit));
			if (subTree != null) {
				IBitSet res = subTree.getSubset(add, nextBit + 1);
				if (res != null)
					return res;
			}
			nextBit = add.nextSetBit(nextBit + 1);
		}

		return null;
	}

	/** Calls consumer for every stored set that is a subset of bs. */
	public void forEachSubSet(IBitSet bs, Consumer<IBitSet> consumer) {
		forEachSubSet(bs, consumer, 0);
	}

	private void forEachSubSet(IBitSet bs, Consumer<IBitSet> consumer, int next) {
		if (bitset != null)
			consumer.accept(bitset);
		for (int nextBit = bs.nextSetBit(next); nextBit >= 0; nextBit = bs.nextSetBit(nextBit + 1)) {
			NTreeSearch subTree = subtrees.get(Integer.valueOf(nextBit));
			if (subTree != null)
				subTree.forEachSubSet(bs, consumer, nextBit + 1);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * ch.javasoft.bitset.search.ITreeSearch#forEachSuperSet(ch.javasoft.bitset.
	 * IBitSet, java.util.function.Consumer)
	 */
	@Override
	public void forEachSuperSet(IBitSet bitset, Consumer<IBitSet> consumer) {
		forEachSuperSet(bitset, consumer, 0);
	}

	private void forEachSuperSet(IBitSet bitset, Consumer<IBitSet> consumer, int next) {
		int nextBit = bitset.nextSetBit(next);
		if (nextBit < 0)
			forEach(consumer);

		// for(int i = next; i <= nextBit; ++i) {
		for (Entry<Integer, NTreeSearch> entry : subtrees.entrySet()) {
			if (entry.getKey().intValue() > nextBit)
				continue;
			NTreeSearch subTree = entry.getValue();
			if (subTree != null)
				subTree.forEachSuperSet(bitset, consumer, entry.getKey().intValue() + 1);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see ch.javasoft.bitset.search.ITreeSearch#forEach(java.util.function.
	 * Consumer)
	 */
	@Override
	public void forEach(Consumer<IBitSet> consumer) {
		if (bitset != null)
			consumer.accept(bitset);
		for (NTreeSearch subtree : subtrees.values()) {
			subtree.forEach(consumer);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * ch.javasoft.bitset.search.ITreeSearch#remove(ch.javasoft.bitset.IBitSet)
	 */
	@Override
	public void remove(IBitSet remove) {
		remove(remove, 0);
	}

	private boolean remove(IBitSet remove, int next) {
		int nextBit = remove.nextSetBit(next);
		if (nextBit < 0) {
			if (bitset != null && bitset.equals(remove)){
				bitset = null;
				checked = false;
			}

		} else {
			NTreeSearch subTree = subtrees.get(Integer.valueOf(nextBit));
			if (subTree != null) {
				if (subTree.remove(remove, nextBit + 1))
					subtrees.remove(Integer.valueOf(nextBit));
			}
		}
		return bitset == null && subtrees.size() == 0;
	}

}

package ch.javasoft.bitset.search;

import java.util.*;
import java.util.function.Consumer;

import ch.javasoft.bitset.IBitSet;
import sgdc.dc.helpers.ArrayIndexComparator;
import sgdc.dc.helpers.BitSetTranslator;

public class TranslatingTreeSearch implements ITreeSearch {

	private NTreeSearch search = new NTreeSearch();

	private BitSetTranslator translator;
	private List<IBitSet> bitsetListTransformed;


	//创建一个 index 重排序器（ArrayIndexComparator）。
	//
	//通过这个顺序建立一个 BitSetTranslator，可实现翻译/反翻译。
	//
	//对输入的 bitsetList 做翻译，并缓存到 bitsetListTransformed，用于后续推理。
	public TranslatingTreeSearch(int[] priorities, List<IBitSet> bitsetList) {
		ArrayIndexComparator comparator = new ArrayIndexComparator(priorities, ArrayIndexComparator.Order.DESCENDING);
		this.translator = new BitSetTranslator(comparator.createIndexArray());
		this.bitsetListTransformed = new ArrayList<>(translator.transform(bitsetList));
	}

	//把输入 BitSet 翻译后加入 NTreeSearch。
	@Override
	public boolean add(IBitSet bs) {
		IBitSet translated = translator.bitsetTransform(bs);
		return search.add(translated);
	}

	@Override
	public void forEachSuperSet(IBitSet bitset, Consumer<IBitSet> consumer) {
		search.forEachSuperSet(bitset, superset -> consumer.accept(translator.bitsetRetransform(superset)));
	}

	@Override
	public void forEach(Consumer<IBitSet> consumer) {
		search.forEach(bitset -> consumer.accept(translator.bitsetRetransform(bitset)));
	}

	//翻译后从树中删除。
	@Override
	public void remove(IBitSet remove) {
		search.remove(translator.bitsetTransform(remove));
	}


	//判断是否存在某个子集。
	@Override
	public boolean containsSubset(IBitSet bitset) {
		return search.containsSubset(translator.bitsetTransform(bitset));
	}


	//作用：查找并移除所有是 invalidDC 的子集的 BitSet。
	@Override
	public Collection<CheckedBitSet> getAndRemoveGeneralizations(IBitSet invalidDC) {
		Set<CheckedBitSet> temp = search.getAndRemoveGeneralizations(invalidDC);
		return translator.retransform(temp);
	}
//	@Override
//	public Collection<IBitSet> getAndRemoveGeneralizations(IBitSet invalidDC) {
//		Set<IBitSet> temp = search.getAndRemoveGeneralizations(invalidDC);
//		return translator.retransform(temp);
//	}

	//第一优先级：按位数多少（cardinality）
	//第二优先级：经过 translator 转换后的字典序（升序）
	public Comparator<IBitSet> getComparator() {
		return (o1, o2) -> {
            int erg = Integer.compare(o2.cardinality(), o1.cardinality());
            return erg != 0 ? erg : translator.bitsetTransform(o2).compareTo(translator.bitsetTransform(o1));
        };

	}


	public void handleInvalid(IBitSet invalidDCU) {
		IBitSet invalidDC = translator.bitsetTransform(invalidDCU);
		Collection<CheckedBitSet> remove = search.getAndRemoveGeneralizations(invalidDC);
		for (CheckedBitSet removed : remove) {
			IBitSet removedBitset = removed.bitset;
			boolean checked = removed.checked;
			for (IBitSet bitset : bitsetListTransformed) {
				IBitSet temp = removedBitset.clone();
				temp.and(bitset);
				// already one bit in block set?
				if (temp.isEmpty()) {
					IBitSet valid = bitset.clone();
					valid.andNot(invalidDC);
					for (int i = valid.nextSetBit(0); i >= 0; i = valid.nextSetBit(i + 1)) {
						IBitSet add = removedBitset.clone();
						add.set(i);
						if (!search.containsSubset(add)) {
							search.add(add, checked);
						}
					}
				}
			}
		}
	}

}

package sgdc.dc.denialcontraints;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import sgdc.dc.coverage.CoverageProvider;
import sgdc.dc.predicates.Predicate;
import sgdc.dc.util.PruningFactors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.javasoft.bitset.IBitSet;
import ch.javasoft.bitset.search.NTreeSearch;
import sgdc.dc.predicates.sets.Closure;
import sgdc.dc.predicates.sets.PredicateBitSet;
import sgdc.dc.predicates.sets.PredicateSetFactory;

public class DenialConstraintSet implements Iterable<DenialConstraint> {

	private Set<DenialConstraint> constraints = new HashSet<>();

	public Set<DenialConstraint> getConstraints() {
		return constraints;
	}

	public boolean contains(DenialConstraint dc) {
		return constraints.contains(dc);
	}

	private static class MinimalDCCandidate {
		DenialConstraint dc;
		IBitSet bitset;

		public MinimalDCCandidate(DenialConstraint dc) {
			this.dc = dc;
			this.bitset = PredicateSetFactory.create(dc.getPredicateSet()).getBitset();
		}

		public boolean shouldReplace(MinimalDCCandidate prior) {
			if (prior == null)
				return true;

			if (dc.getPredicateCount() < prior.dc.getPredicateCount())
				return true;

			if (dc.getPredicateCount() > prior.dc.getPredicateCount())
				return false;

			return bitset.compareTo(prior.bitset) <= 0;
		}
	}
	public void minimize() {
		Map<PredicateBitSet, MinimalDCCandidate> constraintsClosureMap = new HashMap<>();
		for (DenialConstraint dc : constraints) {
			PredicateBitSet predicateSet = dc.getPredicateSet();
			Closure c = new Closure(predicateSet);
			if (c.construct()) {
				MinimalDCCandidate candidate = new MinimalDCCandidate(dc);
				PredicateBitSet closure = c.getClosure();
				MinimalDCCandidate prior = constraintsClosureMap.get(closure);
				if (candidate.shouldReplace(prior))
					constraintsClosureMap.put(closure, candidate);
			}
		}

		List<Entry<PredicateBitSet, MinimalDCCandidate>> constraints2 = new ArrayList<>(constraintsClosureMap.entrySet());
//		log.info("Sym size created " + constraints2.size());

		constraints2.sort((entry1, entry2) -> {
			int res = Integer.compare(entry1.getKey().size(), entry2.getKey().size());
			if (res != 0)
				return res;
			res = Integer.compare(entry1.getValue().dc.getPredicateCount(), entry2.getValue().dc.getPredicateCount());
			if (res != 0)
				return res;
			return entry1.getValue().bitset.compareTo(entry2.getValue().bitset);
		});

		constraints = new HashSet<>();
		NTreeSearch tree = new NTreeSearch();
		for (Entry<PredicateBitSet, MinimalDCCandidate> entry : constraints2) {
			if (tree.containsSubset(PredicateSetFactory.create(entry.getKey()).getBitset()))
				continue;

			DenialConstraint inv = entry.getValue().dc.getInvT1T2DC();
			if (inv != null) {
				Closure c = new Closure(inv.getPredicateSet());
				if (!c.construct())
					continue;
				if
				(tree.containsSubset(PredicateSetFactory.create(c.getClosure()).getBitset()))
				 continue;
			}

			constraints.add(entry.getValue().dc);
			tree.add(entry.getValue().bitset);
			 if(inv != null)
				 tree.add(PredicateSetFactory.create(inv.getPredicateSet()).getBitset());
		}
		// etmMonitor.render(new SimpleTextRenderer());
	}

	public void add(DenialConstraint dc) {
		constraints.add(dc);
	}

	public void addAll(DenialConstraintSet set) {
		constraints.addAll(set.constraints);
	}
	@Override
	public Iterator<DenialConstraint> iterator() {
		return constraints.iterator();
	}

	public int size() {
		return constraints.size();
	}

	public List<DenialConstraint> getConstraintsSortedByPredicateCount() {
		return constraints.stream()
				.sorted(Comparator.comparingInt(DenialConstraint::getPredicateCount))
				.collect(Collectors.toList());
	}

	public static List<DenialConstraint> getCommonDenialConstraints(
			List<DenialConstraint> list1,
			List<DenialConstraint> list2) {

		Set<DenialConstraint> set1 = new HashSet<>(list1);
		Set<DenialConstraint> set2 = new HashSet<>(list2);

		set1.retainAll(set2); // 求交集

		return new ArrayList<>(set1);
	}




	public static List<DenialConstraint> getUniqueDenialConstraints(
			List<DenialConstraint> list1,
			List<DenialConstraint> list2) {

		Set<DenialConstraint> set1 = new HashSet<>(list1);
		Set<DenialConstraint> set2 = new HashSet<>(list2);

		set1.removeAll(set2); // 求差集：list1 中有但 list2 中没有的 DC

		return new ArrayList<>(set1);
	}

	public static void sortDenialConstraints(List<DenialConstraint> list) {
		Collections.sort(list, new Comparator<DenialConstraint>() {
			@Override
			public int compare(DenialConstraint dc1, DenialConstraint dc2) {
				// 先按谓词数量升序排序
				int countCompare = Integer.compare(dc1.getPredicateCount(), dc2.getPredicateCount());
				if (countCompare != 0) {
					return countCompare;
				}

				// 如果谓词数量相同，再比较 bitset 的字典序
				IBitSet bs1 = PredicateSetFactory.create(dc1.getPredicateSet()).getBitset();
				IBitSet bs2 = PredicateSetFactory.create(dc2.getPredicateSet()).getBitset();
				return bs1.compareTo(bs2);
			}
		});

	}
	public DenialConstraintSet selectTopPPercentByPredicateCount(double p) {
		if (p <= 0 || p > 100) {
			throw new IllegalArgumentException("p must be in (0, 100]");
		}

		// 拷贝原始 DC 集合为列表
		List<DenialConstraint> list = new ArrayList<>();
		for (DenialConstraint dc : this) {
			list.add(dc);
		}

		// 按谓词数量升序排序
		Collections.sort(list, new Comparator<DenialConstraint>() {
			@Override
			public int compare(DenialConstraint dc1, DenialConstraint dc2) {
				return Integer.compare(dc1.getPredicateCount(), dc2.getPredicateCount());
			}
		});

		// 计算前 p% 的个数
		int total = list.size();
		int selectCount = (int) Math.ceil(total * p / 100.0);

		// 取出前 p% 的 DC
		DenialConstraintSet resultSet = new DenialConstraintSet();
		for (int i = 0; i < selectCount && i < total; i++) {
			resultSet.add(list.get(i));
		}

		return resultSet;
	}

	public DenialConstraintSet selectByPredicateCountThreshold(int l) {
		if (l < 0) {
			throw new IllegalArgumentException("Predicate count threshold must be >= 0");
		}

		DenialConstraintSet resultSet = new DenialConstraintSet();
		for (DenialConstraint dc : this) {
			if (dc.getPredicateCount() <= l) {
				resultSet.add(dc);
			}
		}

		return resultSet;
	}


	public DenialConstraintSet selectByScoreAndRange(double minP, double maxP) {
		if (minP < 0 || maxP > 100 || minP > maxP) {
			throw new IllegalArgumentException("minP and maxP must satisfy 0 <= minP <= maxP <= 100");
		}

		// 拷贝原始 DC 集合为列表
		List<DenialConstraint> list = new ArrayList<>();
		for (DenialConstraint dc : this) {
			list.add(dc);
		}

		// 按谓词数量升序排序
		list.sort(Comparator.comparingInt(DenialConstraint::getPredicateCount));

		int total = list.size();
		int startIndex = (int) Math.floor(total * minP / 100.0);
		int endIndex = (int) Math.ceil(total * maxP / 100.0);

		// 防止越界
		startIndex = Math.min(startIndex, total);
		endIndex = Math.min(endIndex, total);

		// 提取[minP%, maxP%)区间的 DC
		DenialConstraintSet resultSet = new DenialConstraintSet();
		for (int i = startIndex; i < endIndex; i++) {
			resultSet.add(list.get(i));
		}

		return resultSet;
	}

	public static DenialConstraintSet fromCollection(Collection<DenialConstraint> dcList) {
		DenialConstraintSet set = new DenialConstraintSet();
		if (dcList != null) {
			set.constraints.addAll(dcList);
		}
		return set;
	}

	public static DenialConstraintSet intersection(DenialConstraintSet set1, DenialConstraintSet set2) {
		Set<DenialConstraint> constraints1 = new HashSet<>();
		for (DenialConstraint dc : set1) {
			constraints1.add(dc);
		}

		Set<DenialConstraint> constraints2 = new HashSet<>();
		for (DenialConstraint dc : set2) {
			constraints2.add(dc);
		}

		constraints1.retainAll(constraints2); // 求交集

		DenialConstraintSet resultSet = new DenialConstraintSet();
		for (DenialConstraint dc : constraints1) {
			resultSet.add(dc);
		}

		return resultSet;
	}
	public static DenialConstraintSet difference(DenialConstraintSet setA, DenialConstraintSet setB) {
		List<DenialConstraint> listA = new ArrayList<>();
		for (DenialConstraint dc : setA) {
			listA.add(dc);
		}

		List<DenialConstraint> listB = new ArrayList<>();
		for (DenialConstraint dc : setB) {
			listB.add(dc);
		}

		List<DenialConstraint> diff = getUniqueDenialConstraints(listA, listB); // A - B

		return fromCollection(diff);
	}



	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		DenialConstraintSet other = (DenialConstraintSet) obj;
		return constraints.equals(other.constraints);
	}

	private int truePositive(DenialConstraintSet prediction) {
		if (prediction == null) throw new IllegalArgumentException("prediction == null");
		Set<DenialConstraint> inter = new HashSet<>(this.constraints);
		inter.retainAll(prediction.constraints);
		return inter.size();
	}

	/** Precision(GT, Pred) = TP / |Pred| */
	public double precision(DenialConstraintSet prediction) {
		if (prediction == null) throw new IllegalArgumentException("prediction == null");
		int predSize = prediction.constraints.size();
		int tp = truePositive(prediction);
		if (predSize == 0) {
			// 若两者都空视为完全一致 => 1.0；否则 0.0
			return this.constraints.isEmpty() ? 1.0 : 0.0;
		}
		return tp / (double) predSize;
	}

	/** Recall(GT, Pred) = TP / |GT| */
	public double recall(DenialConstraintSet prediction) {
		if (prediction == null) throw new IllegalArgumentException("prediction == null");
		int gtSize = this.constraints.size();
		int tp = truePositive(prediction);
		if (gtSize == 0) {
			// 没有真实正例，通常定义为 1.0
			return 1.0;
		}
		return tp / (double) gtSize;
	}

	public DenialConstraintSet pruneWithProvider(PruningFactors pruningFactors,
												  CoverageProvider coverageProvider){
		Set<DenialConstraint> result = new HashSet<>();
		for (DenialConstraint dc : this) {
			// Score >= tau (paper: D_tau = {phi : Score(phi) >= tau}); the old code used '>'.
			if (pruningFactors.accepts(pruningFactors.score(dc, coverageProvider.calculate(dc)))) {
				result.add(dc);
			}
		}

		DenialConstraintSet ret = new DenialConstraintSet();
		ret.constraints.addAll(result);
		return ret;
	}

	private static Logger log = LoggerFactory.getLogger(DenialConstraintSet.class);
}

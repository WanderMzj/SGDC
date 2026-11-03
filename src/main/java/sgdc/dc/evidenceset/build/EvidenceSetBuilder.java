package sgdc.dc.evidenceset.build;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sgdc.dc.input.ColumnPair;
import sgdc.dc.input.ParsedColumn;
import sgdc.dc.predicates.Predicate;
import sgdc.dc.predicates.PredicateBuilder;
import sgdc.dc.predicates.PredicateProvider;
import sgdc.dc.predicates.operands.ColumnOperand;
import sgdc.dc.predicates.sets.PredicateBitSet;
import sgdc.dc.predicates.sets.PredicateSetFactory;
import de.metanome.algorithm_integration.Operator;

public abstract class EvidenceSetBuilder {

	protected PredicateBuilder predicates;
//	protected static final Map<PredicateKey,Long> predicate2SupportMap = new HashMap<>();

	public EvidenceSetBuilder(PredicateBuilder predicates2) {
		this.predicates = predicates2;

	}


	protected PredicateBitSet getStatic(Collection<ColumnPair> pairs, int i) {
		PredicateBitSet set = PredicateSetFactory.create();
		// which predicates are satisfied by these two lines?
		for (ColumnPair p : pairs) {
			if (p.getC1().equals(p.getC2()))
				continue;

			PredicateBitSet[] list = map.get(p);
			if (p.isJoinable()) {
				if (equals(i, i, p)){
					set.addAll(list[2]);

//					addPredicate(set ,list,p,2);
				}
				else
					set.addAll(list[3]);
//					addPredicate(set ,list,p,3);
			}
			if (p.isComparable()) {
				int compare2 = compare(i, i, p);
				if (compare2 < 0) {
					set.addAll(list[7]);
//					addPredicate(set ,list,p,7);
				} else if (compare2 == 0) {
					set.addAll(list[8]);
//					addPredicate(set ,list,p,8);
				} else {
					set.addAll(list[9]);
//					addPredicate(set ,list,p,9);
				}
			}

		}
		return set;
	}

	protected PredicateBitSet getPredicateSet(PredicateBitSet staticSet, Collection<ColumnPair> pairs, int i, int j) {
		PredicateBitSet set = PredicateSetFactory.create(staticSet);
		// which predicates are satisfied by these two lines?
		for (ColumnPair p : pairs) {
			PredicateBitSet[] list = map.get(p);
			if (p.isJoinable()) {
				if (equals(i, j, p))
					set.addAll(list[0]);
				else
					set.addAll(list[1]);
			}
			if (p.isComparable()) {
				int compare = compare(i, j, p);
				if (compare < 0) {
					set.addAll(list[4]);
				} else if (compare == 0) {
					set.addAll(list[5]);
				} else {
					set.addAll(list[6]);
				}

			}

		}
		return set;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private int compare(int i, int j, ColumnPair p) {
		return ((Comparable) getValue(i, p.getC1())).compareTo((getValue(j, p.getC2())));
	}

	private boolean equals(int i, int j, ColumnPair p) {
		return getValue(i, p.getC1()) != null && getValue(i, p.getC1()).equals(getValue(j, p.getC2()));
	}

	private Object getValue(int i, ParsedColumn<?> p) {
		return p.getValue(i);
	}

	protected Map<ColumnPair, PredicateBitSet[]> map;
//	public static Map<LongBitSet,PredicateKey> predicateBitSetPredicateKeyMap = new HashMap<>();

	protected void createSets(Collection<ColumnPair> pairs) {
		map = new HashMap<>();
		// which predicates are satisfied by these two lines?
		for (ColumnPair p : pairs) {
			PredicateBitSet[] list = new PredicateBitSet[10];
			for(int i = 0; i < list.length; ++i)
				list[i] = PredicateSetFactory.create();
			map.put(p, list);
			if (p.isJoinable()) {
				addIfValid(p, list[0], Operator.EQUAL, 1,0);
				addIfValid(p, list[1], Operator.UNEQUAL, 1,1);
				if (!p.getC1().equals(p.getC2())) {
					addIfValid(p, list[2], Operator.EQUAL, 0,2);
					addIfValid(p, list[3], Operator.UNEQUAL, 0,3);
				}
			}
			if (p.isComparable()) {

				addIfValid(p, list[4], Operator.LESS, 1,4);
				addIfValid(p, list[4], Operator.LESS_EQUAL, 1,4);
				
				addIfValid(p, list[5], Operator.LESS_EQUAL, 1,5);
				addIfValid(p, list[5], Operator.GREATER_EQUAL, 1,5);
				
				addIfValid(p, list[6], Operator.GREATER_EQUAL, 1,6);
				addIfValid(p, list[6], Operator.GREATER, 1,6);

				if (!p.getC1().equals(p.getC2())) {
					addIfValid(p, list[7], Operator.LESS, 0,7);
					addIfValid(p, list[7], Operator.LESS_EQUAL, 0,7);
					
					addIfValid(p, list[8], Operator.LESS_EQUAL, 0,8);
					addIfValid(p, list[8], Operator.GREATER_EQUAL, 0,8);
					
					addIfValid(p, list[9], Operator.GREATER_EQUAL, 0,9);
					addIfValid(p, list[9], Operator.GREATER, 0,9);
				}
			}

		}
//		predicateBitSetPredicateKeyMap = convertMap(map);

//		System.out.println(predicateBitSetPredicateKeyMap);

	}

	private void addIfValid(ColumnPair p, PredicateBitSet list, Operator op, int index2,int index) {
		Predicate pr = predicateProvider.getPredicate(op, new ColumnOperand<>(p.getC1(), 0),
				new ColumnOperand<>(p.getC2(), index2));
		if(predicates.getPredicates().contains(pr))
			list.add(pr);
//		PredicateKey predicateKey = PredicateKey.create(p,index);
//		predicateBitSetPredicateKeyMap.put(list,predicateKey);
	}

	@SuppressWarnings("unused")
	private static Logger log = LoggerFactory.getLogger(EvidenceSetBuilder.class);
	
	private static final PredicateProvider predicateProvider = PredicateProvider.getInstance();

//	private void  addPredicate(PredicateBitSet set,PredicateBitSet[] list,ColumnPair p,int index){
//		set.addAll(list[2]);
////		PredicateKey predicateKey = PredicateKey.create(p,2);
//		Long support = predicate2SupportMap.get(predicateKey);
//		if(support == null) {
//			predicate2SupportMap.put(predicateKey, 1L);
//		}else {
//			predicate2SupportMap.put(predicateKey, support + 1);
//		}
//	}

//	public  Map<LongBitSet, PredicateKey> convertMap(
//			Map<ColumnPair, PredicateBitSet[]> oldMap) {
//
//		Map<LongBitSet, PredicateKey> newMap = new HashMap<>();
//
//		for (Map.Entry<ColumnPair, PredicateBitSet[]> entry : oldMap.entrySet()) {
//			ColumnPair columnPair = entry.getKey();
//			PredicateBitSet[] bitSets = entry.getValue();
//
//			for (int i = 0; i < bitSets.length; i++) {
//				PredicateBitSet bitSet = bitSets[i];
//				PredicateKey key = PredicateKey.create(columnPair, i);
//				newMap.put((LongBitSet) bitSet.getBitset(), key);
//			}
//		}

//		return newMap;
//	}



}

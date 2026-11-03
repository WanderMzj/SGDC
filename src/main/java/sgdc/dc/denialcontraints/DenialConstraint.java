package sgdc.dc.denialcontraints;

import ch.javasoft.bitset.IBitSet;
import ch.javasoft.bitset.search.NTreeSearch;
import sgdc.dc.predicates.Predicate;
import sgdc.dc.predicates.PredicateProvider;
import sgdc.dc.predicates.sets.Closure;
import sgdc.dc.predicates.sets.PredicateBitSet;
import sgdc.dc.predicates.sets.PredicateSetFactory;
import de.metanome.algorithm_integration.Operator;
import de.metanome.algorithm_integration.PredicateVariable;

import java.util.ArrayList;
import java.util.List;

public class DenialConstraint {

	private PredicateBitSet predicateSet;
	public boolean isSymmetric;
	//private PredicateBitSet predicateSet; // 用于存储一个 DC 中的谓词集合（bitset + 实际谓词对象）
	//public boolean isSymmetric;           // 是否是对称约束（即对调 T1 和 T2 后仍等价）

	public DenialConstraint(Predicate... predicates) {
		predicateSet = PredicateSetFactory.create(predicates);
		Symmetric();
	}

	public DenialConstraint(PredicateBitSet predicateSet) {
		this.predicateSet = predicateSet;
		Symmetric();
	}
	//检查当前 DC 是否是平凡约束（即不会限制任何数据）
	public boolean isTrivial() {
		return !new Closure(predicateSet).construct();
	}


	//判断该 DC 是否能从已有的 DC（通过 tree 搜索）中推出
	public boolean isImpliedBy(NTreeSearch tree) {
		Closure c = new Closure(predicateSet);
		if (!c.construct())
			return true;

		return isImpliedBy(tree, c.getClosure());
	}
	//判断该 DC 是否能从已有的 DC（通过 tree 搜索）中推出
	//
	//若原始 DC 推不出来，则尝试对调 t1, t2 后的对称形式是否能推出
	//
	//使用 Closure 构建闭包后，在 tree 中查找是否有“包含子集”支撑该 DC
	public boolean isImpliedBy(NTreeSearch tree, PredicateBitSet closure) {
		IBitSet subset = tree.getSubset(PredicateSetFactory.create(closure).getBitset());
		if (subset != null) {
			return true;
		}

		DenialConstraint sym = getInvT1T2DC();
		if (sym != null) {
			Closure c = new Closure(sym.getPredicateSet());
			if (!c.construct())
				return true;
			IBitSet subset2 = tree.getSubset(PredicateSetFactory.create(c.getClosure()).getBitset());
			return subset2 != null;
		}

		return false;

	}
	//检查谓词 p（或其对称形式）是否在该 DC 中出现
	public boolean containsPredicate(Predicate p) {
		return predicateSet.containsPredicate(p) || predicateSet.containsPredicate(p.getSymmetric());
	}
	//返回 将谓词中 t1 和 t2 对调后的 DC
	//
	//若任何谓词无法对调，返回 null
	public DenialConstraint getInvT1T2DC() {
		PredicateBitSet invT1T2 = PredicateSetFactory.create();
		for (Predicate predicate : predicateSet) {
			Predicate sym = predicate.getInvT1T2();
			if (sym == null)
				return null;
			invT1T2.add(sym);
		}
		return new DenialConstraint(invT1T2);
	}
	private void Symmetric() {
		for(Predicate pred : predicateSet) {
			if (!pred.getOperand1().getColumn().equals(pred.getOperand2().getColumn()) || (!pred.getOperator().equals(Operator.EQUAL) && !pred.getOperator().equals(Operator.UNEQUAL))) {
				this.isSymmetric = false;
				return;
			}
			isSymmetric = true;

		}
	}
	//public ArrayList<DenialConstraint> decompose()
	public ArrayList<DenialConstraint> decompose() {
		ArrayList<DenialConstraint> DCs = new ArrayList<DenialConstraint>();
		ArrayList<Predicate> predicates1 = new ArrayList<Predicate>();
		ArrayList<Predicate> predicates2 = new ArrayList<Predicate>();
		boolean foundUneq = false;
		for (Predicate pred : predicateSet) {
			if (!foundUneq && pred.getOperator().equals(Operator.UNEQUAL)) {

				predicates1.add(predicateProvider.getPredicate(Operator.GREATER,pred.getOperand1(),pred.getOperand2()));

				predicates1.add(predicateProvider.getPredicate(Operator.LESS,pred.getOperand1(),pred.getOperand2()));
				foundUneq = true;
			} else if (pred.getOperator().equals(Operator.EQUAL) && !pred.getOperand1().getColumn().equals(pred.getOperand2().getColumn())) {

				Predicate predGeq = predicateProvider.getPredicate(Operator.GREATER_EQUAL,pred.getOperand1(),pred.getOperand2());
				Predicate predLeq = predicateProvider.getPredicate(Operator.LESS_EQUAL,pred.getOperand1(),pred.getOperand2());
				predicates1.add(predGeq);
				predicates1.add(predLeq);
				predicates2.add(predGeq);
				predicates2.add(predLeq);
			} else {
				predicates1.add(pred);
				predicates2.add(pred);
			}
		}
		if (foundUneq && !this.isSymmetric) {
			Predicate[] predicateArray1 = predicates1.toArray(new Predicate[predicates1.size()]);
			Predicate[] predicateArray2 = predicates2.toArray(new Predicate[predicates2.size()]);
			DenialConstraint DC1 = new DenialConstraint(predicateArray1);
			DenialConstraint DC2 = new DenialConstraint(predicateArray2);
			DCs.addAll(DC1.decompose());
			DCs.addAll(DC2.decompose());
		} else if (foundUneq) {
			Predicate[] predicateArray1 = predicates1.toArray(new Predicate[predicates1.size()]);
			DenialConstraint DC1 = new DenialConstraint(predicateArray1);
			DCs.addAll(DC1.decompose());
		}
		else {
			Predicate[] predicateArray1 = predicates1.toArray(new Predicate[predicates1.size()]);
			DCs.add(new DenialConstraint(predicateArray1));
		}
		return DCs;
	}

	public int size(){
		return predicateSet.size();
	}

	public PredicateBitSet getPredicateSet() {
		return predicateSet;
	}

	public int getPredicateCount() {
		return predicateSet.size();
	}

	private boolean containedIn(PredicateBitSet otherPS) {
		for (Predicate p : predicateSet) {
			if (!otherPS.containsPredicate(p) && !otherPS.containsPredicate(p.getSymmetric()))
				return false;
		}
		return true;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (Predicate pred : predicateSet) {
			if (!first) {
				sb.append(" ^ ");
			}
			sb.append(pred.toString());
			first = false;
		}
		return "¬(" + sb + ")";
	}

	@Override
	public int hashCode() {
		// final int prime = 31;
		int result1 = 0;
		for (Predicate p : predicateSet) {
			result1 += Math.max(p.hashCode(), p.getSymmetric().hashCode());
		}
		int result2 = 0;
		if (getInvT1T2DC() != null)
			for (Predicate p : getInvT1T2DC().predicateSet) {
				result2 += Math.max(p.hashCode(), p.getSymmetric().hashCode());
			}
		return Math.max(result1, result2);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		DenialConstraint other = (DenialConstraint) obj;
		if (predicateSet == null) {
			return other.predicateSet == null;
		} else if (predicateSet.size() != other.predicateSet.size()) {
			return false;
		} else {
			PredicateBitSet otherPS = other.predicateSet;
			return containedIn(otherPS) || getInvT1T2DC().containedIn(otherPS)
					|| containedIn(other.getInvT1T2DC().predicateSet);
		}
	}



	public DenialConstraintSet generateSubset() {
		if(predicateSet.size() == 1){
			DenialConstraintSet denialConstraints = new DenialConstraintSet();
			denialConstraints.add(this);
			return denialConstraints;
		}
		List<PredicateBitSet> properNonEmptySubsets = predicateSet.getProperNonEmptySubsets();
		DenialConstraintSet result = new DenialConstraintSet();
		for (PredicateBitSet subset : properNonEmptySubsets) {
			result.add(new DenialConstraint(subset));
		}
		return result;
	}

	static final PredicateProvider predicateProvider = PredicateProvider.getInstance();

	//方法名	作用
	//DenialConstraint(...)	构造一个 DC
	//isTrivial()	是否是平凡约束（对所有数据都成立）
	//isImpliedBy(...)	是否能从现有 DC 中推出（推理）
	//getInvT1T2DC()	获取 t1/t2 对调后的等价 DC
	//decompose()	拆解成更细粒度或更基本的 DC
	//Symmetric()	设置是否对称
	//equals() / hashCode()	逻辑相等判断（包含对称）
	//toString()	格式化为可读字符串
	//toResult()	转换为框架标准结果类型

}

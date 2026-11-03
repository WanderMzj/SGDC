package sgdc.dc.predicates.sets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import sgdc.dc.predicates.Predicate;
import sgdc.dc.predicates.PredicateProvider;
import de.metanome.algorithm_integration.Operator;

public class Closure {

	private static class TrivialPredicateSetException extends Exception {
		private static final long serialVersionUID = -6515524472203224770L;
	}

	private PredicateBitSet start;
	private PredicateBitSet closure;
	private boolean added;
	private Map<Operator, List<Predicate>> grouped;
	//private PredicateBitSet start;         // 初始谓词集合（输入）
	//private PredicateBitSet closure;       // 构造出的闭包集合（输出）
	//private boolean added;                 // 标记本轮闭包是否有新谓词加入
	//private Map<Operator, List<Predicate>> grouped; // 按操作符分组的谓词集合（用于传递性规则推理）


	//入口：初始谓词集合。
	//
	//初始化闭包为空集合，构造用来逐步扩展闭包。
	public Closure(PredicateBitSet start) {
		this.start = start;
		this.grouped = new HashMap<>();
		this.closure = PredicateSetFactory.create();
	}
	//从一个已有闭包中复制，并额外加一个谓词，通常用于局部增量扩展。
	public Closure(Closure closure, Predicate add) {
		this.closure = PredicateSetFactory.create(closure.closure);
		this.start = PredicateSetFactory.create(add);
		this.grouped = new HashMap<>(closure.grouped);
	}
	//这是核心方法，作用是构建谓词闭包：
	//
	//初始化闭包集合：
	//
	//加入初始集合中每个谓词及其对称谓词的所有蕴含谓词（implications）。
	//
	//**迭代地执行传递闭包（transitivityStep）**直到没有新谓词添加。
	//
	//如果在构建过程中产生了逻辑冲突（例如同时包含 a > b 和 a ≤ b），就抛出 TrivialPredicateSetException，认为这组谓词是平凡约束（即永远为真）。
	public boolean construct() {
		try {
			for(Predicate p : start) {
				addAll(p.getImplications());
				if (p.getSymmetric() != null)
					addAll(p.getSymmetric().getImplications());
			}

			added = true;
			while (added) {
				added = false;
				transitivityStep();
			}
			return true;
		} catch (TrivialPredicateSetException e) {
			return false;
		}
	}
	
	public PredicateBitSet getClosure() {
		return closure;
	}


	//这是构建闭包的核心步骤，进行传递性规则和操作符逻辑蕴含关系推理：
	//
	//步骤：
	//遍历当前闭包集合，对每个谓词：
	//
	//加入其蕴含的谓词（通过 getImplications）
	//
	//加入其对称谓词的蕴含（如果有）
	//
	//结合操作符的传递性进行组合推理，例如：
	//
	//a > b 且 b > c ⇒ a > c
	//
	//a ≥ b 且 b ≥ c ⇒ a ≥ c
	//
	//特殊规则：
	//
	//若存在 a ≠ b 且 a ≤ b ⇒ 推导出 a < b
	//
	//若存在 a ≤ b 且 a ≥ b ⇒ 推导出 a = b
	private void transitivityStep() throws TrivialPredicateSetException {
		Set<Predicate> additions = new HashSet<Predicate>();
		closure.forEach(p -> {
			if (p.getSymmetric() != null)
				additions.addAll(p.getSymmetric().getImplications());
			additions.addAll(p.getImplications());
//			additions.add(predicateProvider.getPredicate(Operator.EQUAL, p.getOperand1(), p.getOperand1()));
//			additions.add(predicateProvider.getPredicate(Operator.EQUAL, p.getOperand2(), p.getOperand2()));
//			additions.add(predicateProvider.getPredicate(Operator.GREATER_EQUAL, p.getOperand1(), p.getOperand1()));
//			additions.add(predicateProvider.getPredicate(Operator.GREATER_EQUAL, p.getOperand2(), p.getOperand2()));
//			additions.add(predicateProvider.getPredicate(Operator.LESS_EQUAL, p.getOperand1(), p.getOperand1()));
//			additions.add(predicateProvider.getPredicate(Operator.LESS_EQUAL, p.getOperand2(), p.getOperand2()));
		});

		for (Entry<Operator, List<Predicate>> entry : grouped.entrySet()) {
			Operator op = entry.getKey();
			List<Predicate> list = entry.getValue();
			for(Operator opTrans : op.getTransitives()) {
				List<Predicate> pTrans = grouped.get(opTrans);
				if(pTrans == null)
					continue;
			
				for (Predicate p : list) {
					for(Predicate p2 : pTrans) {
						if(p == p2)
							continue;
						// A -> B ; B -> C
						if(p.getOperand2().equals(p2.getOperand1())) {
							Predicate newPred = predicateProvider.getPredicate(op, p.getOperand1(), p2.getOperand2());
							additions.add(newPred);
						}
						// C -> A ; A -> B
						if(p2.getOperand2().equals(p.getOperand1())) {
							Predicate newPred = predicateProvider.getPredicate(op, p2.getOperand1(), p.getOperand2());
							additions.add(newPred);
						}
					}
				}
			}
		}

		
		List<Predicate> uneqList = grouped.get(Operator.UNEQUAL);
		if(uneqList != null) {
			for(Predicate p : uneqList) {
				if(closure.containsPredicate(predicateProvider.getPredicate(Operator.LESS_EQUAL, p.getOperand1(), p.getOperand2())))
					additions.add(predicateProvider.getPredicate(Operator.LESS, p.getOperand1(), p.getOperand2()));
				if(closure.containsPredicate(predicateProvider.getPredicate(Operator.GREATER_EQUAL, p.getOperand1(), p.getOperand2())))
					additions.add(predicateProvider.getPredicate(Operator.GREATER, p.getOperand1(), p.getOperand2()));
			}
		}
		List<Predicate> leqList = grouped.get(Operator.LESS_EQUAL);
		if(leqList != null) {
			for(Predicate p : leqList) {
				if(closure.containsPredicate(predicateProvider.getPredicate(Operator.GREATER_EQUAL, p.getOperand1(), p.getOperand2())))
					additions.add(predicateProvider.getPredicate(Operator.EQUAL, p.getOperand1(), p.getOperand2()));
			}
		}
		addAll(additions);
	}
	//将谓词集合添加进闭包集合：
	//
	//如果谓词已存在则跳过；
	//
	//如果谓词的“逆谓词”（例如 a > b vs a ≤ b）已存在，说明有逻辑冲突 ⇒ 抛出 TrivialPredicateSetException；
	//
	//添加进操作符分组以支持后续推理；
	//
	//设置 added = true，表示闭包继续扩展。
	private void addAll(Collection<Predicate> predicates) throws TrivialPredicateSetException {
		for (Predicate p : predicates) {
			if (closure.add(p)) {
//				if((p.getOperator() == Operator.GREATER || p.getOperator() == Operator.LESS || p.getOperator() == Operator.UNEQUAL) && p.getOperand1().equals(p.getOperand2()))
//					throw new TrivialPredicateSetException();
				if (closure.containsPredicate(p.getInverse()))
					throw new TrivialPredicateSetException();
				grouped.computeIfAbsent(p.getOperator(), op -> new ArrayList<>()).add(p);
				added = true;
			}
		}
	}
	
	static final PredicateProvider predicateProvider = PredicateProvider.getInstance();  
}

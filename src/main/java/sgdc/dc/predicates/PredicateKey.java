package sgdc.dc.predicates;

//
//public class PredicateKey {
//    private final ColumnPair columnPair;
//    private final int index;
//
//    private static final Map<PredicateKey,PredicateKey> cache = new HashMap<>();
//
//    private PredicateKey(ColumnPair columnPair, int index) {
//        this.columnPair = columnPair;
//        this.index = index;
//    }
//    public ColumnPair getColumnPair() {
//        return columnPair;
//    }
//    public int getIndex() {
//        return index;
//    }
//
//    public static PredicateKey create(ColumnPair columnPair, int index) {
//        PredicateKey predicateKey = new PredicateKey(columnPair, index);
//        return cache.computeIfAbsent(predicateKey,k->predicateKey);
//    }
//    @Override
//    public boolean equals(Object o) {
//        if (this == o) return true;
//        if (o == null || getClass() != o.getClass()) return false;
//        if (!super.equals(o)) return false;
//        if (!columnPair.equals(((PredicateKey) o).columnPair)) return false;
//        return index == ((PredicateKey) o).index;
//    }
//
//    @Override
//    public int hashCode() {
//        int result = super.hashCode();
//        result = 31 * result + columnPair.hashCode();
//        result = 31 * result + index;
//        return result;
//    }
//
//}

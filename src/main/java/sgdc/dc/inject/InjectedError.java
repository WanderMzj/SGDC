package sgdc.dc.inject;

// ========== 错误日志条目类 ==========
public class InjectedError {
    public final int rowId;          // 注入后数据集中的行号（从1开始，不含表头）
    public final String columnName;
    public final Object originalValue;
    public final Object corruptedValue;
    public final String errorType;

    public InjectedError(int rowId, String columnName, Object original, Object corrupted, String errorType) {
        this.rowId = rowId;
        this.columnName = columnName;
        this.originalValue = original;
        this.corruptedValue = corrupted;
        this.errorType = errorType;
    }

    @Override
    public String toString() {
        return String.format("Row[%d].%s: %s -> %s (%s)",
                rowId, columnName, originalValue, corruptedValue, errorType);
    }
}

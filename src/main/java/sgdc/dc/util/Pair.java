package sgdc.dc.util;

/**
 * 行索引对（record自动提供不可变性、正确equals/hashCode、线程安全）
 */
public record Pair(int first, int second) {
}

package sgdc.dc.inject;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV 数据错误注入器 - 错误配置类（支持多个主键）
 */
public class ErrorProfile {
    // 全局错误率 (0.0 - 1.0)，每个单元格被考虑注入错误的概率
    public double errorRate = 0.05;

    // 各类型错误权重分配（目前逻辑里主要用到了 missingValueWeight / duplicateWeight）
    public double typoWeight = 0;         // 字符串拼写错误
    public double rangeViolationWeight = 0; // 数值越界
    public double precisionErrorWeight = 0; // 精度错误
    public double missingValueWeight = 0;   // 缺失值
    public double duplicateWeight = 0.0;      // 重复行

    // String类型参数
    public double charSwapProb = 0;
    public double charDropProb = 0;
    public double charReplaceProb = 0;

    // Integer类型参数
    public double outOfRangeProb = 0;
    public double signFlipProb = 0.5;
    public double offsetProb = 0;

    // Double类型参数
    public double precisionLossProb = 0;
    public double scientificNotationProb = 0;
    public double roundingErrorProb = 0;

    // 重复值注入参数
    public double duplicateRowProb = 0.1; // 行被重复的概率（0.1表示约10%的行会被重复）
    public String duplicateMode = "FULL"; // "FULL"=完全重复, "PARTIAL"=部分重复（主键不同）

    // 支持多个主键列索引
    //public List<Integer> pkColumnIndexes = new ArrayList<>(); // 主键列索引（支持多个）
    public int pkColumnIndex;
//    public double[] getTypeWeights() {
//        double sum = typoWeight + rangeViolationWeight + precisionErrorWeight + missingValueWeight + duplicateWeight;
//        return new double[] {
//                typoWeight / sum,
//                rangeViolationWeight / sum,
//                precisionErrorWeight / sum,
//                missingValueWeight / sum,
//                duplicateWeight / sum
//        };
//    }
}

package sgdc.dc.util;

import java.io.File;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileNameGenerator {

    public static class FileNameInfo {
        public final String outputPath;
        public final String logPath;
        public final String detailsPath; // 新增详情日志路径
        public final int nextIndex;

        public FileNameInfo(String outputPath, String logPath, String detailsPath, int nextIndex) {
            this.outputPath = outputPath;
            this.logPath = logPath;
            this.detailsPath = detailsPath;
            this.nextIndex = nextIndex;
        }
    }

    public static FileNameInfo generateNextFileNames(String name, String basePath) {
        String injectedDir = basePath + name + "/injected/";
        String logDir = basePath + name + "/log/";
        String detailsDir = basePath + name + "/"; // details.txt放在name目录下

        // 创建所有必要目录
        new File(injectedDir).mkdirs();
        new File(logDir).mkdirs();
        new File(detailsDir).mkdirs(); // 确保details所在目录存在

        String basePattern = Pattern.quote(name);
        Pattern injectedPattern = Pattern.compile(basePattern + "_injected_(\\d+)\\.csv");
        Pattern logPattern = Pattern.compile(basePattern + "_injection_log_(\\d+)\\.csv");

        int maxInjectedIndex = findMaxIndex(injectedDir, injectedPattern);
        int maxLogIndex = findMaxIndex(logDir, logPattern);

        int currentMax = Math.max(maxInjectedIndex, maxLogIndex);
        int nextIndex = currentMax + 1;

        String newOutput = String.format("%s%s_injected_%d.csv", injectedDir, name, nextIndex);
        String newLog = String.format("%s%s_injection_log_%d.csv", logDir, name, nextIndex);
        String detailsPath = detailsDir + "details.txt"; // 固定文件名

        return new FileNameInfo(newOutput, newLog, detailsPath, nextIndex);
    }

    private static int findMaxIndex(String directoryPath, Pattern pattern) {
        // 与之前相同...
        File dir = new File(directoryPath);
        if (!dir.exists() || !dir.isDirectory()) return 0;

        File[] files = dir.listFiles((d, name) -> pattern.matcher(name).matches());
        if (files == null || files.length == 0) return 0;

        return Arrays.stream(files)
                .map(File::getName)
                .mapToInt(filename -> {
                    Matcher matcher = pattern.matcher(filename);
                    return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
                })
                .max()
                .orElse(0);
    }

    /**
     * 将执行日志追加到details.txt文件
     * @param info 文件信息（包含details.txt路径）
     * @param index 第几次执行（从1开始）
     * @param inputPath 输入文件路径
     * @param errorCount 单元格错误数量
     * @param duplicatedRowCount 重复行数量
     * @param errorStats 错误类型统计Map
     */
    public static void appendExecutionDetails(
            FileNameInfo info, int index, String inputPath,
            int errorCount, int duplicatedRowCount,
            java.util.Map<String, Long> errorStats) {


        StringBuilder content = new StringBuilder();
        content.append(String.format("==========error dataset %d ==========%n", index));
        content.append("=====================================\n");
        content.append("错误注入完成！\n");
        content.append("输入文件: ").append(inputPath).append("\n");
        content.append("输出文件: ").append(info.outputPath).append("\n");
        content.append("日志文件: ").append(info.logPath).append("\n");
        content.append("=====================================\n");
        content.append(String.format("单元格错误注入: %d 处%n", errorCount));
        content.append(String.format("重复行注入: %d 行%n", duplicatedRowCount));
        content.append("-------------------------------------\n");
        content.append("错误类型分布:\n");
        errorStats.forEach((type, count) ->
                content.append(String.format("  %s: %d 处%n", type, count))
        );
        content.append("=====================================\n\n");

        // 追加写入文件（自动创建文件）
        try (java.io.FileWriter writer =
                     new java.io.FileWriter(info.detailsPath, true)) { // true表示追加模式
            writer.write(content.toString());
        } catch (java.io.IOException e) {
            throw new RuntimeException("写入详情日志失败: " + info.detailsPath, e);
        }
    }
}
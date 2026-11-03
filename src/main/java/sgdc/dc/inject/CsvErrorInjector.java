package sgdc.dc.inject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import com.csvreader.CsvReader;
import sgdc.dc.util.FileNameGenerator;
import de.metanome.algorithm_integration.input.InputIterationException;

// ========== 核心错误注入器类 ==========
class CsvErrorInjector {
    private final ErrorProfile profile;
    private final Random random;
    private final List<InjectedError> errorLog = new ArrayList<>();
    // 记录“被选中做重复注入”的原始行ID（基于原始数据集，从1开始，不含表头）
    private final List<Integer> duplicatedRowIds = new ArrayList<>();

    public CsvErrorInjector(ErrorProfile profile, long seed) {
        this.profile = profile;
        this.random = new Random(seed);
    }

    /**
     * 主方法：注入错误到CSV文件（含重复行）
     * 生成的数据集可以被 Input 正确读到（列数固定 + UTF-8 + 标准CSV）。
     */
    public void injectErrors(String inputPath, String outputPath, String logPath) throws IOException, InputIterationException {
        // 多次调用时清空上一次的记录
        errorLog.clear();
        duplicatedRowIds.clear();

        // 确保输出目录存在
        File outputFile = new File(outputPath);
        File outputParent = outputFile.getParentFile();
        if (outputParent != null && !outputParent.exists()) {
            outputParent.mkdirs();
        }

        // 确保日志目录存在
        if (logPath != null) {
            File logFile = new File(logPath);
            File logParent = logFile.getParentFile();
            if (logParent != null && !logParent.exists()) {
                logParent.mkdirs();
            }
        }

        try (
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8))
        ) {
            CsvReader csv = new CsvReader(inputPath, ',', StandardCharsets.UTF_8);
            try {
            // 读表头
            csv.readHeaders();
            String[] headers = csv.getHeaders();
            if (headers == null || headers.length == 0) {
                throw new IllegalArgumentException("Empty header in input file: " + inputPath);
            }

            int columnCount = headers.length;

            // 从 header 推断 schema（假设每一列形如 name(type)）
            Map<String, String> schema = parseHeader(headers);

            // 写表头（标准CSV格式）
            writer.write(escapeCsvFields(headers));
            writer.newLine();

            int injectedRowId = 0;   // 注入后数据集中的当前行号（不含表头，从1开始）
            int originalRowId = 0;   // 原始数据集中的当前行号（不含表头，从1开始）

            // 逐行读取数据
            while (csv.readRecord()) {
                originalRowId++;

                String[] values = csv.getValues();
                if (values == null) {
                    values = new String[0];
                }

                // 强制让每行列数 == 表头列数：不足补空字符串，多的截断
                if (values.length != columnCount) {
                    String[] fixed = new String[columnCount];
                    int copyLen = Math.min(values.length, columnCount);
                    System.arraycopy(values, 0, fixed, 0, copyLen);
                    for (int j = copyLen; j < columnCount; j++) {
                        fixed[j] = "";
                    }
                    values = fixed;
                }

                // ====== 写“原始行”（带单元格错误） ======
                injectedRowId++;
                String[] primaryRow = Arrays.copyOf(values, values.length);

                injectErrorsToRow(primaryRow, schema, injectedRowId);

                writer.write(escapeCsvFields(primaryRow));
                writer.newLine();

                // ====== 是否生成重复行 ======
                if (random.nextDouble() < profile.duplicateRowProb) {
                    // 记录哪一行被选中做重复（基于原始数据集的行号）
                    duplicatedRowIds.add(originalRowId);

                    injectedRowId++;
                    String[] duplicateRow = createDuplicateRow(primaryRow, injectedRowId, schema);
                    writer.write(escapeCsvFields(duplicateRow));
                    writer.newLine();
                }
            }
            } finally {
                csv.close();
            }
        }

        // 输出日志文件
        if (logPath != null) {
            writeErrorLog(logPath);
        }
    }

    /** 从 header 数组解析 Schema：形如 name(type) */
    private Map<String, String> parseHeader(String[] headerColumns) {
        Map<String, String> schema = new LinkedHashMap<>();
        for (String col : headerColumns) {
            if (col == null) continue;
            col = col.trim();
            int parenStart = col.indexOf('(');
            int parenEnd = col.indexOf(')');

            if (parenStart > 0 && parenEnd > parenStart) {
                String name = col.substring(0, parenStart).trim();
                String type = col.substring(parenStart + 1, parenEnd).trim();
                if (!isSupportedType(type)) {
                    throw new IllegalArgumentException("Unsupported type: " + type);
                }
                schema.put(name, type);
            } else {
                throw new IllegalArgumentException("Invalid column format (expect name(type)): " + col);
            }
        }
        return schema;
    }

    private boolean isSupportedType(String type) {
        String t = type.toLowerCase();
        return t.equals("string") || t.equals("integer") || t.equals("double");
    }

    /**
     * 创建重复行：
     * - FULL 模式：整行完全相同；
     * - PARTIAL 模式：只有主键列做 String 错误注入，其他列保持不变。
     *
     * @param originalRow   已经注入过单元格错误的那一行
     * @param injectedRowId 重复行在“注入后数据集”中的行号（不含表头，从1开始）
     * @param schema        列名 -> 类型
     */
    private String[] createDuplicateRow(String[] originalRow, int injectedRowId, Map<String, String> schema) {
        String[] duplicatedRow = originalRow.clone();

        if ("PARTIAL".equals(profile.duplicateMode)) {
            // 部分重复：只修改主键列，其他列保持不变
            if (profile.pkColumnIndex < duplicatedRow.length) {
                String pkColumnName = new ArrayList<>(schema.keySet()).get(profile.pkColumnIndex);
                String originalPk = duplicatedRow[profile.pkColumnIndex];
                String corruptedPk = injectStringError(originalPk);
                duplicatedRow[profile.pkColumnIndex] = corruptedPk;

                errorLog.add(new InjectedError(
                        injectedRowId,
                        pkColumnName + "[DUPLICATE_ROW]",
                        originalPk,
                        corruptedPk,
                        "DUPLICATE_PARTIAL"
                ));
            }
        } else {
            // 完全重复：所有列完全相同（只在日志中做一个标记）
            errorLog.add(new InjectedError(
                    injectedRowId,
                    "ALL_COLUMNS[DUPLICATE_ROW]",
                    "FULL_DUPLICATE",
                    "FULL_DUPLICATE",
                    "DUPLICATE_FULL"
            ));
        }

        return duplicatedRow;
    }

    /** 对一行数据注入单元格错误（rowId 为注入后数据集中的行号） */
    public void injectErrorsToRow(String[] values, Map<String, String> schema, int rowId) {
        List<String> columnNames = new ArrayList<>(schema.keySet());

        for (int i = 0; i < values.length && i < columnNames.size(); i++) {
            if (values[i] == null) {
                values[i] = "";
            }

            // 以全局 errorRate 控制这个单元格是否被尝试注入错误
            if (random.nextDouble() > profile.errorRate || values[i].isEmpty()) {
                continue;
            }

            String columnName = columnNames.get(i);
            String type = schema.get(columnName);
            String original = values[i];

            // 处理缺失值
            if (random.nextDouble() < profile.missingValueWeight) {
                errorLog.add(new InjectedError(rowId, columnName, original, "NULL", "MISSING_VALUE"));
                values[i] = "";
                continue;
            }

            String corrupted = injectByType(original, type, columnName, rowId);
            if (!original.equals(corrupted)) {
                values[i] = corrupted;
            }
        }
    }

    private String injectByType(String value, String type, String columnName, int rowId) {
        String original = value;
        String corrupted = value;
        String errorType = "UNKNOWN";

        try {
            switch (type.toLowerCase()) {
                case "string":
                    corrupted = injectStringError(original);
                    errorType = "TYPO";
                    break;
                case "integer":
                    long intVal = Long.parseLong(original);
                    corrupted = String.valueOf(injectIntError(intVal));
                    errorType = "RANGE_VIOLATION";
                    break;
                case "double":
                    double doubleVal = Double.parseDouble(original);
                    corrupted = String.valueOf(injectDoubleError(doubleVal));
                    errorType = "PRECISION_ERROR";
                    break;
            }
        } catch (NumberFormatException e) {
            // 解析失败时退化为字符串 typo
            corrupted = injectStringError(original);
            errorType = "PARSE_ERROR_FALLBACK";
        }

        if (!original.equals(corrupted)) {
            errorLog.add(new InjectedError(rowId, columnName, original, corrupted, errorType));
        }

        return corrupted;
    }

    private String injectStringError(String original) {
        if (original == null || original.length() < 2) return original;
        double rand = random.nextDouble();
        StringBuilder sb = new StringBuilder(original);

        if (rand < profile.charSwapProb) {
            int pos = random.nextInt(original.length() - 1);
            char temp = sb.charAt(pos);
            sb.setCharAt(pos, sb.charAt(pos + 1));
            sb.setCharAt(pos + 1, temp);
        } else if (rand < profile.charSwapProb + profile.charDropProb) {
            int pos = random.nextInt(original.length());
            sb.deleteCharAt(pos);
        } else {
            int pos = random.nextInt(original.length());
            char randomChar = (char) (random.nextInt(26) + 'a');
            sb.setCharAt(pos, randomChar);
        }
        return sb.toString();
    }

    private long injectIntError(long original) {
        double rand = random.nextDouble();
        if (rand < profile.signFlipProb) {
            return -original;
        } else {
            // 简单偏移
            return original + random.nextInt(201) - 100;
        }
    }

    private double injectDoubleError(double original) {
        double rand = random.nextDouble();
        if (rand < profile.precisionLossProb) {
            int precision = random.nextInt(3) + 1;
            double factor = Math.pow(10, precision);
            return Math.round(original * factor) / factor;
        } else if (rand < profile.precisionLossProb + profile.scientificNotationProb) {
            return Double.parseDouble(String.format("%e", original));
        } else {
            int roundingBase = random.nextInt(3) + 2;
            double factor = Math.pow(10, roundingBase);
            return Math.round(original / factor) * factor;
        }
    }

    /** 将字段数组转回CSV格式（自动加引号并转义） */
    private String escapeCsvFields(String[] fields) {
        return Arrays.stream(fields)
                .map(this::escapeCsvField)
                .collect(Collectors.joining(","));
    }

    private String escapeCsvField(String field) {
        if (field == null) {
            return "";
        }
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    /** 写入错误日志（UTF-8） */
    private void writeErrorLog(String logPath) throws IOException {
        try (BufferedWriter logWriter = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(logPath), StandardCharsets.UTF_8))) {

            logWriter.write("rowId,column,original,corrupted,errorType");
            logWriter.newLine();

            for (InjectedError error : errorLog) {
                String orig = escapeCsvField(error.originalValue.toString());
                String corr = escapeCsvField(error.corruptedValue.toString());

                logWriter.write(String.format("%d,%s,%s,%s,%s",
                        error.rowId,
                        error.columnName,
                        orig,
                        corr,
                        error.errorType
                ));
                logWriter.newLine();
            }
        }
    }

    public List<InjectedError> getErrorLog() {
        return errorLog;
    }

    /**
     * 返回“被选中做重复注入的原始行ID”（基于原始数据集，从1开始，不含表头）
     */
    public List<Integer> getDuplicatedRowIds() {
        return duplicatedRowIds;
    }

    public Map<String, Long> getErrorStatistics() {
        return errorLog.stream()
                .collect(Collectors.groupingBy(e -> e.errorType, Collectors.counting()));
    }

    // ========== main ==========
    /**
     * Usage: CsvErrorInjector [dir] [name] [seed]
     * dir / name / seed can also be given as -Dinject.dir, -Dinject.name, -Dinject.seed; the input file is
     * dir/name.csv, outputs are written by FileNameGenerator below dir. The original code had the
     * directory "D:/workspace/error_injection/" and the dataset "airport" hard coded.
     */
    public static void main(String[] args) throws IOException, InputIterationException {
        String path = args.length > 0 ? args[0] : System.getProperty("inject.dir");
        String name = args.length > 1 ? args[1] : System.getProperty("inject.name");
        long seed = args.length > 2 ? Long.parseLong(args[2]) : Long.getLong("inject.seed", 42L);
        if (path == null || name == null) {
            System.err.println("usage: CsvErrorInjector <dir> <name> [seed]   (reads <dir>/<name>.csv)");
            System.exit(2);
        }
        if (!path.endsWith("/") && !path.endsWith(File.separator)) path = path + File.separator;

        // 配置错误注入策略
        ErrorProfile profile = new ErrorProfile();
        profile.errorRate = Double.parseDouble(System.getProperty("inject.errorRate", "0.003"));
        profile.duplicateRowProb = Double.parseDouble(System.getProperty("inject.duplicateRowProb", "0.03"));
        profile.duplicateMode = System.getProperty("inject.duplicateMode", "FULL");
        profile.pkColumnIndex = Integer.getInteger("inject.pkColumn", 0);

        CsvErrorInjector injector = new CsvErrorInjector(profile, seed);
        FileNameGenerator.FileNameInfo fileNameInfo = FileNameGenerator.generateNextFileNames(name, path);
        String input = path + name + ".csv";
        if (!new File(input).isFile()) throw new FileNotFoundException(input);

        injector.injectErrors(input, fileNameInfo.outputPath, fileNameInfo.logPath);
        FileNameGenerator.appendExecutionDetails(fileNameInfo, fileNameInfo.nextIndex,
                input, injector.getErrorLog().size(),
                injector.getDuplicatedRowIds().size(),
                injector.getErrorStatistics());
        System.out.println("=====================================");
        System.out.println("错误注入完成");
        System.out.println("输入文件: " + input);
        System.out.println("输出文件: " + fileNameInfo.outputPath);
        System.out.println("日志文件: " + fileNameInfo.logPath);
        System.out.printf("单元格错误注入: %d 处%n", injector.getErrorLog().size());
        System.out.printf("重复行注入: %d 行%n", injector.getDuplicatedRowIds().size());
        System.out.println("错误类型分布:");
        injector.getErrorStatistics().forEach((type, count) -> System.out.printf("  %s: %d 处%n", type, count));
    }
}

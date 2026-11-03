package sgdc.dc.input;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.csvreader.CsvReader;
import sgdc.dc.helpers.IndexProvider;

/**
 * Parsed relation. Records are read with the CSV parser of {@link RelationalInput} (quotes, charset);
 * the reader is always closed. A record with fewer fields than the header is an error (reported with
 * its line number) unless input.padShort=true, in which case the missing fields are empty. Extra
 * fields are ignored with a warning. The original code swallowed every exception (the run went on
 * with a truncated relation) and failed with ArrayIndexOutOfBounds on short records.
 */
public class Input {
    private final int lineCount;
    private final List<ParsedColumn<?>> parsedColumns;
    private final String name;
    private final Map<String, Integer> nameLoc = new HashMap<>();
    private final Map<String, ParsedColumn<?>> name2ParsedColumn = new HashMap<>();

    /**
     * @param rowLimit    maximal number of records (<= 0: all)
     * @param columnLimit maximal number of columns (<= 0: all)
     */
    public Input(RelationalInput relationalInput, int rowLimit, int columnLimit) {
        final int columnCount = relationalInput.numberOfColumns();
        final int used = columnLimit > 0 ? Math.min(columnLimit, columnCount) : columnCount;
        Column[] columns = readColumns(relationalInput, rowLimit, used);
        this.lineCount = columns.length > 0 ? columns[0].size() : 0;
        for (int i = 0; i < used; i++) nameLoc.put(relationalInput.columnNames[i], i);
        this.parsedColumns = new ArrayList<>(columns.length);
        createParsedColumns(relationalInput, columns);
        this.name = relationalInput.relationName();
        log.info("input {}: {} rows, {} of {} columns", name, lineCount, used, columnCount);
    }

    public Input(RelationalInput relationalInput) {
        this(relationalInput, -1, -1);
    }

    private static Column[] readColumns(RelationalInput in, int rowLimit, int used) {
        Column[] columns = new Column[used];
        for (int i = 0; i < used; ++i) columns[i] = new Column(in.relationName(), in.columnNames[i]);
        boolean pad = Boolean.getBoolean("input.padShort");
        long longer = 0;
        CsvReader csv = null;
        try {
            csv = new CsvReader(in.filePath, in.delimiter, in.charset);
            csv.setSafetySwitch(false);                 // allow fields longer than 100,000 chars
            if (!csv.readHeaders()) throw new IllegalStateException("no header: " + in.filePath);
            int nLine = 0;
            while ((rowLimit <= 0 || nLine < rowLimit) && csv.readRecord()) {
                String[] line = csv.getValues();
                if (line.length < in.numberOfColumns() && !pad) {
                    throw new IllegalStateException(String.format(
                            "%s: record %d (file line %d) has %d fields, header has %d (use -Dinput.padShort=true to pad)",
                            in.filePath, nLine + 1, csv.getCurrentRecord() + 2, line.length, in.numberOfColumns()));
                }
                if (line.length > in.numberOfColumns()) longer++;
                for (int i = 0; i < used; ++i) columns[i].addLine(i < line.length ? line[i] : "");
                ++nLine;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + in.filePath, e);
        } finally {
            if (csv != null) csv.close();
        }
        if (longer > 0) log.warn("{}: {} record(s) have more fields than the header; the extra fields are ignored",
                in.filePath, longer);
        return columns;
    }

    private void createParsedColumns(RelationalInput relationalInput, Column[] columns) {
        int i = 0;
        for (Column c : columns) {
            ParsedColumn<?> pc;
            switch (c.getType()) {
            case LONG: {
                ParsedColumn<Long> p = new ParsedColumn<>(relationalInput.relationName(), c.getName(), Long.class, i);
                for (int l = 0; l < lineCount; ++l) p.addLine(c.getLong(l));
                pc = p;
                break;
            }
            case NUMERIC: {
                ParsedColumn<Double> p = new ParsedColumn<>(relationalInput.relationName(), c.getName(), Double.class, i);
                for (int l = 0; l < lineCount; ++l) p.addLine(c.getDouble(l));
                pc = p;
                break;
            }
            case STRING:
            default: {
                ParsedColumn<String> p = new ParsedColumn<>(relationalInput.relationName(), c.getName(), String.class, i);
                for (int l = 0; l < lineCount; ++l) p.addLine(c.getString(l));
                pc = p;
                break;
            }
            }
            parsedColumns.add(pc);
            name2ParsedColumn.put(c.getName(), pc);
            log.debug("column {} -> {}", c.getName(), pc.getType().getSimpleName());
            ++i;
        }
    }

    public int getLineCount() {
        return lineCount;
    }

    public ParsedColumn<?>[] getColumns() {
        return parsedColumns.toArray(new ParsedColumn[0]);
    }

    public String getName() {
        return name;
    }

    public Map<String, Integer> getNameLoc() {
        return Collections.unmodifiableMap(nameLoc);
    }

    public Map<String, ParsedColumn<?>> getName2ParsedColumn() {
        return Collections.unmodifiableMap(name2ParsedColumn);
    }

    /** Rank-encoded relation: values of the same type share one sorted dictionary (row-major). */
    public int[][] getInts() {
        final int COLUMN_COUNT = parsedColumns.size();
        final int ROW_COUNT = getLineCount();
        int[][] input2s = new int[ROW_COUNT][COLUMN_COUNT];
        IndexProvider<String> providerS = new IndexProvider<>();
        IndexProvider<Long> providerL = new IndexProvider<>();
        IndexProvider<Double> providerD = new IndexProvider<>();
        for (int col = 0; col < COLUMN_COUNT; ++col) {
            ParsedColumn<?> pc = parsedColumns.get(col);
            for (int line = 0; line < ROW_COUNT; ++line) {
                if (pc.getType() == String.class) providerS.getIndex((String) pc.getValue(line));
                else if (pc.getType() == Double.class) providerD.getIndex((Double) pc.getValue(line));
                else if (pc.getType() == Long.class) providerL.getIndex((Long) pc.getValue(line));
            }
        }
        providerS = IndexProvider.getSorted(providerS);
        providerL = IndexProvider.getSorted(providerL);
        providerD = IndexProvider.getSorted(providerD);
        for (int col = 0; col < COLUMN_COUNT; ++col) {
            ParsedColumn<?> pc = parsedColumns.get(col);
            for (int line = 0; line < ROW_COUNT; ++line) {
                if (pc.getType() == String.class)
                    input2s[line][col] = providerS.getIndex((String) pc.getValue(line)).intValue();
                else if (pc.getType() == Double.class)
                    input2s[line][col] = providerD.getIndex((Double) pc.getValue(line)).intValue();
                else if (pc.getType() == Long.class)
                    input2s[line][col] = providerL.getIndex((Long) pc.getValue(line)).intValue();
            }
        }
        return input2s;
    }

    private static final Logger log = LoggerFactory.getLogger(Input.class);
}

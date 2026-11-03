package sgdc.dc.input;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.csvreader.CsvReader;

/**
 * Header information of a CSV relation. Only the header is read here (with the same CSV parser and
 * charset that {@link Input} uses for the records, so quoted header fields containing commas are
 * handled correctly); the reader is closed immediately.
 *
 * <p>Properties: input.charset (UTF-8), input.delimiter (,).
 */
public class RelationalInput {

    public final int numberOfColumns;
    public final String relationName;
    public final String filePath;
    public final String[] columnNames;
    public final Charset charset;
    public final char delimiter;

    public RelationalInput(File file) {
        if (file == null || !file.isFile())
            throw new IllegalArgumentException("dataset file not found: " + (file == null ? "null" : file.getPath()));
        this.charset = Charset.forName(System.getProperty("input.charset", StandardCharsets.UTF_8.name()));
        String d = System.getProperty("input.delimiter", ",");
        if (d.length() != 1) throw new IllegalArgumentException("input.delimiter must be one character");
        this.delimiter = d.charAt(0);
        this.relationName = file.getName();
        this.filePath = file.getPath();
        CsvReader r = null;
        try {
            r = new CsvReader(filePath, delimiter, charset);
            if (!r.readHeaders())
                throw new IllegalArgumentException("dataset has no header line: " + filePath);
            this.columnNames = r.getHeaders();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read header of " + filePath, e);
        } finally {
            if (r != null) r.close();
        }
        if (columnNames.length == 0)
            throw new IllegalArgumentException("dataset has an empty header: " + filePath);
        this.numberOfColumns = columnNames.length;
    }

    public int numberOfColumns() {
        return numberOfColumns;
    }

    public String relationName() {
        return relationName;
    }

    public String[] columnNames() {
        return columnNames.clone();
    }
}

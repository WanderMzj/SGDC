package sgdc.dc.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sgdc.dc.helpers.ParserHelper;

/**
 * Raw (string) column with type detection.
 *
 * <p>Type: a type hint at the end of the header name is honoured when all non-empty values can be
 * parsed with it: "(String)", "(Integer)", "(Int)", "(Long)", "(Double)", "(Float)" (Metanome style)
 * or " str", " int", " float" (ECP style). Otherwise (no hint, or a value that does not parse) the
 * type is inferred from the non-empty values: LONG if all are integers, NUMERIC if all are numbers,
 * STRING otherwise; a hint that does not fit is reported with a warning. The original code only
 * checked whether the header contained the substrings "String"/"Double"/"Integer" anywhere (so a
 * column named e.g. "IntegerCount(String)" or "Doubles" was mistyped and "(Int)" was ignored) and
 * counted empty values as non-numeric.
 *
 * <p>Empty values of numeric columns: LONG -> Long.MIN_VALUE (input.nullLong), NUMERIC ->
 * Double.NEGATIVE_INFINITY (input.nullDouble). Both sort before every real value. The original code
 * used Double.MIN_VALUE (= 4.9e-324, the smallest POSITIVE double), which placed empty values between
 * the negative and the positive numbers.
 *
 * <p>The Hydra reference (ref/*_exact.tsv) was produced with the original encoding. The two encodings differ only
 * for order predicates on NUMERIC columns that contain empty values and negative numbers (in the 10 datasets:
 * inspection.Longitude, 782 empty values, all values negative); none of those reference DCs reaches tau
 * (max Score 0.5013), so D_tau is not affected. -Dinput.nullDouble=4.9E-324 reproduces Hydra's encoding.
 */
public class Column {

    public enum Type {
        STRING, NUMERIC, LONG
    }

    private static final Pattern HINT_PAREN = Pattern.compile("\\((String|Integer|Int|Long|Double|Float)\\)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HINT_SPACE = Pattern.compile("\\s(str|string|int|integer|long|float|double)\\s*$",
            Pattern.CASE_INSENSITIVE);

    static final long NULL_LONG = Long.parseLong(System.getProperty("input.nullLong", String.valueOf(Long.MIN_VALUE)));
    static final double NULL_DOUBLE = Double.parseDouble(System.getProperty("input.nullDouble", "-Infinity"));

    private final String tableName;
    private final String name;
    private final Type hint;
    private final List<String> values = new ArrayList<>();
    private boolean allLong = true;
    private boolean allDouble = true;
    private int empty = 0;
    private Type type;

    public Column(String tableName, String name) {
        this.tableName = tableName;
        this.name = name;
        this.hint = hintOf(name);
    }

    static Type hintOf(String name) {
        Matcher m = HINT_PAREN.matcher(name);
        String h = m.find() ? m.group(1) : null;
        if (h == null) {
            m = HINT_SPACE.matcher(name);
            h = m.find() ? m.group(1) : null;
        }
        if (h == null) return null;
        switch (h.toLowerCase(Locale.ROOT)) {
        case "string":
        case "str":
            return Type.STRING;
        case "integer":
        case "int":
        case "long":
            return Type.LONG;
        default:
            return Type.NUMERIC;
        }
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return tableName + "." + name;
    }

    public void addLine(String s) {
        if (s == null) s = "";
        values.add(s);
        type = null;
        if (s.isEmpty()) {
            empty++;
            return;
        }
        if (allLong && !isLong(s)) allLong = false;
        if (!allLong && allDouble && !ParserHelper.isDouble(s)) allDouble = false;
    }

    private static boolean isLong(String s) {
        if (!ParserHelper.isInteger(s)) return false;
        try {
            Long.parseLong(s);
            return true;
        } catch (NumberFormatException e) {
            return false;               // out of range
        }
    }

    public int size() {
        return values.size();
    }

    public Type getType() {
        if (type != null) return type;
        boolean anyValue = values.size() > empty;
        Type inferred = !anyValue ? Type.STRING : allLong ? Type.LONG : allDouble ? Type.NUMERIC : Type.STRING;
        Type t;
        if (hint == null) {
            t = inferred;
        } else if (hint == Type.STRING || (hint == Type.LONG && allLong) || (hint == Type.NUMERIC && allDouble)) {
            t = hint;
        } else {
            log.warn("column {}: header says {}, but the values are {}; using {}", name, hint, inferred, inferred);
            t = inferred;
        }
        type = t;
        return t;
    }

    public Long getLong(int line) {
        String v = values.get(line);
        return v.isEmpty() ? NULL_LONG : Long.valueOf(v);
    }

    public Double getDouble(int line) {
        String v = values.get(line);
        return v.isEmpty() ? NULL_DOUBLE : Double.valueOf(v);
    }

    public String getString(int line) {
        return values.get(line);
    }

    private static final Logger log = LoggerFactory.getLogger(Column.class);
}

package jarrunner.jr.apputils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Just enough JSON for jr's -Xjr:batch line and the install receipt: objects become LinkedHashMap, arrays ArrayList,
 *  numbers Double. No dependency, on purpose (this library sits on the startup path of every app that uses it). */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        var p = new Json(text);
        p.space();
        var v = p.value();
        p.space();
        if (p.i != p.s.length()) throw p.error("text after the value");
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(String text) {
        if (parse(text) instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("not a JSON object: " + text);
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end");
        var c = s.charAt(i);
        if (c == '{') {
            i++;
            var m = new LinkedHashMap<String, Object>();
            space();
            if (peek('}')) return m;
            do {
                space();
                var k = string();
                space();
                expect(':');
                space();
                m.put(k, value());
                space();
            } while (peek(','));
            expect('}');
            return m;
        }
        if (c == '[') {
            i++;
            var l = new ArrayList<Object>();
            space();
            if (peek(']')) return l;
            do {
                space();
                l.add(value());
                space();
            } while (peek(','));
            expect(']');
            return l;
        }
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        var start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("unexpected '" + c + "'");
        return Double.parseDouble(s.substring(start, i));
    }

    private String string() {
        expect('"');
        var b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            var c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            var e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                default -> b.append(e);
            }
        }
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected '" + c + "'");
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " at " + i);
    }

    /** Writes maps, lists, strings, numbers, booleans and null, two-space indented. */
    static String write(Object v) {
        var b = new StringBuilder();
        write(b, v, "");
        return b.append('\n').toString();
    }

    private static void write(StringBuilder b, Object v, String indent) {
        if (v == null) b.append("null");
        else if (v instanceof String str) quote(b, str);
        else if (v instanceof Boolean || v instanceof Number) b.append(v);
        else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append("{\n");
            var first = true;
            for (var e : m.entrySet()) {
                if (!first) b.append(",\n");
                first = false;
                b.append(indent).append("  ");
                quote(b, String.valueOf(e.getKey()));
                b.append(": ");
                write(b, e.getValue(), indent + "  ");
            }
            b.append('\n').append(indent).append('}');
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) { b.append("[]"); return; }
            b.append("[\n");
            for (var k = 0; k < l.size(); k++) {
                if (k > 0) b.append(",\n");
                b.append(indent).append("  ");
                write(b, l.get(k), indent + "  ");
            }
            b.append('\n').append(indent).append(']');
        } else quote(b, v.toString());
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (var k = 0; k < s.length(); k++) {
            var c = s.charAt(k);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        b.append('"');
    }
}

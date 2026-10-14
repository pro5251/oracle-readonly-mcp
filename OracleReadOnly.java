import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** Fixed SELECT tools only. No caller-supplied SQL or PL/SQL is accepted. */
public class OracleReadOnly {
    static final Set<String> TOOLS = Set.of("database_info", "list_objects", "table_columns", "table_constraints", "procedure_source", "procedure_arguments", "table_rows");
    static String value(Properties p, String key) {
        return new String(Base64.getDecoder().decode(p.getProperty(key, "")), StandardCharsets.UTF_8);
    }
    static String identifier(String text) {
        if (!text.matches("[A-Z][A-Z0-9_$#]{0,127}")) throw new IllegalArgumentException("Invalid identifier");
        return text;
    }
    static int number(Properties p, String key, int fallback, int max) {
        String v = value(p, key);
        int n = v.isEmpty() ? fallback : Integer.parseInt(v);
        if (n < 1 || n > max) throw new IllegalArgumentException("Invalid limit");
        return n;
    }
    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                default -> { if (c < 32) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }
    static String query(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setQueryTimeout(15); s.setMaxRows(2001); s.setFetchSize(100);
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (ResultSet r = s.executeQuery()) {
                StringBuilder out = new StringBuilder("{\"rows\":[");
                ResultSetMetaData m = r.getMetaData(); int count = 0; boolean truncated = false;
                while (r.next()) {
                    if (out.length() > 1_000_000 || count == 2000) { truncated = true; break; }
                    if (count++ > 0) out.append(','); out.append('{');
                    for (int i = 1; i <= m.getColumnCount(); i++) {
                        if (i > 1) out.append(',');
                        String v = r.getString(i);
                        // ponytail: large cells are capped at 4096 chars; use source line pagination for code.
                        if (v != null && v.length() > 4096) { v = v.substring(0, 4096); truncated = true; }
                        out.append(json(m.getColumnLabel(i))).append(':').append(v == null ? "null" : json(v));
                    }
                    out.append('}');
                }
                return out.append("],\"truncated\":").append(truncated).append('}').toString();
            }
        }
    }
    static String run(Connection c, String tool, Properties p) throws SQLException {
        String owner = value(p, "owner");
        if (owner.isEmpty() && !tool.equals("list_objects")) owner = c.getMetaData().getUserName().toUpperCase(Locale.ROOT);
        if (!owner.isEmpty()) identifier(owner);
        String name = value(p, "name");
        if (!name.isEmpty()) identifier(name);
        return switch (tool) {
            case "database_info" -> query(c, "SELECT banner FROM v$version");
            case "list_objects" -> {
                String pattern = value(p, "pattern");
                if (pattern.length() > 128) throw new IllegalArgumentException("Pattern too long");
                String type = value(p, "type");
                if (!type.isEmpty() && !Set.of("TABLE", "VIEW", "PROCEDURE", "FUNCTION", "PACKAGE", "PACKAGE BODY", "TRIGGER", "SEQUENCE", "SYNONYM").contains(type)) throw new IllegalArgumentException("Invalid type");
                yield query(c, "SELECT * FROM (SELECT owner, object_name, object_type, status FROM all_objects WHERE (? IS NULL OR owner = ?) AND object_name LIKE ? AND (? IS NULL OR object_type = ?) ORDER BY owner, object_type, object_name) WHERE ROWNUM <= ?", owner, owner, pattern.isEmpty() ? "%" : pattern, type, type, number(p, "limit", 100, 500));
            }
            case "table_columns" -> query(c, "SELECT column_id, column_name, data_type, data_length, data_precision, data_scale, nullable FROM all_tab_columns WHERE owner = ? AND table_name = ? ORDER BY column_id", owner, identifier(name));
            case "table_constraints" -> query(c, "SELECT c.constraint_name, c.constraint_type, c.status, k.column_name, k.position, c.r_owner, c.r_constraint_name, c.delete_rule FROM all_constraints c LEFT JOIN all_cons_columns k ON k.owner = c.owner AND k.constraint_name = c.constraint_name AND k.table_name = c.table_name WHERE c.owner = ? AND c.table_name = ? ORDER BY c.constraint_name, k.position", owner, identifier(name));
            case "procedure_source" -> {
                String type = value(p, "type");
                if (!Set.of("PROCEDURE", "FUNCTION", "PACKAGE", "PACKAGE BODY", "TRIGGER", "TYPE", "TYPE BODY").contains(type)) throw new IllegalArgumentException("Invalid source type");
                int start = number(p, "start_line", 1, 1_000_000), limit = number(p, "limit", 200, 1000);
                yield query(c, "SELECT owner, name, type, line, text FROM all_source WHERE owner = ? AND name = ? AND type = ? AND line >= ? AND line < ? ORDER BY line", owner, identifier(name), type, start, start + limit);
            }
            case "procedure_arguments" -> {
                String routine = value(p, "routine"); if (!routine.isEmpty()) identifier(routine);
                yield query(c, "SELECT owner, package_name, object_name, overload, argument_name, position, sequence, data_level, data_type, in_out, defaulted FROM all_arguments WHERE owner = ? AND (object_name = ? OR package_name = ?) AND (? IS NULL OR object_name = ?) ORDER BY package_name, object_name, overload, sequence", owner, identifier(name), name, routine, routine);
            }
            case "table_rows" -> sample(c, owner, identifier(name), number(p, "limit", 20, 100));
            default -> throw new IllegalArgumentException("Unknown tool");
        };
    }
    static String sample(Connection c, String owner, String name, int limit) throws SQLException {
        // Do not execute views, synonyms, external/object/temporary tables, or virtual columns.
        try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM all_tables t WHERE t.owner = ? AND t.table_name = ? AND t.temporary = 'N' AND t.nested = 'NO' AND t.secondary = 'N' AND NOT EXISTS (SELECT 1 FROM all_external_tables e WHERE e.owner = t.owner AND e.table_name = t.table_name) AND NOT EXISTS (SELECT 1 FROM all_object_tables o WHERE o.owner = t.owner AND o.table_name = t.table_name)")) {
            s.setQueryTimeout(15); s.setString(1, owner); s.setString(2, name);
            try (ResultSet r = s.executeQuery()) { if (!r.next()) throw new IllegalArgumentException("Only ordinary base tables may be sampled"); }
        }
        List<String> columns = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT column_name FROM all_tab_cols WHERE owner = ? AND table_name = ? AND virtual_column = 'NO' AND hidden_column = 'NO' AND (data_type IN ('CHAR','VARCHAR2','NCHAR','NVARCHAR2','NUMBER','FLOAT','BINARY_FLOAT','BINARY_DOUBLE','DATE') OR data_type LIKE 'TIMESTAMP%') ORDER BY column_id")) {
            s.setQueryTimeout(15); s.setString(1, owner); s.setString(2, name);
            try (ResultSet r = s.executeQuery()) { while (r.next()) columns.add('"' + identifier(r.getString(1)) + '"'); }
        }
        if (columns.isEmpty()) throw new IllegalArgumentException("No supported stored scalar columns");
        String projection = columns.stream().collect(Collectors.joining(","));
        return query(c, "SELECT " + projection + " FROM \"" + owner + "\".\"" + name + "\" WHERE ROWNUM <= ?", limit);
    }
    public static void main(String[] args) {
        try {
            if (args.length != 1 || !TOOLS.contains(args[0])) throw new IllegalArgumentException("Unknown tool");
            Properties p = new Properties(); p.load(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            Properties credentials = new Properties();
            credentials.setProperty("user", value(p, "user")); credentials.setProperty("password", value(p, "password"));
            credentials.setProperty("oracle.net.CONNECT_TIMEOUT", "10000"); credentials.setProperty("oracle.jdbc.ReadTimeout", "20000");
            credentials.setProperty("oracle.jdbc.timezoneAsRegion", "false");
            DriverManager.setLoginTimeout(15);
            try (Connection c = DriverManager.getConnection("jdbc:oracle:thin:@" + value(p, "dsn"), credentials)) {
                c.setAutoCommit(false);
                try {
                    try (Statement s = c.createStatement()) { s.setQueryTimeout(15); s.execute("SET TRANSACTION READ ONLY"); }
                    System.out.println(run(c, args[0], p));
                } finally { c.rollback(); }
            }
        } catch (SQLException e) {
            System.err.println("Oracle query failed (ORA-" + String.format("%05d", e.getErrorCode()) + ")");
            for (SQLException next = e.getNextException(); next != null; next = next.getNextException())
                System.err.println("Additional Oracle error: ORA-" + String.format("%05d", next.getErrorCode()));
            System.exit(1);
        } catch (Exception e) {
            System.err.println(e instanceof IllegalArgumentException ? e.getMessage() : "Oracle query setup failed"); System.exit(1);
        }
    }
}

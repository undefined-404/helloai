import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal psql replacement for verify scripts (no docker / no local psql).
 *
 * Usage: java -cp postgresql-<ver>.jar PgExec.java <jdbcUrl> <user> <password>
 *        (SQL via stdin, UTF-8; multiple statements separated by ';' run in order)
 *
 * Output mimics: psql -X -t -A -F '|'   (tuples only, unaligned, '|' separator, NULL as empty)
 * Exit code: 0 = all ok; 1 = any SQLException (ON_ERROR_STOP=1 semantics); 2 = bad args.
 */
public class PgExec {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PgExec <jdbcUrl> <user> <password> (SQL via stdin)");
            System.exit(2);
        }
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        String sql = new String(readAll(System.in), StandardCharsets.UTF_8);
        if (!sql.isEmpty() && sql.charAt(0) == '\uFEFF') {
            sql = sql.substring(1);   // strip BOM leaked by the piping shell (PS 5.1 native stdin)
        }
        List<String> stmts = splitStatements(sql);
        Class.forName("org.postgresql.Driver");
        try (Connection conn = DriverManager.getConnection(args[0], args[1], args[2])) {
            conn.setAutoCommit(true);
            for (String stmt : stmts) {
                if (stmt.trim().isEmpty()) {
                    continue;
                }
                try (Statement st = conn.createStatement()) {
                    boolean hasRs = st.execute(stmt);
                    if (hasRs) {
                        try (ResultSet rs = st.getResultSet()) {
                            dump(rs, out);
                        }
                    }
                } catch (SQLException e) {
                    out.flush();
                    System.err.println("ERROR: " + e.getMessage());
                    System.exit(1);
                }
            }
        }
        out.flush();
    }

    private static void dump(ResultSet rs, PrintStream out) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        StringBuilder sb = new StringBuilder();
        while (rs.next()) {
            for (int i = 1; i <= n; i++) {
                if (i > 1) {
                    sb.append('|');
                }
                String v = rs.getString(i);
                if (v != null) {
                    sb.append(v);
                }
            }
            sb.append('\n');
        }
        out.print(sb);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) {
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }

    /** split on ';' outside single-quoted strings (handles '' escape) */
    private static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inQuote) {
                cur.append(c);
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        cur.append('\'');
                        i++;
                    } else {
                        inQuote = false;
                    }
                }
            } else if (c == '\'') {
                inQuote = true;
                cur.append(c);
            } else if (c == ';') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}

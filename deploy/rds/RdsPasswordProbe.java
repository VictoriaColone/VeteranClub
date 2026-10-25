import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/** Read-only connection probe. Credentials arrive over stdin, never arguments. */
public final class RdsPasswordProbe {
    public static void main(String[] args) {
        try {
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String url = input.readLine();
            Properties properties = new Properties();
            properties.setProperty("user", input.readLine());
            properties.setProperty("password", input.readLine());
            properties.setProperty("connectTimeout", "5000");
            properties.setProperty("socketTimeout", "5000");
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection connection = DriverManager.getConnection(url, properties)) {
                connection.setReadOnly(true);
                try (Statement query = connection.createStatement();
                     ResultSet result = query.executeQuery("SELECT DATABASE(), CURRENT_USER(), COUNT(*) FROM club_order")) {
                    if (!result.next() || !"norman_acceptance".equals(result.getString(1))
                            || !result.getString(2).startsWith("norman_service@")) {
                        throw new IllegalStateException();
                    }
                    System.out.println("{\"connected\":true,\"order_count\":" + result.getLong(3) + "}");
                }
            }
        } catch (SQLException error) {
            String state = error.getSQLState();
            if (state == null || !state.matches("[A-Z0-9]{5}")) state = "unknown";
            System.out.println("{\"connected\":false,\"sql_state\":\"" + state
                    + "\",\"vendor_code\":" + error.getErrorCode() + "}");
            System.exit(2);
        } catch (Exception error) {
            System.out.println("{\"connected\":false,\"reason\":\"probe_failed\"}");
            System.exit(2);
        }
    }
}

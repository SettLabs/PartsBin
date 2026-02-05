package util.database;

import java.sql.Connection;
import java.sql.SQLException;
import org.sqlite.Function; // Ensure this is imported from the Xerial driver package

public class SQLiteUtils {

    public static void registerRegexFunction(Connection conn) throws SQLException {
        // --- 1. Define the custom REGEXP function ---
        Function.create(conn, "REGEXP", new Function() {
            /**
             * The xFunc method is the heart of the custom function.
             * It receives the arguments from the SQL query.
             */
            @Override
            protected void xFunc() throws SQLException {
                // Argument 0: The regex pattern (String)
                String regex = value_text(0);
                // Argument 1: The string value to check (String)
                String value = value_text(1);
/*
                System.out.println("--- REGEXP DEBUG START ---");
                System.out.println("Pattern (Arg 0): " + regex);
                System.out.println("Value (Arg 1): " + value);
*/
                // Check for NULL values before attempting to match
                if (value == null || regex == null) {
                    result(0); // Return false if either is null
                    return;
                }

                // --- 2. Perform the Java Regex Check ---
                try {
                    boolean matches = value.matches(regex);
                 //   System.err.println("Result:"+matches);
                    result(matches ? 1 : 0); // Return 1 for true, 0 for false
                } catch (Exception e) {
                    // Handle potential regex errors gracefully
                    System.err.println("Regex execution failed: " + e.getMessage());
                    result(0);
                }
            }
        });

        System.out.println("Custom SQL function 'REGEXP' registered successfully.");
    }
}

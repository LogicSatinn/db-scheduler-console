package io.github.logicsatinn.dbscheduler.console.data;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;

public enum Dialect {
    POSTGRES, MYSQL, MARIADB, SQLSERVER, ORACLE, H2;

    public static Optional<Dialect> fromProductName(String productName) {
        if (productName == null) {
            return Optional.empty();
        }
        String p = productName.toLowerCase(Locale.ROOT);
        if (p.contains("postgres")) return Optional.of(POSTGRES);
        if (p.contains("mariadb")) return Optional.of(MARIADB); // before mysql: compat strings
        if (p.contains("mysql")) return Optional.of(MYSQL);
        if (p.contains("microsoft sql server")) return Optional.of(SQLSERVER);
        if (p.contains("oracle")) return Optional.of(ORACLE);
        if (p.contains("h2")) return Optional.of(H2);
        return Optional.empty();
    }

    public static Optional<Dialect> fromDataSource(DataSource ds) {
        try (var con = ds.getConnection()) {
            return fromProductName(con.getMetaData().getDatabaseProductName());
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    /** Appended after an ORDER BY clause. Bind with {@link #paginationParams}. */
    public String paginationClause() {
        return switch (this) {
            case MYSQL, MARIADB -> " LIMIT ? OFFSET ?";
            default -> " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        };
    }

    public Object[] paginationParams(int offset, int limit) {
        return switch (this) {
            case MYSQL, MARIADB -> new Object[] {limit, offset};
            default -> new Object[] {offset, limit};
        };
    }

    /** Classpath location of the dsc_execution_history DDL for this dialect. */
    public String migrationResource() {
        String file = switch (this) {
            case POSTGRES -> "postgresql";
            case MYSQL -> "mysql";
            case MARIADB -> "mariadb";
            case SQLSERVER -> "sqlserver";
            case ORACLE -> "oracle";
            case H2 -> "h2";
        };
        return "db-scheduler-console/migrations/" + file + ".sql";
    }
}

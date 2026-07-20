package io.github.logicsatinn.dbscheduler.console.data;

import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    /**
     * SQL Server's datetimeoffset driver treats {@link Timestamp} as a local wall-clock value.
     * Bind an explicit UTC offset there so console reads, filters, and writes preserve Instants.
     */
    public Object jdbcTimestamp(Instant instant) {
        return this == SQLSERVER
                ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
                : Timestamp.from(instant);
    }

    /** Classpath location of the dsc_execution_history DDL for this dialect. */
    public String migrationResource() {
        return "db-scheduler-console/migrations/" + migrationFileName() + ".sql";
    }

    /** Classpath location of the 0.1.0-M1 to 0.1.0-M2 upgrade DDL. */
    public String upgradeMigrationResource() {
        return "db-scheduler-console/migrations/upgrade/0.1.0-M1-to-0.1.0-M2/"
                + migrationFileName() + ".sql";
    }

    private String migrationFileName() {
        return switch (this) {
            case POSTGRES -> "postgresql";
            case MYSQL -> "mysql";
            case MARIADB -> "mariadb";
            case SQLSERVER -> "sqlserver";
            case ORACLE -> "oracle";
            case H2 -> "h2";
        };
    }
}

package io.github.logicsatinn.dbscheduler.console.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class DialectTest {

    @Test
    void detectsKnownProductNames() {
        assertThat(Dialect.fromProductName("PostgreSQL")).contains(Dialect.POSTGRES);
        assertThat(Dialect.fromProductName("MySQL")).contains(Dialect.MYSQL);
        assertThat(Dialect.fromProductName("MariaDB")).contains(Dialect.MARIADB);
        // MariaDB servers can report "MySQL" wire compat strings that include "MariaDB"
        assertThat(Dialect.fromProductName("MySQL (MariaDB fork)")).contains(Dialect.MARIADB);
        assertThat(Dialect.fromProductName("Microsoft SQL Server")).contains(Dialect.SQLSERVER);
        assertThat(Dialect.fromProductName("Oracle")).contains(Dialect.ORACLE);
        assertThat(Dialect.fromProductName("H2")).contains(Dialect.H2);
        assertThat(Dialect.fromProductName("SQLite")).isEmpty();
        assertThat(Dialect.fromProductName(null)).isEmpty();
    }

    @Test
    void detectsFromDataSourceMetadata() {
        var ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        assertThat(Dialect.fromDataSource(ds)).contains(Dialect.H2);
    }

    @Test
    void paginationVariants() {
        assertThat(Dialect.MYSQL.paginationClause()).isEqualTo(" LIMIT ? OFFSET ?");
        assertThat(Dialect.MYSQL.paginationParams(40, 20)).containsExactly(20, 40);
        assertThat(Dialect.MARIADB.paginationClause()).isEqualTo(" LIMIT ? OFFSET ?");
        assertThat(Dialect.POSTGRES.paginationClause())
                .isEqualTo(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(Dialect.POSTGRES.paginationParams(40, 20)).containsExactly(40, 20);
        assertThat(Dialect.ORACLE.paginationClause())
                .isEqualTo(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
    }

    @Test
    @Disabled("until Task 4")
    void migrationResourcesExistOnClasspath() {
        for (Dialect d : Dialect.values()) {
            assertThat(getClass().getClassLoader().getResource(d.migrationResource()))
                    .as("migration for %s", d).isNotNull();
        }
    }
}

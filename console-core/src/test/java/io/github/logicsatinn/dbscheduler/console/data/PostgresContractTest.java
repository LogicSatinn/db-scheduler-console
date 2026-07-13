package io.github.logicsatinn.dbscheduler.console.data;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("containers")
@Testcontainers
class PostgresContractTest extends RepositoryContractTest {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource ds;

    @BeforeAll
    static void schema() {
        ds = simpleDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        applyScripts(ds, "db-scheduler-schema/postgresql.sql",
                "db-scheduler-console/migrations/postgresql.sql");
    }

    @Override protected DataSource dataSource() { return ds; }

    @Override protected Dialect dialect() { return Dialect.POSTGRES; }
}

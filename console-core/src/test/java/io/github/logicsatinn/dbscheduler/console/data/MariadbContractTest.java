package io.github.logicsatinn.dbscheduler.console.data;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("containers")
@Testcontainers
class MariadbContractTest extends RepositoryContractTest {

    @Container
    static final MariaDBContainer<?> DB = new MariaDBContainer<>("mariadb:11.4");

    static DataSource ds;

    @BeforeAll
    static void schema() {
        ds = simpleDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        applyScripts(ds, "db-scheduler-schema/mariadb.sql",
                "db-scheduler-console/migrations/mariadb.sql");
    }

    @Override protected DataSource dataSource() { return ds; }

    @Override protected Dialect dialect() { return Dialect.MARIADB; }
}

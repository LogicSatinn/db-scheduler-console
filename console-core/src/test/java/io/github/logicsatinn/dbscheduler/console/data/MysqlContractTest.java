package io.github.logicsatinn.dbscheduler.console.data;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("containers")
@Testcontainers
class MysqlContractTest extends RepositoryContractTest {

    @Container
    static final MySQLContainer<?> DB = new MySQLContainer<>("mysql:8.4");

    static DataSource ds;

    @BeforeAll
    static void schema() {
        ds = simpleDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        applyScripts(ds, "db-scheduler-schema/mysql.sql",
                "db-scheduler-console/migrations/mysql.sql");
    }

    @Override protected DataSource dataSource() { return ds; }

    @Override protected Dialect dialect() { return Dialect.MYSQL; }
}

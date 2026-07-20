package io.github.logicsatinn.dbscheduler.console.data;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

@Tag("containers")
@Testcontainers
class OracleContractTest extends RepositoryContractTest {

    @Container
    static final OracleContainer DB = new OracleContainer("gvenzl/oracle-free:23-slim-faststart");

    static DataSource ds;

    @BeforeAll
    static void schema() {
        ds = simpleDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        prepareSchema(ds, Dialect.ORACLE, "db-scheduler-schema/oracle.sql");
    }

    @Override protected DataSource dataSource() { return ds; }

    @Override protected Dialect dialect() { return Dialect.ORACLE; }
}

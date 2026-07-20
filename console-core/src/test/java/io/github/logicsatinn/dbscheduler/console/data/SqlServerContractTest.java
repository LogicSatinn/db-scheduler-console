package io.github.logicsatinn.dbscheduler.console.data;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("containers")
@Testcontainers
class SqlServerContractTest extends RepositoryContractTest {

    @Container
    static final MSSQLServerContainer<?> DB =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

    static DataSource ds;

    @BeforeAll
    static void schema() {
        ds = simpleDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        prepareSchema(ds, Dialect.SQLSERVER, "db-scheduler-schema/sqlserver.sql");
    }

    @Override protected DataSource dataSource() { return ds; }

    @Override protected Dialect dialect() { return Dialect.SQLSERVER; }
}

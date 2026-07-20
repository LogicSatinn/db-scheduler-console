package io.github.logicsatinn.dbscheduler.console.data;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class H2ContractTest extends RepositoryContractTest {

    static DataSource ds;

    @BeforeAll
    static void schema() {
        ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        prepareSchema(ds, Dialect.H2, "db-scheduler-schema/h2.sql");
    }

    @Override protected DataSource dataSource() { return ds; }

    @Override protected Dialect dialect() { return Dialect.H2; }
}

package io.github.logicsatinn.dbscheduler.console.data;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

public final class NonAutoCommitDataSource extends DelegatingDataSource {

    public NonAutoCommitDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection connection = super.getConnection(username, password);
        connection.setAutoCommit(false);
        return connection;
    }
}

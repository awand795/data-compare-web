package com.dbdiff.repository;

import com.dbdiff.model.ConnectionDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class ConnectionRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RowMapper<ConnectionDetails> rowMapper = new RowMapper<ConnectionDetails>() {
        @Override
        public ConnectionDetails mapRow(ResultSet rs, int rowNum) throws SQLException {
            ConnectionDetails c = new ConnectionDetails();
            c.setId(rs.getString("id"));
            c.setName(rs.getString("name"));
            c.setType(rs.getString("type"));
            c.setHost(rs.getString("host"));
            c.setPort(rs.getInt("port"));
            c.setDatabase(rs.getString("database_name"));
            c.setUsername(rs.getString("username"));
            c.setPassword(rs.getString("password"));
            return c;
        }
    };

    public List<ConnectionDetails> findAll() {
        return jdbcTemplate.query("SELECT * FROM connections", rowMapper);
    }

    public void save(ConnectionDetails c) {
        // Simple UPSERT for PostgreSQL
        String sql = "INSERT INTO connections (id, name, type, host, port, database_name, username, password) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO UPDATE SET " +
                     "name = EXCLUDED.name, type = EXCLUDED.type, host = EXCLUDED.host, " +
                     "port = EXCLUDED.port, database_name = EXCLUDED.database_name, " +
                     "username = EXCLUDED.username, password = EXCLUDED.password";
        jdbcTemplate.update(sql, c.getId(), c.getName(), c.getType(), c.getHost(), c.getPort(), c.getDatabase(), c.getUsername(), c.getPassword());
    }

    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM connections WHERE id = ?", id);
    }
}

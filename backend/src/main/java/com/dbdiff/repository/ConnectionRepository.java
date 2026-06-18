package com.dbdiff.repository;

import com.dbdiff.model.ConnectionDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.nio.charset.StandardCharsets;

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
            // Password stored base64-encoded from frontend; decode when loading
            c.setPassword(decodeOrKeepRaw(rs.getString("password")));
            c.setSchema(rs.getString("schema_name"));
            c.setSslMode(rs.getString("ssl_mode"));
            c.setSslCaFile(rs.getString("ssl_ca_file"));
            c.setSslCertFile(rs.getString("ssl_cert_file"));
            c.setSslKeyFile(rs.getString("ssl_key_file"));
            c.setUseSsh(rs.getBoolean("use_ssh"));
            c.setSshHost(rs.getString("ssh_host"));
            c.setSshPort(rs.getObject("ssh_port") != null ? rs.getInt("ssh_port") : null);
            c.setSshUsername(rs.getString("ssh_username"));
            c.setSshAuthMode(rs.getString("ssh_auth_mode"));
            c.setSshPassword(decodeOrKeepRaw(rs.getString("ssh_password")));
            c.setSshKeyFile(decodeOrKeepRaw(rs.getString("ssh_key_file")));
            c.setSshPassphrase(decodeOrKeepRaw(rs.getString("ssh_passphrase")));
            c.setConnectionTimeout(rs.getObject("connection_timeout") != null ? rs.getInt("connection_timeout") : null);
            c.setSocketTimeout(rs.getObject("socket_timeout") != null ? rs.getInt("socket_timeout") : null);
            c.setFetchSize(rs.getObject("fetch_size") != null ? rs.getInt("fetch_size") : null);
            c.setReadOnly(rs.getBoolean("read_only"));
            c.setExtraProps(rs.getString("extra_props"));
            return c;
        }
    };

    public List<ConnectionDetails> findAll() {
        return jdbcTemplate.query("SELECT * FROM connections", rowMapper);
    }
    
    public ConnectionDetails findById(String id) {
        List<ConnectionDetails> list = jdbcTemplate.query("SELECT * FROM connections WHERE id = ?", rowMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public void save(ConnectionDetails c) {
        String sql = "INSERT INTO connections (id, name, type, host, port, database_name, username, password, schema_name, ssl_mode, ssl_ca_file, ssl_cert_file, ssl_key_file, use_ssh, ssh_host, ssh_port, ssh_username, ssh_auth_mode, ssh_password, ssh_key_file, ssh_passphrase, connection_timeout, socket_timeout, fetch_size, read_only, extra_props) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (id) DO UPDATE SET " +
                     "name = EXCLUDED.name, type = EXCLUDED.type, host = EXCLUDED.host, " +
                     "port = EXCLUDED.port, database_name = EXCLUDED.database_name, " +
                     "username = EXCLUDED.username, password = EXCLUDED.password, " +
                     "schema_name = EXCLUDED.schema_name, ssl_mode = EXCLUDED.ssl_mode, " +
                     "ssl_ca_file = EXCLUDED.ssl_ca_file, ssl_cert_file = EXCLUDED.ssl_cert_file, " +
                     "ssl_key_file = EXCLUDED.ssl_key_file, use_ssh = EXCLUDED.use_ssh, " +
                     "ssh_host = EXCLUDED.ssh_host, ssh_port = EXCLUDED.ssh_port, " +
                     "ssh_username = EXCLUDED.ssh_username, ssh_auth_mode = EXCLUDED.ssh_auth_mode, " +
                     "ssh_password = EXCLUDED.ssh_password, ssh_key_file = EXCLUDED.ssh_key_file, " +
                     "ssh_passphrase = EXCLUDED.ssh_passphrase, connection_timeout = EXCLUDED.connection_timeout, " +
                     "socket_timeout = EXCLUDED.socket_timeout, fetch_size = EXCLUDED.fetch_size, " +
                     "read_only = EXCLUDED.read_only, extra_props = EXCLUDED.extra_props";
                     
        jdbcTemplate.update(sql, c.getId(), c.getName(), c.getType(), c.getHost(), c.getPort(), c.getDatabase(), c.getUsername(), c.getPassword(),
                c.getSchema(), c.getSslMode(), c.getSslCaFile(), c.getSslCertFile(), c.getSslKeyFile(),
                c.isUseSsh(), c.getSshHost(), c.getSshPort(), c.getSshUsername(), c.getSshAuthMode(), c.getSshPassword(), c.getSshKeyFile(), c.getSshPassphrase(),
                c.getConnectionTimeout(), c.getSocketTimeout(), c.getFetchSize(), c.isReadOnly(), c.getExtraProps());
    }

    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM connections WHERE id = ?", id);
    }

    private String decodeOrKeepRaw(String raw) {
        if (raw == null) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(raw);
            String decodedStr = new String(decoded, StandardCharsets.UTF_8);
            if (decodedStr.contains("\uFFFD")) {
                return raw; // Invalid UTF-8 sequence, so it's likely a raw password that happened to be valid Base64 length
            }
            for (char ch : decodedStr.toCharArray()) {
                if (ch < 32 && ch != '\t' && ch != '\n' && ch != '\r') {
                    return raw; // Contains unprintable control characters, likely a raw password
                }
            }
            return decodedStr;
        } catch (Exception e) {
            return raw;
        }
    }
}

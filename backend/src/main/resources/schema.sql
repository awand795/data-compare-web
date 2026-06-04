CREATE TABLE IF NOT EXISTS connections (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(50) NOT NULL,
    host VARCHAR(200) NOT NULL,
    port INT NOT NULL,
    database_name VARCHAR(100) NOT NULL,
    username VARCHAR(100) NOT NULL,
    password VARCHAR(200),
    schema_name VARCHAR(100),
    ssl_mode VARCHAR(50),
    ssl_ca_file VARCHAR(255),
    ssl_cert_file VARCHAR(255),
    ssl_key_file VARCHAR(255),
    use_ssh BOOLEAN DEFAULT FALSE,
    ssh_host VARCHAR(200),
    ssh_port INT,
    ssh_username VARCHAR(100),
    ssh_auth_mode VARCHAR(50),
    ssh_password VARCHAR(200),
    ssh_key_file VARCHAR(255),
    ssh_passphrase VARCHAR(200),
    connection_timeout INT,
    socket_timeout INT,
    fetch_size INT,
    read_only BOOLEAN DEFAULT FALSE,
    extra_props VARCHAR(1000)
);

ALTER TABLE connections ADD COLUMN IF NOT EXISTS schema_name VARCHAR(100);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssl_mode VARCHAR(50);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssl_ca_file VARCHAR(255);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssl_cert_file VARCHAR(255);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssl_key_file VARCHAR(255);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS use_ssh BOOLEAN DEFAULT FALSE;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_host VARCHAR(200);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_port INT;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_username VARCHAR(100);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_auth_mode VARCHAR(50);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_password VARCHAR(200);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_key_file VARCHAR(255);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_passphrase VARCHAR(200);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS connection_timeout INT;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS socket_timeout INT;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS fetch_size INT;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS read_only BOOLEAN DEFAULT FALSE;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS extra_props VARCHAR(1000);

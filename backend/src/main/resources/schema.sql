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
    ssl_ca_file TEXT,
    ssl_cert_file TEXT,
    ssl_key_file TEXT,
    use_ssh BOOLEAN DEFAULT FALSE,
    ssh_host VARCHAR(200),
    ssh_port INT,
    ssh_username VARCHAR(100),
    ssh_auth_mode VARCHAR(50),
    ssh_password VARCHAR(200),
    ssh_key_file TEXT,
    ssh_passphrase VARCHAR(200),
    connection_timeout INT,
    socket_timeout INT,
    fetch_size INT,
    read_only BOOLEAN DEFAULT FALSE,
    extra_props VARCHAR(1000),
    enable_data_warehouse BOOLEAN DEFAULT FALSE
);

ALTER TABLE connections ADD COLUMN IF NOT EXISTS schema_name VARCHAR(100);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssl_mode VARCHAR(50);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssl_ca_file TEXT;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssl_cert_file TEXT;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssl_key_file TEXT;
ALTER TABLE connections ALTER COLUMN ssl_ca_file TYPE TEXT;
ALTER TABLE connections ALTER COLUMN ssl_cert_file TYPE TEXT;
ALTER TABLE connections ALTER COLUMN ssl_key_file TYPE TEXT;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS use_ssh BOOLEAN DEFAULT FALSE;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_host VARCHAR(200);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_port INT;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_username VARCHAR(100);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_auth_mode VARCHAR(50);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_password VARCHAR(200);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_key_file TEXT;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_passphrase VARCHAR(200);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS connection_timeout INT;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS socket_timeout INT;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS fetch_size INT;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS read_only BOOLEAN DEFAULT FALSE;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS extra_props VARCHAR(1000);
ALTER TABLE connections ADD COLUMN IF NOT EXISTS enable_data_warehouse BOOLEAN DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS schedules (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    source_connection_id VARCHAR(50) NOT NULL,
    target_connection_id VARCHAR(50) NOT NULL,
    source_table VARCHAR(200),
    target_table VARCHAR(200),
    cron_expression VARCHAR(100) NOT NULL,
    telegram_bot_token VARCHAR(255),
    telegram_chat_id VARCHAR(100),
    discord_webhook_url VARCHAR(500),
    save_full_data BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_run TIMESTAMP
);

CREATE TABLE IF NOT EXISTS schedule_results (
    id VARCHAR(50) PRIMARY KEY,
    schedule_id VARCHAR(50) NOT NULL,
    run_time TIMESTAMP NOT NULL,
    match_count INT DEFAULT 0,
    different_count INT DEFAULT 0,
    source_only_count INT DEFAULT 0,
    target_only_count INT DEFAULT 0,
    error_message TEXT,
    FOREIGN KEY (schedule_id) REFERENCES schedules(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS schedule_result_rows (
    id SERIAL PRIMARY KEY,
    result_id VARCHAR(50) NOT NULL,
    row_key VARCHAR(255),
    status VARCHAR(50),
    data_json TEXT,
    table_name VARCHAR(200),
    FOREIGN KEY (result_id) REFERENCES schedule_results(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS notification_channels (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL,
    bot_token VARCHAR(255),
    chat_id VARCHAR(100),
    webhook_url VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE schedules ADD COLUMN IF NOT EXISTS custom_query_source TEXT;
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS custom_query_target TEXT;
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS primary_keys VARCHAR(500);
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS exclude_columns VARCHAR(500);
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS sort_columns VARCHAR(500);
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS telegram_channel_id VARCHAR(50);
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS discord_channel_id VARCHAR(50);
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS mappings TEXT;
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS notify_only_on_diff BOOLEAN DEFAULT FALSE;
ALTER TABLE schedules ADD COLUMN IF NOT EXISTS disable_on_error BOOLEAN DEFAULT FALSE;
ALTER TABLE schedule_results ADD COLUMN IF NOT EXISTS details TEXT;
ALTER TABLE schedule_result_rows ADD COLUMN IF NOT EXISTS table_name VARCHAR(200);

CREATE TABLE IF NOT EXISTS templates (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    app_mode VARCHAR(20) NOT NULL,
    source_connection_id VARCHAR(50),
    target_connection_id VARCHAR(50),
    table_mappings TEXT,
    custom_query_source TEXT,
    custom_query_target TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE templates ADD COLUMN IF NOT EXISTS query_primary_keys TEXT;

ALTER TABLE schedules ALTER COLUMN source_table DROP NOT NULL;
ALTER TABLE schedules ALTER COLUMN target_table DROP NOT NULL;
ALTER TABLE connections ADD COLUMN IF NOT EXISTS ssh_strict_host_key_checking BOOLEAN DEFAULT TRUE;

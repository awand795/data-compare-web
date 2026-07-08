const { Client } = require('pg');
const { Client: SSHClient } = require('ssh2');
const fs = require('fs');
const net = require('net');

async function testConnection() {
  console.log("Connecting to internal database...");
  const internalDb = new Client({
    connectionString: 'postgres://postgres:dataAnalyticW2024@war.darkosuite.com:8832/data_setting_sync',
    ssl: { rejectUnauthorized: false }
  });
  
  await internalDb.connect();
  const res = await internalDb.query("SELECT * FROM connections WHERE name LIKE '%ATM%'");
  await internalDb.end();

  if (res.rows.length === 0) {
    console.log("No ATM ERP connection found.");
    return;
  }
  const atm = res.rows[0];
  console.log("Found ATM ERP:", atm.name, "->", atm.host + ":" + atm.port);
  console.log("SSH settings:", atm.ssh_host + ":" + atm.ssh_port, "user:", atm.ssh_username);

  console.log("Establishing SSH Tunnel...");
  const ssh = new SSHClient();
  
  ssh.on('ready', () => {
    console.log('SSH tunnel ready. Forwarding port to', atm.host, atm.port);
    ssh.forwardOut('127.0.0.1', 12345, atm.host, atm.port, (err, stream) => {
      if (err) {
        console.error("SSH Forwarding error:", err);
        ssh.end();
        return;
      }
      
      console.log('Port forwarded successfully. Connecting to PostgreSQL...');
      
      // Start a local server to map the stream to a local port
      const server = net.createServer((socket) => {
        stream.pipe(socket);
        socket.pipe(stream);
      }).listen(0, '127.0.0.1', async () => {
        const localPort = server.address().port;
        console.log(`Local mapping created at 127.0.0.1:${localPort}`);
        
        const testClient = new Client({
          host: '127.0.0.1',
          port: localPort,
          database: atm.database_name,
          user: atm.username,
          password: atm.password,
          ssl: { rejectUnauthorized: false },
          connectionTimeoutMillis: 10000 // 10 seconds timeout
        });
        
        try {
          console.log("Sending PG Startup message...");
          await testClient.connect();
          console.log("SUCCESS! Connected to ATM ERP database.");
          const res = await testClient.query("SELECT 1 as test");
          console.log("Query success:", res.rows);
          await testClient.end();
        } catch (e) {
          console.error("ERROR CONNECTING TO ATM ERP:", e);
        } finally {
          server.close();
          ssh.end();
        }
      });
    });
  });
  
  const rawKey = atm.ssh_key_file;
  let decodedKey = rawKey;
  if (rawKey && !rawKey.includes('-----BEGIN')) {
     decodedKey = Buffer.from(rawKey, 'base64').toString('utf8');
  }
  
  ssh.connect({
    host: atm.ssh_host,
    port: atm.ssh_port,
    username: atm.ssh_username,
    privateKey: decodedKey
  });
}

testConnection().catch(console.error);

package com.dbdiff.service;

import com.dbdiff.model.ConnectionDetails;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class SshTunnelService {

    private static final Logger logger = LoggerFactory.getLogger(SshTunnelService.class);

    private final Map<String, Session> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, Integer> localPorts = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public int getOrOpenTunnel(ConnectionDetails details) throws Exception {
        if (!details.isUseSsh()) {
            return details.getPort();
        }
        lock.lock();
        try {
            String connId = details.getId() != null && !details.getId().isBlank() 
                ? details.getId() 
                : "temp_" + java.util.UUID.randomUUID().toString();
            if (activeSessions.containsKey(connId) && activeSessions.get(connId).isConnected()) {
                int cachedPort = localPorts.get(connId);
                logger.debug("Reusing existing SSH tunnel for {} on localhost:{}", connId, cachedPort);
                return cachedPort;
            }

            closeTunnelInternal(connId);

            JSch jsch = new JSch();
            
            if ("key".equalsIgnoreCase(details.getSshAuthMode()) && details.getSshKeyFile() != null && !details.getSshKeyFile().trim().isEmpty()) {
                byte[] prvk = details.getSshKeyFile().getBytes();
                byte[] passphrase = (details.getSshPassphrase() != null && !details.getSshPassphrase().isEmpty()) 
                                        ? details.getSshPassphrase().getBytes() 
                                        : null;
                jsch.addIdentity("ssh-key", prvk, null, passphrase);
            }

            int sshPort = (details.getSshPort() != null && details.getSshPort() > 0) ? details.getSshPort() : 22;
            Session session = jsch.getSession(details.getSshUsername(), details.getSshHost(), sshPort);

            if ("password".equalsIgnoreCase(details.getSshAuthMode())) {
                session.setPassword(details.getSshPassword());
            }

            // SSH keepalive agar tunnel tidak mati di tengah streaming lama
            session.setConfig("StrictHostKeyChecking", "no");
            session.setServerAliveInterval(30000);  // ping SSH server setiap 30 detik
            session.setServerAliveCountMax(3);       // max 3 kali gagal sebelum disconnect
            
            logger.info("Opening SSH tunnel to {}@{}:{}", details.getSshUsername(), details.getSshHost(), sshPort);
            session.connect(60000);  // naik dari 30s → 60s untuk koneksi lambat

            int assignedLocalPort = session.setPortForwardingL("127.0.0.1", 0, details.getHost(), details.getPort());
            
            activeSessions.put(connId, session);
            localPorts.put(connId, assignedLocalPort);

            logger.info("SSH tunnel established: localhost:{} → {}:{} via {}@{}",
                assignedLocalPort, details.getHost(), details.getPort(), details.getSshUsername(), details.getSshHost());

            return assignedLocalPort;
        } finally {
            lock.unlock();
        }
    }

    public void closeTunnel(String connectionId) {
        if (connectionId == null) return;
        lock.lock();
        try {
            closeTunnelInternal(connectionId);
        } finally {
            lock.unlock();
        }
    }

    private void closeTunnelInternal(String connectionId) {
        Session session = activeSessions.remove(connectionId);
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        localPorts.remove(connectionId);
    }
}

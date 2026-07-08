package com.dbdiff.service;

import com.dbdiff.model.ConnectionDetails;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.factory.DisposableBean;

@Service
public class SshTunnelService implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(SshTunnelService.class);

    private final Map<String, Session> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, Integer> localPorts = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    public int getOrOpenTunnel(ConnectionDetails details, String connId) throws Exception {
        if (!details.isUseSsh()) {
            return details.getPort();
        }
        lock.lock();
        try {
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
            // FORCE 'no' for headless docker environment to prevent "reject HostKey" exception
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("PreferredAuthentications", "publickey,password");
            session.setConfig("PubkeyAcceptedAlgorithms", "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa");
            session.setConfig("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-rsa");
            session.setConfig("kex", "curve25519-sha256,curve25519-sha256@libssh.org,diffie-hellman-group18-sha512,diffie-hellman-group16-sha512,diffie-hellman-group-exchange-sha256");
            session.setConfig("TCPKeepAlive", "yes"); // Let OS handle keep-alive to avoid blocking during heavy data stream
            session.setServerAliveInterval(15000);    // 15 detik (agar cepat mendeteksi tunnel putus)
            session.setServerAliveCountMax(3);        // 3 kali gagal = 45 detik
            
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

    /**
     * Cek apakah SSH tunnel masih benar-benar hidup.
     * Menggunakan session.sendKeepAliveMsg() lebih akurat daripada sekadar probe TCP lokal
     * karena JSch tetap membuka port lokal meskipun koneksi ke server SSH sudah terputus.
     */
    public boolean isTunnelHealthy(String connId) {
        Session session;
        lock.lock();
        try {
            session = activeSessions.get(connId);
        } finally {
            lock.unlock();
        }

        if (session == null || !session.isConnected()) {
            return false;
        }

        try {
            // Mengirim global request SSH-level keepalive ke server
            session.sendKeepAliveMsg();
            return true;
        } catch (Exception e) {
            logger.warn("SSH tunnel health-check FAILED for {}: {}", connId, e.getMessage());
            return false;
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
        if (session != null) {
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (Exception ignored) {
                // abaikan error saat menutup session yang sudah mati
            }
        }
        localPorts.remove(connectionId);
    }

    @Override
    public void destroy() throws Exception {
        lock.lock();
        try {
            logger.info("Shutting down all active SSH tunnels during application exit...");
            for (Map.Entry<String, Session> entry : activeSessions.entrySet()) {
                if (entry.getValue() != null && entry.getValue().isConnected()) {
                    entry.getValue().disconnect();
                    logger.info("Closed SSH tunnel for {}", entry.getKey());
                }
            }
            activeSessions.clear();
            localPorts.clear();
        } finally {
            lock.unlock();
        }
    }
}

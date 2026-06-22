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

    /**
     * Cek apakah SSH tunnel masih benar-benar hidup dengan membuka test TCP socket
     * ke localhost:localPort. session.isConnected() saja tidak cukup — JSch bisa
     * mengira tunnel masih aktif padahal server sudah memutus koneksi secara diam-diam.
     */
    public boolean isTunnelHealthy(String connId) {
        Session session;
        Integer port;
        lock.lock();
        try {
            session = activeSessions.get(connId);
            port = localPorts.get(connId);
        } finally {
            lock.unlock();
        }

        if (session == null || !session.isConnected() || port == null) {
            return false;
        }

        // Probe TCP ke port forwarding — ini yang paling akurat
        try (Socket probe = new Socket()) {
            probe.connect(new java.net.InetSocketAddress("127.0.0.1", port), 3000);
            return true;
        } catch (Exception e) {
            logger.warn("SSH tunnel health-check FAILED for {} on port {}: {}", connId, port, e.getMessage());
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

package com.dbdiff.service;

import com.dbdiff.model.ConnectionDetails;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SshTunnelService {

    private final Map<String, Session> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, Integer> localPorts = new ConcurrentHashMap<>();

    public synchronized int getOrOpenTunnel(ConnectionDetails details) throws Exception {
        if (!details.isUseSsh()) {
            return details.getPort();
        }
        String connId = details.getId();
        if (activeSessions.containsKey(connId) && activeSessions.get(connId).isConnected()) {
            return localPorts.get(connId);
        }

        closeTunnel(connId);

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

        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(30000);

        int assignedLocalPort = session.setPortForwardingL(0, details.getHost(), details.getPort());
        
        activeSessions.put(connId, session);
        localPorts.put(connId, assignedLocalPort);

        return assignedLocalPort;
    }

    public synchronized void closeTunnel(String connectionId) {
        if (connectionId == null) return;
        Session session = activeSessions.remove(connectionId);
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        localPorts.remove(connectionId);
    }
}

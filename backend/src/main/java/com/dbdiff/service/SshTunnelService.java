package com.dbdiff.service;

import com.dbdiff.model.ConnectionDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.DisposableBean;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class SshTunnelService implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(SshTunnelService.class);

    private final Map<String, Process> activeAutossh = new ConcurrentHashMap<>();
    private final Map<String, Integer> localPorts = new ConcurrentHashMap<>();
    private final Map<String, ConnectionDetails> connectionDetailsMap = new ConcurrentHashMap<>();
    private final Set<String> permanentTunnels = ConcurrentHashMap.newKeySet();
    private final Map<String, ReentrantLock> connectionLocks = new ConcurrentHashMap<>();

    private ReentrantLock getLock(String connId) {
        return connectionLocks.computeIfAbsent(connId, k -> new ReentrantLock());
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000)
    public void checkAndReconnectTunnels() {
        for (String connId : permanentTunnels) {
            if (!isTunnelHealthy(connId)) {
                logger.warn("Auto-Heal: autossh process {} is dead. Attempting to restart...", connId);
                ConnectionDetails details = connectionDetailsMap.get(connId);
                if (details != null) {
                    try {
                        getOrOpenTunnel(details, connId);
                        logger.info("Auto-Heal: Successfully restarted autossh for {}", connId);
                    } catch (Exception e) {
                        logger.error("Auto-Heal: Failed to restart autossh {}: {}", connId, e.getMessage());
                    }
                }
            }
        }
    }

    public int getOrOpenTunnel(ConnectionDetails details, String connId) throws Exception {
        if (!details.isUseSsh()) {
            return details.getPort();
        }
        ReentrantLock connLock = getLock(connId);
        connLock.lock();
        try {
            return getOrOpenTunnelInternal(details, connId);
        } finally {
            connLock.unlock();
        }
    }

    private int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private int getOrOpenTunnelInternal(ConnectionDetails details, String connId) throws Exception {
        if (activeAutossh.containsKey(connId)) {
            Process p = activeAutossh.get(connId);
            if (p.isAlive()) {
                int cachedPort = localPorts.get(connId);
                logger.debug("Reusing existing autossh process for {} on localhost:{}", connId, cachedPort);
                return cachedPort;
            } else {
                closeTunnelInternal(connId);
            }
        }

        int assignedLocalPort = localPorts.containsKey(connId) ? localPorts.get(connId) : findFreePort();
        int sshPort = (details.getSshPort() != null && details.getSshPort() > 0) ? details.getSshPort() : 22;

        List<String> cmd = new ArrayList<>();
        
        String keyPath = null;
        if ("password".equalsIgnoreCase(details.getSshAuthMode())) {
            cmd.add("sshpass");
            cmd.add("-p");
            cmd.add(details.getSshPassword());
        } else if ("key".equalsIgnoreCase(details.getSshAuthMode()) && details.getSshKeyFile() != null) {
            keyPath = "/tmp/key_" + connId + ".pem";
            Files.writeString(Paths.get(keyPath), details.getSshKeyFile());
            try {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(Paths.get(keyPath), perms);
            } catch (Exception e) {
                // Ignore on non-POSIX systems (e.g. Windows local run)
            }
        }

        cmd.add("autossh");
        cmd.add("-M");
        cmd.add("0"); // Use ServerAliveInterval instead of monitor port
        cmd.add("-N");
        cmd.add("-o"); cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o"); cmd.add("ServerAliveInterval=15");
        cmd.add("-o"); cmd.add("ServerAliveCountMax=3");
        cmd.add("-o"); cmd.add("ExitOnForwardFailure=yes");
        
        if (keyPath != null) {
            cmd.add("-i");
            cmd.add(keyPath);
        }
        
        cmd.add("-p");
        cmd.add(String.valueOf(sshPort));
        cmd.add("-L");
        cmd.add("0.0.0.0:" + assignedLocalPort + ":" + details.getHost() + ":" + details.getPort());
        cmd.add(details.getSshUsername() + "@" + details.getSshHost());

        // Do not print password in logs
        List<String> logCmd = new ArrayList<>(cmd);
        if ("password".equalsIgnoreCase(details.getSshAuthMode())) {
            logCmd.set(2, "****");
        }
        logger.info("Spawning autossh: {}", String.join(" ", logCmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        // Wait briefly to see if it immediately crashes (auth failure, bad port, etc)
        Thread.sleep(1500);
        if (!process.isAlive()) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new RuntimeException("autossh failed to start: " + output);
        }
        
        activeAutossh.put(connId, process);
        localPorts.put(connId, assignedLocalPort);
        connectionDetailsMap.put(connId, details);

        logger.info("autossh tunnel established: localhost:{} → {}:{} via {}@{}",
            assignedLocalPort, details.getHost(), details.getPort(), details.getSshUsername(), details.getSshHost());

        return assignedLocalPort;
    }

    public boolean isTunnelHealthy(String connId) {
        Process p = activeAutossh.get(connId);
        return p != null && p.isAlive();
    }

    public void markTunnelAsPermanent(String connectionId) {
        if (connectionId != null) {
            permanentTunnels.add(connectionId);
        }
    }

    public void registerAndRecoverTunnel(String connectionId, ConnectionDetails details, int expectedPort) {
        if (connectionId == null || details == null) return;
        permanentTunnels.add(connectionId);
        connectionDetailsMap.put(connectionId, details);
        if (!localPorts.containsKey(connectionId)) {
            localPorts.put(connectionId, expectedPort);
        }
    }

    public void closeTunnel(String connectionId) {
        if (connectionId == null) return;
        if (permanentTunnels.contains(connectionId)) {
            logger.info("Ignoring close request for permanent SSH tunnel: {}", connectionId);
            return;
        }
        ReentrantLock connLock = getLock(connectionId);
        connLock.lock();
        try {
            closeTunnelInternal(connectionId);
        } finally {
            connLock.unlock();
        }
    }

    private void closeTunnelInternal(String connectionId) {
        Process p = activeAutossh.remove(connectionId);
        if (p != null) {
            try {
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
            } catch (Exception ignored) {}
        }
        
        try {
            Files.deleteIfExists(Paths.get("/tmp/key_" + connectionId + ".pem"));
        } catch (Exception ignored) {}
        
        localPorts.remove(connectionId);
    }

    @Override
    public void destroy() throws Exception {
        logger.info("Shutting down all active autossh processes during application exit...");
        for (Map.Entry<String, Process> entry : activeAutossh.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isAlive()) {
                try {
                    entry.getValue().destroyForcibly();
                } catch (Exception ignored) {}
                logger.info("Killed autossh for {}", entry.getKey());
            }
            try {
                Files.deleteIfExists(Paths.get("/tmp/key_" + entry.getKey() + ".pem"));
            } catch (Exception ignored) {}
        }
        activeAutossh.clear();
        localPorts.clear();
    }
}

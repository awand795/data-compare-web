package com.dbdiff.service;

import com.dbdiff.model.SystemAlertSchedule;
import com.dbdiff.model.SystemMetrics;
import com.dbdiff.model.SystemMetrics.DiskInfo;
import com.dbdiff.model.SystemMetrics.MemoryInfo;
import com.dbdiff.repository.NotificationChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class SystemMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(SystemMonitorService.class);

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationChannelRepository channelRepository;

    public SystemMetrics getSystemMetrics() {
        SystemMetrics metrics = new SystemMetrics();

        // Host & OS Info
        try {
            metrics.setHostName(InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            metrics.setHostName(System.getenv("HOSTNAME") != null ? System.getenv("HOSTNAME") : "server-host");
        }
        metrics.setOsName(System.getProperty("os.name") + " " + System.getProperty("os.version"));
        metrics.setCpuCores(Runtime.getRuntime().availableProcessors());

        // CPU & Load via OperatingSystemMXBean
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
            if (osBean != null) {
                double cpuLoad = osBean.getCpuLoad();
                if (cpuLoad >= 0) {
                    metrics.setCpuUsagePercent(Math.round(cpuLoad * 10000.0) / 100.0);
                } else {
                    double processLoad = osBean.getProcessCpuLoad();
                    if (processLoad >= 0) {
                        metrics.setCpuUsagePercent(Math.round(processLoad * 10000.0) / 100.0);
                    }
                }
                double loadAvg = osBean.getSystemLoadAverage();
                metrics.setSystemLoad1m(loadAvg >= 0 ? Math.round(loadAvg * 100.0) / 100.0 : 0.0);
            }
        } catch (Exception ignored) {}

        // Read /proc/loadavg on Linux if available
        parseLinuxLoadAvg(metrics);

        // Read Uptime on Linux
        parseLinuxUptime(metrics);

        // Memory Metrics
        collectMemoryMetrics(metrics);

        // Disk Metrics
        collectDiskMetrics(metrics);

        return metrics;
    }

    private void parseLinuxLoadAvg(SystemMetrics metrics) {
        File file = new File("/proc/loadavg");
        if (file.exists() && file.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line = br.readLine();
                if (line != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 3) {
                        metrics.setSystemLoad1m(Double.parseDouble(parts[0]));
                        metrics.setSystemLoad5m(Double.parseDouble(parts[1]));
                        metrics.setSystemLoad15m(Double.parseDouble(parts[2]));
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void parseLinuxUptime(SystemMetrics metrics) {
        File file = new File("/proc/uptime");
        if (file.exists() && file.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line = br.readLine();
                if (line != null) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 1) {
                        double up = Double.parseDouble(parts[0]);
                        metrics.setUptimeSeconds((long) up);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void collectMemoryMetrics(SystemMetrics metrics) {
        MemoryInfo mem = metrics.getMemory();

        // 1. Try reading /proc/meminfo for true Host RAM in Linux
        boolean readMemInfo = false;
        File memFile = new File("/proc/meminfo");
        if (memFile.exists() && memFile.canRead()) {
            try (BufferedReader br = new BufferedReader(new FileReader(memFile))) {
                String line;
                long totalKb = 0;
                long freeKb = 0;
                long availKb = 0;
                long swapTotalKb = 0;
                long swapFreeKb = 0;

                while ((line = br.readLine()) != null) {
                    String[] p = line.split(":");
                    if (p.length < 2) continue;
                    String key = p[0].trim();
                    long val = Long.parseLong(p[1].trim().split("\\s+")[0]);

                    switch (key) {
                        case "MemTotal": totalKb = val; break;
                        case "MemFree": freeKb = val; break;
                        case "MemAvailable": availKb = val; break;
                        case "SwapTotal": swapTotalKb = val; break;
                        case "SwapFree": swapFreeKb = val; break;
                    }
                }

                if (totalKb > 0) {
                    long totalBytes = totalKb * 1024L;
                    long availBytes = (availKb > 0 ? availKb : freeKb) * 1024L;
                    long usedBytes = Math.max(0, totalBytes - availBytes);

                    mem.setTotalBytes(totalBytes);
                    mem.setUsedBytes(usedBytes);
                    mem.setFreeBytes(freeKb * 1024L);
                    mem.setAvailableBytes(availBytes);
                    mem.setUsagePercent(Math.round(((double) usedBytes / totalBytes) * 10000.0) / 100.0);

                    long swapTotalBytes = swapTotalKb * 1024L;
                    long swapUsedBytes = Math.max(0, swapTotalBytes - (swapFreeKb * 1024L));
                    mem.setSwapTotalBytes(swapTotalBytes);
                    mem.setSwapUsedBytes(swapUsedBytes);
                    if (swapTotalBytes > 0) {
                        mem.setSwapUsagePercent(Math.round(((double) swapUsedBytes / swapTotalBytes) * 10000.0) / 100.0);
                    }
                    readMemInfo = true;
                }
            } catch (Exception ignored) {}
        }

        // 2. Fallback to OperatingSystemMXBean if /proc/meminfo is not available (e.g. Windows)
        if (!readMemInfo) {
            try {
                com.sun.management.OperatingSystemMXBean osBean =
                        ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
                if (osBean != null) {
                    long total = osBean.getTotalMemorySize();
                    long free = osBean.getFreeMemorySize();
                    long used = Math.max(0, total - free);
                    mem.setTotalBytes(total);
                    mem.setFreeBytes(free);
                    mem.setAvailableBytes(free);
                    mem.setUsedBytes(used);
                    if (total > 0) {
                        mem.setUsagePercent(Math.round(((double) used / total) * 10000.0) / 100.0);
                    }

                    long swapTotal = osBean.getTotalSwapSpaceSize();
                    long swapFree = osBean.getFreeSwapSpaceSize();
                    long swapUsed = Math.max(0, swapTotal - swapFree);
                    mem.setSwapTotalBytes(swapTotal);
                    mem.setSwapUsedBytes(swapUsed);
                    if (swapTotal > 0) {
                        mem.setSwapUsagePercent(Math.round(((double) swapUsed / swapTotal) * 10000.0) / 100.0);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void collectDiskMetrics(SystemMetrics metrics) {
        List<DiskInfo> disks = new ArrayList<>();
        Map<String, DiskInfo> diskByMount = new LinkedHashMap<>();

        // 1. Try running `df -Pk` on Linux to get real filesystem names like /dev/vda2, /dev/vdb1, /dev/sda2
        boolean dfSuccess = false;
        try {
            Process process = new ProcessBuilder("df", "-Pk").start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = br.readLine(); // skip header
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 6) {
                        String filesystem = parts[0];
                        long totalBytes = Long.parseLong(parts[1]) * 1024L;
                        long usedBytes = Long.parseLong(parts[2]) * 1024L;
                        long freeBytes = Long.parseLong(parts[3]) * 1024L;
                        String pctStr = parts[4].replace("%", "");
                        double pct = Double.parseDouble(pctStr);
                        String mount = parts[5];

                        // Skip Docker internal overlayfs snapshots
                        if (mount.contains("/var/lib/docker/rootfs") || mount.contains("/containerd/io.containerd")) {
                            continue;
                        }

                        // Normalize /host mounts back to original host mount points
                        if (mount.equals("/host")) {
                            mount = "/";
                        } else if (mount.startsWith("/host/")) {
                            mount = mount.substring(5); // e.g. /host/data/clickhouse -> /data/clickhouse
                        }

                        // Filter relevant physical or root filesystems
                        boolean isRelevant = filesystem.startsWith("/dev/") ||
                                             mount.equals("/") ||
                                             filesystem.contains("sda") ||
                                             filesystem.contains("vda") ||
                                             filesystem.contains("vdb") ||
                                             filesystem.contains("vdc") ||
                                             filesystem.contains("nvme");

                        if (isRelevant) {
                            DiskInfo di = new DiskInfo();
                            di.setFilesystem(filesystem);
                            di.setMount(mount);
                            di.setTotalBytes(totalBytes);
                            di.setUsedBytes(usedBytes);
                            di.setFreeBytes(freeBytes);
                            di.setUsagePercent(pct);

                            boolean isPrimary = mount.equals("/") || filesystem.contains("sda2") || filesystem.contains("vda2");
                            di.setTargetMatch(isPrimary);

                            // Prefer physical block device (/dev/...) over virtual 'overlay' for the same mount
                            if (!diskByMount.containsKey(mount) || (filesystem.startsWith("/dev/") && !diskByMount.get(mount).getFilesystem().startsWith("/dev/"))) {
                                diskByMount.put(mount, di);
                            }
                        }
                    }
                }
            }
            process.waitFor();
            if (!diskByMount.isEmpty()) {
                disks.addAll(diskByMount.values());
                dfSuccess = true;
            }
        } catch (Exception ignored) {}

        // 2. Cross-platform fallback via Java FileStore & Roots
        if (!dfSuccess) {
            try {
                for (FileStore store : FileSystems.getDefault().getFileStores()) {
                    try {
                        long total = store.getTotalSpace();
                        if (total <= 0) continue;
                        long usable = store.getUsableSpace();
                        long used = total - usable;
                        double pct = Math.round(((double) used / total) * 10000.0) / 100.0;

                        DiskInfo di = new DiskInfo();
                        di.setFilesystem(store.name());
                        di.setMount(store.type() + " (" + store.name() + ")");
                        di.setTotalBytes(total);
                        di.setUsedBytes(used);
                        di.setFreeBytes(usable);
                        di.setUsagePercent(pct);
                        if (store.name().contains("sda2") || store.name().contains("vda2") || store.name().equals("/")) {
                            di.setTargetMatch(true);
                        }
                        disks.add(di);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            if (disks.isEmpty()) {
                for (File root : File.listRoots()) {
                    long total = root.getTotalSpace();
                    if (total <= 0) continue;
                    long free = root.getFreeSpace();
                    long used = total - free;
                    double pct = Math.round(((double) used / total) * 10000.0) / 100.0;

                    DiskInfo di = new DiskInfo();
                    di.setFilesystem(root.getAbsolutePath());
                    di.setMount(root.getAbsolutePath());
                    di.setTotalBytes(total);
                    di.setUsedBytes(used);
                    di.setFreeBytes(free);
                    di.setUsagePercent(pct);
                    di.setTargetMatch(true);
                    disks.add(di);
                }
            }
        }

        metrics.setDisks(disks);
    }

    public boolean evaluateAndSendAlert(SystemAlertSchedule schedule, boolean forceSend) {
        SystemMetrics metrics = getSystemMetrics();
        List<String> alertReasons = new ArrayList<>();
        boolean isCritical = false;

        // 1. Check RAM Threshold
        double ramUsage = metrics.getMemory().getUsagePercent();
        if (schedule.isCheckRam() && ramUsage >= schedule.getRamThresholdPercent()) {
            isCritical = true;
            alertReasons.add(String.format("⚠️ <b>RAM Terpakai:</b> %.1f%% (Batas: %d%%) | Sisa Bebas: %s / Total: %s",
                    ramUsage,
                    schedule.getRamThresholdPercent(),
                    formatBytes(metrics.getMemory().getAvailableBytes()),
                    formatBytes(metrics.getMemory().getTotalBytes())));
        }

        // 2. Check Disk Threshold
        if (schedule.isCheckDisk()) {
            String targetDisk = schedule.getTargetDisk() != null ? schedule.getTargetDisk().trim().toLowerCase() : "";
            boolean anyMatched = false;

            for (DiskInfo disk : metrics.getDisks()) {
                boolean matches = false;
                if (targetDisk.isEmpty() || "all".equals(targetDisk) || "semua".equals(targetDisk)) {
                    matches = true;
                } else if (disk.getFilesystem().toLowerCase().contains(targetDisk) ||
                           disk.getMount().toLowerCase().equals(targetDisk) ||
                           disk.getMount().toLowerCase().contains(targetDisk) ||
                           // Smart aliases for primary OS root partition:
                           ((targetDisk.equals("/") || targetDisk.contains("root") || targetDisk.contains("sda2") || targetDisk.contains("vda2") || targetDisk.contains("sda") || targetDisk.contains("vda")) &&
                            (disk.getMount().equals("/") || disk.getFilesystem().contains("vda2") || disk.getFilesystem().contains("sda2") || disk.getFilesystem().equals("overlay")))) {
                    matches = true;
                }

                if (matches) {
                    anyMatched = true;
                    if (disk.getUsagePercent() >= schedule.getDiskThresholdPercent()) {
                        isCritical = true;
                        alertReasons.add(String.format("🚨 <b>Disk [%s (%s)]:</b> %.1f%% (Batas: %d%%) | Sisa: %s / Total: %s",
                                disk.getFilesystem(),
                                disk.getMount(),
                                disk.getUsagePercent(),
                                schedule.getDiskThresholdPercent(),
                                formatBytes(disk.getFreeBytes()),
                                formatBytes(disk.getTotalBytes())));
                    }
                }
            }

            // Fail-safe: if specific targetDisk was not found at all, evaluate all disks to prevent silent failure
            if (!anyMatched && !metrics.getDisks().isEmpty()) {
                logger.warn("Target disk '{}' not found in detected disks. Falling back to all disks for safety.", targetDisk);
                for (DiskInfo disk : metrics.getDisks()) {
                    if (disk.getUsagePercent() >= schedule.getDiskThresholdPercent()) {
                        isCritical = true;
                        alertReasons.add(String.format("🚨 <b>Disk [%s (%s)]:</b> %.1f%% (Batas: %d%%) | Sisa: %s / Total: %s",
                                disk.getFilesystem(),
                                disk.getMount(),
                                disk.getUsagePercent(),
                                schedule.getDiskThresholdPercent(),
                                formatBytes(disk.getFreeBytes()),
                                formatBytes(disk.getTotalBytes())));
                    }
                }
            }
        }

        if (forceSend || isCritical) {
            sendSystemAlertNotification(schedule, metrics, alertReasons, isCritical, forceSend);
            return true;
        }

        return false;
    }

    private void sendSystemAlertNotification(SystemAlertSchedule schedule, SystemMetrics metrics,
                                             List<String> reasons, boolean isCritical, boolean isTest) {
        String channelIds = schedule.getChannelIds();
        if (channelIds == null || channelIds.trim().isEmpty()) {
            logger.warn("System Alert Schedule {} has no target notification channels configured.", schedule.getName());
            return;
        }

        String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String host = metrics.getHostName() != null ? metrics.getHostName() : "Server Host";

        // Build HTML Message for Telegram
        StringBuilder html = new StringBuilder();
        if (isTest) {
            html.append("🧪 <b>[TEST NOTIFICATION] SYSTEM MONITOR ALERT</b>\n\n");
        } else if (isCritical) {
            html.append("🚨 <b>[ALERT] SYSTEM RESOURCE CRITICAL WARNING!</b>\n\n");
        } else {
            html.append("ℹ️ <b>[STATUS] SYSTEM HEALTH REPORT</b>\n\n");
        }

        html.append("📌 <b>Rule Name:</b> ").append(schedule.getName()).append("\n");
        html.append("🖥️ <b>Server Host:</b> <code>").append(host).append("</code>\n");
        html.append("⏱️ <b>Waktu:</b> ").append(timeStr).append("\n\n");

        if (!reasons.isEmpty()) {
            html.append("<b>Detail Peringatan:</b>\n");
            for (String r : reasons) {
                html.append(" • ").append(r).append("\n");
            }
            html.append("\n");
        }

        html.append("<b>📊 Ringkasan Metrik Saat Ini:</b>\n");
        html.append(" • <b>RAM:</b> ").append(String.format("%.1f%%", metrics.getMemory().getUsagePercent()))
            .append(" (Used: ").append(formatBytes(metrics.getMemory().getUsedBytes()))
            .append(" / ").append(formatBytes(metrics.getMemory().getTotalBytes())).append(")\n");
        
        if (metrics.getCpuUsagePercent() > 0) {
            html.append(" • <b>CPU Usage:</b> ").append(String.format("%.1f%%", metrics.getCpuUsagePercent())).append("\n");
        }

        html.append("\n<b>💾 Ringkasan Partisi Disk:</b>\n");
        for (DiskInfo d : metrics.getDisks()) {
            html.append(String.format(" • <code>%s</code> (%s): <b>%.1f%%</b> (Free: %s)\n",
                    d.getFilesystem(), d.getMount(), d.getUsagePercent(), formatBytes(d.getFreeBytes())));
        }

        html.append("\n<i>Mohon segera periksa server jika kapasitas mendekati batas maksimum.</i>");

        // Build Plain/Markdown Message for Discord
        StringBuilder disc = new StringBuilder();
        if (isTest) {
            disc.append("🧪 **[TEST] SYSTEM MONITOR ALERT**\n");
        } else if (isCritical) {
            disc.append("🚨 **[CRITICAL ALERT] SYSTEM RESOURCE WARNING!**\n");
        } else {
            disc.append("ℹ️ **[STATUS] SYSTEM HEALTH REPORT**\n");
        }
        disc.append("📌 **Rule Name:** ").append(schedule.getName()).append("\n");
        disc.append("🖥️ **Host:** `").append(host).append("` | **Time:** ").append(timeStr).append("\n\n");

        if (!reasons.isEmpty()) {
            disc.append("**Detail Peringatan:**\n");
            for (String r : reasons) {
                disc.append("• ").append(r.replaceAll("<[^>]*>", "")).append("\n");
            }
            disc.append("\n");
        }

        disc.append("**Metrik Server:**\n");
        disc.append(String.format("• **RAM:** %.1f%% (%s / %s)\n",
                metrics.getMemory().getUsagePercent(),
                formatBytes(metrics.getMemory().getUsedBytes()),
                formatBytes(metrics.getMemory().getTotalBytes())));
        for (DiskInfo d : metrics.getDisks()) {
            disc.append(String.format("• **Disk `%s`:** %.1f%% (Free: %s / Total: %s)\n",
                    d.getFilesystem(), d.getUsagePercent(), formatBytes(d.getFreeBytes()), formatBytes(d.getTotalBytes())));
        }

        String[] ids = channelIds.split(",");
        for (String chId : ids) {
            chId = chId.trim();
            if (chId.isEmpty()) continue;
            try {
                var ch = channelRepository.findById(chId);
                if (ch != null) {
                    if ("TELEGRAM".equalsIgnoreCase(ch.getType())) {
                        notificationService.sendTelegramMessage(ch.getBotToken(), ch.getChatId(), html.toString());
                    } else if ("DISCORD".equalsIgnoreCase(ch.getType())) {
                        notificationService.sendDiscordMessage(ch.getWebhookUrl(), disc.toString());
                    }
                }
            } catch (Exception ex) {
                logger.error("Failed to send alert to channel {}", chId, ex);
            }
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}

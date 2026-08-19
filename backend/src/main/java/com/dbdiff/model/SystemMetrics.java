package com.dbdiff.model;

import java.util.ArrayList;
import java.util.List;

public class SystemMetrics {
    private String hostName;
    private String osName;
    private int cpuCores;
    private double cpuUsagePercent;
    private double systemLoad1m;
    private double systemLoad5m;
    private double systemLoad15m;
    private long uptimeSeconds;

    private MemoryInfo memory = new MemoryInfo();
    private List<DiskInfo> disks = new ArrayList<>();

    public static class MemoryInfo {
        private long totalBytes;
        private long usedBytes;
        private long freeBytes;
        private long availableBytes;
        private double usagePercent;
        private long swapTotalBytes;
        private long swapUsedBytes;
        private double swapUsagePercent;

        public long getTotalBytes() { return totalBytes; }
        public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }
        public long getUsedBytes() { return usedBytes; }
        public void setUsedBytes(long usedBytes) { this.usedBytes = usedBytes; }
        public long getFreeBytes() { return freeBytes; }
        public void setFreeBytes(long freeBytes) { this.freeBytes = freeBytes; }
        public long getAvailableBytes() { return availableBytes; }
        public void setAvailableBytes(long availableBytes) { this.availableBytes = availableBytes; }
        public double getUsagePercent() { return usagePercent; }
        public void setUsagePercent(double usagePercent) { this.usagePercent = usagePercent; }
        public long getSwapTotalBytes() { return swapTotalBytes; }
        public void setSwapTotalBytes(long swapTotalBytes) { this.swapTotalBytes = swapTotalBytes; }
        public long getSwapUsedBytes() { return swapUsedBytes; }
        public void setSwapUsedBytes(long swapUsedBytes) { this.swapUsedBytes = swapUsedBytes; }
        public double getSwapUsagePercent() { return swapUsagePercent; }
        public void setSwapUsagePercent(double swapUsagePercent) { this.swapUsagePercent = swapUsagePercent; }
    }

    public static class DiskInfo {
        private String filesystem;
        private String mount;
        private long totalBytes;
        private long usedBytes;
        private long freeBytes;
        private double usagePercent;
        private boolean isTargetMatch;

        public String getFilesystem() { return filesystem; }
        public void setFilesystem(String filesystem) { this.filesystem = filesystem; }
        public String getMount() { return mount; }
        public void setMount(String mount) { this.mount = mount; }
        public long getTotalBytes() { return totalBytes; }
        public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }
        public long getUsedBytes() { return usedBytes; }
        public void setUsedBytes(long usedBytes) { this.usedBytes = usedBytes; }
        public long getFreeBytes() { return freeBytes; }
        public void setFreeBytes(long freeBytes) { this.freeBytes = freeBytes; }
        public double getUsagePercent() { return usagePercent; }
        public void setUsagePercent(double usagePercent) { this.usagePercent = usagePercent; }
        public boolean isTargetMatch() { return isTargetMatch; }
        public void setTargetMatch(boolean targetMatch) { isTargetMatch = targetMatch; }
    }

    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }
    public String getOsName() { return osName; }
    public void setOsName(String osName) { this.osName = osName; }
    public int getCpuCores() { return cpuCores; }
    public void setCpuCores(int cpuCores) { this.cpuCores = cpuCores; }
    public double getCpuUsagePercent() { return cpuUsagePercent; }
    public void setCpuUsagePercent(double cpuUsagePercent) { this.cpuUsagePercent = cpuUsagePercent; }
    public double getSystemLoad1m() { return systemLoad1m; }
    public void setSystemLoad1m(double systemLoad1m) { this.systemLoad1m = systemLoad1m; }
    public double getSystemLoad5m() { return systemLoad5m; }
    public void setSystemLoad5m(double systemLoad5m) { this.systemLoad5m = systemLoad5m; }
    public double getSystemLoad15m() { return systemLoad15m; }
    public void setSystemLoad15m(double systemLoad15m) { this.systemLoad15m = systemLoad15m; }
    public long getUptimeSeconds() { return uptimeSeconds; }
    public void setUptimeSeconds(long uptimeSeconds) { this.uptimeSeconds = uptimeSeconds; }
    public MemoryInfo getMemory() { return memory; }
    public void setMemory(MemoryInfo memory) { this.memory = memory; }
    public List<DiskInfo> getDisks() { return disks; }
    public void setDisks(List<DiskInfo> disks) { this.disks = disks; }
}

// @ts-nocheck
import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { 
  Activity, Server, HardDrive, Cpu, RefreshCw, Plus, Bell, 
  AlertTriangle, CheckCircle, Clock, Trash2, Edit, Play, ShieldAlert,
  Sliders, MessageCircle, XCircle, Info, Radio, Save
} from 'lucide-react';
import { useAppStore, type NotificationChannel } from '../store/useAppStore';
import { NotificationChannelsModal } from './NotificationChannelsModal';
import clsx from 'clsx';

interface DiskInfo {
  filesystem: string;
  mount: string;
  totalBytes: number;
  usedBytes: number;
  freeBytes: number;
  usagePercent: number;
  targetMatch: boolean;
}

interface MemoryInfo {
  totalBytes: number;
  usedBytes: number;
  freeBytes: number;
  availableBytes: number;
  usagePercent: number;
  swapTotalBytes: number;
  swapUsedBytes: number;
  swapUsagePercent: number;
}

interface SystemMetrics {
  hostName: string;
  osName: string;
  cpuCores: number;
  cpuUsagePercent: number;
  systemLoad1m: number;
  systemLoad5m: number;
  systemLoad15m: number;
  uptimeSeconds: number;
  memory: MemoryInfo;
  disks: DiskInfo[];
}

interface SystemAlertSchedule {
  id: string;
  name: string;
  targetDisk: string;
  diskThresholdPercent: number;
  ramThresholdPercent: number;
  checkDisk: boolean;
  checkRam: boolean;
  cronExpression: string;
  channelIds: string;
  active: boolean;
  cooldownMinutes: number;
  lastRun?: string;
  lastStatus?: string;
  lastAlertTime?: string;
  createdAt?: string;
}

export const SystemMonitoringView: React.FC = () => {
  const { notificationChannels, setNotificationChannels, addToast } = useAppStore();

  const [metrics, setMetrics] = useState<SystemMetrics | null>(null);
  const [schedules, setSchedules] = useState<SystemAlertSchedule[]>([]);
  const [isLoadingMetrics, setIsLoadingMetrics] = useState(false);
  const [isLoadingSchedules, setIsLoadingSchedules] = useState(false);
  const [autoRefreshInterval, setAutoRefreshInterval] = useState<number>(10); // 10s default

  // Modals
  const [isProfilesModalOpen, setIsProfilesModalOpen] = useState(false);
  const [isFormOpen, setIsFormOpen] = useState(false);
  const [editingSchedule, setEditingSchedule] = useState<SystemAlertSchedule | null>(null);

  // Form State
  const [formName, setFormName] = useState('');
  const [formTargetDisk, setFormTargetDisk] = useState('/dev/sda2');
  const [formDiskThreshold, setFormDiskThreshold] = useState(70);
  const [formRamThreshold, setFormRamThreshold] = useState(80);
  const [formCheckDisk, setFormCheckDisk] = useState(true);
  const [formCheckRam, setFormCheckRam] = useState(true);
  const [formCronTriggers, setFormCronTriggers] = useState<string[]>(['0 */10 * * * *']);
  const [formCooldown, setFormCooldown] = useState(30);
  const [formSelectedChannels, setFormSelectedChannels] = useState<string[]>([]);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [testingId, setTestingId] = useState<string | null>(null);

  const fetchMetrics = async () => {
    setIsLoadingMetrics(true);
    try {
      const res = await axios.get('/api/system-monitor/metrics');
      setMetrics(res.data);
    } catch (err: any) {
      console.error('Failed to fetch system metrics:', err);
    } finally {
      setIsLoadingMetrics(false);
    }
  };

  const fetchSchedules = async () => {
    setIsLoadingSchedules(true);
    try {
      const res = await axios.get('/api/system-alert-schedules');
      setSchedules(res.data || []);
    } catch (err: any) {
      console.error('Failed to fetch alert schedules:', err);
    } finally {
      setIsLoadingSchedules(false);
    }
  };

  const fetchChannels = async () => {
    try {
      const res = await axios.get('/api/notification-channels');
      setNotificationChannels(res.data || []);
    } catch (err: any) {
      console.error('Failed to fetch notification channels:', err);
    }
  };

  useEffect(() => {
    fetchMetrics();
    fetchSchedules();
    fetchChannels();
  }, []);

  // Auto-refresh metrics timer
  useEffect(() => {
    if (autoRefreshInterval <= 0) return;
    const interval = setInterval(() => {
      fetchMetrics();
    }, autoRefreshInterval * 1000);
    return () => clearInterval(interval);
  }, [autoRefreshInterval]);

  const handleOpenCreateForm = () => {
    setEditingSchedule(null);
    setFormName('Server Health Check & Alert');
    setFormTargetDisk('/dev/sda2');
    setFormDiskThreshold(70);
    setFormRamThreshold(80);
    setFormCheckDisk(true);
    setFormCheckRam(true);
    setFormCronTriggers(['0 */10 * * * *']);
    setFormCooldown(30);
    setFormSelectedChannels(notificationChannels.map(c => c.id));
    setIsFormOpen(true);
  };

  const handleOpenEditForm = (s: SystemAlertSchedule) => {
    setEditingSchedule(s);
    setFormName(s.name);
    setFormTargetDisk(s.targetDisk || '/dev/sda2');
    setFormDiskThreshold(s.diskThresholdPercent || 70);
    setFormRamThreshold(s.ramThresholdPercent || 80);
    setFormCheckDisk(s.checkDisk !== false);
    setFormCheckRam(s.checkRam !== false);
    const parsedTriggers = s.cronExpression
      ? s.cronExpression.split(/[,;\n]+/).map((x: string) => x.trim()).filter(Boolean)
      : ['0 */10 * * * *'];
    setFormCronTriggers(parsedTriggers.length > 0 ? parsedTriggers : ['0 */10 * * * *']);
    setFormCooldown(s.cooldownMinutes || 30);
    setFormSelectedChannels(s.channelIds ? s.channelIds.split(',').map(x => x.trim()).filter(Boolean) : []);
    setIsFormOpen(true);
  };

  const handleToggleChannel = (channelId: string) => {
    setFormSelectedChannels(prev => 
      prev.includes(channelId) ? prev.filter(id => id !== channelId) : [...prev, channelId]
    );
  };

  const handleSaveSchedule = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formName.trim()) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'Rule name is required.' });
      return;
    }
    if (formSelectedChannels.length === 0) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'Please select at least one notification channel.' });
      return;
    }

    const validTriggers = formCronTriggers.map(c => c.trim()).filter(Boolean);
    if (validTriggers.length === 0) {
      addToast({ type: 'warning', title: 'Validation Error', message: 'Setidaknya masukkan 1 ekspresi Spring Cron.' });
      return;
    }
    const finalCron = validTriggers.join('; ');

    const payload = {
      name: formName.trim(),
      targetDisk: formTargetDisk.trim(),
      diskThresholdPercent: formDiskThreshold,
      ramThresholdPercent: formRamThreshold,
      checkDisk: formCheckDisk,
      checkRam: formCheckRam,
      cronExpression: finalCron,
      channelIds: formSelectedChannels.join(','),
      active: editingSchedule ? editingSchedule.active : true,
      cooldownMinutes: formCooldown
    };

    setIsSubmitting(true);
    try {
      if (editingSchedule) {
        await axios.put(`/api/system-alert-schedules/${editingSchedule.id}`, payload);
        addToast({ type: 'success', title: 'Updated', message: `Schedule "${formName}" updated successfully.` });
      } else {
        await axios.post('/api/system-alert-schedules', payload);
        addToast({ type: 'success', title: 'Created', message: `Schedule "${formName}" created and activated.` });
      }
      setIsFormOpen(false);
      fetchSchedules();
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: err.response?.data?.error || 'Failed to save schedule.' });
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleToggleActive = async (id: string, currentActive: boolean) => {
    try {
      await axios.put(`/api/system-alert-schedules/${id}/active`, { active: !currentActive });
      setSchedules(prev => prev.map(s => s.id === id ? { ...s, active: !currentActive } : s));
      addToast({ 
        type: 'info', 
        title: !currentActive ? 'Activated' : 'Paused', 
        message: `Alert schedule has been ${!currentActive ? 'activated' : 'paused'}.` 
      });
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: 'Failed to update schedule status.' });
    }
  };

  const handleDeleteSchedule = async (id: string, name: string) => {
    if (!confirm(`Are you sure you want to delete the schedule "${name}"?`)) return;
    try {
      await axios.delete(`/api/system-alert-schedules/${id}`);
      setSchedules(prev => prev.filter(s => s.id !== id));
      addToast({ type: 'success', title: 'Deleted', message: `Schedule "${name}" removed.` });
    } catch (err: any) {
      addToast({ type: 'error', title: 'Error', message: 'Failed to delete schedule.' });
    }
  };

  const handleTestAlert = async (id: string, name: string) => {
    setTestingId(id);
    try {
      const res = await axios.post(`/api/system-alert-schedules/${id}/test`);
      if (res.data?.success) {
        addToast({ type: 'success', title: 'Test Alert Sent', message: `Test message sent to selected channels for "${name}".` });
      } else {
        addToast({ type: 'warning', title: 'Test Alert', message: res.data?.error || 'Failed to send test alert.' });
      }
    } catch (err: any) {
      addToast({ type: 'error', title: 'Test Failed', message: err.response?.data?.error || 'Failed to trigger test alert.' });
    } finally {
      setTestingId(null);
    }
  };

  const formatBytes = (bytes?: number) => {
    if (!bytes || bytes <= 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  };

  const formatUptime = (seconds?: number) => {
    if (!seconds || seconds <= 0) return '-';
    const d = Math.floor(seconds / (3600 * 24));
    const h = Math.floor((seconds % (3600 * 24)) / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    return `${d}d ${h}h ${m}m`;
  };

  const getStatusColor = (percent: number, warnThresh = 70, critThresh = 85) => {
    if (percent >= critThresh) return 'text-red-500 bg-red-500/10 border-red-500/30';
    if (percent >= warnThresh) return 'text-amber-500 bg-amber-500/10 border-amber-500/30';
    return 'text-emerald-500 bg-emerald-500/10 border-emerald-500/30';
  };

  const getBarColor = (percent: number, warnThresh = 70, critThresh = 85) => {
    if (percent >= critThresh) return 'bg-red-500';
    if (percent >= warnThresh) return 'bg-amber-500';
    return 'bg-emerald-500';
  };

  const primaryDisk = metrics?.disks.find(d => d.filesystem.includes('sda2') || d.mount === '/') || metrics?.disks[0];

  return (
    <div className="flex-1 overflow-y-auto bg-bg-main p-4 md:p-6 space-y-6">
      {/* Top Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 bg-bg-panel border border-border-main p-4 rounded-xl shadow-sm">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-600 flex items-center justify-center text-white shadow-lg shadow-indigo-500/20">
            <Server className="w-5 h-5" />
          </div>
          <div>
            <h1 className="text-base md:text-lg font-bold text-text-main flex items-center gap-2">
              Sistem Monitoring & Resource Alert
              <span className="px-2 py-0.5 text-[10px] font-mono font-semibold rounded-full bg-blue-500/10 text-blue-500 border border-blue-500/20">
                LIVE
              </span>
            </h1>
            <p className="text-xs text-text-muted mt-0.5">
              Host: <span className="font-mono text-text-main font-semibold">{metrics?.hostName || 'Loading...'}</span> &bull; OS: {metrics?.osName || '-'} &bull; Uptime: {formatUptime(metrics?.uptimeSeconds)}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2.5">
          {/* Auto Refresh selector */}
          <div className="flex items-center gap-1.5 bg-bg-main border border-border-main rounded-lg px-2.5 py-1.5 text-xs text-text-muted">
            <Clock className="w-3.5 h-3.5" />
            <span>Auto Refresh:</span>
            <select
              value={autoRefreshInterval}
              onChange={e => setAutoRefreshInterval(Number(e.target.value))}
              className="bg-transparent text-text-main font-semibold focus:outline-none cursor-pointer"
            >
              <option value={0}>Off</option>
              <option value={5}>5s</option>
              <option value={10}>10s</option>
              <option value={30}>30s</option>
              <option value={60}>1m</option>
            </select>
          </div>

          <button
            onClick={fetchMetrics}
            disabled={isLoadingMetrics}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-bg-main hover:bg-bg-hover border border-border-main rounded-lg text-xs font-semibold text-text-main transition-colors"
          >
            <RefreshCw className={clsx("w-3.5 h-3.5", isLoadingMetrics && "animate-spin text-blue-500")} />
            Refresh
          </button>

          <button
            onClick={() => setIsProfilesModalOpen(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-purple-600/10 hover:bg-purple-600/20 border border-purple-500/30 text-purple-400 rounded-lg text-xs font-semibold transition-colors"
          >
            <MessageCircle className="w-3.5 h-3.5" />
            Channels ({notificationChannels.length})
          </button>

          <button
            onClick={handleOpenCreateForm}
            className="flex items-center gap-1.5 px-3.5 py-1.5 bg-blue-600 hover:bg-blue-500 text-white rounded-lg text-xs font-semibold shadow-md shadow-blue-500/20 transition-colors"
          >
            <Plus className="w-3.5 h-3.5" />
            + Buat Alert Schedule
          </button>
        </div>
      </div>

      {/* Metrics Summary Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* RAM Card */}
        <div className="bg-bg-panel border border-border-main p-4 rounded-xl flex flex-col justify-between relative overflow-hidden shadow-sm">
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold text-text-muted uppercase tracking-wider flex items-center gap-1.5">
              <Activity className="w-4 h-4 text-purple-400" /> Memory (RAM)
            </span>
            <span className={clsx(
              "px-2 py-0.5 rounded text-xs font-bold font-mono border",
              getStatusColor(metrics?.memory?.usagePercent || 0, 75, 80)
            )}>
              {metrics?.memory?.usagePercent?.toFixed(1) || 0}%
            </span>
          </div>

          <div className="my-3">
            <div className="text-xl md:text-2xl font-bold font-mono text-text-main">
              {formatBytes(metrics?.memory?.usedBytes)} <span className="text-xs font-sans text-text-muted font-normal">/ {formatBytes(metrics?.memory?.totalBytes)}</span>
            </div>
            <div className="w-full bg-bg-main h-2 rounded-full overflow-hidden mt-2 border border-border-main/40">
              <div
                className={clsx("h-full rounded-full transition-all duration-500", getBarColor(metrics?.memory?.usagePercent || 0, 75, 80))}
                style={{ width: `${Math.min(100, metrics?.memory?.usagePercent || 0)}%` }}
              />
            </div>
          </div>

          <div className="text-[11px] text-text-muted flex justify-between pt-2 border-t border-border-main/50">
            <span>Sisa Bebas: <strong className="text-text-main">{formatBytes(metrics?.memory?.availableBytes)}</strong></span>
            {metrics?.memory?.swapTotalBytes > 0 && (
              <span>Swap: <strong className="text-text-main">{metrics.memory.swapUsagePercent?.toFixed(0)}%</strong></span>
            )}
          </div>
        </div>

        {/* Primary Disk Card (/dev/sda2) */}
        <div className="bg-bg-panel border border-border-main p-4 rounded-xl flex flex-col justify-between relative overflow-hidden shadow-sm">
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold text-text-muted uppercase tracking-wider flex items-center gap-1.5">
              <HardDrive className="w-4 h-4 text-blue-400" /> Disk ({primaryDisk?.filesystem || '/dev/sda2'})
            </span>
            <span className={clsx(
              "px-2 py-0.5 rounded text-xs font-bold font-mono border",
              getStatusColor(primaryDisk?.usagePercent || 0, 65, 70)
            )}>
              {primaryDisk?.usagePercent?.toFixed(1) || 0}%
            </span>
          </div>

          <div className="my-3">
            <div className="text-xl md:text-2xl font-bold font-mono text-text-main">
              {formatBytes(primaryDisk?.usedBytes)} <span className="text-xs font-sans text-text-muted font-normal">/ {formatBytes(primaryDisk?.totalBytes)}</span>
            </div>
            <div className="w-full bg-bg-main h-2 rounded-full overflow-hidden mt-2 border border-border-main/40">
              <div
                className={clsx("h-full rounded-full transition-all duration-500", getBarColor(primaryDisk?.usagePercent || 0, 65, 70))}
                style={{ width: `${Math.min(100, primaryDisk?.usagePercent || 0)}%` }}
              />
            </div>
          </div>

          <div className="text-[11px] text-text-muted flex justify-between pt-2 border-t border-border-main/50">
            <span>Free Space: <strong className="text-text-main">{formatBytes(primaryDisk?.freeBytes)}</strong></span>
            <span>Mount: <strong className="text-text-main font-mono">{primaryDisk?.mount || '/'}</strong></span>
          </div>
        </div>

        {/* CPU & Load Card */}
        <div className="bg-bg-panel border border-border-main p-4 rounded-xl flex flex-col justify-between relative overflow-hidden shadow-sm">
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold text-text-muted uppercase tracking-wider flex items-center gap-1.5">
              <Cpu className="w-4 h-4 text-emerald-400" /> CPU & Load
            </span>
            <span className="px-2 py-0.5 rounded text-xs font-bold font-mono bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
              {metrics?.cpuCores || 1} Cores
            </span>
          </div>

          <div className="my-3">
            <div className="text-xl md:text-2xl font-bold font-mono text-text-main">
              {metrics?.cpuUsagePercent !== undefined ? `${metrics.cpuUsagePercent.toFixed(1)}%` : '0%'}
            </div>
            <div className="w-full bg-bg-main h-2 rounded-full overflow-hidden mt-2 border border-border-main/40">
              <div
                className="h-full rounded-full bg-emerald-500 transition-all duration-500"
                style={{ width: `${Math.min(100, metrics?.cpuUsagePercent || 0)}%` }}
              />
            </div>
          </div>

          <div className="text-[11px] text-text-muted flex justify-between pt-2 border-t border-border-main/50">
            <span>Load Avg: <strong className="text-text-main font-mono">{metrics?.systemLoad1m || 0}, {metrics?.systemLoad5m || 0}</strong></span>
            <span>15m: <strong className="text-text-main font-mono">{metrics?.systemLoad15m || 0}</strong></span>
          </div>
        </div>

        {/* Active Schedules Summary Card */}
        <div className="bg-bg-panel border border-border-main p-4 rounded-xl flex flex-col justify-between relative overflow-hidden shadow-sm">
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold text-text-muted uppercase tracking-wider flex items-center gap-1.5">
              <Bell className="w-4 h-4 text-amber-400" /> Active Alert Rules
            </span>
            <span className="px-2 py-0.5 rounded text-xs font-bold font-mono bg-blue-500/10 text-blue-400 border border-blue-500/20">
              {schedules.filter(s => s.active).length} / {schedules.length}
            </span>
          </div>

          <div className="my-3">
            <div className="text-xl md:text-2xl font-bold text-text-main flex items-center gap-2">
              <ShieldAlert className="w-6 h-6 text-amber-500" />
              <span>Multi-Schedule</span>
            </div>
            <p className="text-xs text-text-muted mt-1">
              Aturan notifikasi otomatis ke Telegram & Discord saat Disk ≥ 70% atau RAM ≥ 80%.
            </p>
          </div>

          <div className="text-[11px] text-text-muted flex justify-between pt-2 border-t border-border-main/50">
            <button 
              onClick={handleOpenCreateForm}
              className="text-blue-400 hover:underline font-semibold"
            >
              + Tambah Rule Baru
            </button>
            <button
              onClick={() => setIsProfilesModalOpen(true)}
              className="text-purple-400 hover:underline font-semibold"
            >
              Edit Channels &rarr;
            </button>
          </div>
        </div>
      </div>

      {/* Disks Breakdown Section */}
      <div className="bg-bg-panel border border-border-main rounded-xl p-4 md:p-5 shadow-sm space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm md:text-base font-bold text-text-main flex items-center gap-2">
            <HardDrive className="w-4 h-4 text-blue-500" />
            Daftar Seluruh Partisi Disk Server
          </h2>
          <span className="text-xs text-text-muted">
            Total Partisi Terdeteksi: <strong>{metrics?.disks?.length || 0}</strong>
          </span>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3.5">
          {metrics?.disks?.map((disk, idx) => {
            const isSda2 = disk.filesystem.includes('sda2') || disk.mount === '/';
            return (
              <div 
                key={idx}
                className={clsx(
                  "p-3.5 rounded-lg border flex flex-col justify-between transition-all",
                  isSda2 
                    ? "bg-blue-500/5 border-blue-500/40 shadow-sm" 
                    : "bg-bg-main border-border-main"
                )}
              >
                <div>
                  <div className="flex items-center justify-between gap-2 mb-1.5">
                    <span className="font-mono text-xs font-bold text-text-main truncate flex items-center gap-1.5">
                      {disk.filesystem}
                      {isSda2 && (
                        <span className="px-1.5 py-0.5 rounded text-[9px] font-bold bg-blue-500 text-white uppercase tracking-wider">
                          Primary / OS
                        </span>
                      )}
                    </span>
                    <span className={clsx(
                      "px-2 py-0.5 rounded text-xs font-bold font-mono border shrink-0",
                      getStatusColor(disk.usagePercent, 70, 85)
                    )}>
                      {disk.usagePercent.toFixed(1)}%
                    </span>
                  </div>

                  <div className="text-xs text-text-muted flex justify-between mt-1">
                    <span>Mount: <strong className="font-mono text-text-main">{disk.mount}</strong></span>
                    <span>Free: <strong className="text-text-main">{formatBytes(disk.freeBytes)}</strong></span>
                  </div>
                </div>

                <div className="mt-3">
                  <div className="w-full bg-bg-panel h-2 rounded-full overflow-hidden border border-border-main/50">
                    <div
                      className={clsx("h-full rounded-full transition-all duration-500", getBarColor(disk.usagePercent, 70, 85))}
                      style={{ width: `${Math.min(100, disk.usagePercent)}%` }}
                    />
                  </div>
                  <div className="text-[10px] text-text-muted flex justify-between mt-1.5 font-mono">
                    <span>Used: {formatBytes(disk.usedBytes)}</span>
                    <span>Total: {formatBytes(disk.totalBytes)}</span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Alert Schedules Management Section */}
      <div className="bg-bg-panel border border-border-main rounded-xl p-4 md:p-5 shadow-sm space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div>
            <h2 className="text-sm md:text-base font-bold text-text-main flex items-center gap-2">
              <Bell className="w-4 h-4 text-amber-500" />
              Daftar Multi-Schedule System Alert (Disk & RAM)
            </h2>
            <p className="text-xs text-text-muted mt-0.5">
              Kelola jadwal otomatis untuk mengirimkan notifikasi darurat saat /dev/sda2 ≥ 70% atau RAM ≥ 80%.
            </p>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={fetchSchedules}
              disabled={isLoadingSchedules}
              className="p-1.5 bg-bg-main hover:bg-bg-hover border border-border-main rounded text-text-muted hover:text-text-main transition-colors"
              title="Refresh Schedules"
            >
              <RefreshCw className={clsx("w-4 h-4", isLoadingSchedules && "animate-spin text-blue-500")} />
            </button>
            <button
              onClick={handleOpenCreateForm}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-blue-600 hover:bg-blue-500 text-white rounded-lg text-xs font-semibold transition-colors shadow-sm"
            >
              <Plus className="w-3.5 h-3.5" /> Tambah Jadwal Baru
            </button>
          </div>
        </div>

        {/* Schedules Table */}
        <div className="border border-border-main rounded-lg overflow-hidden bg-bg-main">
          {isLoadingSchedules ? (
            <div className="p-8 text-center text-xs text-text-muted flex items-center justify-center gap-2">
              <Activity className="w-4 h-4 animate-spin text-blue-500" /> Loading schedules...
            </div>
          ) : schedules.length === 0 ? (
            <div className="p-8 text-center text-xs text-text-muted space-y-3">
              <p className="italic">Belum ada aturan jadwal pemantauan sistem yang dibuat.</p>
              <button
                onClick={handleOpenCreateForm}
                className="px-3.5 py-1.5 bg-blue-600 hover:bg-blue-500 text-white text-xs font-bold rounded-lg shadow transition-colors inline-flex items-center gap-1.5"
              >
                <Plus className="w-3.5 h-3.5" /> Buat Schedule Sekarang
              </button>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs border-collapse">
                <thead className="bg-bg-panel/90 border-b border-border-main text-[11px] font-semibold text-text-muted uppercase">
                  <tr>
                    <th className="py-3 px-4">Nama Rule</th>
                    <th className="py-3 px-4">Target Disk & Batas</th>
                    <th className="py-3 px-4">Batas RAM</th>
                    <th className="py-3 px-4">Interval / Cron</th>
                    <th className="py-3 px-4">Target Channels</th>
                    <th className="py-3 px-4">Status Terakhir</th>
                    <th className="py-3 px-4 text-center">Status</th>
                    <th className="py-3 px-4 text-right">Aksi</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-main font-sans">
                  {schedules.map(s => {
                    const chIds = s.channelIds ? s.channelIds.split(',').map(x => x.trim()) : [];
                    const attachedChannels = notificationChannels.filter(c => chIds.includes(c.id));

                    return (
                      <tr key={s.id} className="hover:bg-bg-panel/50 transition-colors">
                        <td className="py-3 px-4 font-semibold text-text-main">
                          {s.name}
                          <div className="text-[10px] text-text-muted font-mono font-normal">
                            ID: {s.id.substring(0, 8)}...
                          </div>
                        </td>

                        <td className="py-3 px-4">
                          {s.checkDisk ? (
                            <div className="flex items-center gap-1.5">
                              <HardDrive className="w-3.5 h-3.5 text-blue-400 shrink-0" />
                              <span className="font-mono font-bold text-text-main">{s.targetDisk || '/dev/sda2'}</span>
                              <span className="text-red-400 font-bold">&ge; {s.diskThresholdPercent}%</span>
                            </div>
                          ) : (
                            <span className="text-text-muted italic">Disabled</span>
                          )}
                        </td>

                        <td className="py-3 px-4">
                          {s.checkRam ? (
                            <div className="flex items-center gap-1.5">
                              <Activity className="w-3.5 h-3.5 text-purple-400 shrink-0" />
                              <span className="text-red-400 font-bold">&ge; {s.ramThresholdPercent}%</span>
                            </div>
                          ) : (
                            <span className="text-text-muted italic">Disabled</span>
                          )}
                        </td>

                        <td className="py-3 px-4 font-mono text-[11px] text-text-main">
                          <div className="flex flex-col gap-1">
                            {s.cronExpression ? s.cronExpression.split(/[,;\n]+/).map((c, i) => (
                              <span key={i} className="flex items-center gap-1 text-blue-400 bg-blue-500/10 px-1.5 py-0.5 rounded border border-blue-500/20 w-fit">
                                <Clock className="w-3 h-3 text-blue-400" />
                                {c.trim()}
                              </span>
                            )) : (
                              <span className="text-text-muted italic">-</span>
                            )}
                          </div>
                          <span className="text-[10px] text-text-muted font-sans mt-1 block">
                            Cooldown: {s.cooldownMinutes || 30}m
                          </span>
                        </td>

                        <td className="py-3 px-4">
                          <div className="flex flex-wrap gap-1">
                            {attachedChannels.length === 0 ? (
                              <span className="text-[10px] text-amber-500 italic">No channel selected</span>
                            ) : (
                              attachedChannels.map(c => (
                                <span
                                  key={c.id}
                                  className={clsx(
                                    "px-1.5 py-0.5 rounded text-[10px] font-semibold flex items-center gap-1 border",
                                    c.type === 'TELEGRAM'
                                      ? "bg-sky-500/10 text-sky-400 border-sky-500/20"
                                      : "bg-indigo-500/10 text-indigo-400 border-indigo-500/20"
                                  )}
                                >
                                  {c.type === 'TELEGRAM' ? '✈️' : '💬'} {c.name}
                                </span>
                              ))
                            )}
                          </div>
                        </td>

                        <td className="py-3 px-4 text-[11px]">
                          {s.lastStatus ? (
                            <span className={clsx(
                              "px-2 py-0.5 rounded text-[10px] font-bold uppercase",
                              s.lastStatus === 'OK' && "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20",
                              s.lastStatus === 'ALERT_SENT' && "bg-red-500/10 text-red-400 border border-red-500/20",
                              s.lastStatus === 'COOLDOWN' && "bg-amber-500/10 text-amber-400 border border-amber-500/20",
                              s.lastStatus.startsWith('ERROR') && "bg-rose-500/10 text-rose-400 border border-rose-500/20"
                            )}>
                              {s.lastStatus}
                            </span>
                          ) : (
                            <span className="text-text-muted italic">-</span>
                          )}
                          {s.lastRun && (
                            <div className="text-[10px] text-text-muted mt-0.5 font-mono">
                              {new Date(s.lastRun).toLocaleTimeString()}
                            </div>
                          )}
                        </td>

                        <td className="py-3 px-4 text-center">
                          <button
                            onClick={() => handleToggleActive(s.id, s.active)}
                            className={clsx(
                              "px-2.5 py-1 rounded-full text-[10px] font-bold transition-colors uppercase",
                              s.active 
                                ? "bg-emerald-500/15 text-emerald-400 hover:bg-emerald-500/25 border border-emerald-500/30" 
                                : "bg-slate-500/15 text-slate-400 hover:bg-slate-500/25 border border-slate-500/30"
                            )}
                          >
                            {s.active ? 'Active' : 'Paused'}
                          </button>
                        </td>

                        <td className="py-3 px-4 text-right">
                          <div className="flex items-center justify-end gap-1.5">
                            <button
                              onClick={() => handleTestAlert(s.id, s.name)}
                              disabled={testingId === s.id}
                              title="Test Send Alert Message Now"
                              className="p-1.5 rounded hover:bg-bg-panel border border-border-main text-text-muted hover:text-blue-400 transition-colors"
                            >
                              <Play className={clsx("w-3.5 h-3.5", testingId === s.id && "animate-spin text-blue-500")} />
                            </button>
                            <button
                              onClick={() => handleOpenEditForm(s)}
                              title="Edit Schedule"
                              className="p-1.5 rounded hover:bg-bg-panel border border-border-main text-text-muted hover:text-amber-400 transition-colors"
                            >
                              <Edit className="w-3.5 h-3.5" />
                            </button>
                            <button
                              onClick={() => handleDeleteSchedule(s.id, s.name)}
                              title="Delete Schedule"
                              className="p-1.5 rounded hover:bg-bg-panel border border-border-main text-text-muted hover:text-red-400 transition-colors"
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                            </button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* Form Modal: Create / Edit System Alert Schedule */}
      {isFormOpen && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 p-4">
          <div className="bg-bg-panel border border-border-main rounded-xl shadow-2xl w-full max-w-xl max-h-[90vh] overflow-y-auto flex flex-col">
            <div className="p-4 border-b border-border-main flex items-center justify-between bg-bg-panel sticky top-0 z-10">
              <h3 className="font-bold text-base text-text-main flex items-center gap-2">
                <Bell className="w-4 h-4 text-blue-500" />
                {editingSchedule ? 'Edit System Alert Schedule' : 'Buat System Alert Schedule Baru'}
              </h3>
              <button
                onClick={() => setIsFormOpen(false)}
                className="text-text-muted hover:text-text-main p-1 rounded transition-colors"
              >
                <XCircle className="w-5 h-5" />
              </button>
            </div>

            <form onSubmit={handleSaveSchedule} className="p-5 space-y-4 text-xs">
              <div>
                <label className="block font-semibold text-text-main mb-1">Nama Rule Schedule</label>
                <input
                  type="text"
                  required
                  value={formName}
                  onChange={e => setFormName(e.target.value)}
                  placeholder="Contoh: Server Host Disk 70% & RAM 80% Alert"
                  className="w-full bg-bg-main border border-border-main rounded-lg px-3 py-2 text-text-main focus:outline-none focus:border-blue-500"
                />
              </div>

              {/* Target Disk Settings */}
              <div className="p-3.5 rounded-lg border border-border-main bg-bg-main/50 space-y-3">
                <div className="flex items-center justify-between">
                  <label className="font-bold text-text-main flex items-center gap-1.5 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={formCheckDisk}
                      onChange={e => setFormCheckDisk(e.target.checked)}
                      className="rounded border-border-main text-blue-600 focus:ring-0 cursor-pointer"
                    />
                    Pantau Kapasitas Disk
                  </label>
                  <span className="text-[10px] text-text-muted">Notifikasi jika ≥ {formDiskThreshold}%</span>
                </div>

                {formCheckDisk && (
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-1">
                    <div>
                      <label className="block text-[11px] font-semibold text-text-muted mb-1">Target Partisi Disk</label>
                      <select
                        value={formTargetDisk}
                        onChange={e => setFormTargetDisk(e.target.value)}
                        className="w-full bg-bg-main border border-border-main rounded-lg px-2.5 py-1.5 text-text-main focus:outline-none focus:border-blue-500 font-mono"
                      >
                        <option value="/dev/sda2">/dev/sda2 (Recommended Primary Disk)</option>
                        <option value="/">Root Mount (/)</option>
                        <option value="all">Semua Partisi Disk (All)</option>
                        {metrics?.disks?.filter(d => d.filesystem !== '/dev/sda2').map((d, i) => (
                          <option key={i} value={d.filesystem}>{d.filesystem} ({d.mount})</option>
                        ))}
                      </select>
                    </div>

                    <div>
                      <label className="block text-[11px] font-semibold text-text-muted mb-1">
                        Batas Threshold Disk: <strong className="text-red-400 font-mono">{formDiskThreshold}%</strong>
                      </label>
                      <input
                        type="range"
                        min="50"
                        max="98"
                        step="1"
                        value={formDiskThreshold}
                        onChange={e => setFormDiskThreshold(Number(e.target.value))}
                        className="w-full cursor-pointer accent-blue-500"
                      />
                    </div>
                  </div>
                )}
              </div>

              {/* RAM Threshold Settings */}
              <div className="p-3.5 rounded-lg border border-border-main bg-bg-main/50 space-y-3">
                <div className="flex items-center justify-between">
                  <label className="font-bold text-text-main flex items-center gap-1.5 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={formCheckRam}
                      onChange={e => setFormCheckRam(e.target.checked)}
                      className="rounded border-border-main text-purple-600 focus:ring-0 cursor-pointer"
                    />
                    Pantau Kapasitas RAM
                  </label>
                  <span className="text-[10px] text-text-muted">Notifikasi jika ≥ {formRamThreshold}%</span>
                </div>

                {formCheckRam && (
                  <div>
                    <label className="block text-[11px] font-semibold text-text-muted mb-1">
                      Batas Threshold RAM: <strong className="text-purple-400 font-mono">{formRamThreshold}%</strong>
                    </label>
                    <input
                      type="range"
                      min="50"
                      max="98"
                      step="1"
                      value={formRamThreshold}
                      onChange={e => setFormRamThreshold(Number(e.target.value))}
                      className="w-full cursor-pointer accent-purple-500"
                    />
                  </div>
                )}
              </div>

              {/* Schedule Cron / Interval (Multiple Spring Cron Triggers Supported) */}
              <div className="space-y-3 p-3.5 rounded-lg border border-border-main bg-bg-main/40">
                <div className="flex items-center justify-between">
                  <div>
                    <label className="block font-bold text-text-main">
                      Jadwal Pemeriksaan (Spring Cron Expressions)
                    </label>
                    <p className="text-[11px] text-text-muted">
                      User dapat mengisi bebas format Spring Cron 6-field. Bisa menambah lebih dari 1 jadwal pemicu.
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => setFormCronTriggers(prev => [...prev, '0 0 23 * * *'])}
                    className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-blue-500/15 text-blue-400 hover:bg-blue-500/25 border border-blue-500/30 text-[11px] font-bold transition-colors"
                  >
                    <Plus className="w-3.5 h-3.5" /> Tambah Cron
                  </button>
                </div>

                <div className="space-y-2">
                  {formCronTriggers.map((cron, idx) => (
                    <div key={idx} className="flex items-center gap-2">
                      <div className="relative flex-1">
                        <div className="absolute inset-y-0 left-0 pl-2.5 flex items-center pointer-events-none text-text-muted">
                          <Clock className="w-3.5 h-3.5 text-blue-400" />
                        </div>
                        <input
                          type="text"
                          required
                          value={cron}
                          onChange={e => {
                            const copy = [...formCronTriggers];
                            copy[idx] = e.target.value;
                            setFormCronTriggers(copy);
                          }}
                          placeholder="e.g. 0 */10 * * * * atau 0 0 23 * * *"
                          className="w-full bg-bg-main border border-border-main rounded-lg pl-8 pr-3 py-1.5 text-xs font-mono font-bold text-blue-400 focus:outline-none focus:border-blue-500 shadow-inner"
                        />
                      </div>
                      {formCronTriggers.length > 1 && (
                        <button
                          type="button"
                          onClick={() => setFormCronTriggers(prev => prev.filter((_, i) => i !== idx))}
                          className="p-1.5 text-text-muted hover:text-red-400 hover:bg-red-500/10 rounded-lg transition-colors border border-border-main"
                          title="Hapus Trigger Cron Ini"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      )}
                    </div>
                  ))}
                </div>

                {/* Quick Presets Helpers */}
                <div className="pt-1">
                  <div className="text-[10px] text-text-muted mb-1.5 font-semibold">
                    Preset Cepat (Klik untuk menambahkan ekspresi cron):
                  </div>
                  <div className="flex flex-wrap gap-1.5">
                    {[
                      { label: 'Tiap 5 Menit (0 */5 * * * *)', value: '0 */5 * * * *' },
                      { label: 'Tiap 10 Menit (0 */10 * * * *)', value: '0 */10 * * * *' },
                      { label: 'Tiap 15 Menit (0 */15 * * * *)', value: '0 */15 * * * *' },
                      { label: 'Tiap 30 Menit (0 */30 * * * *)', value: '0 */30 * * * *' },
                      { label: 'Tiap 1 Jam (0 0 * * * *)', value: '0 0 * * * *' },
                      { label: 'Tiap Hari 23:00 (0 0 23 * * *)', value: '0 0 23 * * *' },
                    ].map(p => (
                      <button
                        key={p.value}
                        type="button"
                        onClick={() => {
                          if (formCronTriggers.length === 1 && !formCronTriggers[0]) {
                            setFormCronTriggers([p.value]);
                          } else if (!formCronTriggers.includes(p.value)) {
                            setFormCronTriggers(prev => [...prev, p.value]);
                          }
                        }}
                        className="py-1 px-2 rounded bg-bg-panel border border-border-main hover:border-blue-500/40 text-[10px] text-text-muted hover:text-blue-400 font-mono transition-colors"
                      >
                        + {p.label}
                      </button>
                    ))}
                  </div>
                  <p className="text-[10px] text-text-muted mt-1 leading-relaxed">
                    💡 <b>Format 6 field:</b> <code>Detik Menit Jam Hari Bulan HariMinggu</code>. User dapat mengetik bebas ekspresi cron apa pun (misal: <code>0 30 8-17 * * MON-FRI</code>).
                  </p>
                </div>
              </div>

              {/* Notification Channels Multi-Select */}
              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="font-semibold text-text-main">
                    Pilih Target Notification Profiles (Telegram / Discord)
                  </label>
                  <button
                    type="button"
                    onClick={() => setIsProfilesModalOpen(true)}
                    className="text-purple-400 hover:underline text-[11px] font-semibold flex items-center gap-1"
                  >
                    + Kelola Channel
                  </button>
                </div>

                {notificationChannels.length === 0 ? (
                  <div className="p-3 bg-amber-500/10 border border-amber-500/30 rounded-lg text-amber-300 text-xs">
                    Belum ada channel Telegram / Discord yang dikonfigurasi. Klik "+ Kelola Channel" di atas untuk menambahkan bot token atau webhook URL.
                  </div>
                ) : (
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 max-h-36 overflow-y-auto p-1">
                    {notificationChannels.map(c => {
                      const isSelected = formSelectedChannels.includes(c.id);
                      return (
                        <div
                          key={c.id}
                          onClick={() => handleToggleChannel(c.id)}
                          className={clsx(
                            "p-2 rounded-lg border cursor-pointer flex items-center justify-between transition-all select-none",
                            isSelected
                              ? c.type === 'TELEGRAM'
                                ? "bg-sky-500/15 border-sky-500 text-sky-300"
                                : "bg-indigo-500/15 border-indigo-500 text-indigo-300"
                              : "bg-bg-main border-border-main text-text-muted hover:border-border-item"
                          )}
                        >
                          <div className="flex items-center gap-2 truncate">
                            <span>{c.type === 'TELEGRAM' ? '✈️' : '💬'}</span>
                            <div className="truncate">
                              <div className="font-bold text-[11px] truncate">{c.name}</div>
                              <div className="text-[9px] opacity-75">{c.type}</div>
                            </div>
                          </div>
                          <input
                            type="checkbox"
                            checked={isSelected}
                            onChange={() => {}}
                            className="rounded border-border-main text-blue-600 focus:ring-0 pointer-events-none"
                          />
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>

              {/* Cooldown Settings */}
              <div>
                <label className="block font-semibold text-text-main mb-1">
                  Cooldown Anti-Spam (Menit)
                </label>
                <div className="flex items-center gap-3">
                  <input
                    type="number"
                    min="5"
                    max="1440"
                    value={formCooldown}
                    onChange={e => setFormCooldown(Number(e.target.value))}
                    className="w-28 bg-bg-main border border-border-main rounded-lg px-3 py-1.5 text-text-main font-mono focus:outline-none focus:border-blue-500"
                  />
                  <span className="text-[11px] text-text-muted">
                    Menghindari pengiriman pesan berulang jika kondisi server tetap kritis dalam rentang waktu ini.
                  </span>
                </div>
              </div>

              {/* Submit Buttons */}
              <div className="pt-4 border-t border-border-main flex justify-end gap-2.5">
                <button
                  type="button"
                  onClick={() => setIsFormOpen(false)}
                  className="px-4 py-2 bg-bg-main hover:bg-bg-hover border border-border-main rounded-lg text-text-muted font-semibold transition-colors"
                >
                  Batal
                </button>
                <button
                  type="submit"
                  disabled={isSubmitting}
                  className="px-5 py-2 bg-blue-600 hover:bg-blue-500 text-white font-bold rounded-lg shadow-md shadow-blue-500/20 flex items-center gap-2 transition-colors disabled:opacity-50"
                >
                  {isSubmitting ? <RefreshCw className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                  {editingSchedule ? 'Simpan Perubahan' : 'Buat & Aktifkan Schedule'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Notification Profiles Modal */}
      {isProfilesModalOpen && (
        <NotificationChannelsModal onClose={() => setIsProfilesModalOpen(false)} />
      )}
    </div>
  );
};

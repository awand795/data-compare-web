// @ts-nocheck
import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { Play, Plus, Clock, FileText, Settings, Database, RefreshCw, XCircle, MessageCircle, CheckSquare, Square, Trash2, LayoutList, Eye } from 'lucide-react';
import { useAppStore, type ScheduleConfig, type TableMapping } from '../store/useAppStore';
import { NotificationChannelsModal } from './NotificationChannelsModal';
import { ScheduleMappingModal } from './ScheduleMappingModal';
import { ScheduleResultsModal } from './ScheduleResultsModal';
import clsx from 'clsx';

export const ScheduleManagerView: React.FC = () => {
    const { connections, addSchedule, schedules, updateScheduleStatus, runScheduleNow, notificationChannels } = useAppStore();
    const [isFormOpen, setIsFormOpen] = useState(false);
    const [isProfilesModalOpen, setIsProfilesModalOpen] = useState(false);
    const [isMappingModalOpen, setIsMappingModalOpen] = useState(false);
    const [viewingResultsJob, setViewingResultsJob] = useState<{id: string, name: string} | null>(null);
    const [expandedJobIds, setExpandedJobIds] = useState<string[]>([]);
    
    // Form state
    const [jobPrefix, setJobPrefix] = useState('');
    const [cronExpression, setCronExpression] = useState('0 0 * * * *');
    const [telegramChannelId, setTelegramChannelId] = useState('');
    const [discordChannelId, setDiscordChannelId] = useState('');
    const [saveFullData, setSaveFullData] = useState(false);
    const [sourceConnectionId, setSourceConnectionId] = useState('');
    const [targetConnectionId, setTargetConnectionId] = useState('');

    const [sourceTables, setSourceTables] = useState<string[]>([]);
    const [targetTables, setTargetTables] = useState<string[]>([]);
    const [loadingTables, setLoadingTables] = useState(false);

    // Mappings state
    const [tableMappings, setTableMappings] = useState<TableMapping[]>([]);
    const [selectedMappingIds, setSelectedMappingIds] = useState<string[]>([]);
    const [editingMapping, setEditingMapping] = useState<TableMapping | null>(null);

    const { setSchedules, setNotificationChannels } = useAppStore();

    const loadSchedules = () => {
        axios.get('/api/schedules')
            .then(res => setSchedules(res.data || []))
            .catch(err => console.error("Failed to fetch schedules", err));
    };

    // Fetch schedules and channels on mount
    useEffect(() => {
        loadSchedules();
            
        axios.get('/api/notification-channels')
            .then(res => setNotificationChannels(res.data || []))
            .catch(err => console.error("Failed to fetch channels", err));
    }, [setSchedules, setNotificationChannels]);

    const sourceConn = connections.find(c => c.id === sourceConnectionId);
    const targetConn = connections.find(c => c.id === targetConnectionId);

    // Fetch tables when connections change
    useEffect(() => {
        let sourceLoading = !!sourceConn;
        let targetLoading = !!targetConn;
        setLoadingTables(sourceLoading || targetLoading);
        
        const p1 = sourceConn 
            ? axios.post('/api/tables', sourceConn).then(res => setSourceTables(res.data.filter((t: any) => !t.name.toLowerCase().startsWith('excel_import_')).map((t: any) => t.name))).catch(console.error)
            : Promise.resolve(setSourceTables([]));
            
        const p2 = targetConn 
            ? axios.post('/api/tables', targetConn).then(res => setTargetTables(res.data.filter((t: any) => !t.name.toLowerCase().startsWith('excel_import_')).map((t: any) => t.name))).catch(console.error)
            : Promise.resolve(setTargetTables([]));
            
        Promise.all([p1, p2]).finally(() => setLoadingTables(false));
    }, [sourceConn?.id, targetConn?.id]);

    // Auto-create mappings
    useEffect(() => {
        if (sourceTables.length > 0 && targetTables.length > 0 && tableMappings.length === 0) {
            const newMappings: TableMapping[] = [];
            const commonTables = sourceTables.filter(t => targetTables.includes(t));
            commonTables.forEach(t => {
                newMappings.push({ id: `auto-${t}`, sourceTable: t, targetTable: t });
            });
            sourceTables.filter(t => !targetTables.includes(t)).forEach(t => {
                newMappings.push({ id: `src-only-${t}`, sourceTable: t, targetTable: '' });
            });
            targetTables.filter(t => !sourceTables.includes(t)).forEach(t => {
                newMappings.push({ id: `tgt-only-${t}`, sourceTable: '', targetTable: t });
            });
            setTableMappings(newMappings);
            // Auto-select the matching ones
            setSelectedMappingIds(commonTables.map(t => `auto-${t}`));
        }
    }, [sourceTables, targetTables]);

    const handleSaveMapping = (mapping: TableMapping) => {
        setTableMappings(prev => {
            const exists = prev.find(m => m.id === mapping.id);
            if (exists) return prev.map(m => m.id === mapping.id ? mapping : m);
            return [...prev, mapping];
        });
        if (!selectedMappingIds.includes(mapping.id)) {
            setSelectedMappingIds(prev => [...prev, mapping.id]);
        }
    };

    const removeMapping = (id: string) => {
        setTableMappings(prev => prev.filter(m => m.id !== id));
        setSelectedMappingIds(prev => prev.filter(sid => sid !== id));
    };

    const toggleSelectAll = () => {
        if (selectedMappingIds.length === tableMappings.length) {
            setSelectedMappingIds([]);
        } else {
            setSelectedMappingIds(tableMappings.map(m => m.id));
        }
    };

    const toggleSelect = (id: string) => {
        setSelectedMappingIds(prev => prev.includes(id) ? prev.filter(sid => sid !== id) : [...prev, id]);
    };

    const handleSubmit = async () => {
        if (!sourceConnectionId || !targetConnectionId || selectedMappingIds.length === 0 || !jobPrefix || !cronExpression) {
            alert('Please fill all required fields and select at least one mapping.');
            return;
        }

        const mappingsToSchedule = tableMappings.filter(m => selectedMappingIds.includes(m.id));
        
        // Auto-fix cron: Spring Boot requires 6 fields. If 5 fields given, prepend '0 '
        let finalCron = cronExpression.trim();
        if (finalCron.split(/\s+/).length === 5) {
            finalCron = '0 ' + finalCron;
        }

        try {
            const payload: Partial<ScheduleConfig> = {
                name: jobPrefix,
                sourceConnectionId,
                targetConnectionId,
                sourceTable: 'multiple',
                targetTable: 'multiple',
                cronExpression: finalCron,
                telegramChannelId,
                discordChannelId,
                saveFullData,
                mappings: JSON.stringify(mappingsToSchedule),
            };

            await axios.post('/api/schedules', payload);
            alert(`Successfully created grouped job: ${jobPrefix}`);
            
            setIsFormOpen(false);
            setTableMappings([]);
            setSelectedMappingIds([]);
            setSourceConnectionId('');
            setTargetConnectionId('');
            setJobPrefix('');
            
            loadSchedules();
        } catch (error) {
            console.error(error);
            alert('Failed to save schedule. Check console.');
        }
    };

    const toggleJobExpand = (id: string) => {
        setExpandedJobIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
    };

    const openAddModal = () => {
        setEditingMapping(null);
        setIsMappingModalOpen(true);
    };
    
    const openEditModal = (m: TableMapping) => {
        setEditingMapping(m);
        setIsMappingModalOpen(true);
    };

    return (
        <div className="flex-1 flex flex-col min-h-0 bg-bg-main relative">
            {isProfilesModalOpen && <NotificationChannelsModal onClose={() => setIsProfilesModalOpen(false)} />}
            {isMappingModalOpen && (
                <ScheduleMappingModal
                    sourceTables={sourceTables}
                    targetTables={targetTables}
                    editingMapping={editingMapping}
                    sourceConn={sourceConn}
                    targetConn={targetConn}
                    onSave={handleSaveMapping}
                    onClose={() => setIsMappingModalOpen(false)}
                />
            )}

            <div className="p-4 border-b border-border-main flex justify-between items-center bg-bg-panel shrink-0">
                <div className="flex items-center gap-2">
                    <div className="w-8 h-8 rounded-lg bg-purple-500/10 flex items-center justify-center">
                        <Clock className="w-5 h-5 text-purple-400" />
                    </div>
                    <div>
                        <h1 className="font-bold text-lg">Scheduled Jobs</h1>
                        <p className="text-xs text-text-muted">Automate database comparisons and sync checks</p>
                    </div>
                </div>
                <div className="flex gap-2">
                    <button onClick={() => setIsFormOpen(true)} className="flex items-center gap-2 px-4 py-2 bg-purple-600 hover:bg-purple-500 text-white text-sm font-semibold rounded shadow-lg shadow-purple-500/20 transition-colors">
                        <Plus className="w-4 h-4" /> New Job
                    </button>
                </div>
            </div>

            <div className="flex-1 overflow-auto p-4 flex flex-col gap-4">
                {schedules.length === 0 ? (
                    <div className="flex flex-col items-center justify-center h-full text-text-muted gap-4">
                        <Clock className="w-16 h-16 opacity-20" />
                        <p>No scheduled jobs configured</p>
                        <button onClick={() => setIsFormOpen(true)} className="text-sm font-semibold text-purple-400 hover:text-purple-300">
                            Create your first job
                        </button>
                    </div>
                ) : (
                    <div className="bg-bg-panel border border-border-main rounded-xl overflow-x-auto shadow-sm">
                        <table className="w-full text-left text-xs border-collapse">
                            <thead className="bg-bg-header text-[10px] text-text-muted uppercase tracking-wider border-b border-border-main sticky top-0 z-10">
                                <tr>
                                    <th className="py-3 px-4 font-bold w-8"></th>
                                    <th className="py-3 px-4 font-bold">Job Name</th>
                                    <th className="py-3 px-4 font-bold">Schedule (Cron)</th>
                                    <th className="py-3 px-4 font-bold">Tables</th>
                                    <th className="py-3 px-4 font-bold text-center">Status</th>
                                    <th className="py-3 px-4 font-bold text-center">Actions</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border-item">
                                {schedules.map(job => {
                                    const isExpanded = expandedJobIds.includes(job.id);
                                    let jobMappings: any[] = [];
                                    try {
                                        jobMappings = typeof job.mappings === 'string' ? JSON.parse(job.mappings) : (job.mappings || []);
                                    } catch (e) {}

                                    // Fallback for legacy jobs (pre-grouping)
                                    if (jobMappings.length === 0 && job.sourceTable && job.sourceTable !== 'multiple') {
                                        jobMappings = [{
                                            sourceTable: job.sourceTable,
                                            targetTable: job.targetTable,
                                            primaryKeys: job.primaryKeys
                                        }];
                                    }

                                    return (
                                        <React.Fragment key={job.id}>
                                            <tr className="hover:bg-bg-hover transition-colors group">
                                                <td className="py-3 px-4">
                                                    <button onClick={() => toggleJobExpand(job.id)} className="p-1 hover:bg-bg-hover rounded text-text-muted">
                                                        <Plus className={clsx("w-3 h-3 transition-transform", isExpanded && "rotate-45")} />
                                                    </button>
                                                </td>
                                                <td className="py-3 px-4">
                                                    <div className="font-bold text-text-main">{job.name}</div>
                                                    <div className="text-[10px] text-text-muted mt-0.5">Created: {job.createdAt ? new Date(job.createdAt).toLocaleDateString() : '-'}</div>
                                                </td>
                                                <td className="py-3 px-4">
                                                    <div className="flex items-center gap-1.5 text-blue-400 font-mono text-[10px]">
                                                        <RefreshCw className="w-3 h-3" />
                                                        {job.cronExpression}
                                                    </div>
                                                </td>
                                                <td className="py-3 px-4">
                                                    <span className="px-2 py-0.5 bg-bg-hover border border-border-item rounded-full font-bold text-[9px]">
                                                        {jobMappings.length} tables
                                                    </span>
                                                </td>
                                                <td className="py-3 px-4 text-center">
                                                    <label className="relative inline-flex items-center cursor-pointer">
                                                        <input type="checkbox" checked={job.isActive} onChange={() => updateScheduleStatus(job.id, !job.isActive)} className="sr-only peer" />
                                                        <div className="w-8 h-4 bg-bg-hover peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-text-muted peer-checked:after:bg-white after:border-gray-300 after:border after:rounded-full after:h-3 after:w-3 after:transition-all peer-checked:bg-purple-500"></div>
                                                    </label>
                                                </td>
                                                <td className="py-3 px-4 text-center">
                                                    <div className="flex items-center justify-center gap-1">
                                                        <button 
                                                            onClick={() => setViewingResultsJob({id: job.id, name: job.name})}
                                                            className="p-1.5 text-text-muted hover:text-blue-400 hover:bg-blue-400/10 rounded transition-colors" 
                                                            title="View Execution History"
                                                        >
                                                            <Eye className="w-4 h-4" />
                                                        </button>
                                                        <button 
                                                            onClick={() => runScheduleNow(job.id)} 
                                                            className="p-1.5 text-text-muted hover:text-emerald-400 hover:bg-emerald-400/10 rounded transition-colors" 
                                                            title="Run Now"
                                                        >
                                                            <Play className="w-4 h-4" />
                                                        </button>
                                                        <button 
                                                            onClick={async () => {
                                                                if (confirm(`Are you sure you want to delete job "${job.name}"?`)) {
                                                                    try {
                                                                        await axios.delete(`/api/schedules/${job.id}`);
                                                                        loadSchedules();
                                                                    } catch (e) { alert("Failed to delete job"); }
                                                                }
                                                            }}
                                                            className="p-1.5 text-text-muted hover:text-red-500 hover:bg-red-500/10 rounded transition-colors" 
                                                            title="Delete Job"
                                                        >
                                                            <Trash2 className="w-4 h-4" />
                                                        </button>
                                                    </div>
                                                </td>
                                            </tr>
                                            {isExpanded && (
                                                <tr className="bg-bg-subtle/30 border-b border-border-item">
                                                    <td colSpan={6} className="p-0">
                                                        <div className="px-12 py-3 border-l-2 border-purple-500/30">
                                                            <table className="w-full text-[10px]">
                                                                <thead>
                                                                    <tr className="text-text-muted border-b border-border-item">
                                                                        <th className="pb-1.5 font-bold text-left">Source Table</th>
                                                                        <th className="pb-1.5 font-bold text-left">Target Table</th>
                                                                        <th className="pb-1.5 font-bold text-left">Primary Keys</th>
                                                                    </tr>
                                                                </thead>
                                                                <tbody className="divide-y divide-border-item/50">
                                                                    {jobMappings.map((m: any, idx: number) => (
                                                                        <tr key={idx}>
                                                                            <td className="py-1.5 font-mono">{m.sourceTable || '-'}</td>
                                                                            <td className="py-1.5 font-mono">{m.targetTable || '-'}</td>
                                                                            <td className="py-1.5 text-text-muted italic">{Array.isArray(m.primaryKeys) ? m.primaryKeys.join(', ') : (m.primaryKeys || 'auto')}</td>
                                                                        </tr>
                                                                    ))}
                                                                </tbody>
                                                            </table>
                                                        </div>
                                                    </td>
                                                </tr>
                                            )}
                                        </React.Fragment>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>

            {/* Modals */}
            {viewingResultsJob && (
                <ScheduleResultsModal 
                    scheduleId={viewingResultsJob.id} 
                    scheduleName={viewingResultsJob.name} 
                    onClose={() => setViewingResultsJob(null)} 
                />
            )}

            {isFormOpen && (
                <div className="absolute inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
                    <div className="bg-bg-panel border border-border-main rounded-xl shadow-2xl w-full max-w-[900px] h-[90vh] flex flex-col">
                        <div className="p-4 border-b border-border-main flex justify-between items-center shrink-0">
                            <h2 className="font-bold text-lg flex items-center gap-2"><Clock className="w-5 h-5 text-purple-400"/> Create Scheduled Jobs</h2>
                            <button onClick={() => setIsFormOpen(false)} className="text-text-muted hover:text-white"><XCircle className="w-5 h-5"/></button>
                        </div>
                        
                        <div className="flex-1 overflow-auto flex flex-col p-5 gap-6">
                            
                            {/* Top Section */}
                            <div className="grid grid-cols-3 gap-4">
                                <div className="col-span-1">
                                    <label className="block text-xs font-semibold text-text-muted mb-1 uppercase tracking-widest">Job Name Prefix</label>
                                    <input required type="text" value={jobPrefix} onChange={e => setJobPrefix(e.target.value)} className="w-full bg-bg-input border border-border-input rounded px-3 py-2 text-sm focus:border-blue-500 focus:outline-none" placeholder="e.g. Daily Sync" />
                                </div>
                                <div className="col-span-1">
                                    <label className="block text-xs font-semibold text-blue-400 mb-1 uppercase tracking-widest">Source Connection</label>
                                    <select required value={sourceConnectionId} onChange={e => {setSourceConnectionId(e.target.value); setTableMappings([]);}} className="w-full bg-bg-input border border-border-input rounded px-3 py-2 text-sm focus:border-blue-500 focus:outline-none">
                                        <option value="">Select...</option>
                                        {connections.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                                    </select>
                                </div>
                                <div className="col-span-1">
                                    <label className="block text-xs font-semibold text-emerald-400 mb-1 uppercase tracking-widest">Target Connection</label>
                                    <select required value={targetConnectionId} onChange={e => {setTargetConnectionId(e.target.value); setTableMappings([]);}} className="w-full bg-bg-input border border-border-input rounded px-3 py-2 text-sm focus:border-blue-500 focus:outline-none">
                                        <option value="">Select...</option>
                                        {connections.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                                    </select>
                                </div>
                            </div>

                            {/* Bottom Section (Cron & Notifications) */}
                            <div className="grid grid-cols-2 gap-6 pt-2">
                                <div className="col-span-1">
                                    <label className="block text-xs font-semibold text-text-muted mb-1 uppercase tracking-widest">Cron Expression (Spring Boot)</label>
                                    <input required type="text" value={cronExpression} onChange={e => setCronExpression(e.target.value)} className="w-full bg-bg-input border border-border-input rounded px-3 py-2 text-sm font-mono text-blue-400 focus:border-blue-500 focus:outline-none" placeholder="0 0 * * * *" />
                                </div>
                                <div className="col-span-1">
                                    <label className="flex items-center gap-2 cursor-pointer mt-6">
                                        <input type="checkbox" checked={saveFullData} onChange={e => setSaveFullData(e.target.checked)} className="rounded border-border-input text-purple-500 bg-bg-input focus:ring-purple-500 w-4 h-4" />
                                        <span className="text-xs font-medium">Save full differing rows to database (Warning: disk space)</span>
                                    </label>
                                </div>
                                
                                <div className="col-span-2 border-t border-border-main pt-4">
                                    <div className="flex justify-between items-center mb-3">
                                        <h3 className="text-xs font-bold text-text-muted uppercase tracking-widest">Notifications</h3>
                                        <button type="button" onClick={() => setIsProfilesModalOpen(true)} className="flex items-center gap-1.5 text-xs text-blue-400 hover:text-blue-300 transition-colors">
                                            <MessageCircle className="w-3.5 h-3.5" /> Manage Profiles
                                        </button>
                                    </div>
                                    <div className="grid grid-cols-2 gap-4">
                                        <div>
                                            <label className="block text-[10px] font-semibold text-text-muted mb-1">Telegram Profile</label>
                                            <select value={telegramChannelId} onChange={e => setTelegramChannelId(e.target.value)} className="w-full bg-bg-input border border-border-input rounded px-3 py-2 text-xs focus:border-blue-500 focus:outline-none">
                                                <option value="">None</option>
                                                {notificationChannels?.filter(c => c.type === 'TELEGRAM').map(c => (
                                                    <option key={c.id} value={c.id}>{c.name}</option>
                                                ))}
                                            </select>
                                        </div>
                                        <div>
                                            <label className="block text-[10px] font-semibold text-text-muted mb-1">Discord Profile</label>
                                            <select value={discordChannelId} onChange={e => setDiscordChannelId(e.target.value)} className="w-full bg-bg-input border border-border-input rounded px-3 py-2 text-xs focus:border-blue-500 focus:outline-none">
                                                <option value="">None</option>
                                                {notificationChannels?.filter(c => c.type === 'DISCORD').map(c => (
                                                    <option key={c.id} value={c.id}>{c.name}</option>
                                                ))}
                                            </select>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        
                            {/* Mappings Table */}
                            <div className="flex-1 flex flex-col border border-border-main rounded-xl overflow-hidden min-h-[300px]">
                                <div className="bg-bg-header px-4 py-2.5 flex items-center justify-between border-b border-border-main shrink-0">
                                    <div className="flex items-center gap-2 text-sm font-semibold text-text-main">
                                        <LayoutList className="w-4 h-4 text-purple-400" /> Tables to Schedule
                                        <span className="px-1.5 py-0.5 bg-bg-hover rounded text-xs text-text-muted">{selectedMappingIds.length} / {tableMappings.length}</span>
                                    </div>
                                    <button
                                        onClick={openAddModal}
                                        className="px-3 py-1.5 border border-border-input bg-bg-panel hover:bg-bg-hover rounded-md text-xs font-medium text-text-main flex items-center gap-1.5 transition-colors"
                                    >
                                        <Plus className="w-3.5 h-3.5 text-blue-500" /> Add Custom Mapping
                                    </button>
                                </div>
                                
                                <div className="flex-1 overflow-auto">
                                    <table className="w-full text-left text-xs">
                                        <thead className="sticky top-0 z-10 bg-bg-header text-[10px] text-text-muted uppercase tracking-wider border-b border-border-main">
                                            <tr>
                                                <th className="py-2 px-3 w-10 text-center">
                                                    <button onClick={toggleSelectAll} className="text-text-muted hover:text-purple-400 pt-0.5">
                                                        {selectedMappingIds.length === tableMappings.length && tableMappings.length > 0
                                                            ? <CheckSquare className="w-4 h-4 text-purple-500" />
                                                            : <Square className="w-4 h-4" />}
                                                    </button>
                                                </th>
                                                <th className="py-2 px-3">Label / Source</th>
                                                <th className="py-2 px-3 text-center w-8">→</th>
                                                <th className="py-2 px-3">Target</th>
                                                <th className="py-2 px-3 text-center w-14">Actions</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {loadingTables && (
                                                <tr>
                                                    <td colSpan={5} className="py-12 text-center text-sm text-text-muted">
                                                        Loading tables...
                                                    </td>
                                                </tr>
                                            )}
                                            {!loadingTables && tableMappings.length === 0 && (
                                                <tr>
                                                    <td colSpan={5} className="py-12 text-center text-sm text-text-muted">
                                                        Select Source and Target connections to load tables.
                                                    </td>
                                                </tr>
                                            )}
                                            {!loadingTables && tableMappings.filter(m => 
                                                !(m.sourceTable?.toLowerCase().startsWith('excel_import_') || m.targetTable?.toLowerCase().startsWith('excel_import_'))
                                            ).map(m => {
                                                const isChecked = selectedMappingIds.includes(m.id);
                                                const hasCustom = !!(m.customQuerySource || m.customQueryTarget);
                                                const displayName = m.label || m.sourceTable || '(none)';

                                                return (
                                                    <tr key={m.id} className={clsx("border-b border-border-item transition-colors", isChecked ? "bg-purple-500/5" : "hover:bg-bg-hover")}>
                                                        <td className="py-2 px-3 text-center">
                                                            <button onClick={() => toggleSelect(m.id)} className="pt-0.5">
                                                                {isChecked
                                                                    ? <CheckSquare className="w-4 h-4 text-purple-500" />
                                                                    : <Square className="w-4 h-4 text-text-muted" />}
                                                            </button>
                                                        </td>
                                                        <td className="py-2 px-3">
                                                            <span className={clsx("font-mono text-xs font-medium", m.sourceTable ? "text-text-main" : "text-text-muted italic")}>
                                                                {displayName}
                                                            </span>
                                                            {hasCustom && <span className="ml-2 text-[9px] bg-blue-500/20 text-blue-500 px-1.5 py-0.5 rounded font-bold">SQL</span>}
                                                        </td>
                                                        <td className="py-2 px-3 text-center text-text-muted">→</td>
                                                        <td className="py-2 px-3">
                                                            <span className={clsx("font-mono text-xs font-medium", m.targetTable ? "text-text-main" : "text-text-muted italic")}>
                                                                {m.targetTable || '(none)'}
                                                            </span>
                                                        </td>
                                                        <td className="py-2 px-3 text-center">
                                                            <div className="flex items-center justify-center gap-1">
                                                                <button onClick={() => openEditModal(m)} className="p-1 rounded text-text-muted hover:text-blue-500 hover:bg-bg-hover transition-colors" title="Edit mapping">
                                                                    <Settings className="w-3.5 h-3.5" />
                                                                </button>
                                                                <button onClick={() => removeMapping(m.id)} className="p-1 rounded text-text-muted hover:text-red-500 hover:bg-bg-hover transition-colors" title="Remove mapping">
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
                            </div>

                            </div>

                        <div className="p-4 border-t border-border-main bg-bg-header shrink-0 flex justify-end gap-3 rounded-b-xl">
                            <button onClick={() => setIsFormOpen(false)} className="px-5 py-2 text-sm font-semibold hover:bg-bg-hover text-text-muted hover:text-text-main rounded-lg transition-colors">Cancel</button>
                            <button onClick={handleSubmit} disabled={selectedMappingIds.length === 0} className="px-5 py-2 bg-purple-600 hover:bg-purple-500 disabled:opacity-50 text-white text-sm font-bold rounded-lg shadow-lg shadow-purple-500/20 transition-all flex items-center gap-2">
                                <Clock className="w-4 h-4" /> Save {selectedMappingIds.length} Job(s)
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

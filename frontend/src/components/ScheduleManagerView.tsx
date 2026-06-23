// @ts-nocheck
import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { Play, Plus, Clock, RefreshCw, XCircle, MessageCircle, Trash2, Eye, Edit, Save } from 'lucide-react';
import { useAppStore, type ScheduleConfig, type Template } from '../store/useAppStore';
import { NotificationChannelsModal } from './NotificationChannelsModal';
import { ScheduleResultsModal } from './ScheduleResultsModal';
import clsx from 'clsx';

export const ScheduleManagerView: React.FC = () => {
    const { connections, schedules, updateScheduleStatus, runScheduleNow, notificationChannels, addToast, templates, setTemplates, setSchedules, setNotificationChannels } = useAppStore();
    const [isFormOpen, setIsFormOpen] = useState(false);
    const [isProfilesModalOpen, setIsProfilesModalOpen] = useState(false);
    const [editingScheduleId, setEditingScheduleId] = useState<string | null>(null);
    const [viewingResultsJob, setViewingResultsJob] = useState<{id: string, name: string} | null>(null);
    const [expandedJobIds, setExpandedJobIds] = useState<string[]>([]);
    
    // Form state
    const [jobPrefix, setJobPrefix] = useState('');
    const [selectedTemplateId, setSelectedTemplateId] = useState('');
    const [cronExpression, setCronExpression] = useState('0 0 * * * *');
    const [telegramChannelId, setTelegramChannelId] = useState('');
    const [discordChannelId, setDiscordChannelId] = useState('');
    const [saveFullData, setSaveFullData] = useState(false);

    const loadSchedules = () => {
        axios.get('/api/schedules')
            .then(res => setSchedules(res.data || []))
            .catch(err => console.error("Failed to fetch schedules", err));
    };

    // Fetch on mount
    useEffect(() => {
        loadSchedules();
        axios.get('/api/notification-channels')
            .then(res => setNotificationChannels(res.data || []))
            .catch(err => console.error("Failed to fetch channels", err));
        axios.get('/api/templates')
            .then(res => setTemplates(res.data || []))
            .catch(err => console.error("Failed to fetch templates", err));
    }, [setSchedules, setNotificationChannels, setTemplates]);

    const handleSubmit = async () => {
        if (!jobPrefix || !selectedTemplateId || !cronExpression) {
            addToast({ type: 'warning', title: 'Validation Error', message: 'Please fill all required fields.' });
            return;
        }

        const template = templates.find(t => t.id === selectedTemplateId);
        if (!template) {
            addToast({ type: 'warning', title: 'Validation Error', message: 'Selected template not found.' });
            return;
        }

        const mappingsToSchedule = [{
            id: `template-${template.id}`,
            sourceTable: 'query',
            targetTable: 'query',
            customQuerySource: template.customQuerySource,
            customQueryTarget: template.customQueryTarget,
            primaryKeys: template.queryPrimaryKeys ? template.queryPrimaryKeys.split(',').map(s=>s.trim()) : [],
            isManualQuerySource: true,
            isManualQueryTarget: true
        }];
        
        let finalCron = cronExpression.trim();
        if (finalCron.split(/\s+/).length === 5) {
            finalCron = '0 ' + finalCron;
        }

        try {
            const payload: Partial<ScheduleConfig> = {
                name: jobPrefix,
                sourceConnectionId: template.sourceConnectionId,
                targetConnectionId: template.targetConnectionId,
                sourceTable: 'multiple',
                targetTable: 'multiple',
                cronExpression: finalCron,
                telegramChannelId,
                discordChannelId,
                saveFullData,
                mappings: JSON.stringify(mappingsToSchedule),
            };

            if (editingScheduleId) {
                payload.id = editingScheduleId;
                await axios.put(`/api/schedules/${editingScheduleId}`, payload);
                addToast({ type: 'success', title: 'Job Updated', message: `Successfully updated job: ${jobPrefix}` });
            } else {
                await axios.post('/api/schedules', payload);
                addToast({ type: 'success', title: 'Job Created', message: `Successfully created job: ${jobPrefix}` });
            }
            
            setIsFormOpen(false);
            setEditingScheduleId(null);
            setJobPrefix('');
            setSelectedTemplateId('');
            
            loadSchedules();
        } catch (error) {
            console.error(error);
            addToast({ type: 'error', title: 'Save Failed', message: 'Failed to save schedule.' });
        }
    };

    const toggleJobExpand = (id: string) => {
        setExpandedJobIds(prev => prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]);
    };

    const handleEditSchedule = (job: ScheduleConfig) => {
        setEditingScheduleId(job.id);
        setJobPrefix(job.name);
        setCronExpression(job.cronExpression);
        setTelegramChannelId(job.telegramChannelId || '');
        setDiscordChannelId(job.discordChannelId || '');
        setSaveFullData(job.saveFullData || false);

        if (job.mappings) {
            try {
                const parsed = typeof job.mappings === 'string' ? JSON.parse(job.mappings) : job.mappings;
                if (parsed.length > 0 && parsed[0].id.startsWith('template-')) {
                    setSelectedTemplateId(parsed[0].id.replace('template-', ''));
                }
            } catch (e) { console.error(e); }
        }

        setIsFormOpen(true);
    };

    const queryTemplates = templates.filter(t => t.appMode === 'query');

    return (
        <div className="flex-1 flex flex-col min-h-0 bg-bg-main relative">
            {isProfilesModalOpen && <NotificationChannelsModal onClose={() => setIsProfilesModalOpen(false)} />}
            
            <div className="px-3 sm:px-4 py-2.5 border-b border-border-main flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3 sm:gap-0 bg-bg-panel shrink-0">
                <div className="flex items-center gap-2">
                    <div className="w-7 h-7 rounded-lg bg-purple-500/10 flex items-center justify-center">
                        <Clock className="w-4 h-4 text-purple-400" />
                    </div>
                    <div>
                        <h1 className="font-bold text-sm">Scheduled Jobs</h1>
                        <p className="text-[11px] text-text-muted">
                            {schedules.filter(s => s.isActive).length} job{schedules.filter(s => s.isActive).length !== 1 ? 's' : ''} active
                        </p>
                    </div>
                </div>
                <div className="flex gap-2 w-full sm:w-auto">
                    <button onClick={() => setIsFormOpen(true)} className="flex items-center justify-center gap-2 px-4 py-2 bg-purple-600 hover:bg-purple-500 text-white text-xs font-semibold rounded shadow-lg shadow-purple-500/20 transition-colors w-full sm:w-auto">
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
                                                    <span className="px-2 py-0.5 bg-bg-hover border border-border-item rounded-full font-bold text-[11px]">
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
                                                            onClick={() => handleEditSchedule(job)}
                                                            className="p-1.5 text-text-muted hover:text-purple-400 hover:bg-purple-400/10 rounded transition-colors" 
                                                            title="Edit Job"
                                                        >
                                                            <Edit className="w-4 h-4" />
                                                        </button>
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
                                                                    } catch (e) { addToast({ type: 'error', title: 'Delete Failed', message: 'Failed to delete job' }); }
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

            {viewingResultsJob && (
                <ScheduleResultsModal 
                    scheduleId={viewingResultsJob.id} 
                    scheduleName={viewingResultsJob.name} 
                    onClose={() => setViewingResultsJob(null)} 
                />
            )}

            {isFormOpen && (
                <div className="absolute inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
                    <div className="bg-bg-panel border border-border-main rounded-xl shadow-2xl w-full max-w-[600px] max-h-[90vh] flex flex-col">
                        <div className="px-5 py-4 border-b border-border-main flex justify-between items-center shrink-0">
                            <h2 className="font-bold text-[15px] flex items-center gap-2"><Clock className="w-4 h-4 text-purple-400"/> {editingScheduleId ? 'Edit Scheduled Job' : 'Create Scheduled Job'}</h2>
                            <button onClick={() => { setIsFormOpen(false); setEditingScheduleId(null); }} className="p-1 text-text-muted hover:text-text-main hover:bg-bg-hover rounded transition-colors"><XCircle className="w-5 h-5"/></button>
                        </div>

                        <div className="p-5 flex flex-col gap-6 overflow-y-auto">
                            <div className="flex flex-col gap-4">
                                <div>
                                    <label className="block text-xs font-semibold text-text-muted mb-1 uppercase tracking-widest">Job Name</label>
                                    <input required type="text" value={jobPrefix} onChange={e => setJobPrefix(e.target.value)} className="w-full bg-bg-input border border-border-input rounded px-3 py-2 text-sm focus:border-blue-500 focus:outline-none" placeholder="e.g. Daily Sync" />
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-text-muted mb-1 uppercase tracking-widest">Query Template</label>
                                    <select required value={selectedTemplateId} onChange={e => setSelectedTemplateId(e.target.value)} className="w-full bg-bg-input border border-border-input rounded px-3 py-2 text-sm focus:border-blue-500 focus:outline-none">
                                        <option value="">Select a template...</option>
                                        {queryTemplates.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                                    </select>
                                    {queryTemplates.length === 0 && <p className="text-[10px] text-amber-500 mt-1">No Query Workspace templates found. Please create one first.</p>}
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-text-muted mb-1 uppercase tracking-widest">Cron Expression</label>
                                    <input required type="text" value={cronExpression} onChange={e => setCronExpression(e.target.value)} className="w-full bg-bg-input border border-border-input rounded px-3 py-2 text-sm font-mono text-blue-400 focus:border-blue-500 focus:outline-none" placeholder="0 0 * * * *" />
                                    <p className="text-[10px] text-text-muted mt-1">Uses Spring Boot 6-field cron syntax (Second, Minute, Hour, Day, Month, Weekday)</p>
                                </div>
                                <div className="flex items-center gap-2 mt-2">
                                    <label className="relative inline-flex items-center cursor-pointer">
                                        <input type="checkbox" checked={saveFullData} onChange={e => setSaveFullData(e.target.checked)} className="sr-only peer" />
                                        <div className="w-8 h-4 bg-bg-hover peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-text-muted peer-checked:after:bg-white after:border-gray-300 after:border after:rounded-full after:h-3 after:w-3 after:transition-all peer-checked:bg-emerald-500"></div>
                                    </label>
                                    <div>
                                        <span className="block text-xs font-bold text-text-main">Save Full Diff Data</span>
                                        <span className="block text-[10px] text-text-muted mt-0.5">Allow viewing the actual row differences (requires more database storage)</span>
                                    </div>
                                </div>
                            </div>

                            <div className="border border-border-main rounded-xl p-4 bg-bg-subtle/30">
                                <div className="flex justify-between items-center mb-4">
                                    <h3 className="text-xs font-bold text-text-muted uppercase tracking-widest">Notifications</h3>
                                    <button type="button" onClick={() => setIsProfilesModalOpen(true)} className="flex items-center gap-1.5 text-xs text-blue-400 hover:text-blue-300 transition-colors">
                                        <MessageCircle className="w-3.5 h-3.5" /> Manage Profiles
                                    </button>
                                </div>
                                <div className="grid grid-cols-2 gap-4">
                                    <div>
                                        <label className="block text-[11px] font-semibold text-text-muted mb-1">Telegram Profile</label>
                                        <select value={telegramChannelId} onChange={e => setTelegramChannelId(e.target.value)} className="w-full bg-bg-input border border-border-input rounded px-3 py-2 text-xs focus:border-blue-500 focus:outline-none">
                                            <option value="">None</option>
                                            {notificationChannels?.filter(c => c.type === 'TELEGRAM').map(c => (
                                                <option key={c.id} value={c.id}>{c.name}</option>
                                            ))}
                                        </select>
                                    </div>
                                    <div>
                                        <label className="block text-[11px] font-semibold text-text-muted mb-1">Discord Profile</label>
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

                        <div className="px-5 py-4 border-t border-border-main bg-bg-header flex justify-end gap-2 shrink-0">
                            <button onClick={() => { setIsFormOpen(false); setEditingScheduleId(null); }} className="px-5 py-2 text-xs font-bold text-text-muted hover:text-text-main transition-colors">
                                Cancel
                            </button>
                            <button onClick={handleSubmit} disabled={!selectedTemplateId} className="px-6 py-2 bg-purple-600 hover:bg-purple-500 disabled:opacity-50 text-white text-xs font-bold rounded-lg shadow-lg shadow-purple-500/20 transition-all flex items-center gap-2">
                                <Save className="w-4 h-4" /> {editingScheduleId ? 'Update Job' : 'Save Job'}
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

// @ts-nocheck
import React, { useState, useEffect, useMemo } from 'react';
import axios from 'axios';
import { 
  Play, Plus, Clock, RefreshCw, XCircle, MessageCircle, Trash2, Eye, Edit, Save, 
  Search, Eraser, Folder, FolderOpen, FolderPlus, FolderTree, X, Loader2, AlertTriangle, Check, Pencil,
  ChevronDown, ChevronRight, CheckSquare, Square
} from 'lucide-react';
import { useAppStore, type ScheduleConfig, type Template } from '../store/useAppStore';
import { NotificationChannelsModal } from './NotificationChannelsModal';
import { ScheduleResultsModal } from './ScheduleResultsModal';
import clsx from 'clsx';

export const ScheduleManagerView: React.FC = () => {
    const { connections, schedules, updateScheduleStatus, runScheduleNow, notificationChannels, addToast, showAlert, templates, setTemplates, setSchedules, setNotificationChannels } = useAppStore();
    const [isFormOpen, setIsFormOpen] = useState(false);
    const [isProfilesModalOpen, setIsProfilesModalOpen] = useState(false);
    const [editingScheduleId, setEditingScheduleId] = useState<string | null>(null);
    const [viewingResultsJob, setViewingResultsJob] = useState<{id: string, name: string} | null>(null);
    const [expandedJobIds, setExpandedJobIds] = useState<string[]>([]);
    
    // Grouping & Search States
    const [searchQuery, setSearchQuery] = useState('');
    const [selectedGroup, setSelectedGroup] = useState<string>('ALL');
    const [isManageGroupsModalOpen, setIsManageGroupsModalOpen] = useState(false);
    const [newGroupInputName, setNewGroupInputName] = useState('');
    const [editingGroupName, setEditingGroupName] = useState<string | null>(null);
    const [editingGroupNewName, setEditingGroupNewName] = useState('');
    const [deletingGroupName, setDeletingGroupName] = useState<string | null>(null);
    const [isProcessingGroupAction, setIsProcessingGroupAction] = useState(false);
    const [isQuickGroupModalOpen, setIsQuickGroupModalOpen] = useState(false);
    const [quickGroupTarget, setQuickGroupTarget] = useState<ScheduleConfig | null>(null);
    const [quickGroupValue, setQuickGroupValue] = useState('');
    const [isSavingQuickGroup, setIsSavingQuickGroup] = useState(false);
    const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>({});
    const [isAssignGroupModalOpen, setIsAssignGroupModalOpen] = useState(false);
    const [assignGroupTarget, setAssignGroupTarget] = useState<string | null>(null);
    const [assignSelectedIds, setAssignSelectedIds] = useState<string[]>([]);
    const [assignSearchQuery, setAssignSearchQuery] = useState('');
    const [isSavingAssignGroup, setIsSavingAssignGroup] = useState(false);

    // Form state
    const [jobPrefix, setJobPrefix] = useState('');
    const [jobGroupName, setJobGroupName] = useState('General');
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

    // Compute all unique groups
    const allGroups = useMemo(() => {
        const set = new Set<string>();
        schedules.forEach(s => {
            if (s.groupName && s.groupName.trim()) set.add(s.groupName.trim());
            else set.add('General');
        });
        if (set.size === 0) set.add('General');
        return Array.from(set).sort();
    }, [schedules]);

    // Realtime search and group filtering
    const filteredSchedules = useMemo(() => {
        return schedules.filter(job => {
            const grp = job.groupName?.trim() || 'General';
            if (selectedGroup !== 'ALL' && grp !== selectedGroup) return false;
            if (searchQuery.trim()) {
                const q = searchQuery.toLowerCase();
                const matchName = job.name?.toLowerCase().includes(q);
                const matchGroup = grp.toLowerCase().includes(q);
                const matchCron = job.cronExpression?.toLowerCase().includes(q);
                const matchSource = job.sourceTable?.toLowerCase().includes(q);
                const matchTarget = job.targetTable?.toLowerCase().includes(q);
                let matchMapping = false;
                if (job.mappings) {
                    const mStr = typeof job.mappings === 'string' ? job.mappings : JSON.stringify(job.mappings);
                    if (mStr.toLowerCase().includes(q)) matchMapping = true;
                }
                return matchName || matchGroup || matchCron || matchSource || matchTarget || matchMapping;
            }
            return true;
        });
    }, [schedules, selectedGroup, searchQuery]);

    // Grouped Schedules for Folder Accordion View
    const displayedGroups = useMemo(() => {
        const groupsToConsider = selectedGroup === 'ALL' ? allGroups : [selectedGroup];
        return groupsToConsider.map(grp => {
            const items = filteredSchedules.filter(s => (s.groupName?.trim() || 'General') === grp);
            return {
                groupName: grp,
                items,
                count: items.length,
                totalInGroup: schedules.filter(s => (s.groupName?.trim() || 'General') === grp).length
            };
        }).filter(g => {
            if (searchQuery.trim()) {
                return g.items.length > 0;
            }
            if (selectedGroup !== 'ALL') return true;
            return g.items.length > 0 || g.groupName === 'General';
        });
    }, [allGroups, filteredSchedules, selectedGroup, searchQuery, schedules]);

    const toggleGroupExpand = (grp: string) => {
        setExpandedGroups(prev => ({
            ...prev,
            [grp]: !prev[grp]
        }));
    };

    const expandAllGroups = () => {
        const all: Record<string, boolean> = {};
        allGroups.forEach(g => { all[g] = true; });
        setExpandedGroups(all);
    };

    const collapseAllGroups = () => {
        setExpandedGroups({});
    };

    // Candidate compare jobs to be added to target group (excludes jobs already in that group)
    const assignCandidateJobs = useMemo(() => {
        if (!assignGroupTarget) return [];
        return schedules.filter(s => {
            const currentGrp = s.groupName?.trim() || 'General';
            if (currentGrp === assignGroupTarget) return false;
            if (assignSearchQuery.trim()) {
                const q = assignSearchQuery.toLowerCase().trim();
                return (s.name || '').toLowerCase().includes(q) ||
                       (s.sourceTable || '').toLowerCase().includes(q) ||
                       (s.targetTable || '').toLowerCase().includes(q) ||
                       currentGrp.toLowerCase().includes(q);
            }
            return true;
        });
    }, [schedules, assignGroupTarget, assignSearchQuery]);

    const handleOpenAssignGroup = (groupName: string) => {
        setAssignGroupTarget(groupName);
        setAssignSelectedIds([]);
        setAssignSearchQuery('');
        setIsAssignGroupModalOpen(true);
    };

    const handleSaveAssignGroup = async () => {
        if (!assignGroupTarget || assignSelectedIds.length === 0) return;
        setIsSavingAssignGroup(true);
        try {
            await Promise.all(
                assignSelectedIds.map(id => 
                    axios.patch(`/api/schedules/${id}/group`, { groupName: assignGroupTarget })
                )
            );
            addToast({
                type: 'success',
                title: 'Jobs Added to Group',
                message: `Successfully moved ${assignSelectedIds.length} ${assignSelectedIds.length === 1 ? 'job' : 'jobs'} to group "${assignGroupTarget}".`
            });
            setIsAssignGroupModalOpen(false);
            setAssignGroupTarget(null);
            setAssignSelectedIds([]);
            loadSchedules();
        } catch (err: any) {
            addToast({
                type: 'error',
                title: 'Failed to Move Jobs',
                message: err.response?.data?.error || err.message
            });
        } finally {
            setIsSavingAssignGroup(false);
        }
    };

    const handleOpenQuickGroup = (job: ScheduleConfig) => {
        setQuickGroupTarget(job);
        setQuickGroupValue(job.groupName || 'General');
        setIsQuickGroupModalOpen(true);
    };

    const handleSaveQuickGroup = async () => {
        if (!quickGroupTarget) return;
        setIsSavingQuickGroup(true);
        try {
            const grp = quickGroupValue.trim() || 'General';
            await axios.patch(`/api/schedules/${quickGroupTarget.id}/group`, { groupName: grp });
            addToast({ type: 'success', title: 'Group Updated', message: `Job "${quickGroupTarget.name}" moved to group "${grp}"` });
            setIsQuickGroupModalOpen(false);
            setQuickGroupTarget(null);
            loadSchedules();
        } catch (err: any) {
            console.error('Failed to update job group:', err);
            addToast({ type: 'error', title: 'Update Failed', message: err?.response?.data?.error || 'Failed to update job group' });
        } finally {
            setIsSavingQuickGroup(false);
        }
    };

    const handleCreateGroup = (name: string) => {
        if (!name.trim()) return;
        const formatted = name.trim();
        setSelectedGroup(formatted);
        setNewGroupInputName('');
        setIsManageGroupsModalOpen(false);
        addToast({ type: 'info', title: 'Group Selected', message: `Filtered by group "${formatted}". Assign jobs to this group via Edit or Quick Group button.` });
    };

    const handleRenameGroup = async (oldName: string, newName: string) => {
        if (!newName.trim() || newName.trim() === oldName) {
            setEditingGroupName(null);
            return;
        }
        setIsProcessingGroupAction(true);
        try {
            await axios.put('/api/schedules/groups/rename', {
                oldName: oldName.trim(),
                newName: newName.trim()
            });
            addToast({
                type: 'success',
                title: 'Group Renamed',
                message: `Group "${oldName}" was renamed to "${newName.trim()}".`
            });
            setEditingGroupName(null);
            if (selectedGroup === oldName) setSelectedGroup(newName.trim());
            loadSchedules();
        } catch (err: any) {
            addToast({
                type: 'error',
                title: 'Rename Failed',
                message: err?.response?.data?.error || 'Failed to rename group'
            });
        } finally {
            setIsProcessingGroupAction(false);
        }
    };

    const handleDeleteGroup = async (groupName: string) => {
        setIsProcessingGroupAction(true);
        try {
            await axios.delete(`/api/schedules/groups/${encodeURIComponent(groupName)}`);
            addToast({
                type: 'info',
                title: 'Group Deleted',
                message: `Group "${groupName}" was deleted. All scheduled jobs moved to "General".`
            });
            setDeletingGroupName(null);
            if (selectedGroup === groupName) setSelectedGroup('ALL');
            loadSchedules();
        } catch (err: any) {
            addToast({
                type: 'error',
                title: 'Delete Failed',
                message: err?.response?.data?.error || 'Failed to delete group'
            });
        } finally {
            setIsProcessingGroupAction(false);
        }
    };

    const handleDeleteJob = (job: ScheduleConfig) => {
        showAlert({
            title: 'Delete Scheduled Job',
            message: `Are you sure you want to delete scheduled job "${job.name}"? This action cannot be undone.`,
            type: 'error',
            confirmLabel: 'Delete Job',
            onConfirm: async () => {
                try {
                    await axios.delete(`/api/schedules/${job.id}`);
                    addToast({ type: 'success', title: 'Job Deleted', message: `Job "${job.name}" has been removed.` });
                    loadSchedules();
                } catch (e: any) {
                    addToast({ type: 'error', title: 'Delete Failed', message: e?.response?.data?.error || 'Failed to delete job' });
                }
            }
        });
    };

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
                groupName: jobGroupName.trim() || 'General',
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
            setJobGroupName('General');
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
        setJobGroupName(job.groupName || 'General');
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

    const openNewForm = (presetGroup?: string) => {
        setEditingScheduleId(null);
        setJobPrefix('');
        setJobGroupName(presetGroup || (selectedGroup !== 'ALL' ? selectedGroup : 'General'));
        setSelectedTemplateId('');
        setCronExpression('0 0 * * * *');
        setTelegramChannelId('');
        setDiscordChannelId('');
        setSaveFullData(false);
        setIsFormOpen(true);
    };

    const queryTemplates = templates.filter(t => t.appMode === 'query');

    return (
        <div className="flex-1 flex flex-col min-h-0 bg-bg-main relative">
            {isProfilesModalOpen && <NotificationChannelsModal onClose={() => setIsProfilesModalOpen(false)} />}
            
            {/* ── HEADER ──────────────────────────────────────────────────────── */}
            <div className="px-3 sm:px-4 py-2.5 border-b border-border-main flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3 sm:gap-0 bg-bg-panel shrink-0">
                <div className="flex items-center gap-2">
                    <div className="w-7 h-7 rounded-lg bg-purple-500/10 flex items-center justify-center">
                        <Clock className="w-4 h-4 text-purple-400" />
                    </div>
                    <div>
                        <h1 className="font-bold text-sm">Scheduled Jobs</h1>
                        <p className="text-[11px] text-text-muted">
                            {schedules.filter(s => s.isActive).length} active / {schedules.length} total jobs
                        </p>
                    </div>
                </div>
                <div className="flex items-center gap-2 w-full sm:w-auto">
                    <button
                        type="button"
                        onClick={() => setIsManageGroupsModalOpen(true)}
                        className="flex items-center gap-1.5 px-3 py-2 bg-bg-panel hover:bg-bg-hover text-text-main border border-border-main rounded-xl text-xs font-bold transition-all shadow-sm cursor-pointer"
                        title="Manage Job Groups"
                    >
                        <FolderTree className="w-3.5 h-3.5 text-purple-400" />
                        <span>Manage Groups</span>
                        <span className="ml-0.5 px-1.5 py-0.2 rounded-full text-[10px] bg-purple-500/20 text-purple-300 font-mono">
                            {allGroups.length}
                        </span>
                    </button>
                    <button 
                        onClick={openNewForm} 
                        className="flex items-center justify-center gap-1.5 px-4 py-2 bg-purple-600 hover:bg-purple-500 text-white text-xs font-semibold rounded-xl shadow-lg shadow-purple-500/20 transition-colors w-full sm:w-auto"
                    >
                        <Plus className="w-4 h-4" /> New Job
                    </button>
                </div>
            </div>

            {/* ── SEARCH & GROUP FILTER TABS ─────────────────────────────────── */}
            <div className="px-4 py-2.5 bg-bg-panel/50 border-b border-border-main flex flex-col gap-2.5 shrink-0">
                {/* Search Bar */}
                <div className="flex items-center gap-2">
                    <div className="relative flex-1">
                        <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-text-muted pointer-events-none" />
                        <input
                            type="text"
                            className="w-full bg-bg-main border border-border-main focus:border-purple-500 focus:ring-1 focus:ring-purple-500 rounded-xl pl-9 pr-8 py-1.5 text-xs text-text-main placeholder:text-text-muted transition-all outline-none"
                            placeholder={selectedGroup !== 'ALL' ? `Search in group "${selectedGroup}" by name, cron, table...` : "Search jobs by name, group, cron, or table mapping..."}
                            value={searchQuery}
                            onChange={e => setSearchQuery(e.target.value)}
                        />
                        {searchQuery && (
                            <button
                                onClick={() => setSearchQuery('')}
                                className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-muted hover:text-text-main p-0.5 rounded-full hover:bg-bg-hover transition-colors"
                                title="Clear Search"
                            >
                                <X className="w-3.5 h-3.5" />
                            </button>
                        )}
                    </div>
                    {searchQuery && (
                        <button
                            onClick={() => setSearchQuery('')}
                            className="flex items-center gap-1 px-2.5 py-1.5 rounded-xl border border-border-main bg-bg-main hover:bg-bg-hover text-text-muted hover:text-text-main text-xs font-medium transition-colors"
                        >
                            <Eraser className="w-3.5 h-3.5" />
                            <span>Reset</span>
                        </button>
                    )}

                    {/* Expand / Collapse All Folders */}
                    <div className="flex items-center gap-1 bg-bg-main border border-border-main p-0.5 rounded-xl shrink-0">
                        <button
                            type="button"
                            onClick={expandAllGroups}
                            className="px-2.5 py-1 rounded-lg text-xs font-bold text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                            title="Expand all group folders"
                        >
                            Expand All
                        </button>
                        <button
                            type="button"
                            onClick={collapseAllGroups}
                            className="px-2.5 py-1 rounded-lg text-xs font-bold text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                            title="Collapse all group folders"
                        >
                            Collapse All
                        </button>
                    </div>
                </div>

                {/* Group Filter Tabs */}
                <div className="flex items-center gap-1.5 overflow-x-auto pb-0.5 no-scrollbar text-xs">
                    <button
                        onClick={() => setSelectedGroup('ALL')}
                        className={clsx(
                            "px-3 py-1 rounded-lg font-bold transition-all flex items-center gap-1.5 shrink-0 cursor-pointer text-xs",
                            selectedGroup === 'ALL'
                                ? "bg-purple-600 text-white shadow-md shadow-purple-600/20"
                                : "bg-bg-main hover:bg-bg-hover text-text-muted hover:text-text-main border border-border-main"
                        )}
                    >
                        <span>All Jobs</span>
                        <span className={clsx(
                            "px-1.5 py-0.2 rounded-full text-[10px] font-mono",
                            selectedGroup === 'ALL' ? "bg-purple-700 text-white" : "bg-bg-panel text-text-muted"
                        )}>
                            {schedules.length}
                        </span>
                    </button>

                    {allGroups.map(grp => {
                        const count = schedules.filter(s => (s.groupName || 'General') === grp).length;
                        return (
                            <button
                                key={grp}
                                onClick={() => setSelectedGroup(grp)}
                                className={clsx(
                                    "px-3 py-1 rounded-lg font-bold transition-all flex items-center gap-1.5 shrink-0 cursor-pointer text-xs",
                                    selectedGroup === grp
                                        ? "bg-purple-600 text-white shadow-md shadow-purple-600/20"
                                        : "bg-bg-main hover:bg-bg-hover text-text-muted hover:text-text-main border border-border-main"
                                )}
                            >
                                <Folder className="w-3.5 h-3.5" />
                                <span>{grp}</span>
                                <span className={clsx(
                                    "px-1.5 py-0.2 rounded-full text-[10px] font-mono",
                                    selectedGroup === grp ? "bg-purple-700 text-white" : "bg-bg-panel text-text-muted"
                                )}>
                                    {count}
                                </span>
                            </button>
                        );
                    })}

                    <button
                        onClick={() => setIsManageGroupsModalOpen(true)}
                        className="px-2.5 py-1 rounded-lg border border-dashed border-border-main hover:border-purple-500 hover:text-purple-400 text-text-muted text-xs font-semibold flex items-center gap-1 shrink-0 transition-colors cursor-pointer"
                        title="Manage / Create Groups"
                    >
                        <FolderPlus className="w-3.5 h-3.5" />
                        <span>Add Group</span>
                    </button>
                </div>
            </div>

            {/* ── MAIN CONTENT (FOLDER ACCORDION) ─────────────────────────────── */}
            <div className="flex-1 overflow-auto p-4 flex flex-col gap-4">
                {schedules.length === 0 ? (
                    <div className="flex flex-col items-center justify-center h-full text-text-muted gap-4">
                        <Clock className="w-16 h-16 opacity-20" />
                        <p>No scheduled jobs configured</p>
                        <button onClick={() => openNewForm()} className="text-sm font-semibold text-purple-400 hover:text-purple-300">
                            Create your first job
                        </button>
                    </div>
                ) : filteredSchedules.length === 0 ? (
                    <div className="flex flex-col items-center justify-center p-12 bg-bg-panel border border-border-main rounded-2xl text-center">
                        <Search className="w-12 h-12 text-text-muted/30 mb-3" />
                        <h3 className="text-sm font-bold text-text-main mb-1">No Matching Jobs Found</h3>
                        <p className="text-xs text-text-muted mb-4 max-w-sm">
                            {searchQuery 
                                ? `No scheduled jobs match keyword "${searchQuery}" in group "${selectedGroup}".` 
                                : `No jobs found in group "${selectedGroup}".`}
                        </p>
                        <button
                            onClick={() => { setSearchQuery(''); setSelectedGroup('ALL'); }}
                            className="px-3.5 py-1.5 bg-bg-hover hover:bg-border-main text-text-main rounded-xl text-xs font-bold transition-all border border-border-main"
                        >
                            Reset Filters
                        </button>
                    </div>
                ) : (
                    <div className="flex flex-col gap-4 pb-6">
                        {displayedGroups.map(group => {
                            const isGroupExpanded = searchQuery.trim() ? true : Boolean(expandedGroups[group.groupName]);

                            return (
                                <div 
                                    key={group.groupName}
                                    className="border border-border-main rounded-2xl bg-bg-panel/60 overflow-hidden shadow-sm transition-all"
                                >
                                    {/* Folder Group Header */}
                                    <div
                                        onClick={() => toggleGroupExpand(group.groupName)}
                                        className="flex items-center justify-between p-3.5 bg-bg-panel hover:bg-bg-hover/70 border-b border-border-main/60 cursor-pointer select-none transition-colors"
                                    >
                                        <div className="flex items-center gap-3">
                                            <div className={clsx(
                                                "w-7 h-7 rounded-lg flex items-center justify-center transition-all",
                                                isGroupExpanded ? "bg-purple-500/10 text-purple-400" : "bg-bg-main text-text-muted"
                                            )}>
                                                {isGroupExpanded ? <ChevronDown className="w-4 h-4" /> : <ChevronRight className="w-4 h-4" />}
                                            </div>
                                            <div className="flex items-center gap-2">
                                                {isGroupExpanded ? (
                                                    <FolderOpen className="w-4.5 h-4.5 text-purple-400" />
                                                ) : (
                                                    <Folder className="w-4.5 h-4.5 text-purple-400" />
                                                )}
                                                <h4 className="text-sm font-extrabold text-text-main">
                                                    {group.groupName}
                                                </h4>
                                                <span className="px-2 py-0.5 rounded-full text-[11px] font-mono font-bold bg-bg-main border border-border-main text-text-muted">
                                                    {group.count} {group.count === 1 ? 'Job' : 'Jobs'}
                                                </span>
                                            </div>
                                        </div>

                                        <div className="flex items-center gap-2" onClick={e => e.stopPropagation()}>
                                            <button
                                                onClick={() => handleOpenAssignGroup(group.groupName)}
                                                className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-purple-500/10 hover:bg-purple-500/20 text-purple-400 border border-purple-500/20 text-xs font-bold transition-all cursor-pointer"
                                                title={`Add existing jobs from other groups to "${group.groupName}"`}
                                            >
                                                <FolderPlus className="w-3.5 h-3.5" />
                                                <span className="hidden sm:inline">Add Jobs</span>
                                            </button>

                                            <button
                                                onClick={() => openNewForm(group.groupName)}
                                                className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-purple-500/10 hover:bg-purple-500/20 text-purple-400 border border-purple-500/20 text-xs font-bold transition-all cursor-pointer"
                                                title={`Create new compare job in group "${group.groupName}"`}
                                            >
                                                <Plus className="w-3.5 h-3.5" />
                                                <span className="hidden sm:inline">New Job</span>
                                            </button>
                                        </div>
                                    </div>

                                    {/* Folder Group Content */}
                                    {isGroupExpanded && (
                                        <div className="p-3.5 bg-bg-main/30 animate-in fade-in duration-150">
                                            {group.items.length === 0 ? (
                                                <div className="p-6 text-center text-text-muted border border-dashed border-border-main rounded-xl">
                                                    <p className="text-xs">No jobs found in group "{group.groupName}".</p>
                                                    <div className="mt-3 flex items-center justify-center gap-2 flex-wrap">
                                                        <button
                                                            onClick={() => handleOpenAssignGroup(group.groupName)}
                                                            className="px-3 py-1.5 bg-purple-500/10 hover:bg-purple-500/20 text-purple-400 border border-purple-500/20 rounded-xl text-xs font-bold transition-all cursor-pointer flex items-center gap-1.5"
                                                        >
                                                            <FolderPlus className="w-3.5 h-3.5" />
                                                            <span>Add Existing Jobs</span>
                                                        </button>
                                                        <button
                                                            onClick={() => openNewForm(group.groupName)}
                                                            className="px-3 py-1.5 bg-purple-500/10 hover:bg-purple-500/20 text-purple-400 border border-purple-500/20 rounded-xl text-xs font-bold transition-all cursor-pointer flex items-center gap-1.5"
                                                        >
                                                            <Plus className="w-3.5 h-3.5" />
                                                            <span>Create New Job</span>
                                                        </button>
                                                    </div>
                                                </div>
                                            ) : (
                                                <div className="bg-bg-panel border border-border-main rounded-xl overflow-x-auto shadow-sm">
                                                    <table className="w-full text-left text-xs border-collapse min-w-[700px]">
                                                        <thead className="bg-bg-header text-[10px] text-text-muted uppercase tracking-wider border-b border-border-main sticky top-0 z-10">
                                                            <tr>
                                                                <th className="py-3 px-4 font-bold w-8"></th>
                                                                <th className="py-3 px-4 font-bold">Job Name &amp; Group</th>
                                                                <th className="py-3 px-4 font-bold">Schedule (Cron)</th>
                                                                <th className="py-3 px-4 font-bold">Tables</th>
                                                                <th className="py-3 px-4 font-bold text-center">Status</th>
                                                                <th className="py-3 px-4 font-bold text-center">Actions</th>
                                                            </tr>
                                                        </thead>
                                                        <tbody className="divide-y divide-border-item">
                                                            {group.items.map(job => {
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
                                                                                <button onClick={() => toggleJobExpand(job.id)} className="p-1 hover:bg-bg-hover rounded text-text-muted cursor-pointer">
                                                                                    <Plus className={clsx("w-3 h-3 transition-transform", isExpanded && "rotate-45")} />
                                                                                </button>
                                                                            </td>
                                                                            <td className="py-3 px-4">
                                                                                <div className="flex items-center gap-2 flex-wrap">
                                                                                    <span className="font-bold text-text-main">{job.name}</span>
                                                                                    <button
                                                                                        onClick={() => handleOpenQuickGroup(job)}
                                                                                        className="inline-flex items-center gap-1 text-[10px] font-bold px-2 py-0.5 rounded-lg bg-purple-500/10 text-purple-400 border border-purple-500/20 hover:bg-purple-500/20 transition-all cursor-pointer"
                                                                                        title="Click to change group"
                                                                                    >
                                                                                        <Folder className="w-3 h-3" />
                                                                                        <span>{job.groupName || 'General'}</span>
                                                                                    </button>
                                                                                </div>
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
                                                                                        className="p-1.5 text-text-muted hover:text-purple-400 hover:bg-purple-400/10 rounded transition-colors cursor-pointer" 
                                                                                        title="Edit Job"
                                                                                    >
                                                                                        <Edit className="w-4 h-4" />
                                                                                    </button>
                                                                                    <button 
                                                                                        onClick={() => setViewingResultsJob({id: job.id, name: job.name})}
                                                                                        className="p-1.5 text-text-muted hover:text-blue-400 hover:bg-blue-400/10 rounded transition-colors cursor-pointer" 
                                                                                        title="View Execution History"
                                                                                    >
                                                                                        <Eye className="w-4 h-4" />
                                                                                    </button>
                                                                                    <button 
                                                                                        onClick={() => runScheduleNow(job.id)} 
                                                                                        className="p-1.5 text-text-muted hover:text-emerald-400 hover:bg-emerald-400/10 rounded transition-colors cursor-pointer" 
                                                                                        title="Run Now"
                                                                                    >
                                                                                        <Play className="w-4 h-4" />
                                                                                    </button>
                                                                                    <button 
                                                                                        onClick={() => handleDeleteJob(job)}
                                                                                        className="p-1.5 text-text-muted hover:text-red-500 hover:bg-red-500/10 rounded transition-colors cursor-pointer" 
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
                                    )}
                                </div>
                            );
                        })}
                    </div>
                )}
            </div>

            {/* ── EXECUTION HISTORY RESULTS MODAL ─────────────────────────────── */}
            {viewingResultsJob && (
                <ScheduleResultsModal 
                    scheduleId={viewingResultsJob.id} 
                    scheduleName={viewingResultsJob.name} 
                    onClose={() => setViewingResultsJob(null)} 
                />
            )}

            {/* ── CREATE / EDIT JOB MODAL ─────────────────────────────────────── */}
            {isFormOpen && (
                <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
                    <div className="bg-bg-panel border border-border-main rounded-xl shadow-2xl w-full max-w-[600px] max-h-[90vh] flex flex-col">
                        <div className="px-5 py-4 border-b border-border-main flex justify-between items-center shrink-0">
                            <h2 className="font-bold text-[15px] flex items-center gap-2"><Clock className="w-4 h-4 text-purple-400"/> {editingScheduleId ? 'Edit Scheduled Job' : 'Create Scheduled Job'}</h2>
                            <button onClick={() => { setIsFormOpen(false); setEditingScheduleId(null); }} className="p-1 text-text-muted hover:text-text-main hover:bg-bg-hover rounded transition-colors"><XCircle className="w-5 h-5"/></button>
                        </div>

                        <div className="p-5 flex flex-col gap-5 overflow-y-auto min-h-0">
                            <div className="flex flex-col gap-4">
                                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                                    <div>
                                        <label className="block text-xs font-semibold text-text-muted mb-1 uppercase tracking-widest">Job Name</label>
                                        <input required type="text" value={jobPrefix} onChange={e => setJobPrefix(e.target.value)} className="w-full bg-bg-input border border-border-input rounded-xl px-3 py-2 text-sm focus:border-purple-500 focus:outline-none" placeholder="e.g. Daily Sync Sales" />
                                    </div>
                                    <div>
                                        <label className="block text-xs font-semibold text-text-muted mb-1 uppercase tracking-widest">Group / Category</label>
                                        <input
                                            list="scheduler-job-group-suggestions"
                                            type="text"
                                            value={jobGroupName}
                                            onChange={e => setJobGroupName(e.target.value)}
                                            className="w-full bg-bg-input border border-border-input rounded-xl px-3 py-2 text-sm focus:border-purple-500 focus:outline-none"
                                            placeholder="e.g. General, Sales, Finance..."
                                        />
                                        <datalist id="scheduler-job-group-suggestions">
                                            {allGroups.map(g => (
                                                <option key={g} value={g} />
                                            ))}
                                        </datalist>
                                    </div>
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-text-muted mb-1 uppercase tracking-widest">Query Template</label>
                                    <select required value={selectedTemplateId} onChange={e => setSelectedTemplateId(e.target.value)} className="w-full bg-bg-input border border-border-input rounded-xl px-3 py-2 text-sm focus:border-purple-500 focus:outline-none">
                                        <option value="">Select a template...</option>
                                        {queryTemplates.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                                    </select>
                                    {queryTemplates.length === 0 && <p className="text-[10px] text-amber-500 mt-1">No Query Workspace templates found. Please create one first.</p>}
                                </div>
                                <div>
                                    <div className="flex items-center justify-between mb-1">
                                        <label className="block text-xs font-semibold text-text-muted uppercase tracking-widest">Cron Expression (Spring Cron 6-Field)</label>
                                        <span className="text-[10px] text-blue-400 font-mono">Bisa multi-trigger dipisah titik koma (;)</span>
                                    </div>
                                    <input required type="text" value={cronExpression} onChange={e => setCronExpression(e.target.value)} className="w-full bg-bg-input border border-border-input rounded-xl px-3 py-2 text-sm font-mono text-blue-400 focus:border-purple-500 focus:outline-none" placeholder="0 0 * * * * atau 0 0 8 * * *; 0 0 23 * * *" />
                                    <div className="flex flex-wrap gap-1 mt-1.5">
                                        {[
                                            { label: 'Tiap 5m', value: '0 */5 * * * *' },
                                            { label: 'Tiap 15m', value: '0 */15 * * * *' },
                                            { label: 'Tiap 1 Jam', value: '0 0 * * * *' },
                                            { label: 'Tiap Jam 23:00', value: '0 0 23 * * *' },
                                        ].map(p => (
                                            <button
                                                key={p.value}
                                                type="button"
                                                onClick={() => {
                                                    if (!cronExpression) setCronExpression(p.value);
                                                    else if (!cronExpression.includes(p.value)) setCronExpression(`${cronExpression}; ${p.value}`);
                                                }}
                                                className="px-1.5 py-0.5 rounded bg-bg-hover text-[10px] text-text-muted hover:text-purple-400 font-mono border border-border-input transition-colors"
                                            >
                                                + {p.label}
                                            </button>
                                        ))}
                                    </div>
                                    <p className="text-[10px] text-text-muted mt-1">Format: <code>Detik Menit Jam Hari Bulan HariMinggu</code>. User dapat mengisi bebas atau menambahkan beberapa cron dipisah <code>;</code></p>
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
                                    <button type="button" onClick={() => setIsProfilesModalOpen(true)} className="flex items-center gap-1.5 text-xs text-purple-400 hover:text-purple-300 transition-colors">
                                        <MessageCircle className="w-3.5 h-3.5" /> Manage Profiles
                                    </button>
                                </div>
                                <div className="grid grid-cols-2 gap-4">
                                    <div>
                                        <label className="block text-[11px] font-semibold text-text-muted mb-1">Telegram Profile</label>
                                        <select value={telegramChannelId} onChange={e => setTelegramChannelId(e.target.value)} className="w-full bg-bg-input border border-border-input rounded-xl px-3 py-2 text-xs focus:border-purple-500 focus:outline-none">
                                            <option value="">None</option>
                                            {notificationChannels?.filter(c => c.type === 'TELEGRAM').map(c => (
                                                <option key={c.id} value={c.id}>{c.name}</option>
                                            ))}
                                        </select>
                                    </div>
                                    <div>
                                        <label className="block text-[11px] font-semibold text-text-muted mb-1">Discord Profile</label>
                                        <select value={discordChannelId} onChange={e => setDiscordChannelId(e.target.value)} className="w-full bg-bg-input border border-border-input rounded-xl px-3 py-2 text-xs focus:border-purple-500 focus:outline-none">
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
                            <button onClick={handleSubmit} disabled={!selectedTemplateId} className="px-6 py-2 bg-purple-600 hover:bg-purple-500 disabled:opacity-50 text-white text-xs font-bold rounded-xl shadow-lg shadow-purple-500/20 transition-all flex items-center gap-2">
                                <Save className="w-4 h-4" /> {editingScheduleId ? 'Update Job' : 'Save Job'}
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* ── MANAGE JOB GROUPS MODAL ─────────────────────────────────────── */}
            {isManageGroupsModalOpen && (
                <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-in fade-in">
                    <div className="bg-bg-panel border border-border-main rounded-2xl shadow-2xl max-w-md w-full overflow-hidden flex flex-col">
                        <div className="p-5 border-b border-border-main flex items-center justify-between bg-bg-header">
                            <div className="flex items-center gap-2.5">
                                <div className="w-8 h-8 rounded-xl bg-purple-500/10 border border-purple-500/20 flex items-center justify-center text-purple-400">
                                    <FolderTree className="w-4 h-4" />
                                </div>
                                <div>
                                    <h3 className="text-sm font-bold text-text-main">Manage Job Groups</h3>
                                    <p className="text-[11px] text-text-muted">Organize Scheduled Compare Jobs into categories</p>
                                </div>
                            </div>
                            <button
                                onClick={() => setIsManageGroupsModalOpen(false)}
                                className="p-1.5 rounded-lg text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                            >
                                <X className="w-4 h-4" />
                            </button>
                        </div>

                        <div className="p-5 space-y-4">
                            {/* Create New Group Input */}
                            <div className="space-y-1.5">
                                <label className="text-[11px] font-extrabold text-text-muted uppercase tracking-wider">
                                    Create New Group
                                </label>
                                <div className="flex items-center gap-2">
                                    <input
                                        type="text"
                                        className="w-full bg-bg-main border border-border-main focus:border-purple-500 focus:ring-1 focus:ring-purple-500 rounded-xl px-3 py-2 text-xs outline-none text-text-main font-medium shadow-inner"
                                        placeholder="e.g. Sales Compare, Stock Sync, Daily Recon..."
                                        value={newGroupInputName}
                                        onChange={e => setNewGroupInputName(e.target.value)}
                                        onKeyDown={e => {
                                            if (e.key === 'Enter' && newGroupInputName.trim()) {
                                                handleCreateGroup(newGroupInputName.trim());
                                            }
                                        }}
                                    />
                                    <button
                                        type="button"
                                        disabled={!newGroupInputName.trim()}
                                        onClick={() => handleCreateGroup(newGroupInputName.trim())}
                                        className="px-4 py-2 bg-purple-600 hover:bg-purple-500 disabled:opacity-50 text-white rounded-xl text-xs font-bold transition-all shrink-0 cursor-pointer"
                                    >
                                        Add
                                    </button>
                                </div>
                            </div>

                            {/* Existing Groups List */}
                            <div className="space-y-2 pt-2">
                                <label className="text-[11px] font-extrabold text-text-muted uppercase tracking-wider block">
                                    Existing Groups ({allGroups.length})
                                </label>
                                <div className="max-h-72 overflow-y-auto space-y-2 pr-1">
                                    {allGroups.map(grp => {
                                        const count = schedules.filter(s => (s.groupName || 'General') === grp).length;
                                        const isEditing = editingGroupName === grp;
                                        const isDeleting = deletingGroupName === grp;

                                        if (isDeleting) {
                                            return (
                                                <div key={grp} className="p-3 rounded-xl border border-rose-500/40 bg-rose-500/10 space-y-2.5 animate-in fade-in">
                                                    <div className="flex items-center gap-2 text-rose-400 text-xs font-bold">
                                                        <AlertTriangle className="w-4 h-4 shrink-0" />
                                                        <span>Delete group "{grp}"?</span>
                                                    </div>
                                                    <p className="text-[11px] text-text-muted leading-relaxed">
                                                        All <b className="text-text-main font-semibold">{count}</b> jobs in this group will be moved to <b className="text-text-main font-semibold">"General"</b>.
                                                    </p>
                                                    <div className="flex items-center justify-end gap-2 pt-1">
                                                        <button
                                                            type="button"
                                                            disabled={isProcessingGroupAction}
                                                            onClick={() => setDeletingGroupName(null)}
                                                            className="px-3 py-1 rounded-lg text-xs font-bold text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                                                        >
                                                            Cancel
                                                        </button>
                                                        <button
                                                            type="button"
                                                            disabled={isProcessingGroupAction}
                                                            onClick={() => handleDeleteGroup(grp)}
                                                            className="px-3 py-1 rounded-lg bg-rose-600 hover:bg-rose-500 text-white text-xs font-bold transition-all flex items-center gap-1.5 shadow-sm shadow-rose-600/30"
                                                        >
                                                            {isProcessingGroupAction ? <Loader2 className="w-3 h-3 animate-spin" /> : <Trash2 className="w-3 h-3" />}
                                                            <span>Delete</span>
                                                        </button>
                                                    </div>
                                                </div>
                                            );
                                        }

                                        if (isEditing) {
                                            return (
                                                <div key={grp} className="p-2.5 rounded-xl border border-purple-500/50 bg-purple-500/10 flex items-center gap-2 animate-in fade-in">
                                                    <input
                                                        type="text"
                                                        autoFocus
                                                        className="flex-1 bg-bg-main border border-purple-500/50 rounded-lg px-2.5 py-1.5 text-xs text-text-main font-bold outline-none shadow-inner"
                                                        value={editingGroupNewName}
                                                        onChange={e => setEditingGroupNewName(e.target.value)}
                                                        onKeyDown={e => {
                                                            if (e.key === 'Enter') handleRenameGroup(grp, editingGroupNewName);
                                                            if (e.key === 'Escape') setEditingGroupName(null);
                                                        }}
                                                    />
                                                    <button
                                                        type="button"
                                                        disabled={isProcessingGroupAction || !editingGroupNewName.trim()}
                                                        onClick={() => handleRenameGroup(grp, editingGroupNewName)}
                                                        className="p-1.5 rounded-lg bg-purple-600 hover:bg-purple-500 text-white disabled:opacity-50 transition-colors"
                                                        title="Save Rename"
                                                    >
                                                        {isProcessingGroupAction ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
                                                    </button>
                                                    <button
                                                        type="button"
                                                        disabled={isProcessingGroupAction}
                                                        onClick={() => setEditingGroupName(null)}
                                                        className="p-1.5 rounded-lg text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                                                        title="Cancel"
                                                    >
                                                        <X className="w-3.5 h-3.5" />
                                                    </button>
                                                </div>
                                            );
                                        }

                                        return (
                                            <div
                                                key={grp}
                                                className={clsx(
                                                    "flex items-center justify-between p-2.5 rounded-xl border transition-all group",
                                                    selectedGroup === grp
                                                        ? "bg-purple-500/10 border-purple-500/40 text-purple-300"
                                                        : "bg-bg-main/60 border-border-main hover:bg-bg-hover hover:border-purple-500/30 text-text-main"
                                                )}
                                            >
                                                <div
                                                    onClick={() => {
                                                        setSelectedGroup(grp);
                                                        setIsManageGroupsModalOpen(false);
                                                    }}
                                                    className="flex items-center gap-2.5 flex-1 min-w-0 cursor-pointer"
                                                >
                                                    <Folder className="w-4 h-4 text-purple-400 shrink-0" />
                                                    <span className="text-xs font-bold truncate">{grp}</span>
                                                    <span className="text-[10px] font-mono px-2 py-0.5 bg-bg-panel rounded-md border border-border-main text-text-muted font-bold shrink-0">
                                                        {count} {count === 1 ? 'Job' : 'Jobs'}
                                                    </span>
                                                </div>

                                                <div className="flex items-center gap-1 shrink-0 ml-2">
                                                    <button
                                                        type="button"
                                                        onClick={(e) => {
                                                            e.stopPropagation();
                                                            setIsManageGroupsModalOpen(false);
                                                            handleOpenAssignGroup(grp);
                                                        }}
                                                        className="p-1.5 rounded-lg text-text-muted hover:text-purple-400 hover:bg-purple-500/10 transition-colors cursor-pointer"
                                                        title={`Add existing jobs to group "${grp}"`}
                                                    >
                                                        <FolderPlus className="w-3.5 h-3.5" />
                                                    </button>
                                                    <button
                                                        type="button"
                                                        onClick={(e) => {
                                                            e.stopPropagation();
                                                            setEditingGroupName(grp);
                                                            setEditingGroupNewName(grp);
                                                        }}
                                                        className="p-1.5 rounded-lg text-text-muted hover:text-purple-400 hover:bg-purple-500/10 transition-colors"
                                                        title={`Rename group "${grp}"`}
                                                    >
                                                        <Pencil className="w-3.5 h-3.5" />
                                                    </button>
                                                    {grp !== 'General' && (
                                                        <button
                                                            type="button"
                                                            onClick={(e) => {
                                                                e.stopPropagation();
                                                                setDeletingGroupName(grp);
                                                            }}
                                                            className="p-1.5 rounded-lg text-text-muted hover:text-rose-400 hover:bg-rose-500/10 transition-colors"
                                                            title={`Delete group "${grp}"`}
                                                        >
                                                            <Trash2 className="w-3.5 h-3.5" />
                                                        </button>
                                                    )}
                                                </div>
                                            </div>
                                        );
                                    })}
                                </div>
                            </div>
                        </div>

                        <div className="p-4 border-t border-border-main flex items-center justify-end bg-bg-main/40">
                            <button
                                type="button"
                                onClick={() => {
                                    setIsManageGroupsModalOpen(false);
                                    setEditingGroupName(null);
                                    setDeletingGroupName(null);
                                }}
                                className="px-4 py-2 rounded-xl text-xs font-bold text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                            >
                                Close
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* ── QUICK CHANGE JOB GROUP MODAL ───────────────────────────────── */}
            {isQuickGroupModalOpen && quickGroupTarget && (
                <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-in fade-in">
                    <div className="bg-bg-panel border border-border-main rounded-2xl shadow-2xl max-w-sm w-full overflow-hidden flex flex-col">
                        <div className="p-5 border-b border-border-main flex items-center justify-between bg-bg-header">
                            <div className="flex items-center gap-2">
                                <Folder className="w-4 h-4 text-purple-400" />
                                <h3 className="text-sm font-bold text-text-main">Change Job Group</h3>
                            </div>
                            <button
                                onClick={() => setIsQuickGroupModalOpen(false)}
                                className="p-1.5 rounded-lg text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                            >
                                <X className="w-4 h-4" />
                            </button>
                        </div>

                        <div className="p-5 space-y-3">
                            <p className="text-xs text-text-muted">
                                Move job <b className="text-text-main font-bold">"{quickGroupTarget.name}"</b> to a group:
                            </p>
                            <div className="relative">
                                <input
                                    list="quick-modal-job-group-list"
                                    className="w-full bg-bg-main border border-border-main focus:border-purple-500 rounded-xl p-2.5 text-xs outline-none text-text-main font-bold shadow-inner"
                                    value={quickGroupValue}
                                    onChange={e => setQuickGroupValue(e.target.value)}
                                    placeholder="Select or enter group name..."
                                />
                                <datalist id="quick-modal-job-group-list">
                                    {allGroups.map(g => (
                                        <option key={g} value={g} />
                                    ))}
                                </datalist>
                            </div>
                            <div className="flex items-center gap-1.5 flex-wrap pt-1">
                                {allGroups.map(g => (
                                    <button
                                        key={g}
                                        type="button"
                                        onClick={() => setQuickGroupValue(g)}
                                        className={clsx(
                                            "px-2.5 py-1 rounded-lg text-[11px] font-bold border transition-all cursor-pointer",
                                            quickGroupValue === g
                                                ? "bg-purple-600 text-white border-purple-500"
                                                : "bg-bg-main hover:bg-bg-hover text-text-muted border-border-main"
                                        )}
                                    >
                                        {g}
                                    </button>
                                ))}
                            </div>
                        </div>

                        <div className="p-4 border-t border-border-main flex items-center justify-end gap-2 bg-bg-main/40">
                            <button
                                type="button"
                                onClick={() => setIsQuickGroupModalOpen(false)}
                                className="px-3.5 py-2 rounded-xl text-xs font-bold text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors"
                            >
                                Cancel
                            </button>
                            <button
                                type="button"
                                onClick={handleSaveQuickGroup}
                                disabled={isSavingQuickGroup}
                                className="px-4 py-2 rounded-xl bg-purple-600 hover:bg-purple-500 disabled:opacity-50 text-white text-xs font-bold transition-all shadow-md shadow-purple-500/20 flex items-center gap-1.5"
                            >
                                {isSavingQuickGroup ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Save className="w-3.5 h-3.5" />}
                                <span>Save Group</span>
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* ── ASSIGN EXISTING ITEMS TO GROUP MODAL ───────────────────────── */}
            {isAssignGroupModalOpen && assignGroupTarget && (
                <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-in fade-in">
                    <div className="bg-bg-panel border border-border-main rounded-2xl shadow-2xl max-w-xl w-full overflow-hidden flex flex-col max-h-[85vh]">
                        {/* Header */}
                        <div className="p-5 border-b border-border-main flex items-center justify-between bg-bg-header shrink-0">
                            <div className="flex items-center gap-2.5">
                                <div className="w-8 h-8 rounded-xl bg-purple-500/10 border border-purple-500/20 flex items-center justify-center text-purple-400">
                                    <FolderPlus className="w-4 h-4" />
                                </div>
                                <div>
                                    <h3 className="text-sm font-bold text-text-main">
                                        Add Jobs to Group: <span className="text-purple-400 font-black font-mono">"{assignGroupTarget}"</span>
                                    </h3>
                                    <p className="text-[11px] text-text-muted">Select existing compare jobs from other groups to move into this group</p>
                                </div>
                            </div>
                            <button
                                onClick={() => setIsAssignGroupModalOpen(false)}
                                className="p-1.5 rounded-lg text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors cursor-pointer"
                            >
                                <X className="w-4 h-4" />
                            </button>
                        </div>

                        {/* Search & Bulk Select Controls */}
                        <div className="p-4 bg-bg-panel border-b border-border-main/60 space-y-3 shrink-0">
                            <div className="relative">
                                <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-text-muted pointer-events-none" />
                                <input
                                    type="text"
                                    className="w-full bg-bg-main border border-border-main focus:border-purple-500 focus:ring-1 focus:ring-purple-500 rounded-xl pl-9 pr-8 py-2 text-xs text-text-main placeholder:text-text-muted outline-none shadow-inner transition-colors"
                                    placeholder="Search candidate jobs by name, tables, or current group..."
                                    value={assignSearchQuery}
                                    onChange={e => setAssignSearchQuery(e.target.value)}
                                />
                                {assignSearchQuery && (
                                    <button
                                        onClick={() => setAssignSearchQuery('')}
                                        className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-muted hover:text-text-main cursor-pointer"
                                    >
                                        <X className="w-3.5 h-3.5" />
                                    </button>
                                )}
                            </div>

                            <div className="flex items-center justify-between text-xs">
                                <button
                                    type="button"
                                    onClick={() => {
                                        if (assignSelectedIds.length === assignCandidateJobs.length) {
                                            setAssignSelectedIds([]);
                                        } else {
                                            setAssignSelectedIds(assignCandidateJobs.map(j => j.id).filter(Boolean));
                                        }
                                    }}
                                    className="text-purple-400 hover:text-purple-300 font-bold transition-colors cursor-pointer flex items-center gap-1.5"
                                >
                                    {assignSelectedIds.length === assignCandidateJobs.length && assignCandidateJobs.length > 0 ? (
                                        <>
                                            <CheckSquare className="w-3.5 h-3.5" /> <span>Deselect All</span>
                                        </>
                                    ) : (
                                        <>
                                            <Square className="w-3.5 h-3.5" /> <span>Select All ({assignCandidateJobs.length})</span>
                                        </>
                                    )}
                                </button>

                                <span className="text-text-muted text-[11px] font-mono">
                                    <b className="text-purple-400">{assignSelectedIds.length}</b> of {assignCandidateJobs.length} selected
                                </span>
                            </div>
                        </div>

                        {/* Candidate Items List */}
                        <div className="flex-1 overflow-y-auto p-4 space-y-2 min-h-0 bg-bg-main/30">
                            {assignCandidateJobs.length === 0 ? (
                                <div className="p-10 text-center text-text-muted border border-dashed border-border-main rounded-xl">
                                    <Check className="w-8 h-8 text-emerald-400 mx-auto mb-2 opacity-80" />
                                    <p className="text-xs font-bold text-text-main">
                                        {schedules.length === 0 
                                            ? 'No compare jobs available.' 
                                            : assignSearchQuery 
                                                ? 'No candidate jobs match your search.' 
                                                : `All existing jobs are already inside group "${assignGroupTarget}".`}
                                    </p>
                                    <p className="text-[11px] text-text-muted mt-1">
                                        Data already in this group is automatically excluded.
                                    </p>
                                </div>
                            ) : (
                                assignCandidateJobs.map(job => {
                                    const isSelected = assignSelectedIds.includes(job.id);
                                    let jobMappings: any[] = [];
                                    try {
                                        jobMappings = typeof job.mappings === 'string' ? JSON.parse(job.mappings) : (job.mappings || []);
                                    } catch (e) {}

                                    return (
                                        <div
                                            key={job.id}
                                            onClick={() => {
                                                setAssignSelectedIds(prev => 
                                                    isSelected ? prev.filter(x => x !== job.id) : [...prev, job.id]
                                                );
                                            }}
                                            className={clsx(
                                                "p-3 rounded-xl border transition-all cursor-pointer flex items-center justify-between gap-3 select-none",
                                                isSelected
                                                    ? "bg-purple-500/10 border-purple-500/50 shadow-sm"
                                                    : "bg-bg-panel hover:bg-bg-hover border-border-main"
                                            )}
                                        >
                                            <div className="flex items-center gap-3 min-w-0">
                                                <input
                                                    type="checkbox"
                                                    checked={isSelected}
                                                    onChange={() => {}}
                                                    className="w-4 h-4 text-purple-600 rounded border-border-main focus:ring-purple-500 pointer-events-none shrink-0"
                                                />
                                                <div className="min-w-0">
                                                    <span className="font-bold text-xs text-text-main truncate block">{job.name}</span>
                                                    <div className="flex items-center gap-2 text-[11px] text-text-muted mt-0.5">
                                                        <span className="font-mono text-blue-400">{job.cronExpression}</span>
                                                        <span>•</span>
                                                        <span>{jobMappings.length || 1} tables</span>
                                                    </div>
                                                </div>
                                            </div>

                                            <div className="shrink-0 flex items-center gap-1.5">
                                                <span className="text-[10px] text-text-muted px-2 py-0.5 rounded-md bg-bg-main border border-border-main font-mono">
                                                    from: <b className="text-text-main font-semibold">{job.groupName || 'General'}</b>
                                                </span>
                                            </div>
                                        </div>
                                    );
                                })
                            )}
                        </div>

                        {/* Footer */}
                        <div className="p-4 border-t border-border-main bg-bg-header flex items-center justify-between gap-3 shrink-0">
                            <button
                                type="button"
                                onClick={() => setIsAssignGroupModalOpen(false)}
                                className="px-4 py-2 rounded-xl text-xs font-bold text-text-muted hover:text-text-main hover:bg-bg-hover transition-colors cursor-pointer"
                            >
                                Cancel
                            </button>

                            <button
                                type="button"
                                disabled={isSavingAssignGroup || assignSelectedIds.length === 0}
                                onClick={handleSaveAssignGroup}
                                className="px-5 py-2 rounded-xl bg-purple-600 hover:bg-purple-500 disabled:opacity-50 text-white text-xs font-bold transition-all shadow-md shadow-purple-600/20 flex items-center gap-2 cursor-pointer"
                            >
                                {isSavingAssignGroup ? (
                                    <>
                                        <Loader2 className="w-3.5 h-3.5 animate-spin" />
                                        <span>Moving Jobs...</span>
                                    </>
                                ) : (
                                    <>
                                        <FolderPlus className="w-3.5 h-3.5" />
                                        <span>Add {assignSelectedIds.length} Jobs to "{assignGroupTarget}"</span>
                                    </>
                                )}
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

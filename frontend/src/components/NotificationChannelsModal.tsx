// @ts-nocheck
import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { XCircle, Trash2, Plus, MessageCircle, Send } from 'lucide-react';
import { useAppStore, type NotificationChannel } from '../store/useAppStore';

interface NotificationChannelsModalProps {
    onClose: () => void;
}

export const NotificationChannelsModal: React.FC<NotificationChannelsModalProps> = ({ onClose }) => {
    const { notificationChannels, setNotificationChannels } = useAppStore();
    const [channels, setChannels] = useState<NotificationChannel[]>(notificationChannels || []);
    const [isCreating, setIsCreating] = useState(false);
    
    const [formData, setFormData] = useState<Partial<NotificationChannel>>({
        name: '',
        type: 'TELEGRAM',
        botToken: '',
        chatId: '',
        webhookUrl: ''
    });

    useEffect(() => {
        fetchChannels();
    }, []);

    const fetchChannels = async () => {
        try {
            const res = await axios.get('/api/notification-channels');
            setChannels(res.data);
            setNotificationChannels(res.data);
        } catch (e) {
            console.error('Failed to fetch channels', e);
        }
    };

    const handleSave = async (e: React.FormEvent) => {
        e.preventDefault();
        try {
            await axios.post('/api/notification-channels', formData);
            setIsCreating(false);
            setFormData({ name: '', type: 'TELEGRAM', botToken: '', chatId: '', webhookUrl: '' });
            fetchChannels();
        } catch (e) {
            alert('Failed to save channel');
        }
    };

    const handleDelete = async (id: string) => {
        if (!confirm('Are you sure you want to delete this profile?')) return;
        try {
            await axios.delete(`/api/notification-channels/${id}`);
            fetchChannels();
        } catch (e) {
            alert('Failed to delete channel');
        }
    };

    return (
        <div className="absolute inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-[60] p-4">
            <div className="bg-bg-panel border border-border-main rounded-xl shadow-2xl w-full max-w-2xl max-h-[80vh] overflow-hidden flex flex-col">
                <div className="p-4 border-b border-border-main flex justify-between items-center shrink-0">
                    <h2 className="font-bold text-lg flex items-center gap-2">
                        <MessageCircle className="w-5 h-5 text-purple-400" />
                        Notification Profiles
                    </h2>
                    <button onClick={onClose} className="p-1 text-text-muted hover:text-text-main hover:bg-bg-hover rounded transition-colors">
                        <XCircle className="w-5 h-5"/>
                    </button>
                </div>

                <div className="flex-1 overflow-auto p-4 flex flex-col gap-4">
                    {!isCreating && (
                        <div className="flex justify-end mb-2">
                            <button 
                                onClick={() => setIsCreating(true)}
                                className="flex items-center gap-1.5 px-3 py-1.5 bg-blue-500 hover:bg-blue-600 text-white text-sm font-semibold rounded shadow-md transition-colors"
                            >
                                <Plus className="w-4 h-4" /> Add Profile
                            </button>
                        </div>
                    )}

                    {isCreating && (
                        <form onSubmit={handleSave} className="bg-bg-subtle border border-border-main rounded-lg p-4 mb-4 flex flex-col gap-3 shadow-inner">
                            <h3 className="font-semibold text-sm border-b border-border-item pb-2 mb-2">New Profile</h3>
                            <div className="grid grid-cols-2 gap-3">
                                <div className="col-span-2">
                                    <label className="block text-xs font-semibold text-text-muted mb-1">Profile Name</label>
                                    <input required type="text" value={formData.name || ''} onChange={e => setFormData({...formData, name: e.target.value})} className="w-full bg-bg-hover border border-border-item rounded px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none" placeholder="e.g. IT Alerts Discord" />
                                </div>
                                <div className="col-span-2">
                                    <label className="block text-xs font-semibold text-text-muted mb-1">Platform</label>
                                    <select value={formData.type} onChange={e => setFormData({...formData, type: e.target.value as any})} className="w-full bg-bg-hover border border-border-item rounded px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none">
                                        <option value="TELEGRAM">Telegram</option>
                                        <option value="DISCORD">Discord</option>
                                    </select>
                                </div>
                                
                                {formData.type === 'TELEGRAM' ? (
                                    <>
                                        <div className="col-span-2">
                                            <label className="block text-xs font-semibold text-text-muted mb-1">Bot Token</label>
                                            <input required type="text" value={formData.botToken || ''} onChange={e => setFormData({...formData, botToken: e.target.value})} className="w-full bg-bg-hover border border-border-item rounded px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none" />
                                        </div>
                                        <div className="col-span-2">
                                            <label className="block text-xs font-semibold text-text-muted mb-1">Chat ID</label>
                                            <input required type="text" value={formData.chatId || ''} onChange={e => setFormData({...formData, chatId: e.target.value})} className="w-full bg-bg-hover border border-border-item rounded px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none" />
                                        </div>
                                    </>
                                ) : (
                                    <div className="col-span-2">
                                        <label className="block text-xs font-semibold text-text-muted mb-1">Webhook URL</label>
                                        <input required type="text" value={formData.webhookUrl || ''} onChange={e => setFormData({...formData, webhookUrl: e.target.value})} className="w-full bg-bg-hover border border-border-item rounded px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none" />
                                    </div>
                                )}
                            </div>
                            <div className="flex justify-end gap-2 mt-4 pt-2 border-t border-border-item">
                                <button type="button" onClick={() => setIsCreating(false)} className="px-3 py-1.5 text-xs font-semibold border border-orange-500/30 bg-orange-500/15 text-orange-500 hover:bg-orange-500/25 hover:text-orange-400 rounded transition-colors">Cancel</button>
                                <button type="submit" className="px-3 py-1.5 bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold rounded shadow transition-colors">Save</button>
                            </div>
                        </form>
                    )}

                    <div className="flex flex-col gap-2">
                        {channels.length === 0 && !isCreating && (
                            <div className="text-center py-8 text-text-muted border border-dashed border-border-item rounded-lg text-sm">
                                No profiles saved. Create one to use in Scheduled Jobs.
                            </div>
                        )}
                        {channels.map(c => (
                            <div key={c.id} className="flex items-center justify-between p-3 border border-border-main bg-bg-subtle rounded-lg hover:border-blue-500/30 transition-colors">
                                <div className="flex flex-col">
                                    <div className="flex items-center gap-2">
                                        {c.type === 'DISCORD' ? <Send className="w-4 h-4 text-indigo-400" /> : <MessageCircle className="w-4 h-4 text-blue-400" />}
                                        <span className="font-semibold text-sm">{c.name}</span>
                                        <span className="text-[10px] uppercase font-bold text-text-muted bg-bg-hover px-1.5 py-0.5 rounded border border-border-item">{c.type}</span>
                                    </div>
                                    <div className="text-xs text-text-muted font-mono mt-1">
                                        {c.type === 'TELEGRAM' ? `Chat ID: ${c.chatId}` : 'Webhook configured'}
                                    </div>
                                </div>
                                <button onClick={() => handleDelete(c.id)} className="p-1.5 text-red-400 hover:bg-red-500/10 hover:text-red-300 rounded transition-colors">
                                    <Trash2 className="w-4 h-4" />
                                </button>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
        </div>
    );
};

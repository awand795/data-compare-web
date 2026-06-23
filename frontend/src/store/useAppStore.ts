// @ts-nocheck
import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import axios from 'axios';
import { detectPrimaryKeysFromQuery } from '../utils/queryHelpers';

export type Connection = {
  id: string;
  name: string;
  type: string;
  host: string;
  port: number | string;
  database: string;
  username: string;
  password?: string;
  schema?: string;
  
  // SSL Settings
  sslMode?: 'disable' | 'require' | 'verify-ca' | 'verify-full';
  sslCaFile?: string;
  sslCertFile?: string;
  sslKeyFile?: string;
  
  // SSH Tunnel Settings
  useSsh?: boolean;
  sshHost?: string;
  sshPort?: number | string;
  sshUsername?: string;
  sshAuthMode?: 'password' | 'key';
  sshPassword?: string;
  sshKeyFile?: string;
  sshPassphrase?: string;
  sshStrictHostKeyChecking?: boolean;
  sshLocalPort?: number | string;
  
  // Advanced Settings
  connectionTimeout?: number;
  socketTimeout?: number;
  fetchSize?: number;
  readOnly?: boolean;
  extraProps?: string;
};

export type DiffCell = {
  sourceValue: any;
  targetValue: any;
  isDifferent: boolean;
};

export type DiffRow = {
  rowKey: string;
  status: 'MATCH' | 'DIFFERENT' | 'SOURCE_ONLY' | 'TARGET_ONLY';
  cells: Record<string, DiffCell>;
};

export type DiffResult = {
  columns: string[];
  rows: DiffRow[];
  totalSourceRows: number;
  totalTargetRows: number;
  totalDifferences: number;
  matchCount: number;
  differentCount: number;
  sourceOnlyCount: number;
  targetOnlyCount: number;
  status?: 'comparing' | 'done' | 'error';
};

export type ColumnDiff = {
  columnName: string;
  status: string;
  sourceType: string | null;
  targetType: string | null;
  sourceNullable: string | null;
  targetNullable: string | null;
  sourceSize: number | null;
  targetSize: number | null;
  isPrimaryKeySource: boolean;
  isPrimaryKeyTarget: boolean;
};

export type SchemaCompareResult = {
  tableName: string;
  status: string;
  columnDiffs: ColumnDiff[];
};

// Table mapping: source table -> target table (allows cross-table mapping)
export type TableMapping = {
  id: string;
  sourceTable: string;
  targetTable: string;
  customQuerySource?: string;
  customQueryTarget?: string;
  primaryKeys?: string[];
  // Date filter
  dateColumn?: string;
  startDate?: string;
  endDate?: string;
  // WHERE clause filter (extra custom filter)
  extraWhereSource?: string;
  extraWhereTarget?: string;
  // Column exclude list
  excludeColumns?: string[];
  // Row limit
  rowLimit?: number;
  // Label / alias
  label?: string;
  // Flag: user has manually written the SQL (not auto-generated)
  isManualQuerySource?: boolean;
  isManualQueryTarget?: boolean;
  sortColumns?: string[];
};

export type Template = {
  id: string;
  name: string;
  appMode: 'data' | 'query';
  sourceConnectionId: string | null;
  targetConnectionId: string | null;
  tableMappings?: TableMapping[];
  customQuerySource?: string;
  customQueryTarget?: string;
  queryPrimaryKeys?: string;
};

export type ScheduleConfig = {
  id: string;
  name: string;
  sourceConnectionId: string;
  targetConnectionId: string;
  sourceTable: string;
  targetTable: string;
  customQuerySource?: string;
  customQueryTarget?: string;
  primaryKeys?: string;
  excludeColumns?: string;
  sortColumns?: string;
  cronExpression: string;
  telegramBotToken?: string;
  telegramChatId?: string;
  discordWebhookUrl?: string;
  telegramChannelId?: string;
  discordChannelId?: string;
  saveFullData: boolean;
  isActive: boolean;
  mappings?: any[]; // For grouped jobs
  createdAt?: string;
  lastRun?: string;
  };

export type NotificationChannel = {
  id: string;
  name: string;
  type: 'TELEGRAM' | 'DISCORD';
  botToken?: string;
  chatId?: string;
  webhookUrl?: string;
  createdAt?: string;
};

export type AppMode = 'data' | 'schema' | 'query' | 'explorer' | 'excel' | 'schedule';

export type AlertType = 'error' | 'success' | 'warning' | 'info';

export type Toast = {
  id: string;
  type: AlertType;
  title?: string;
  message?: string;
  duration?: number;
};

export type AlertModalState = {
  isOpen: boolean;
  title: string;
  message: string;
  type: AlertType;
  details?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  onConfirm?: () => void;
  onCancel?: () => void;
};

type AppState = {
  connections: Connection[];
  setConnections: (conns: Connection[]) => void;
  addConnection: (conn: Connection) => void;
  updateConnection: (id: string, updates: Partial<Connection>) => void;
  removeConnection: (id: string) => void;

  sourceConnectionId: string | null;
  setSourceConnectionId: (id: string | null) => void;

  targetConnectionId: string | null;
  setTargetConnectionId: (id: string | null) => void;

  // App mode
  appMode: AppMode;
  setAppMode: (mode: AppMode) => void;

  // Explorer
  explorerConnectionId: string | null;
  setExplorerConnectionId: (id: string | null) => void;
  explorerSchemaName: string | null;
  setExplorerSchemaName: (schema: string | null) => void;
  explorerTableName: string | null;
  setExplorerTableName: (name: string | null) => void;

  // Max rows to keep per mapping (to prevent browser memory issues)
  maxRowsInMemory: number;
  setMaxRowsInMemory: (limit: number) => void;

  // Data compare
  defaultFetchSize: number;
  setDefaultFetchSize: (size: number) => void;
  diffResults: Record<string, DiffResult>;
  setDiffResult: (mappingId: string, result: DiffResult) => void;
  initDiffResult: (mappingId: string) => void;
  setDiffColumns: (mappingId: string, columns: string[]) => void;
  appendDiffRows: (mappingId: string, rows: DiffRow[]) => void;
  setDiffSummary: (mappingId: string, summary: Partial<DiffResult>) => void;
  setBatchProgress: (mappingId: string, current: number, total: number) => void;
  clearDiffResults: () => void;

  // Schema compare
  schemaResults: SchemaCompareResult[];
  setSchemaResults: (results: SchemaCompareResult[]) => void;

  // Table mappings (cross-table mapping feature)
  tableMappings: TableMapping[];
  addTableMapping: (mapping: TableMapping) => void;
  removeTableMapping: (id: string) => void;
  updateTableMapping: (id: string, updates: Partial<TableMapping>) => void;
  clearTableMappings: () => void;

  // Excel mappings
  excelMappings: TableMapping[];
  addExcelMapping: (mapping: TableMapping) => void;
  removeExcelMapping: (id: string) => void;
  updateExcelMapping: (id: string, updates: Partial<TableMapping>) => void;
  clearExcelMappings: () => void;

  // Persistent workspace states
  selectedMappingIds: string[];
  setSelectedMappingIds: (ids: string[]) => void;
  focusedMappingId: string | null;
  setFocusedMappingId: (id: string | null) => void;

  // Custom query workspace — always mirrors the focused mapping's queries
  customQuerySource: string;
  setCustomQuerySource: (q: string) => void;
  customQueryTarget: string;
  setCustomQueryTarget: (q: string) => void;

  queryPrimaryKeys: string;
  setQueryPrimaryKeys: (keys: string) => void;

  queryResult: DiffResult | null;
  setQueryResult: (r: DiffResult | null) => void;

  // Toasts
  toasts: Toast[];
  addToast: (toast: Omit<Toast, 'id'>) => void;
  removeToast: (id: string) => void;

  // Alert Modal
  alert: AlertModalState | null;
  showAlert: (config: Omit<AlertModalState, 'isOpen'>) => void;
  hideAlert: () => void;

  // Notification Channels

  notificationChannels: NotificationChannel[];
  setNotificationChannels: (channels: NotificationChannel[]) => void;
  addNotificationChannel: (channel: NotificationChannel) => void;
  updateNotificationChannel: (id: string, updates: Partial<NotificationChannel>) => void;
  removeNotificationChannel: (id: string) => void;

  schedules: ScheduleConfig[];
  setSchedules: (schedules: ScheduleConfig[]) => void;
  addSchedule: (schedule: ScheduleConfig) => void;
  updateSchedule: (id: string, updates: Partial<ScheduleConfig>) => void;
  updateScheduleStatus: (id: string, isActive: boolean) => void;
  runScheduleNow: (id: string) => void;

  // Templates
  templates: Template[];
  setTemplates: (templates: Template[]) => void;
  addTemplate: (template: Template) => void;
  updateTemplate: (id: string, updates: Partial<Template>) => void;
  removeTemplate: (id: string) => void;
  activeTemplateId: string | null;
  setActiveTemplateId: (id: string | null) => void;

  // Theme
  theme: 'dark' | 'light';
  setTheme: (theme: 'dark' | 'light') => void;
  fontSize: 'small' | 'medium' | 'large';
  setFontSize: (size: 'small' | 'medium' | 'large') => void;
  gridDensity: 'compact' | 'comfortable';
  setGridDensity: (density: 'compact' | 'comfortable') => void;
  defaultRowLimit: number;
  setDefaultRowLimit: (limit: number) => void;
};

const MAX_ROWS_IN_MEMORY = 100000;

export const useAppStore = create<AppState>()(
  persist(
    (set, get) => ({
      connections: [],
  setConnections: (conns) => set({ connections: conns }),
  addConnection: (conn) => set((state) => ({ connections: [...state.connections, conn] })),
  updateConnection: (id, updates) => set((state) => {
    const newConnections = state.connections.map(c => c.id === id ? { ...c, ...updates } : c);
    const updated = newConnections.find(c => c.id === id);
    if (updated) {
      axios.put(`/api/connections/${id}`, updated).catch(err => console.error('Failed to update connection:', err));
    }
    return { connections: newConnections };
  }),
  removeConnection: (id) => {
    axios.delete(`/api/connections/${id}`).catch(err => {
      console.error('Failed to delete connection on backend:', err);
      get().addToast?.({ type: 'error', title: 'Delete Failed', message: 'Could not delete connection from server.' });
      axios.get('/api/connections').then(res => set({ connections: res.data }));
    });
    set((state) => ({ connections: state.connections.filter(c => c.id !== id) }));
  },

  sourceConnectionId: null,
  setSourceConnectionId: (id) => set({ 
    sourceConnectionId: id,
    tableMappings: [],
    diffResults: {},
    selectedMappingIds: [],
    focusedMappingId: null
  }),

  targetConnectionId: null,
  setTargetConnectionId: (id) => set({ 
    targetConnectionId: id,
    tableMappings: [],
    diffResults: {},
    selectedMappingIds: [],
    focusedMappingId: null
  }),

  appMode: 'data',
  setAppMode: (mode) => set({ appMode: mode }),

  explorerConnectionId: null,
  setExplorerConnectionId: (id) => set({ explorerConnectionId: id }),
  explorerSchemaName: null,
  setExplorerSchemaName: (schema) => set({ explorerSchemaName: schema }),
  explorerTableName: null,
  setExplorerTableName: (name) => set({ explorerTableName: name }),

  diffResults: {},
  setDiffResult: (mappingId, result) => set((state) => ({
    diffResults: { ...state.diffResults, [mappingId]: result }
  })),
  initDiffResult: (mappingId) => set((state) => ({
    diffResults: { 
      ...state.diffResults, 
      [mappingId]: { columns: [], rows: [], totalSourceRows: 0, totalTargetRows: 0, totalDifferences: 0, matchCount: 0, differentCount: 0, sourceOnlyCount: 0, targetOnlyCount: 0, status: 'comparing' } 
    }
  })),
  setDiffColumns: (mappingId, columns) => set((state) => {
    const existing = state.diffResults[mappingId];
    if (!existing) return state;
    return {
      diffResults: { ...state.diffResults, [mappingId]: { ...existing, columns } }
    };
  }),
  appendDiffRows: (mappingId, newRows) => set((state) => {
    const existing = state.diffResults[mappingId];
    if (!existing) return state;

    const combinedRows = [...existing.rows, ...newRows];
    let { matchCount, differentCount, sourceOnlyCount, targetOnlyCount } = existing;

    for (const r of newRows) {
      if (r.status === 'MATCH') matchCount++;
      else if (r.status === 'DIFFERENT') differentCount++;
      else if (r.status === 'SOURCE_ONLY') sourceOnlyCount++;
      else if (r.status === 'TARGET_ONLY') targetOnlyCount++;
    }

    return {
      diffResults: {
        ...state.diffResults,
        [mappingId]: {
          ...existing,
          rows: combinedRows,
          matchCount, differentCount, sourceOnlyCount, targetOnlyCount,
        }
      }
    };
  }),
  setDiffSummary: (mappingId, summary) => set((state) => {
    const existing = state.diffResults[mappingId];
    if (!existing) return state;
    return {
      diffResults: { ...state.diffResults, [mappingId]: { ...existing, ...summary, rows: existing.rows, status: 'done' } }
    };
  }),
  setBatchProgress: (mappingId, current, total) => set((state) => {
    const existing = state.diffResults[mappingId];
    if (!existing) return state;
    return {
      diffResults: { ...state.diffResults, [mappingId]: { ...existing, batchCurrent: current, batchTotal: total } }
    };
  }),
  clearDiffResults: () => set({ diffResults: {} }),

  schemaResults: [],
  setSchemaResults: (results) => set({ schemaResults: results }),

  tableMappings: [],
  addTableMapping: (mapping) => set((state) => ({
    tableMappings: [...state.tableMappings, mapping]
  })),
  removeTableMapping: (id) => set((state) => ({
    tableMappings: state.tableMappings.filter(m => m.id !== id),
    focusedMappingId: state.focusedMappingId === id ? null : state.focusedMappingId,
    selectedMappingIds: state.selectedMappingIds.filter(sid => sid !== id),
  })),
  updateTableMapping: (id, updates) => set((state) => {
    const newMappings = state.tableMappings.map(m => m.id === id ? { ...m, ...updates } : m);
    // If the focused mapping is updated, mirror its queries to workspace state
    if (state.focusedMappingId === id) {
      const updated = newMappings.find(m => m.id === id);
      if (updated) {
        return {
          tableMappings: newMappings,
          customQuerySource: updated.customQuerySource ?? state.customQuerySource,
          customQueryTarget: updated.customQueryTarget ?? state.customQueryTarget,
        };
      }
    }
    return { tableMappings: newMappings };
  }),
  clearTableMappings: () => set({ tableMappings: [], focusedMappingId: null, selectedMappingIds: [] }),

  excelMappings: [],
  addExcelMapping: (mapping) => set((state) => ({
    excelMappings: [...state.excelMappings, mapping]
  })),
  removeExcelMapping: (id) => set((state) => ({
    excelMappings: state.excelMappings.filter(m => m.id !== id),
    focusedMappingId: state.focusedMappingId === id ? null : state.focusedMappingId,
    selectedMappingIds: state.selectedMappingIds.filter(sid => sid !== id),
  })),
  updateExcelMapping: (id, updates) => set((state) => {
    const newMappings = state.excelMappings.map(m => m.id === id ? { ...m, ...updates } : m);
    if (state.focusedMappingId === id) {
      const updated = newMappings.find(m => m.id === id);
      if (updated) {
        return { 
          excelMappings: newMappings,
          customQuerySource: updated.customQuerySource ?? state.customQuerySource,
          customQueryTarget: updated.customQueryTarget ?? state.customQueryTarget,
        };
      }
    }
    return { excelMappings: newMappings };
  }),
  clearExcelMappings: () => set({ excelMappings: [] }),

  selectedMappingIds: [],
  setSelectedMappingIds: (ids) => set({ selectedMappingIds: ids }),
  focusedMappingId: null,
  // When focus changes, mirror the mapping's stored queries to workspace
  setFocusedMappingId: (id) => set((state) => {
    if (!id) return { focusedMappingId: null };
    const m = state.tableMappings.find(x => x.id === id) || state.excelMappings.find(x => x.id === id);
    if (!m) return { focusedMappingId: id };
    const sc = state.connections.find(c => c.id === state.sourceConnectionId);
    const tc = state.connections.find(c => c.id === state.targetConnectionId);
    const getTable = (c: any, t?: string) => t && c?.schema && (c.type === 'postgresql' || c.type === 'sqlserver') ? `${c.schema}.${t}` : (t || '');
    const sTable = getTable(sc, m.sourceTable);
    const tTable = getTable(tc, m.targetTable);
    const sq = m.customQuerySource ?? (sTable ? `SELECT * FROM ${sTable}` : '');
    const tq = m.customQueryTarget ?? (tTable ? `SELECT * FROM ${tTable}` : '');
    const pks = m.primaryKeys ? m.primaryKeys.join(', ') : '';
    return {
      focusedMappingId: id,
      customQuerySource: sq,
      customQueryTarget: tq,
      queryPrimaryKeys: pks,
    };
  }),

  queryPrimaryKeys: '',
  setQueryPrimaryKeys: (keys) => set((state) => {
    const updates: any = { queryPrimaryKeys: keys };
    if (state.focusedMappingId) {
      const pks = keys.split(',').map(s => s.trim()).filter(Boolean);
      if (state.tableMappings.some(m => m.id === state.focusedMappingId)) {
        updates.tableMappings = state.tableMappings.map(m =>
          m.id === state.focusedMappingId ? { ...m, primaryKeys: pks } : m
        );
      } else {
        updates.excelMappings = state.excelMappings.map(m =>
          m.id === state.focusedMappingId ? { ...m, primaryKeys: pks } : m
        );
      }
    }
    return updates;
  }),

  customQuerySource: '',
  setCustomQuerySource: (q) => set((state) => {
    const updates: any = { customQuerySource: q };
    if (state.focusedMappingId) {
      if (state.tableMappings.some(m => m.id === state.focusedMappingId)) {
        updates.tableMappings = state.tableMappings.map(m =>
          m.id === state.focusedMappingId ? { ...m, customQuerySource: q, isManualQuerySource: true } : m
        );
      } else {
        updates.excelMappings = state.excelMappings.map(m =>
          m.id === state.focusedMappingId ? { ...m, customQuerySource: q, isManualQuerySource: true } : m
        );
      }
    }
    return updates;
  }),
  customQueryTarget: '',
  setCustomQueryTarget: (q) => set((state) => {
    const updates: any = { customQueryTarget: q };
    if (state.focusedMappingId) {
      if (state.tableMappings.some(m => m.id === state.focusedMappingId)) {
        updates.tableMappings = state.tableMappings.map(m =>
          m.id === state.focusedMappingId ? { ...m, customQueryTarget: q, isManualQueryTarget: true } : m
        );
      } else {
        updates.excelMappings = state.excelMappings.map(m =>
          m.id === state.focusedMappingId ? { ...m, customQueryTarget: q, isManualQueryTarget: true } : m
        );
      }
    }
    return updates;
  }),
  queryResult: null,
  setQueryResult: (r) => set({ queryResult: r }),


  notificationChannels: [],
  setNotificationChannels: (channels) => set({ notificationChannels: channels }),
  addNotificationChannel: (channel) => set((state) => ({ notificationChannels: [...state.notificationChannels, channel] })),
  updateNotificationChannel: (id, updates) => set((state) => ({
    notificationChannels: state.notificationChannels.map(c => c.id === id ? { ...c, ...updates } : c)
  })),
  removeNotificationChannel: (id) => set((state) => ({
    notificationChannels: state.notificationChannels.filter(c => c.id !== id)
  })),

  schedules: [],
  setSchedules: (schedules) => set({ schedules }),
  addSchedule: (schedule) => set((state) => ({ schedules: [...state.schedules, schedule] })),
  updateSchedule: (id, updates) => set((state) => {
    const newSchedules = state.schedules.map(s => s.id === id ? { ...s, ...updates } : s);
    const updated = newSchedules.find(s => s.id === id);
    if (updated) {
      axios.put(`/api/schedules/${id}`, updated).catch(err => console.error('Failed to update schedule:', err));
    }
    return { schedules: newSchedules };
  }),
  updateScheduleStatus: async (id, isActive) => {
    console.log("[Toggle] Updating ID:", id, "to:", isActive);
    
    // 1. Get current list
    const currentSchedules = get().schedules;
    const scheduleToUpdate = currentSchedules.find(s => s.id === id);
    if (!scheduleToUpdate) {
        console.error("[Toggle] Schedule not found in store");
        return;
    }

    // 2. Optimistic update
    const updatedSchedules = currentSchedules.map(s => 
        s.id === id ? { ...s, isActive: !!isActive } : s
    );
    set({ schedules: updatedSchedules });

    try {
      // 3. Prepare payload EXPLICITLY
      const payload = { 
        ...scheduleToUpdate,
        isActive: !!isActive 
      };
      
      console.log("[Toggle] Sending PUT to backend...", payload);
      const response = await axios.put(`/api/schedules/${id}`, payload);
      console.log("[Toggle] Backend responded:", response.data);
      
      // 4. Update store with backend response to be sure
      set((state) => ({
        schedules: state.schedules.map(s => s.id === id ? response.data : s)
      }));
    } catch (err) {
      console.error("[Toggle] Failed!", err);
      // Revert
      const res = await axios.get('/api/schedules');
      set({ schedules: res.data || [] });
    }
  },
  runScheduleNow: async (id) => {
    try {
      await axios.post(`/api/schedules/${id}/trigger`);
      get().addToast({ type: 'success', title: 'Success', message: 'Job triggered successfully!' });
    } catch (err) {
      console.error("Failed to trigger job", err);
      get().addToast({ type: 'error', title: 'Error', message: 'Failed to trigger job' });
    }
  },

  templates: [],
  setTemplates: (templates) => set({ templates }),
  addTemplate: (template) => {
    const payload = { ...template, tableMappings: template.tableMappings ? JSON.stringify(template.tableMappings) : null };
    axios.post('/api/templates', payload).catch(e => console.error('Failed to save template to backend:', e));
    set((state) => ({ templates: [...state.templates, template] }));
  },
  updateTemplate: (id, updates) => {
    set((state) => {
      const newTemplates = state.templates.map(t => t.id === id ? { ...t, ...updates } : t);
      const updated = newTemplates.find(t => t.id === id);
      if (updated) {
        const payload = { ...updated, tableMappings: updated.tableMappings ? JSON.stringify(updated.tableMappings) : null };
        axios.put(`/api/templates/${id}`, payload).catch(e => console.error('Failed to update template on backend:', e));
      }
      return { templates: newTemplates };
    });
  },
  removeTemplate: (id) => {
    axios.delete(`/api/templates/${id}`).catch(e => console.error('Failed to delete template from backend:', e));
    set((state) => ({
      templates: state.templates.filter(t => t.id !== id),
      activeTemplateId: state.activeTemplateId === id ? null : state.activeTemplateId
    }));
  },
  activeTemplateId: null,
  setActiveTemplateId: (id) => set({ activeTemplateId: id }),

  theme: 'light',
  setTheme: (theme) => set({ theme }),

  fontSize: 'medium',
  setFontSize: (fontSize) => set({ fontSize }),

  gridDensity: 'comfortable',
  setGridDensity: (gridDensity) => set({ gridDensity }),

  maxRowsInMemory: MAX_ROWS_IN_MEMORY,
  setMaxRowsInMemory: (limit) => set({ maxRowsInMemory: limit }),

  defaultRowLimit: 100,
  setDefaultRowLimit: (limit) => set({ defaultRowLimit: limit }),

  toasts: [],
  addToast: (toast) => set((state) => ({
    toasts: [...state.toasts, { ...toast, id: Date.now().toString() + Math.random().toString(36).slice(2, 9) }]
  })),
  removeToast: (id) => set((state) => ({
    toasts: state.toasts.filter(t => t.id !== id)
  })),

  alert: null,
  showAlert: (config) => set({ alert: { ...config, isOpen: true } }),
  hideAlert: () => set({ alert: null }),
    }),
    {
      name: 'dbdiff-storage',
      partialize: (state) => ({
        connections: state.connections,
        theme: state.theme,
        fontSize: state.fontSize,
        gridDensity: state.gridDensity,
        notificationChannels: state.notificationChannels,
        defaultRowLimit: state.defaultRowLimit,
      }),
    }
  )
);

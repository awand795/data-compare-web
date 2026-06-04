import { create } from 'zustand';
import { persist } from 'zustand/middleware';

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
};

export type AppMode = 'data' | 'schema' | 'query' | 'explorer';

type AppState = {
  connections: Connection[];
  setConnections: (conns: Connection[]) => void;
  addConnection: (conn: Connection) => void;
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

  // Data compare
  diffResults: Record<string, DiffResult>;
  setDiffResult: (mappingId: string, result: DiffResult) => void;
  initDiffResult: (mappingId: string) => void;
  setDiffColumns: (mappingId: string, columns: string[]) => void;
  appendDiffRows: (mappingId: string, rows: DiffRow[]) => void;
  setDiffSummary: (mappingId: string, summary: Partial<DiffResult>) => void;
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
  queryResult: DiffResult | null;
  setQueryResult: (r: DiffResult | null) => void;

  // Theme
  theme: 'dark' | 'light';
  setTheme: (theme: 'dark' | 'light') => void;
  defaultRowLimit: number;
  setDefaultRowLimit: (limit: number) => void;
};

export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
      connections: [],
  setConnections: (conns) => set({ connections: conns }),
  addConnection: (conn) => set((state) => ({ connections: [...state.connections, conn] })),
  removeConnection: (id) => set((state) => ({ connections: state.connections.filter(c => c.id !== id) })),

  sourceConnectionId: null,
  setSourceConnectionId: (id) => set({ sourceConnectionId: id }),

  targetConnectionId: null,
  setTargetConnectionId: (id) => set({ targetConnectionId: id }),

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
      [mappingId]: { columns: [], rows: [], totalSourceRows: 0, totalTargetRows: 0, totalDifferences: 0, status: 'comparing' } 
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
    // Mutate the array directly to avoid O(N^2) memory copies during streaming
    existing.rows.push(...newRows);
    return {
      diffResults: { ...state.diffResults, [mappingId]: { ...existing } }
    };
  }),
  setDiffSummary: (mappingId, summary) => set((state) => {
    const existing = state.diffResults[mappingId];
    if (!existing) return state;
    return {
      diffResults: { ...state.diffResults, [mappingId]: { ...existing, ...summary, status: 'done' } }
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

  selectedMappingIds: [],
  setSelectedMappingIds: (ids) => set({ selectedMappingIds: ids }),
  focusedMappingId: null,
  // When focus changes, mirror the mapping's stored queries to workspace
  setFocusedMappingId: (id) => set((state) => {
    if (!id) return { focusedMappingId: null };
    const m = state.tableMappings.find(x => x.id === id);
    if (!m) return { focusedMappingId: id };
    const sc = state.connections.find(c => c.id === state.sourceConnectionId);
    const tc = state.connections.find(c => c.id === state.targetConnectionId);
    const getTable = (c: any, t?: string) => t && c?.schema && (c.type === 'postgresql' || c.type === 'sqlserver') ? `${c.schema}.${t}` : (t || '');
    const sTable = getTable(sc, m.sourceTable);
    const tTable = getTable(tc, m.targetTable);
    const sq = m.customQuerySource ?? (sTable ? `SELECT * FROM ${sTable}` : '');
    const tq = m.customQueryTarget ?? (tTable ? `SELECT * FROM ${tTable}` : '');
    return {
      focusedMappingId: id,
      customQuerySource: sq,
      customQueryTarget: tq,
    };
  }),

  customQuerySource: '',
  setCustomQuerySource: (q) => set((state) => {
    const updates: any = { customQuerySource: q };
    if (state.focusedMappingId) {
      updates.tableMappings = state.tableMappings.map(m =>
        m.id === state.focusedMappingId ? { ...m, customQuerySource: q, isManualQuerySource: true } : m
      );
    }
    return updates;
  }),
  customQueryTarget: '',
  setCustomQueryTarget: (q) => set((state) => {
    const updates: any = { customQueryTarget: q };
    if (state.focusedMappingId) {
      updates.tableMappings = state.tableMappings.map(m =>
        m.id === state.focusedMappingId ? { ...m, customQueryTarget: q, isManualQueryTarget: true } : m
      );
    }
    return updates;
  }),
  queryResult: null,
  setQueryResult: (r) => set({ queryResult: r }),

  theme: 'light',
  setTheme: (theme) => set({ theme }),

  defaultRowLimit: 100,
  setDefaultRowLimit: (limit) => set({ defaultRowLimit: limit }),
    }),
    {
      name: 'dbdiff-storage',
      partialize: (state) => ({
        connections: state.connections,
        theme: state.theme,
        defaultRowLimit: state.defaultRowLimit,
        tableMappings: state.tableMappings,
      }),
    }
  )
);

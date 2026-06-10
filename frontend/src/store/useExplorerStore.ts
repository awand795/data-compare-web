import { create } from 'zustand';

export type ExplorerNodeType = 'server' | 'database' | 'schema' | 'tables' | 'views' | 'functions' | 'sequences' | 'table' | 'view' | 'function' | 'sequence' | 'columns' | 'indexes' | 'fks' | 'column' | 'index' | 'fk';

export type ExplorerNode = {
  id: string;
  parentId: string | null;
  type: ExplorerNodeType;
  name: string;
  label?: string; // override name for display (e.g. 'Tables (10)')
  childrenIds?: string[];
  isLoaded: boolean;
  isLoading: boolean;
  isExpanded: boolean;
  error?: string;
  metadata?: any;
};

type ExplorerState = {
  nodes: Record<string, ExplorerNode>;
  selectedNodeId: string | null;
  
  // Actions
  setNodes: (nodes: Record<string, ExplorerNode>) => void;
  upsertNode: (node: ExplorerNode) => void;
  patchNode: (id: string, updates: Partial<ExplorerNode>) => void;
  removeNode: (id: string) => void;
  toggleExpand: (id: string, expand?: boolean) => void;
  setLoading: (id: string, isLoading: boolean, error?: string) => void;
  setLoaded: (id: string, childrenIds: string[]) => void;
  setSelectedNodeId: (id: string | null) => void;
};

export const useExplorerStore = create<ExplorerState>((set) => ({
  nodes: {},
  selectedNodeId: null,

  setNodes: (nodes) => set({ nodes }),
  
  upsertNode: (node) => set((state) => ({
    nodes: { ...state.nodes, [node.id]: node }
  })),

  patchNode: (id, updates) => set((state) => {
    const node = state.nodes[id];
    if (!node) return state;
    return {
      nodes: {
        ...state.nodes,
        [id]: { ...node, ...updates }
      }
    };
  }),
  
  removeNode: (id) => set((state) => {
    const newNodes = { ...state.nodes };
    delete newNodes[id];
    return { nodes: newNodes };
  }),
  
  toggleExpand: (id, expand) => set((state) => {
    const node = state.nodes[id];
    if (!node) return state;
    return {
      nodes: {
        ...state.nodes,
        [id]: { ...node, isExpanded: expand !== undefined ? expand : !node.isExpanded }
      }
    };
  }),

  setLoading: (id, isLoading, error) => set((state) => {
    const node = state.nodes[id];
    if (!node) return state;
    return {
      nodes: {
        ...state.nodes,
        [id]: { ...node, isLoading, error: error || undefined }
      }
    };
  }),

  setLoaded: (id, childrenIds) => set((state) => {
    const node = state.nodes[id];
    if (!node) return state;
    return {
      nodes: {
        ...state.nodes,
        [id]: { ...node, isLoaded: true, isLoading: false, childrenIds, error: undefined }
      }
    };
  }),

  setSelectedNodeId: (id) => set({ selectedNodeId: id })
}));

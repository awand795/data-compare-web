import React, { useState } from 'react';
import { useAppStore, type Connection } from '../store/useAppStore';
import { X, Server, CheckCircle, XCircle, Shield, Key, Settings as SettingsIcon, Plug } from 'lucide-react';
import axios from 'axios';

type TabType = 'general' | 'ssl' | 'ssh' | 'advanced';

interface Props {
  isOpen: boolean;
  onClose: () => void;
}

export const ConnectionDialog: React.FC<Props> = ({ isOpen, onClose }) => {
  const { addConnection } = useAppStore();
  const [activeTab, setActiveTab] = useState<TabType>('general');
  const [testStatus, setTestStatus] = useState<'idle' | 'testing' | 'success' | 'error'>('idle');
  const [testDetails, setTestDetails] = useState<string | null>(null);

  const [formData, setFormData] = useState<Partial<Connection>>({
    type: 'postgresql',
    port: 5432,
    sslMode: 'disable',
    useSsh: false,
    sshAuthMode: 'password',
    sshPort: 22,
    connectionTimeout: 30,
    socketTimeout: 0,
    fetchSize: 1000,
    readOnly: false
  });

  if (!isOpen) return null;

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => {
    const value = e.target.type === 'checkbox' ? (e.target as HTMLInputElement).checked : e.target.value;
    setFormData(prev => ({ ...prev, [e.target.name]: value }));
  };

  const handleTest = async () => {
    setTestStatus('testing');
    setTestDetails(null);
    try {
      // Endpoint to be implemented by backend subagent
      const res = await axios.post('http://localhost:8081/api/test-connection', formData);
      setTestStatus(res.data.success ? 'success' : 'error');
      setTestDetails(res.data.message || 'Connection successful');
    } catch (err: any) {
      setTestStatus('error');
      setTestDetails(err.response?.data?.message || err.message || 'Connection failed');
    }
  };

  const handleSave = async () => {
    if (!formData.name || !formData.host || !formData.database) {
      alert("Name, Host, and Database are required.");
      return;
    }
    
    // Encrypt passwords locally before saving (simple base64 for now, can be upgraded to AES)
    const secureData = { ...formData };
    if (secureData.password) secureData.password = btoa(secureData.password);
    if (secureData.sshPassword) secureData.sshPassword = btoa(secureData.sshPassword);
    if (secureData.sshPassphrase) secureData.sshPassphrase = btoa(secureData.sshPassphrase);

    const newConn = { ...secureData, id: Date.now().toString() } as Connection;
    try {
      await axios.post('http://localhost:8081/api/connections', newConn);
      addConnection(newConn);
      onClose();
    } catch (err) {
      console.error('Failed to save connection:', err);
      alert('Failed to save connection');
    }
  };

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="bg-bg-panel w-[600px] border border-border-main shadow-2xl rounded-lg flex flex-col overflow-hidden animate-in fade-in zoom-in duration-200">
        
        {/* Header */}
        <div className="px-4 py-3 border-b border-border-main flex items-center justify-between bg-bg-header">
          <div className="flex items-center gap-2 text-text-main font-semibold">
            <Plug className="w-4 h-4 text-blue-500" />
            <span>Create New Connection</span>
          </div>
          <button onClick={onClose} className="p-1 text-text-muted hover:text-red-500 rounded transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Tabs */}
        <div className="flex px-2 border-b border-border-main bg-bg-header pt-2">
          {[
            { id: 'general', label: 'General', icon: Server },
            { id: 'ssl', label: 'SSL', icon: Shield },
            { id: 'ssh', label: 'SSH Tunnel', icon: Key },
            { id: 'advanced', label: 'Advanced', icon: SettingsIcon },
          ].map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id as TabType)}
              className={`flex items-center gap-1.5 px-4 py-2 border-b-2 text-xs font-medium transition-colors ${
                activeTab === tab.id 
                  ? 'border-blue-500 text-blue-500 bg-blue-500/5' 
                  : 'border-transparent text-text-muted hover:text-text-main hover:bg-bg-hover'
              }`}
            >
              <tab.icon className="w-3.5 h-3.5" />
              {tab.label}
            </button>
          ))}
        </div>

        {/* Content */}
        <div className="p-4 h-[350px] overflow-y-auto">
          {activeTab === 'general' && (
            <div className="flex flex-col gap-3">
              <div className="grid grid-cols-2 gap-4">
                <div className="flex flex-col gap-1">
                  <label className="text-xs text-text-muted">Connection Name</label>
                  <input name="name" value={formData.name || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" placeholder="e.g. Production DB" />
                </div>
                <div className="flex flex-col gap-1">
                  <label className="text-xs text-text-muted">Database Type</label>
                  <select name="type" value={formData.type || 'postgresql'} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500">
                    <option value="postgresql">PostgreSQL</option>
                    <option value="mysql">MySQL</option>
                    <option value="mariadb">MariaDB</option>
                    <option value="sqlserver">SQL Server</option>
                  </select>
                </div>
              </div>

              <div className="grid grid-cols-4 gap-4">
                <div className="col-span-3 flex flex-col gap-1">
                  <label className="text-xs text-text-muted">Host</label>
                  <input name="host" value={formData.host || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" placeholder="localhost" />
                </div>
                <div className="flex flex-col gap-1">
                  <label className="text-xs text-text-muted">Port</label>
                  <input name="port" type="number" value={formData.port || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div className="flex flex-col gap-1">
                  <label className="text-xs text-text-muted">Database</label>
                  <input name="database" value={formData.database || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" />
                </div>
                <div className="flex flex-col gap-1">
                  <label className="text-xs text-text-muted">Schema (Optional)</label>
                  <input name="schema" value={formData.schema || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" placeholder="e.g. public" />
                </div>
              </div>

              <div className="grid grid-cols-2 gap-4">
                <div className="flex flex-col gap-1">
                  <label className="text-xs text-text-muted">Username</label>
                  <input name="username" value={formData.username || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" />
                </div>
                <div className="flex flex-col gap-1">
                  <label className="text-xs text-text-muted">Password</label>
                  <input name="password" type="password" value={formData.password || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" />
                </div>
              </div>
            </div>
          )}

          {activeTab === 'ssl' && (
            <div className="flex flex-col gap-4">
              <div className="flex flex-col gap-1">
                <label className="text-xs text-text-muted">SSL Mode</label>
                <select name="sslMode" value={formData.sslMode || 'disable'} onChange={handleChange} className="w-full max-w-[200px] px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500">
                  <option value="disable">Disable</option>
                  <option value="require">Require</option>
                  <option value="verify-ca">Verify-CA</option>
                  <option value="verify-full">Verify-Full</option>
                </select>
              </div>

              {formData.sslMode !== 'disable' && (
                <div className="flex flex-col gap-3">
                  <div className="flex flex-col gap-1">
                    <label className="text-xs text-text-muted">CA Certificate Path (root.crt)</label>
                    <input name="sslCaFile" value={formData.sslCaFile || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" placeholder="/path/to/server-ca.pem" />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-xs text-text-muted">Client Certificate Path (client.crt)</label>
                    <input name="sslCertFile" value={formData.sslCertFile || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" placeholder="/path/to/client-cert.pem" />
                  </div>
                  <div className="flex flex-col gap-1">
                    <label className="text-xs text-text-muted">Client Key Path (client.key)</label>
                    <input name="sslKeyFile" value={formData.sslKeyFile || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" placeholder="/path/to/client-key.pem" />
                  </div>
                </div>
              )}
            </div>
          )}

          {activeTab === 'ssh' && (
            <div className="flex flex-col gap-4">
              <label className="flex items-center gap-2 cursor-pointer">
                <input type="checkbox" name="useSsh" checked={formData.useSsh || false} onChange={handleChange} className="w-4 h-4 rounded border-border-input bg-bg-input text-blue-500 focus:ring-blue-500" />
                <span className="text-sm font-semibold text-text-main">Use SSH Tunnel</span>
              </label>

              {formData.useSsh && (
                <div className="flex flex-col gap-3 p-3 bg-bg-main border border-border-main rounded">
                  <div className="grid grid-cols-4 gap-4">
                    <div className="col-span-3 flex flex-col gap-1">
                      <label className="text-xs text-text-muted">SSH Host</label>
                      <input name="sshHost" value={formData.sshHost || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" placeholder="bastion.example.com" />
                    </div>
                    <div className="flex flex-col gap-1">
                      <label className="text-xs text-text-muted">Port</label>
                      <input name="sshPort" type="number" value={formData.sshPort || 22} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" />
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-4">
                    <div className="flex flex-col gap-1">
                      <label className="text-xs text-text-muted">SSH Username</label>
                      <input name="sshUsername" value={formData.sshUsername || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" />
                    </div>
                    <div className="flex flex-col gap-1">
                      <label className="text-xs text-text-muted">Authentication Method</label>
                      <select name="sshAuthMode" value={formData.sshAuthMode || 'password'} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500">
                        <option value="password">Password</option>
                        <option value="key">Private Key</option>
                      </select>
                    </div>
                  </div>

                  {formData.sshAuthMode === 'password' ? (
                    <div className="flex flex-col gap-1">
                      <label className="text-xs text-text-muted">SSH Password</label>
                      <input name="sshPassword" type="password" value={formData.sshPassword || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" />
                    </div>
                  ) : (
                    <>
                      <div className="flex flex-col gap-1">
                        <label className="text-xs text-text-muted">Private Key File Path</label>
                        <input name="sshKeyFile" value={formData.sshKeyFile || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" placeholder="/home/user/.ssh/id_rsa" />
                      </div>
                      <div className="flex flex-col gap-1">
                        <label className="text-xs text-text-muted">Passphrase (Optional)</label>
                        <input name="sshPassphrase" type="password" value={formData.sshPassphrase || ''} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" />
                      </div>
                    </>
                  )}
                  
                  <div className="flex flex-col gap-1 mt-2 border-t border-border-item pt-2">
                      <label className="text-xs text-text-muted">Local Port Binding (Leave 0 for auto-assign)</label>
                      <input name="sshLocalPort" type="number" value={formData.sshLocalPort || 0} onChange={handleChange} className="w-full max-w-[200px] px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" />
                  </div>
                </div>
              )}
            </div>
          )}

          {activeTab === 'advanced' && (
            <div className="grid grid-cols-2 gap-4">
              <div className="flex flex-col gap-1">
                <label className="text-xs text-text-muted">Connection Timeout (s)</label>
                <input name="connectionTimeout" type="number" value={formData.connectionTimeout || 30} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" />
              </div>
              <div className="flex flex-col gap-1">
                <label className="text-xs text-text-muted">Socket Timeout (s) (0 = inf)</label>
                <input name="socketTimeout" type="number" value={formData.socketTimeout || 0} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" />
              </div>
              <div className="flex flex-col gap-1">
                <label className="text-xs text-text-muted">Fetch Size (Rows)</label>
                <input name="fetchSize" type="number" value={formData.fetchSize || 1000} onChange={handleChange} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500" />
              </div>
              <div className="flex flex-col gap-1 justify-center mt-4">
                <label className="flex items-center gap-2 cursor-pointer">
                  <input type="checkbox" name="readOnly" checked={formData.readOnly || false} onChange={handleChange} className="w-4 h-4 rounded border-border-input bg-bg-input text-blue-500 focus:ring-blue-500" />
                  <span className="text-sm text-text-main">Read Only Connection</span>
                </label>
              </div>
              <div className="col-span-2 flex flex-col gap-1 mt-2">
                <label className="text-xs text-text-muted">Extra JDBC Properties (key=value, multiline)</label>
                <textarea name="extraProps" value={formData.extraProps || ''} onChange={(e) => setFormData({...formData, extraProps: e.target.value})} className="w-full px-2.5 py-1.5 bg-bg-input border border-border-input rounded text-sm text-text-input outline-none focus:border-blue-500 h-20" placeholder="application_name=DataSyncStudio" />
              </div>
            </div>
          )}
        </div>

        {/* Test Result popup inline */}
        {testStatus !== 'idle' && (
          <div className={`px-4 py-2 text-xs border-t ${testStatus === 'success' ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-600 dark:text-emerald-400' : testStatus === 'error' ? 'bg-red-500/10 border-red-500/20 text-red-600 dark:text-red-400' : 'bg-blue-500/10 border-blue-500/20 text-blue-500'}`}>
            <div className="font-semibold flex items-center gap-1.5">
              {testStatus === 'success' && <CheckCircle className="w-3.5 h-3.5" />}
              {testStatus === 'error' && <XCircle className="w-3.5 h-3.5" />}
              {testStatus === 'testing' && <span className="w-3.5 h-3.5 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />}
              {testStatus === 'success' ? 'Connection Successful!' : testStatus === 'error' ? 'Connection Failed' : 'Testing Connection...'}
            </div>
            {testDetails && <div className="mt-1 font-mono text-[10px] opacity-80 whitespace-pre-wrap">{testDetails}</div>}
          </div>
        )}

        {/* Footer */}
        <div className="px-4 py-3 border-t border-border-main bg-bg-header flex justify-between">
          <button onClick={handleTest} disabled={testStatus === 'testing'} className="px-4 py-1.5 text-xs font-semibold border border-border-input bg-bg-panel hover:bg-bg-hover rounded text-text-main transition-colors disabled:opacity-50">
            Test Connection
          </button>
          <div className="flex gap-2">
            <button onClick={onClose} className="px-4 py-1.5 text-xs font-semibold text-text-muted hover:text-text-main transition-colors">
              Cancel
            </button>
            <button onClick={handleSave} className="px-5 py-1.5 bg-blue-600 hover:bg-blue-500 text-white rounded text-xs font-semibold shadow-lg shadow-blue-500/20 transition-colors">
              Save Connection
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

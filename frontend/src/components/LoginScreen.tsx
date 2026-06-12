import React, { useState } from "react";
import { DatabaseZap, Lock, ArrowRight, AlertCircle } from "lucide-react";

interface LoginScreenProps {
  onLogin: () => void;
}

export const LoginScreen: React.FC<LoginScreenProps> = ({ onLogin }) => {
  const [password, setPassword] = useState("");
  const [error, setError] = useState(false);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (password === "Menujutakterbatasdanmelampauinya123#$#*@") {
      setError(false);
      onLogin();
    } else {
      setError(true);
    }
  };

  return (
    <div className="h-screen w-screen bg-bg-main flex items-center justify-center font-sans text-text-main">
      <div className="w-full max-w-md p-8 bg-bg-panel border border-border-main rounded-xl shadow-2xl relative overflow-hidden">
        <div className="absolute top-0 left-0 w-full h-1.5 bg-gradient-to-r from-blue-500 to-cyan-400"></div>
        
        <div className="flex flex-col items-center mb-8">
          <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-blue-500 to-cyan-400 flex items-center justify-center shadow-lg shadow-blue-500/30 mb-4">
            <DatabaseZap className="w-8 h-8 text-white" />
          </div>
          <h1 className="text-2xl font-bold tracking-wide bg-gradient-to-r from-blue-400 to-cyan-300 bg-clip-text text-transparent">
            Darkosync Studio
          </h1>
          <p className="text-text-muted mt-2 text-sm text-center">
            Please enter your access password to continue
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-6">
          <div>
            <div className="relative">
              <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                <Lock className="h-5 w-5 text-text-muted" />
              </div>
              <input
                type="password"
                value={password}
                onChange={(e) => { setPassword(e.target.value); setError(false); }}
                className={`block w-full pl-10 pr-3 py-3 border ${error ? "border-red-500 focus:border-red-500" : "border-border-input focus:border-blue-500"} rounded-lg bg-bg-input text-text-main placeholder-text-muted focus:outline-none focus:ring-1 ${error ? "focus:ring-red-500" : "focus:ring-blue-500"} transition-colors`}
                placeholder="Enter password"
                autoFocus
              />
            </div>
            {error && (
              <div className="mt-2 flex items-center gap-1.5 text-red-500 text-sm">
                <AlertCircle className="w-4 h-4" />
                <span>Incorrect password. Please try again.</span>
              </div>
            )}
          </div>

          <button
            type="submit"
            className="w-full flex justify-center items-center gap-2 py-3 px-4 border border-transparent rounded-lg shadow-sm text-sm font-medium text-white bg-blue-600 hover:bg-blue-500 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 focus:ring-offset-bg-panel transition-colors"
          >
            Access Studio
            <ArrowRight className="w-4 h-4" />
          </button>
        </form>
      </div>
    </div>
  );
};

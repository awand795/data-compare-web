import React, { useState, useRef, useEffect } from 'react';
import { ChevronDown, Database } from 'lucide-react';
import clsx from 'clsx';

interface Connection {
  id: string;
  name: string;
  database: string;
}

interface ConnectionSelectProps {
  value: string;
  onChange: (value: string) => void;
  connections: Connection[];
  placeholder?: string;
  className?: string;
}

export function ConnectionSelect({ value, onChange, connections, placeholder = "Select connection...", className }: ConnectionSelectProps) {
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const selectedConn = connections.find(c => c.id === value);

  return (
    <div className={clsx("relative", className)} ref={containerRef}>
      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        className="w-full px-3 py-2 bg-bg-input border border-border-input rounded-md text-[12px] sm:text-[13px] font-medium text-text-input focus:border-blue-500 outline-none flex items-center justify-between gap-2 text-left transition-colors hover:border-blue-400 min-h-[38px] h-auto"
      >
        <div className="flex-1 whitespace-normal break-words leading-snug">
          {selectedConn ? (
            <span className="line-clamp-3">
              {selectedConn.name} <span className="opacity-75">({selectedConn.database})</span>
            </span>
          ) : (
            <span className="text-text-muted">{placeholder}</span>
          )}
        </div>
        <ChevronDown className="w-3.5 h-3.5 shrink-0 opacity-50" />
      </button>

      {isOpen && (
        <div className="absolute z-[100] mt-1 w-full min-w-full sm:w-max max-w-[90vw] sm:max-w-[400px] xl:max-w-[500px] bg-bg-panel border border-border-main rounded-md shadow-lg max-h-[300px] overflow-y-auto">
          {connections.length === 0 ? (
            <div className="px-3 py-3 text-[12px] sm:text-[13px] text-text-muted text-center">No connections available</div>
          ) : (
            <div className="flex flex-col p-1">
              {connections.map(c => (
                <button
                  key={c.id}
                  type="button"
                  className={clsx(
                    "w-full text-left px-3 py-2 text-[12px] sm:text-[13px] hover:bg-bg-hover transition-colors rounded-md flex flex-col gap-0.5",
                    value === c.id ? "bg-blue-500/10 text-blue-500 font-bold" : "text-text-main font-medium"
                  )}
                  onClick={() => {
                    onChange(c.id);
                    setIsOpen(false);
                  }}
                >
                  <span className="whitespace-normal break-words leading-tight">{c.name}</span>
                  <span className={clsx("text-[10px] whitespace-normal break-words leading-tight", value === c.id ? "opacity-90" : "opacity-60")}>
                    ({c.database})
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

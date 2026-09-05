import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import axios from 'axios'

// If VITE_API_URL is defined and not empty, use it. Otherwise use relative URL path (Nginx proxy)
const apiUrl = import.meta.env.VITE_API_URL
if (apiUrl && apiUrl.trim() !== '') {
  axios.defaults.baseURL = apiUrl
}

// Prevent browser caching for all API GET requests
axios.interceptors.request.use(config => {
  if (config.method?.toLowerCase() === 'get') {
    config.params = config.params || {};
    config.params['_t'] = Date.now();
    
    config.headers = config.headers || {};
    config.headers['Cache-Control'] = 'no-cache, no-store, must-revalidate';
    config.headers['Pragma'] = 'no-cache';
    config.headers['Expires'] = '0';
  }
  return config;
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

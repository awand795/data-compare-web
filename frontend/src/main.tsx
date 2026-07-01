import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import axios from 'axios'

// In production (Vercel), VITE_API_URL reads from .env.production or Vercel env vars
// set to Railway backend URL: https://data-compare-web-production.up.railway.app
// In development, requests are proxied via Vite to localhost:8081
const apiUrl = import.meta.env.VITE_API_URL
if (apiUrl) {
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

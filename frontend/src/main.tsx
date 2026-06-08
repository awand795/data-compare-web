import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import axios from 'axios'

// In production (Vercel), set VITE_API_URL to the Render backend URL
// e.g. https://datasync-backend.onrender.com
// In development, requests are proxied via Vite to localhost:8081
const apiUrl = import.meta.env.VITE_API_URL
if (apiUrl) {
  axios.defaults.baseURL = apiUrl
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

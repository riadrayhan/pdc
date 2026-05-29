import axios from 'axios'

// Use relative URL so it works on any domain (same-origin = no CORS issues)
const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1'

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Response interceptor for handling errors
api.interceptors.response.use(
  (response) => response,
  (error) => {
    // Just pass through errors without redirecting
    return Promise.reject(error)
  }
)

export default api

import axios from 'axios'

// Use relative URL so it works on any domain (same-origin = no CORS issues)
const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1'

const api = axios.create({
  baseURL: API_BASE_URL,
  // Hugging Face Spaces can cold-start; allow enough time before giving up.
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Retry configuration for transient failures (e.g. Hugging Face Space cold start).
const MAX_RETRIES = 4
const RETRY_BASE_DELAY = 1500 // ms, exponential backoff

const isRetryable = (error) => {
  // Network error / timeout (no response received)
  if (!error.response) return true
  // Gateway / unavailable / timeout statuses returned while the Space wakes up
  return [429, 500, 502, 503, 504].includes(error.response.status)
}

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// Response interceptor: retry transient errors with exponential backoff.
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = error.config

    // Only retry idempotent GET requests automatically.
    if (config && (config.method || 'get').toLowerCase() === 'get' && isRetryable(error)) {
      config.__retryCount = config.__retryCount || 0
      if (config.__retryCount < MAX_RETRIES) {
        config.__retryCount += 1
        const delay = RETRY_BASE_DELAY * Math.pow(2, config.__retryCount - 1)
        await wait(delay)
        return api(config)
      }
    }

    return Promise.reject(error)
  }
)

export default api


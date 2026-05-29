import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import api from '../services/api'

export const useAuthStore = create(
  persist(
    (set, get) => ({
      user: null,
      token: null,
      isAuthenticated: false,

      login: async (username, password) => {
        const response = await api.post('/auth/login', { username, password })
        const { access_token, refresh_token } = response.data
        
        // Set token in API client
        api.defaults.headers.common['Authorization'] = `Bearer ${access_token}`
        
        // Get user info
        const userResponse = await api.get('/users/me')
        
        set({
          token: access_token,
          user: userResponse.data,
          isAuthenticated: true,
        })
        
        return userResponse.data
      },

      logout: () => {
        delete api.defaults.headers.common['Authorization']
        set({
          user: null,
          token: null,
          isAuthenticated: false,
        })
      },

      initAuth: () => {
        const { token } = get()
        if (token) {
          api.defaults.headers.common['Authorization'] = `Bearer ${token}`
        }
      },
    }),
    {
      name: 'emi-auth',
      partialize: (state) => ({
        token: state.token,
        user: state.user,
        isAuthenticated: state.isAuthenticated,
      }),
    }
  )
)

// Initialize auth on app load
useAuthStore.getState().initAuth()

import { createContext, useContext, useEffect, useState, ReactNode } from 'react'
import keycloak from './keycloak'

export type AuthUser = {
  sub: string
  email?: string
  preferred_username?: string
  roles: string[]
  customer_id?: string
}

type AuthContextValue = {
  keycloak: typeof keycloak
  authenticated: boolean
  initializing: boolean
  token: string | null
  user: AuthUser | null
  login: () => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

function parseUser(parsed: any): AuthUser {
  return {
    sub: parsed.sub,
    email: parsed.email,
    preferred_username: parsed.preferred_username,
    roles: parsed.realm_access?.roles ?? [],
    customer_id: parsed.customer_id,
  }
}

// MODULE-LEVEL INIT PROMISE
// Guarantees keycloak.init() is called exactly once, even when React 18
// StrictMode double-mounts the AuthProvider in development.
let keycloakInitPromise: Promise<boolean> | null = null

function initKeycloakOnce(): Promise<boolean> {
  if (!keycloakInitPromise) {
    keycloakInitPromise = keycloak
      .init({
        onLoad: 'check-sso',
        pkceMethod: 'S256',
        checkLoginIframe: false,
        // NOTE: silentCheckSsoRedirectUri intentionally omitted.
        // Modern browsers block the third-party cookies needed for the
        // iframe-based silent check, causing init() to hang indefinitely.
        // Since this app has a public landing page with an explicit
        // "Sign In" button, we don't need the silent check at all.
      })
      .catch((err) => {
        console.error('Keycloak init failed:', err)
        return false
      })
  }
  return keycloakInitPromise
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [authenticated, setAuthenticated] = useState(false)
  const [initializing, setInitializing] = useState(true)
  const [token, setToken] = useState<string | null>(null)
  const [user, setUser] = useState<AuthUser | null>(null)

  useEffect(() => {
    keycloak.onTokenExpired = () => {
      keycloak
        .updateToken(30)
        .then((refreshed) => {
          if (refreshed) setToken(keycloak.token ?? null)
        })
        .catch(() => {
          keycloak.login()
        })
    }

    keycloak.onAuthRefreshSuccess = () => {
      setToken(keycloak.token ?? null)
      if (keycloak.tokenParsed) setUser(parseUser(keycloak.tokenParsed))
    }

    keycloak.onAuthLogout = () => {
      setAuthenticated(false)
      setToken(null)
      setUser(null)
    }

    initKeycloakOnce().then((auth) => {
      setAuthenticated(auth)
      if (auth && keycloak.tokenParsed) {
        setToken(keycloak.token ?? null)
        setUser(parseUser(keycloak.tokenParsed))
      }
      setInitializing(false)
    })
  }, [])

  const login = async () => {
    await keycloak.login({ redirectUri: window.location.origin + '/dashboard' })
  }

  const logout = async () => {
    await keycloak.logout({ redirectUri: window.location.origin })
  }

  return (
    <AuthContext.Provider
      value={{ keycloak, authenticated, initializing, token, user, login, logout }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
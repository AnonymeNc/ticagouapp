import { createContext, useCallback, useContext, useEffect, useMemo, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, registerUnauthenticated } from "@/lib/api";

export type Operator = { id: string; login: string; displayName: string };
export type LoginStep = { step: "MOT_DE_PASSE" | "TOTP" | "TOTP_ENROLL" | "CONNECTE"; secret: string | null; uri: string | null };
export type AuthState = { setupRequired: boolean; login: LoginStep; operator: Operator | null };

export const authKey = ["auth-state"] as const;

type AuthContextValue = {
  state: AuthState;
  reload: () => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export const authApi = {
  state: (signal?: AbortSignal) => api<AuthState>("/auth/state", { signal }),
  login: (login: string, password: string) => api<LoginStep>("/auth/login", { method: "POST", body: { login, password } }),
  totp: (code: string) => api<Operator>("/auth/totp", { method: "POST", body: { code } }),
  setup: (body: { token: string; login: string; displayName: string; password: string }) =>
    api<Operator>("/auth/setup", { method: "POST", body }),
  logout: () => api<void>("/auth/logout", { method: "POST" }),
};

/**
 * État de connexion lu sur le serveur (la session est un cookie HttpOnly). Le premier appel pose aussi le cookie CSRF.
 * `children` reçoit l'état une fois chargé.
 */
export function AuthProvider({ children }: { children: (state: AuthState) => ReactNode }) {
  const client = useQueryClient();
  const query = useQuery({ queryKey: authKey, queryFn: ({ signal }) => authApi.state(signal), staleTime: Infinity, retry: 1 });

  const reload = useCallback(async () => {
    await client.invalidateQueries({ queryKey: authKey });
  }, [client]);

  const logout = useCallback(async () => {
    await authApi.logout().catch(() => undefined);
    // Les données de la session fermée disparaissent ; l'état de connexion est relu (vider tout le cache
    // détacherait aussi la requête de l'état, et l'écran resterait affiché).
    client.removeQueries({ predicate: (query) => query.queryKey[0] !== authKey[0] });
    await client.refetchQueries({ queryKey: authKey });
  }, [client]);

  useEffect(() => {
    registerUnauthenticated(() => void client.invalidateQueries({ queryKey: authKey }));
    return () => registerUnauthenticated(null);
  }, [client]);

  const value = useMemo(() => (query.data ? { state: query.data, reload, logout } : null), [query.data, reload, logout]);

  if (query.isError) {
    return (
      <div role="alert" className="flex min-h-screen items-center justify-center p-6 text-danger-700">
        La console ne répond pas. Rechargez la page dans un instant.
      </div>
    );
  }
  if (!value) {
    return <div className="flex min-h-screen items-center justify-center text-muted-foreground">Chargement…</div>;
  }
  return <AuthContext.Provider value={value}>{children(value.state)}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error("useAuth hors de AuthProvider");
  }
  return value;
}

export function useOperator(): Operator {
  const { state } = useAuth();
  if (!state.operator) {
    throw new Error("Aucun opérateur connecté");
  }
  return state.operator;
}

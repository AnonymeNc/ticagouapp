import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api, registerAuthHooks } from "@/lib/api";
import {
  clearSession,
  configureTokenStorage,
  currentRefreshToken,
  getAccessToken,
  mayHaveSession,
  refreshSession,
  sessionHeaders,
  storeSession,
} from "./tokens";
import { disablePushNotifications } from "@/lib/native/push";
import type { Me, SessionResponse } from "./types";

type AuthState =
  | { status: "loading" }
  | { status: "anonymous" }
  | { status: "authenticated"; user: Me };

type AuthContextValue = {
  state: AuthState;
  login: (login: string, password: string, code?: string) => Promise<void>;
  loginWithPin: (userId: string, pin: string) => Promise<void>;
  /** Session déjà obtenue (assistant de premier lancement). */
  applySession: (session: SessionResponse) => void;
  /** Relit la session après une activation de la double authentification. */
  reloadSession: () => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

type Props = {
  /** true : bureau et application Direction, la session survit à la fermeture ; false : tablette partagée. */
  persist: boolean;
  children: ReactNode;
};

export function AuthProvider({ persist, children }: Props) {
  const queryClient = useQueryClient();
  const [state, setState] = useState<AuthState>(() => {
    configureTokenStorage({ persist });
    return mayHaveSession() ? { status: "loading" } : { status: "anonymous" };
  });

  const applySession = useCallback((session: SessionResponse) => {
    storeSession(session);
    setState({ status: "authenticated", user: session.user });
  }, []);

  const endSession = useCallback(() => {
    clearSession();
    queryClient.clear();
    setState({ status: "anonymous" });
  }, [queryClient]);

  useEffect(() => {
    registerAuthHooks({
      getAccessToken,
      refresh: async () => {
        const session = await refreshSession();
        if (!session) endSession();
        return session !== null;
      },
    });
    return () => registerAuthHooks(null);
  }, [endSession]);

  // Reprise de la session au démarrage du poste.
  useEffect(() => {
    if (state.status !== "loading") return;
    refreshSession().then((session) =>
      setState(session ? { status: "authenticated", user: session.user } : { status: "anonymous" }),
    );
  }, [state.status]);

  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      applySession,
      reloadSession: async () => {
        const session = await refreshSession();
        setState(session ? { status: "authenticated", user: session.user } : { status: "anonymous" });
      },
      login: async (login, password, code) => {
        applySession(
          await api<SessionResponse>("/auth/login", { method: "POST", body: { login, password, code }, auth: false, headers: sessionHeaders() }),
        );
      },
      loginWithPin: async (userId, pin) => {
        applySession(await api<SessionResponse>("/auth/pin", { method: "POST", body: { userId, pin }, auth: false }));
      },
      logout: async () => {
        // Application : ce téléphone ne reçoit plus les alertes de la crèche (ADR 0026).
        await disablePushNotifications();
        const token = currentRefreshToken();
        endSession();
        await api("/auth/logout", { method: "POST", body: token ? { refreshToken: token } : undefined, auth: false }).catch(() => {});
      },
    }),
    [state, applySession, endSession],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth doit être utilisé dans <AuthProvider>");
  return context;
}

/** Utilisateur connecté ; à n'utiliser que sous une route protégée. */
export function useCurrentUser(): Me {
  const { state } = useAuth();
  if (state.status !== "authenticated") throw new Error("Aucun utilisateur connecté");
  return state.user;
}

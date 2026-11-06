import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { ApiError, portal, type Child, type Family, type Me } from "./api";

type SessionValue = {
  me: Me;
  family: Family;
  child: Child | null;
  /** Choix de l'enfant (et de sa crèche) quand la famille en a plusieurs. */
  select: (structureId: string, childId: string) => void;
};

const SessionContext = createContext<SessionValue | null>(null);
const CHOICE_KEY = "ticagou.parents.child";

export const meKey = ["me"] as const;

/** Session du parent : sans session valide, l'écran de connexion. */
export function useMe() {
  return useQuery({ queryKey: meKey, queryFn: ({ signal }) => portal.me(signal), retry: false, refetchInterval: 120_000 });
}

export function isUnauthenticated(error: unknown) {
  return error instanceof ApiError && error.status === 401;
}

export function SessionProvider({ me, children }: { me: Me; children: ReactNode }) {
  const [choice, setChoice] = useState<{ structureId: string; childId: string } | null>(() => {
    try {
      return JSON.parse(window.localStorage.getItem(CHOICE_KEY) ?? "null");
    } catch {
      return null;
    }
  });
  useEffect(() => {
    try {
      if (choice) window.localStorage.setItem(CHOICE_KEY, JSON.stringify(choice));
    } catch {
      // Stockage indisponible (navigation privée) : le choix n'est simplement pas retenu.
    }
  }, [choice]);

  const family = me.families.find((f) => f.structureId === choice?.structureId && f.children.some((c) => c.id === choice?.childId))
    ?? me.families[0];
  const child = family?.children.find((c) => c.id === choice?.childId) ?? family?.children[0] ?? null;
  if (!family) return <>{children}</>;
  return (
    <SessionContext.Provider value={{ me, family, child, select: (structureId, childId) => setChoice({ structureId, childId }) }}>
      {children}
    </SessionContext.Provider>
  );
}

export function useSession() {
  const value = useContext(SessionContext);
  if (!value) throw new Error("useSession doit être utilisé dans <SessionProvider>");
  return value;
}

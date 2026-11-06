import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { Role } from "@/features/auth/types";
import { useServerHealth } from "@/features/health/useServerHealth";
import { useStaffingStream } from "@/features/staffing/stream";
import { kioskApi, newId, type KioskStaff, type KioskToday, type Operation, type OperationResult } from "./api";
import { getDevice, subscribeDevice, type PairedDevice } from "./device";
import { defaultStore, Outbox, type OutboxState } from "./outbox";
import { deleteSealed, loadSealed, saveSealed } from "./secureStore";
import { applyOperations } from "./view";

/** Personne connectée sur la tablette ; hors connexion, son code sera vérifié au retour du serveur. */
export type KioskSession = {
  userId: string;
  firstName: string;
  displayName: string;
  role: Role;
  sessionId?: string;
  offlineSessionId?: string;
  refreshToken?: string;
};

type Action = Omit<Operation, "clientId" | "occurredAt" | "offline" | "userId" | "sessionId" | "offlineSessionId"> & {
  clientId?: string;
};

type KioskContextValue = {
  device: PairedDevice | null;
  today: KioskToday | null;
  todayError: unknown;
  session: KioskSession | null;
  online: boolean;
  outbox: OutboxState;
  /** Heure du serveur (horloge de la tablette corrigée). */
  now: () => Date;
  act: (action: Action) => Promise<OperationResult | null>;
  /** Opération d'une autre personne que la session (pointage du personnel hors connexion). */
  enqueue: (op: Omit<Operation, "clientId" | "occurredAt" | "offline"> & { clientId?: string }) => Promise<OperationResult | null>;
  loginOnline: (userId: string, pin: string) => Promise<void>;
  loginOffline: (staff: KioskStaff, pin: string) => Promise<void>;
  logout: () => void;
  refresh: () => void;
  retry: () => void;
  dismiss: (clientId: string) => void;
};

const KioskContext = createContext<KioskContextValue | null>(null);

const CACHE_KEY = "ticagou.kiosk.today";
const TODAY_KEY = ["kiosk-today"] as const;

function localDay(date = new Date()) {
  return date.toLocaleDateString("sv-SE", { timeZone: "Pacific/Noumea" });
}

/** Instantané gardé chiffré pour pointer hors connexion ; effacé au changement de jour (ADR 0005, ADR 0013). */
async function readCache(): Promise<KioskToday | undefined> {
  try {
    const cached = await loadSealed<KioskToday>(CACHE_KEY);
    if (cached && cached.day !== localDay()) {
      await deleteSealed(CACHE_KEY);
      return undefined;
    }
    return cached;
  } catch {
    return undefined;
  }
}

function writeCache(today: KioskToday | null) {
  // Stockage plein ou refusé : la tablette pointera sans instantané de secours.
  void (today ? saveSealed(CACHE_KEY, today) : deleteSealed(CACHE_KEY)).catch(() => {});
}

export function KioskProvider({ children, outbox: given }: { children: ReactNode; outbox?: Outbox }) {
  const queryClient = useQueryClient();
  const device = useSyncExternalStore(subscribeDevice, getDevice, getDevice);
  const [outbox] = useState(() => given ?? new Outbox(defaultStore(), kioskApi.operations));
  const outboxState = useSyncExternalStore(outbox.subscribe, outbox.getState, outbox.getState);
  const [session, setSession] = useState<KioskSession | null>(null);
  const offset = useRef(0);
  const health = useServerHealth();

  useEffect(() => {
    void outbox.load();
  }, [outbox]);

  useEffect(() => {
    if (health === "connected" || health === "degraded") outbox.setOnline(true);
    if (health === "offline") outbox.setOnline(false);
  }, [health, outbox]);

  useEffect(() => {
    if (!device) {
      writeCache(null);
      setSession(null);
    }
  }, [device]);

  const query = useQuery({
    queryKey: TODAY_KEY,
    enabled: device !== null,
    queryFn: async ({ signal }) => {
      const started = Date.now();
      const data = await kioskApi.today(signal);
      offset.current = new Date(data.serverTime).getTime() - Date.now();
      writeCache(data);
      outbox.forgetAckedBefore(started);
      return data;
    },
    refetchInterval: 60_000,
    retry: false,
  });

  // Le serveur ne répond pas : la tablette repart de son instantané, tant qu'il n'y a rien de plus récent.
  useEffect(() => {
    if (!device) return;
    void readCache().then((cached) => {
      if (cached && !queryClient.getQueryData(TODAY_KEY)) queryClient.setQueryData(TODAY_KEY, cached, { updatedAt: 0 });
    });
  }, [device, queryClient]);

  // Une opération confirmée : on relit l'instantané du serveur.
  const ackedCount = outboxState.acked.length;
  useEffect(() => {
    if (ackedCount > 0) void queryClient.invalidateQueries({ queryKey: TODAY_KEY });
  }, [ackedCount, queryClient]);

  useStaffingStream({
    auth: "device",
    enabled: device !== null,
    onEvent: (event) => {
      if (event.name === "attendance") void queryClient.invalidateQueries({ queryKey: TODAY_KEY });
    },
  });

  const names = useMemo(
    () => new Map((query.data?.staff ?? []).map((s) => [s.userId, s.displayName])),
    [query.data],
  );
  const today = useMemo(() => {
    if (!query.data) return null;
    return applyOperations(
      query.data,
      [
        ...outboxState.acked.map((a) => ({ op: a.op, pending: false })),
        ...outboxState.pending.map((op) => ({ op, pending: true })),
      ],
      names,
    );
  }, [query.data, outboxState.acked, outboxState.pending, names]);

  const online = health !== "offline" && outboxState.online;
  const now = useCallback(() => new Date(Date.now() + offset.current), []);

  const enqueue = useCallback<KioskContextValue["enqueue"]>(
    (op) =>
      outbox.enqueue({
        ...op,
        clientId: op.clientId ?? newId(),
        occurredAt: new Date(Date.now() + offset.current).toISOString(),
        offline: !online,
      }),
    [outbox, online],
  );

  const act = useCallback<KioskContextValue["act"]>(
    (action) => {
      if (!session) return Promise.reject(new Error("Aucune session"));
      return enqueue({
        ...action,
        userId: session.userId,
        sessionId: session.sessionId,
        offlineSessionId: session.offlineSessionId,
      });
    },
    [enqueue, session],
  );

  const value = useMemo<KioskContextValue>(
    () => ({
      device,
      today,
      todayError: query.error,
      session,
      online,
      outbox: outboxState,
      now,
      act,
      enqueue,
      loginOnline: async (userId, pin) => {
        const response = await kioskApi.pinLogin(userId, pin);
        const user = response.user;
        setSession({
          userId: user.id,
          firstName: user.firstName,
          displayName: `${user.firstName} ${user.lastName.charAt(0)}.`,
          role: user.role,
          sessionId: response.sessionId,
          refreshToken: response.refreshToken ?? undefined,
        });
      },
      loginOffline: async (staff, pin) => {
        const clientId = newId();
        await enqueue({ clientId, type: "CONNEXION_HORS_LIGNE", userId: staff.userId, pin });
        setSession({
          userId: staff.userId,
          firstName: staff.displayName.split(" ")[0],
          displayName: `${staff.displayName.split(" ")[0]} ${staff.displayName.split(" ")[1]?.charAt(0) ?? ""}.`,
          role: staff.role,
          offlineSessionId: clientId,
        });
      },
      logout: () => {
        if (session?.refreshToken) void kioskApi.logout(session.refreshToken).catch(() => {});
        setSession(null);
      },
      refresh: () => void queryClient.invalidateQueries({ queryKey: TODAY_KEY }),
      retry: () => {
        outbox.retryNow();
        void queryClient.invalidateQueries({ queryKey: TODAY_KEY });
      },
      dismiss: (clientId) => outbox.dismiss(clientId),
    }),
    [device, today, query.error, session, online, outboxState, now, act, enqueue, outbox, queryClient],
  );

  return <KioskContext.Provider value={value}>{children}</KioskContext.Provider>;
}

export function useKiosk() {
  const context = useContext(KioskContext);
  if (!context) throw new Error("useKiosk doit être utilisé dans <KioskProvider>");
  return context;
}

/** Session ouverte ; à n'utiliser que sous l'écran de connexion. */
export function useKioskSession(): KioskSession {
  const { session } = useKiosk();
  if (!session) throw new Error("Aucune session sur la tablette");
  return session;
}

/** Pointage réservé à la direction et aux éducatrices ; la cuisine consulte. */
export function canPoint(session: KioskSession | null) {
  return session?.role === "DIRECTION" || session?.role === "EDUCATRICE";
}

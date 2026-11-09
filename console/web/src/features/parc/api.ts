import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";

export type Level = "OK" | "WARN" | "BAD" | "OFF";
export type Lifecycle = "PROSPECT" | "CLIENT" | "RESILIE";
export type ClientKind = "PRIVE" | "ASSOCIATION" | "COLLECTIVITE" | "ENTREPRISE";

export type AlertView = { id: number; kind: string; label: string; detail: string; openedAt: string; closedAt: string | null };

export type Row = {
  slug: string;
  name: string;
  city: string | null;
  clientName: string | null;
  lifecycle: Lifecycle;
  health: { level: Level; reasons: string[] };
  lastContactAt: string | null;
  version: string | null;
  plan: string | null;
  modules: string[];
  places: number | null;
  activeChildren: number | null;
  licenseState: string | null;
  validUntil: string | null;
  deadline: string | null;
  desktops: number | null;
  desktopLimit: number | null;
  tablets: number | null;
  tabletLimit: number | null;
  backupAt: string | null;
  backupStale: boolean;
  diskUsedPercent: number | null;
  container: string | null;
  alerts: AlertView[];
  lastCallAt: string | null;
  balanceDue: number;
  daysLate: number;
};

export type Summary = {
  total: number;
  ok: number;
  warn: number;
  bad: number;
  off: number;
  evaluationsEnding: number;
  renewals: number;
  versionsBehind: number;
  latestVersion: string | null;
  unpaid: number;
};

export type Sources = {
  supervisionAt: string | null;
  supervisionAttemptAt: string | null;
  supervisionError: string | null;
  hostAt: string | null;
  hostError: string | null;
  unlinkedSites: string[];
  rejectedSites: string[];
  hostDiskFree: number | null;
  hostDiskTotal: number | null;
};

export type Parc = { creches: Row[]; summary: Summary; sources: Sources };

export type Client = {
  id: string;
  name: string;
  kind: ClientKind;
  ridet: string | null;
  address: string | null;
  directorName: string | null;
  directorEmail: string | null;
  directorPhone: string | null;
  billingEmail: string | null;
  reminderEmail: string | null;
  paymentDays: number;
};

export type Note = { id: number; at: string; operatorLogin: string | null; kind: "NOTE" | "APPEL"; text: string };
export type JournalEvent = { id: number; at: string; operatorLogin: string | null; action: string; target: string | null; detail: string | null; ip: string | null };
export type Point = { day: string; diskUsedPercent: number | null; dataFree: number | null; activeChildren: number | null; attendances7d: number | null; activeUsers30d: number | null };

/** État de supervision tel que l'instance l'envoie (MonitoringStatus). */
export type Status = {
  version?: string;
  database?: string;
  backup?: { enabled?: boolean; toolsAvailable?: boolean; stale?: boolean; lastSuccessAt?: string | null; lastTestOn?: string | null; lastTestOk?: boolean | null };
  disk?: { dataFreeBytes?: number | null; dataTotalBytes?: number | null; backupFreeBytes?: number | null; backupTotalBytes?: number | null };
  license?: { state?: string; validUntil?: string | null; deadline?: string | null; desktops?: number; desktopLimit?: number };
  tablets?: { paired?: number; silent?: number };
  portal?: { state?: string; failures?: number; lastError?: string | null; pending?: number };
  attendanceToReview?: number;
  usage?: { activeChildren?: number; places?: number | null; activeUsers30d?: number; lastDirectionLoginOn?: string | null; attendances7d?: number; familyInvoicesLastMonth?: number };
};

export type Subscription = { number?: string; plan?: string; places?: number; desktops?: number; tablets?: number; validUntil?: string; modules?: string | string[] };

export type Detail = {
  row: Row;
  domain: string | null;
  clientSince: string | null;
  createdAt: string;
  supervisionName: string | null;
  hostSeenAt: string | null;
  client: Client;
  status: Status | null;
  subscription: Subscription | null;
  history: AlertView[];
  points: Point[];
  notes: Note[];
  journal: JournalEvent[];
};

export type SearchHit = { slug: string; name: string; city: string | null; clientName: string | null; level: Level };

export type FicheForm = {
  name: string;
  city: string | null;
  lifecycle: Lifecycle;
  clientSince: string | null;
  client: Omit<Client, "id">;
};

export const parcKey = ["parc"] as const;
export const crecheKey = (slug: string) => ["parc", slug] as const;

/** Le parc se relit toutes les minutes : la console lit la supervision toutes les 5 min. */
export function useParc() {
  return useQuery({ queryKey: parcKey, queryFn: ({ signal }) => api<Parc>("/parc", { signal }), refetchInterval: 60_000 });
}

export function useCreche(slug: string) {
  return useQuery({
    queryKey: crecheKey(slug),
    queryFn: ({ signal }) => api<Detail>(`/parc/${encodeURIComponent(slug)}`, { signal }),
    refetchInterval: 60_000,
  });
}

export function useSearch(query: string) {
  const q = query.trim();
  return useQuery({
    queryKey: ["recherche", q],
    queryFn: ({ signal }) => api<SearchHit[]>(`/recherche?q=${encodeURIComponent(q)}`, { signal }),
    enabled: q.length >= 2,
    staleTime: 30_000,
  });
}

/** Écritures sur une crèche : la fiche renvoyée remplace le cache, le parc est relu. */
function useCrecheWrite<T>(write: (input: T) => Promise<Detail>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: write,
    onSuccess: (detail) => {
      client.setQueryData(crecheKey(detail.row.slug), detail);
      void client.invalidateQueries({ queryKey: parcKey, exact: true });
      void client.invalidateQueries({ queryKey: ["recherche"] });
    },
  });
}

export function useUpdateFiche(slug: string) {
  return useCrecheWrite((form: FicheForm) => api<Detail>(`/parc/${encodeURIComponent(slug)}`, { method: "PUT", body: form }));
}

export function useAddNote(slug: string) {
  return useCrecheWrite((note: { kind: "NOTE" | "APPEL"; text: string }) =>
    api<Detail>(`/parc/${encodeURIComponent(slug)}/notes`, { method: "POST", body: note }),
  );
}

export function useCreateProspect() {
  return useCrecheWrite((prospect: { slug: string; name: string; city: string | null }) =>
    api<Detail>("/parc", { method: "POST", body: prospect }),
  );
}

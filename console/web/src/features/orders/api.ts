import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useConfirmed } from "@/features/auth/Confirm";
import { api, ApiError } from "@/lib/api";

export type OrderType = "CREER" | "DEMO" | "SUPPRIMER_DEMO" | "ABONNEMENT" | "SUSPENDRE" | "REPRENDRE" | "METTRE_A_JOUR" | "EXPORTER" | "SUPPRIMER";
export type OrderStatus = "A_CONFIRMER" | "EN_ATTENTE" | "EN_COURS" | "FAIT" | "ECHEC" | "ANNULE";

export type Order = {
  id: string;
  type: OrderType;
  slug: string;
  crecheName: string | null;
  params: Record<string, string>;
  status: OrderStatus;
  reason: string | null;
  proposedBy: string | null;
  confirmedBy: string | null;
  createdAt: string;
  confirmedAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  output: string | null;
  result: OrderResult | null;
  stale: boolean;
};

/** Ce qu'un ordre a produit : lien de démarrage, adresse d'une démo, export (et sa suite). */
export type OrderResult = {
  setupLink?: string;
  url?: string;
  exportFile?: string;
  size?: number;
  streamedAt?: string;
  receivedAt?: string;
  deletionOrder?: string;
};

export type Misaligned = { slug: string; name: string; differences: string[] };
export type OrdersBoard = { orders: Order[]; misaligned: Misaligned[]; images: string[]; hostAt: string | null };

export const ordersKey = ["ordres"] as const;

/** Relu toutes les 15 s tant qu'un ordre attend l'hôte (cron chaque minute), sinon chaque minute. */
export function useOrders() {
  return useQuery({
    queryKey: ordersKey,
    queryFn: ({ signal }) => api<OrdersBoard>("/ordres", { signal }),
    refetchInterval: (query) => (query.state.data?.orders.some((o) => o.status === "EN_ATTENTE" || o.status === "EN_COURS") ? 15_000 : 60_000),
  });
}

export function useCrecheOrders(slug: string) {
  return useQuery({
    queryKey: ["ordres", slug],
    queryFn: ({ signal }) => api<Order[]>(`/parc/${encodeURIComponent(slug)}/ordres`, { signal }),
    refetchInterval: (query) => (query.state.data?.some((o) => o.status === "EN_ATTENTE" || o.status === "EN_COURS") ? 15_000 : false),
  });
}

/** Donner, confirmer ou annuler un ordre ; le code est redemandé pour les deux premiers. */
export function useOrderAction<I>(action: (input: I) => Promise<Order>) {
  const client = useQueryClient();
  const confirmed = useConfirmed();
  return useMutation({
    mutationFn: (input: I) => confirmed(() => action(input)),
    onSuccess: () => {
      for (const key of [["ordres"], ["parc"], ["tableau"], ["contrat"]]) {
        void client.invalidateQueries({ queryKey: key });
      }
    },
  });
}

export const ordersApi = {
  give: (input: { type: OrderType; slug?: string; params?: Record<string, string>; reason?: string | null }) =>
    api<Order>("/ordres", { method: "POST", body: input }),
  confirm: (id: string) => api<Order>(`/ordres/${id}/confirmer`, { method: "POST" }),
  cancel: (id: string) => api<Order>(`/ordres/${id}/annuler`, { method: "POST" }),
  exportReceived: (id: string) => api<Order>(`/ordres/${id}/export/recu`, { method: "POST" }),
};

/**
 * Télécharge l'export d'une crèche sur cet ordinateur : lu en entier, enregistré, puis signalé reçu à la console. Pour
 * un export « puis supprimer », c'est ce signal qui transmet la suppression à l'hôte.
 */
export async function downloadExport(order: Pick<Order, "id" | "result">): Promise<Order> {
  const file = order.result?.exportFile ?? "export.tar.gz.enc";
  let response: Response;
  try {
    response = await fetch(`/api/v1/ordres/${order.id}/export`, { credentials: "same-origin" });
  } catch {
    throw new ApiError(0, "network", "Téléchargement interrompu : la console ne répond pas. Réessayez.");
  }
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as { code?: string; detail?: string };
    throw new ApiError(response.status, problem.code ?? "error", problem.detail ?? "Téléchargement impossible.");
  }
  const blob = await response.blob();
  if (order.result?.size != null && blob.size !== order.result.size) {
    throw new ApiError(0, "incomplet", "Export incomplet : réessayez le téléchargement.");
  }
  const href = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = href;
  link.download = file;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(href), 60_000);
  return ordersApi.exportReceived(order.id);
}

export function useDownloadExport() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (order: Pick<Order, "id" | "result">) => downloadExport(order),
    onSettled: () => {
      for (const key of [["ordres"], ["parc"], ["tableau"]]) {
        void client.invalidateQueries({ queryKey: key });
      }
    },
  });
}

/** « 12,4 Mo ». */
export function formatSize(bytes: number): string {
  if (bytes < 1024 * 1024) {
    return `${Math.max(1, Math.round(bytes / 1024))} Ko`;
  }
  return `${(bytes / (1024 * 1024)).toLocaleString("fr-FR", { maximumFractionDigits: 1 })} Mo`;
}

export const typeLabels: Record<OrderType, string> = {
  CREER: "Créer la crèche",
  DEMO: "Démonstration",
  SUPPRIMER_DEMO: "Supprimer la démo",
  ABONNEMENT: "Abonnement",
  SUSPENDRE: "Suspendre",
  REPRENDRE: "Reprendre",
  METTRE_A_JOUR: "Mise à jour",
  EXPORTER: "Export des données",
  SUPPRIMER: "Supprimer la crèche",
};

export const statusPill: Record<OrderStatus, { tone: "info" | "lagon" | "ok" | "bad" | "neutral" | "warn"; label: string }> = {
  A_CONFIRMER: { tone: "info", label: "À confirmer" },
  EN_ATTENTE: { tone: "lagon", label: "En attente de l’hôte" },
  EN_COURS: { tone: "warn", label: "En cours" },
  FAIT: { tone: "ok", label: "Fait" },
  ECHEC: { tone: "bad", label: "Échec" },
  ANNULE: { tone: "neutral", label: "Annulé" },
};

/** « Gestion, 12 places, 1 PC, 3 tablettes, jusqu’au 31/10/2027, + Repas ». */
export function describe(order: Pick<Order, "type" | "params">): string {
  const p = order.params;
  const day = (d?: string) => (d ? d.split("-").reverse().join("/") : "?");
  switch (order.type) {
    case "ABONNEMENT":
      return [`${(p.offre ?? "").charAt(0).toUpperCase()}${(p.offre ?? "").slice(1)}`, `${p.places} places`, `${p.pc} PC`, `${p.tablettes} tablettes`,
        `jusqu’au ${day(p.jusqu_au)}`, p.modules ? `+ ${p.modules.split(",").join(", ").toLowerCase()}` : null].filter(Boolean).join(", ");
    case "METTRE_A_JOUR":
      return `Version ${p.version}`;
    case "DEMO":
      return `Formule ${p.formule}${p.domaine ? `, ${p.domaine}` : ""}`;
    case "CREER":
      return p.domaine ? `Domaine ${p.domaine}` : "Domaine par défaut";
    case "EXPORTER":
      return p.suite === "supprimer" ? "Puis suppression, après téléchargement" : "Base, documents et clés";
    case "SUPPRIMER":
      return `Après export ${p.export ?? "?"}`;
    default:
      return "—";
  }
}

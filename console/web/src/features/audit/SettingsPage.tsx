import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { cn } from "@/lib/utils";

type AuditEvent = { id: number; at: string; operatorLogin: string | null; action: string; target: string | null; detail: string | null; ip: string | null };

const tabs = [
  { id: "bareme", label: "Barème", phase: 3 },
  { id: "relances", label: "Relances", phase: 4 },
  { id: "editeur", label: "Éditeur et TGC", phase: 3 },
  { id: "modeles", label: "Modèles d’e-mails", phase: 4 },
  { id: "comptes", label: "Comptes", phase: 1 },
  { id: "journal", label: "Journal", phase: 1 },
] as const;

const actionLabels: Record<string, string> = {
  PREMIER_COMPTE: "Premier compte créé",
  TOTP_ENREGISTRE: "Double authentification enregistrée",
  CONNEXION: "Connexion",
  CONNEXION_ECHEC: "Connexion refusée",
  DECONNEXION: "Déconnexion",
  CONFIRMATION: "Code confirmé",
  TOTP_REINITIALISE: "Double authentification réinitialisée (serveur)",
};

/** Paramètres (maquette) : onglets du plan ; le journal est disponible dès la phase 1. */
export function SettingsPage() {
  const [tab, setTab] = useState<(typeof tabs)[number]["id"]>("journal");
  const current = tabs.find((t) => t.id === tab)!;
  return (
    <>
      <div>
        <h1 className="m-0 font-display text-display">Paramètres</h1>
        <p className="mt-1 text-muted-foreground">Barème, règles de relance, mentions de l’éditeur, comptes de la console.</p>
      </div>
      <div role="tablist" className="flex gap-1 overflow-x-auto border-b border-border">
        {tabs.map((t) => (
          <button
            key={t.id}
            role="tab"
            type="button"
            aria-selected={t.id === tab}
            onClick={() => setTab(t.id)}
            className={cn(
              "border-b-2 border-transparent px-3.5 py-2.5 font-semibold whitespace-nowrap text-muted-foreground",
              t.id === tab && "border-lagon-600 text-lagon-600",
            )}
          >
            {t.label}
          </button>
        ))}
      </div>
      {tab === "journal" ? (
        <Journal />
      ) : tab === "comptes" ? (
        <section className="rounded-lg border border-border-subtle bg-card p-6 text-muted-foreground shadow-1">
          Un seul compte pour l’instant, créé avec le lien du socle. Téléphone perdu : <span className="font-mono">./creche.sh console-telephone-perdu &lt;identifiant&gt;</span>.
        </section>
      ) : (
        <section className="rounded-lg border border-border-subtle bg-card p-6 text-muted-foreground shadow-1">
          Cet onglet arrive avec la phase {current.phase} du plan.
        </section>
      )}
    </>
  );
}

function Journal() {
  const query = useQuery({ queryKey: ["audit"], queryFn: ({ signal }) => api<AuditEvent[]>("/audit?limit=200", { signal }) });
  return (
    <section className="rounded-lg border border-border-subtle bg-card p-2 shadow-1">
      {query.isPending ? (
        <p className="p-4 text-muted-foreground">Chargement…</p>
      ) : query.isError ? (
        <p role="alert" className="p-4 text-danger-700">Journal illisible pour l’instant.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse">
            <thead>
              <tr className="text-left text-small text-muted-foreground">
                <th className="border-b border-border px-3 py-2 font-semibold">Date</th>
                <th className="border-b border-border px-3 py-2 font-semibold">Compte</th>
                <th className="border-b border-border px-3 py-2 font-semibold">Action</th>
                <th className="border-b border-border px-3 py-2 font-semibold">Détail</th>
                <th className="border-b border-border px-3 py-2 font-semibold">Adresse</th>
              </tr>
            </thead>
            <tbody>
              {query.data.map((event) => (
                <tr key={event.id}>
                  <td className="border-b border-border-subtle px-3 py-2.5 whitespace-nowrap tabular-nums">{formatDateTime(new Date(event.at))}</td>
                  <td className="border-b border-border-subtle px-3 py-2.5">{event.operatorLogin ?? "—"}</td>
                  <td className="border-b border-border-subtle px-3 py-2.5">
                    <span
                      className={cn(
                        "rounded-full px-2.5 py-0.5 text-small font-semibold whitespace-nowrap",
                        event.action === "CONNEXION_ECHEC" ? "bg-danger-100 text-danger-700" : "bg-lagon-100 text-lagon-700",
                      )}
                    >
                      {actionLabels[event.action] ?? event.action}
                    </span>
                  </td>
                  <td className="border-b border-border-subtle px-3 py-2.5 text-small text-muted-foreground">
                    {[event.target, detailText(event.detail)].filter(Boolean).join(" · ") || "—"}
                  </td>
                  <td className="border-b border-border-subtle px-3 py-2.5 text-small text-muted-foreground tabular-nums">{event.ip ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function detailText(detail: string | null): string | null {
  if (!detail) {
    return null;
  }
  try {
    const parsed = JSON.parse(detail) as Record<string, unknown>;
    return Object.entries(parsed).map(([k, v]) => `${k} : ${String(v)}`).join(", ");
  } catch {
    return detail;
  }
}

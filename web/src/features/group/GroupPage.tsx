import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Building2, ExternalLink } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { api, errorMessage } from "@/lib/api";
import { formatDate, formatTime, isoToDateFr } from "@/lib/datetime";
import { formatXpf } from "@/lib/format";

type Creche = {
  name: string; url: string | null; activeChildren: number; places: number | null; occupancyPercent: number | null;
  invoicedLastMonth: number; outstanding: number; ratioBreaches7d: number; asOf: string; self: boolean; lastSeenAt: string | null;
};
type GroupView = { joined: boolean; joinedAt: string | null; joinedBy: string | null; creches: Creche[]; error: string | null };

const key = ["monitoring", "group"] as const;

/**
 * Vue groupe (lot 7) : pour un gestionnaire de plusieurs crèches, les indicateurs agrégés de chaque crèche du groupe
 * (occupation, facturé, reste dû, alertes d'encadrement), avec un lien vers chacune ; chaque crèche garde ses comptes
 * et ses données. Rien n'est partagé sans le consentement de chaque direction. Écart : pas de maquette dédiée.
 */
export function GroupPage() {
  const queryClient = useQueryClient();
  const group = useQuery({ queryKey: key, queryFn: ({ signal }) => api<GroupView>("/monitoring/group", { signal }) });
  const [code, setCode] = useState("");
  const [consent, setConsent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = async (action: () => Promise<GroupView>) => {
    setError(null);
    try {
      queryClient.setQueryData(key, await action());
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const data = group.data;
  return (
    <>
      <div className="flex flex-col gap-1">
        <h1 className="m-0 font-display text-display">Vue groupe</h1>
        <p className="m-0 text-muted-foreground">Les chiffres clés des crèches de votre groupe, sans aucune donnée sur les enfants, les familles ou le personnel.</p>
      </div>
      {error && <Alert>{error}</Alert>}
      {group.isError && <Alert>{errorMessage(group.error)}</Alert>}

      {data && !data.joined && (
        <section className="flex max-w-2xl flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 className="m-0 flex items-center gap-2 text-title-3"><Building2 aria-hidden className="size-5" />Rejoindre un groupe</h2>
          <p className="m-0">
            Saisissez le code commun que vous a donné le gestionnaire du groupe. La crèche partagera chaque quart d’heure : son
            nom et son adresse, le nombre d’enfants et de places, l’occupation des 30 derniers jours, le montant facturé aux
            familles le mois dernier, le reste dû et les alertes d’encadrement des 7 derniers jours.
          </p>
          <FormField label="Code du groupe"><Input value={code} maxLength={64} autoComplete="off" onChange={(e) => setCode(e.target.value)} /></FormField>
          <label className="flex items-start gap-2">
            <input type="checkbox" className="mt-1 size-5" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
            <span>En tant que direction, j’accepte de partager ces chiffres avec les autres crèches du groupe. Je peux arrêter à tout moment.</span>
          </label>
          <Button className="self-start" disabled={!consent || code.trim().length < 8}
            onClick={() => void run(() => api<GroupView>("/monitoring/group", { method: "POST", body: { code: code.trim(), url: window.location.origin } }))}>
            Rejoindre le groupe
          </Button>
        </section>
      )}

      {data?.joined && (
        <>
          {data.error && <Alert>{data.error}</Alert>}
          <div className="overflow-x-auto rounded-lg border border-border-subtle bg-card shadow-1">
            <table className="w-full min-w-[760px] text-left">
              <thead className="bg-muted text-small text-muted-foreground">
                <tr><th className="px-3 py-2">Crèche</th><th className="px-3 py-2 text-right">Enfants / places</th><th className="px-3 py-2 text-right">Occupation</th>
                  <th className="px-3 py-2 text-right">Facturé le mois dernier</th><th className="px-3 py-2 text-right">Reste dû</th>
                  <th className="px-3 py-2 text-right">Alertes encadrement (7 j)</th><th className="px-3 py-2">Dernier état</th></tr>
              </thead>
              <tbody>
                {data.creches.map((c) => (
                  <tr key={c.name + c.url} className="border-t border-border-subtle">
                    <td className="px-3 py-2">
                      {c.url && !c.self ? (
                        <a href={c.url} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 font-semibold">{c.name}<ExternalLink aria-hidden className="size-4" /></a>
                      ) : <span className="font-semibold">{c.name}{c.self && <span className="font-normal text-muted-foreground"> (cette crèche)</span>}</span>}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">{c.activeChildren} / {c.places ?? "—"}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{c.occupancyPercent === null ? "—" : `${c.occupancyPercent} %`}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{formatXpf(c.invoicedLastMonth)}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{formatXpf(c.outstanding)}</td>
                    <td className={c.ratioBreaches7d > 0 ? "px-3 py-2 text-right font-semibold text-warning-700 tabular-nums" : "px-3 py-2 text-right tabular-nums"}>{c.ratioBreaches7d}</td>
                    <td className="px-3 py-2 text-small text-muted-foreground tabular-nums">
                      {c.lastSeenAt ? `${formatDate(new Date(c.lastSeenAt))} ${formatTime(new Date(c.lastSeenAt))}` : isoToDateFr(c.asOf)}
                    </td>
                  </tr>
                ))}
                {data.creches.length === 0 && <tr><td colSpan={7} className="px-3 py-4 text-muted-foreground">Pas encore de chiffres reçus : ils arrivent dans le quart d’heure.</td></tr>}
              </tbody>
            </table>
          </div>
          <p className="m-0 text-small text-muted-foreground">
            Partage accepté{data.joinedBy ? ` par ${data.joinedBy}` : ""}{data.joinedAt ? ` le ${formatDate(new Date(data.joinedAt))}` : ""}.
          </p>
          <Button variant="outline" className="self-start" onClick={() => void run(() => api<GroupView>("/monitoring/group/leave", { method: "POST" }))}>
            Quitter le groupe
          </Button>
        </>
      )}
    </>
  );
}

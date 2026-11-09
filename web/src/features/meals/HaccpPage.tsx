import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, Thermometer } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { TonePill } from "@/features/enrollment/components";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { api, apiBlob, errorMessage } from "@/lib/api";
import { formatDate, formatTime } from "@/lib/datetime";
import { selectClass } from "@/lib/formFields";
import { MealsHeader } from "./components";

type Kind = "FRIGO" | "CONGELATEUR" | "PLAT_TEMOIN" | "LIVRAISON" | "AUTRE";
type Point = { id: string; label: string; kind: Kind; minTenths: number; maxTenths: number; active: boolean };
type Reading = { id: string; pointId: string; pointLabel: string; takenAt: string; tenths: number; outOfRange: boolean; action: string | null; authorName: string };

const kindLabels: Record<Kind, string> = { FRIGO: "Réfrigérateur", CONGELATEUR: "Congélateur", PLAT_TEMOIN: "Plat témoin", LIVRAISON: "Livraison", AUTRE: "Autre" };
/** −18,0 °C ↔ −180 dixièmes */
const degrees = (tenths: number) => `${(tenths / 10).toFixed(1).replace(".", ",")} °C`;
const toTenths = (value: string) => {
  const n = Number(value.replace(",", ".").trim());
  return value.trim() && Number.isFinite(n) ? Math.round(n * 10) : null;
};
const thisMonth = () => new Date().toLocaleDateString("sv-SE", { timeZone: "Pacific/Noumea" }).slice(0, 7);

/**
 * Relevés de températures (lot 4, plan de maîtrise sanitaire) : saisie rapide par point de contrôle, mesure
 * corrective obligatoire hors seuil, export du mois pour les contrôles. Écart : pas de maquette dédiée.
 */
export function HaccpPage() {
  const user = useCurrentUser();
  const canEdit = user.role === "DIRECTION" || user.role === "CUISINE";
  const queryClient = useQueryClient();
  const month = thisMonth();
  const points = useQuery({ queryKey: ["haccp", "points"], queryFn: ({ signal }) => api<Point[]>("/haccp/points", { signal }) });
  const readings = useQuery({ queryKey: ["haccp", "readings", month], queryFn: ({ signal }) => api<Reading[]>(`/haccp/readings?month=${month}`, { signal }) });
  const [values, setValues] = useState<Record<string, string>>({});
  const [actions, setActions] = useState<Record<string, string>>({});
  const [draft, setDraft] = useState({ label: "", kind: "FRIGO" as Kind, min: "0", max: "4" });
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const active = (points.data ?? []).filter((p) => p.active);

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null);
    setMessage(null);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: ["haccp"] });
      setMessage(done);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const outOfRange = (p: Point) => {
    const t = toTenths(values[p.id] ?? "");
    return t !== null && (t < p.minTenths || t > p.maxTenths);
  };

  const download = async () => {
    const { blob } = await apiBlob(`/haccp/readings/${month}.csv`);
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = `releves-temperatures-${month}.csv`;
    link.click();
  };

  return (
    <>
      <MealsHeader />
      {message && <p role="status" className="m-0 rounded-md bg-success-100 px-3 py-2 text-success-700">{message}</p>}
      {error && <Alert>{error}</Alert>}

      <section aria-labelledby="releve" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 id="releve" className="m-0 flex items-center gap-2 text-title-3"><Thermometer aria-hidden className="size-5" />Noter un relevé</h2>
        {active.length === 0 && <p className="m-0 text-muted-foreground">Aucun point de contrôle : la direction ou la cuisine les ajoute ci-dessous.</p>}
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {active.map((p) => (
            <li key={p.id} className="flex flex-wrap items-end gap-3 border-b border-border-subtle pb-3">
              <span className="w-64">
                <strong className="font-semibold">{p.label}</strong>
                <span className="block text-small text-muted-foreground">{kindLabels[p.kind]} · entre {degrees(p.minTenths)} et {degrees(p.maxTenths)}</span>
              </span>
              <FormField label={`Température de ${p.label}`} className="w-40">
                <Input inputMode="decimal" placeholder="3,5" value={values[p.id] ?? ""} onChange={(e) => setValues({ ...values, [p.id]: e.target.value })} />
              </FormField>
              {outOfRange(p) && (
                <FormField label="Mesure corrective" className="min-w-64 grow">
                  <Input value={actions[p.id] ?? ""} maxLength={300} onChange={(e) => setActions({ ...actions, [p.id]: e.target.value })} />
                </FormField>
              )}
              <Button disabled={toTenths(values[p.id] ?? "") === null || (outOfRange(p) && !actions[p.id]?.trim())}
                onClick={() => void run(async () => {
                  await api("/haccp/readings", { method: "POST", body: { pointId: p.id, tenths: toTenths(values[p.id]), action: actions[p.id]?.trim() || null } });
                  setValues({ ...values, [p.id]: "" });
                  setActions({ ...actions, [p.id]: "" });
                }, `Relevé noté pour ${p.label}.`)}>Noter</Button>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="releves-mois" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-wrap items-center gap-3">
          <h2 id="releves-mois" className="m-0 grow text-title-3">Relevés du mois</h2>
          <Button variant="outline" size="sm" onClick={() => void download().catch((e) => setError(errorMessage(e)))}><Download aria-hidden />Exporter pour les contrôles</Button>
        </div>
        {(readings.data ?? []).length === 0 ? <p className="m-0 text-muted-foreground">Aucun relevé ce mois-ci.</p> : (
          <table className="w-full text-small">
            <thead><tr className="text-left text-muted-foreground"><th className="py-1">Date</th><th>Point</th><th className="text-right">Température</th><th>État</th><th>Mesure corrective</th><th>Par</th></tr></thead>
            <tbody>
              {readings.data!.slice().reverse().map((r) => (
                <tr key={r.id} className="border-t border-border-subtle">
                  <td className="py-1 tabular-nums">{formatDate(new Date(r.takenAt))} {formatTime(new Date(r.takenAt))}</td>
                  <td>{r.pointLabel}</td>
                  <td className="text-right tabular-nums">{degrees(r.tenths)}</td>
                  <td>{r.outOfRange ? <TonePill tone="danger">Hors seuil</TonePill> : <TonePill tone="success">Conforme</TonePill>}</td>
                  <td>{r.action ?? ""}</td>
                  <td>{r.authorName}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {canEdit && (
        <section aria-labelledby="points" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 id="points" className="m-0 text-title-3">Points de contrôle</h2>
          <div className="flex flex-wrap items-end gap-3">
            <FormField label="Nom" className="min-w-56 grow"><Input value={draft.label} maxLength={80} onChange={(e) => setDraft({ ...draft, label: e.target.value })} /></FormField>
            <FormField label="Type" className="w-48">
              <select className={selectClass} value={draft.kind} onChange={(e) => setDraft({ ...draft, kind: e.target.value as Kind })}>
                {(Object.keys(kindLabels) as Kind[]).map((k) => <option key={k} value={k}>{kindLabels[k]}</option>)}
              </select>
            </FormField>
            <FormField label="Minimum (°C)" className="w-32"><Input inputMode="decimal" value={draft.min} onChange={(e) => setDraft({ ...draft, min: e.target.value })} /></FormField>
            <FormField label="Maximum (°C)" className="w-32"><Input inputMode="decimal" value={draft.max} onChange={(e) => setDraft({ ...draft, max: e.target.value })} /></FormField>
            <Button disabled={!draft.label.trim() || toTenths(draft.min) === null || toTenths(draft.max) === null}
              onClick={() => void run(async () => {
                await api("/haccp/points", { method: "POST", body: { label: draft.label.trim(), kind: draft.kind, minTenths: toTenths(draft.min), maxTenths: toTenths(draft.max) } });
                setDraft({ label: "", kind: "FRIGO", min: "0", max: "4" });
              }, "Point de contrôle ajouté.")}>Ajouter</Button>
          </div>
          <ul className="m-0 flex list-none flex-col gap-1 p-0">
            {active.map((p) => (
              <li key={p.id} className="flex items-center gap-3">
                <span className="grow">{p.label} · {kindLabels[p.kind]} · {degrees(p.minTenths)} à {degrees(p.maxTenths)}</span>
                <Button size="sm" variant="ghost" onClick={() => void run(() => api(`/haccp/points/${p.id}`, { method: "DELETE" }), "Point retiré (ses relevés restent).")}>Ne plus relever</Button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}

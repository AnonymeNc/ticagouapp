import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Play, ShieldCheck } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { TonePill } from "@/features/enrollment/components";
import { errorMessage } from "@/lib/api";
import { formatDate, formatTime, isoToDateFr } from "@/lib/datetime";
import { formatBytes, systemApi, systemKeys, type BackupRun } from "./systemApi";

const stamp = (iso: string) => `${formatDate(new Date(iso))} · ${formatTime(new Date(iso))}`;
const duration = (r: BackupRun) => {
  if (!r.finishedAt) return "—";
  const s = Math.round((Date.parse(r.finishedAt) - Date.parse(r.startedAt)) / 1000);
  return s < 60 ? `${s} s` : `${Math.round(s / 60)} min`;
};
const kindLabel = (r: BackupRun) =>
  r.kind === "AUTOMATIQUE" ? "Automatique" : r.kind === "AVANT_MIGRATION" ? "Avant mise à jour"
    : r.kind === "TEST" ? `Test de restauration · ${r.author ?? ""}` : `Manuelle · ${r.author ?? ""}`;

/**
 * Paramètres › Sauvegardes (maquette) : dernière sauvegarde, prochaine, conservation, chiffrement, test de restauration
 * et historique. Direction seulement. Le dossier, l'heure et la clé de secours sont gérés par Ti'Cagou (ADR 0013).
 */
export function BackupsTab() {
  const queryClient = useQueryClient();
  const overview = useQuery({
    queryKey: systemKeys.backups,
    queryFn: ({ signal }) => systemApi.backups(signal),
    // Pendant une sauvegarde ou un test, l'historique se rafraîchit seul.
    refetchInterval: (q) => (q.state.data?.running ? 2000 : false),
  });
  const data = overview.data;
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const run = async (action: () => Promise<unknown>, done?: string) => {
    setError(null);
    setMessage(null);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: systemKeys.backups });
      if (done) setMessage(done);
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  if (!data) return overview.isError ? <Alert>{errorMessage(overview.error)}</Alert> : <p className="text-muted-foreground">Chargement…</p>;
  const last = data.lastSuccess;
  const lastFailed = data.history.find((r) => r.kind === "AUTOMATIQUE" || r.kind === "MANUELLE")?.status === "ECHEC";

  return (
    <>
      <section className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="m-0 text-title-3">Sauvegardes</h2>
            <p className="m-0 text-muted-foreground">Vos données sont sauvegardées chaque nuit, chiffrées et copiées hors site par Ti’Cagou.</p>
          </div>
          <div className="flex gap-2">
            <Button variant="outline" disabled={data.running || !last} onClick={() => void run(systemApi.restoreTest, "Test de restauration lancé.")}><ShieldCheck aria-hidden />Tester une restauration</Button>
            <Button disabled={data.running} onClick={() => void run(systemApi.runBackup, "Sauvegarde lancée.")}><Play aria-hidden />Sauvegarder maintenant</Button>
          </div>
        </div>
        {data.stale && (
          <Alert>
            {last ? `Aucune sauvegarde réussie depuis le ${stamp(last.startedAt)}.` : "Aucune sauvegarde réussie depuis l’installation."}
            {" "}Ti’Cagou est prévenu ; vous pouvez lancer une sauvegarde maintenant.
          </Alert>
        )}
        {!data.toolsAvailable && <Alert>Les sauvegardes ne peuvent pas tourner : contactez Ti’Cagou.</Alert>}
        {error && <Alert>{error}</Alert>}
        {message && <p role="status" className="m-0 text-success-700">{message}</p>}
        <dl className="m-0 grid gap-4 md:grid-cols-4">
          <div className="flex flex-col gap-1">
            <dt className="text-label text-muted-foreground">Dernière sauvegarde réussie</dt>
            <dd className="m-0">{last ? stamp(last.startedAt) : "Aucune"}</dd>
            {last && <dd className="m-0 text-small text-muted-foreground">{last.sizeBytes ? formatBytes(last.sizeBytes) : ""} · durée {duration(last)}</dd>}
            {lastFailed && <dd className="m-0"><TonePill tone="danger">Dernière tentative en échec</TonePill></dd>}
            {data.running && <dd className="m-0"><TonePill tone="info">En cours…</TonePill></dd>}
          </div>
          <div className="flex flex-col gap-1">
            <dt className="text-label text-muted-foreground">Prochaine</dt>
            <dd className="m-0">{data.nextRun ? stamp(data.nextRun) : "Désactivée"}</dd>
          </div>
          <div className="flex flex-col gap-1">
            <dt className="text-label text-muted-foreground">Conservation · chiffrement</dt>
            <dd className="m-0">{data.keepDays} jours · {data.keepWeeks} semaines · {data.keepMonths} mois</dd>
            <dd className="m-0 text-small text-muted-foreground">Activé · AES-256</dd>
          </div>
          <div className="flex flex-col gap-1">
            <dt className="text-label text-muted-foreground">Dernier test</dt>
            <dd className="m-0">{data.lastTestOn ? isoToDateFr(data.lastTestOn) : "Jamais"}</dd>
            {data.lastTestOn && <dd className="m-0"><TonePill tone={data.lastTestOk ? "success" : "danger"}>{data.lastTestOk ? "Réussi" : "Échoué"}</TonePill></dd>}
          </div>
        </dl>
        <p className="m-0 text-small text-muted-foreground">Un test mensuel vérifie que les sauvegardes sont lisibles et complètes, sans toucher aux données en service.</p>
      </section>

      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 className="m-0 text-title-3">Historique</h2>
        {data.history.length === 0 ? <p className="m-0 text-muted-foreground">Aucune sauvegarde pour l’instant.</p> : (
          <table className="w-full border-collapse text-left tabular-nums">
            <thead>
              <tr className="border-b border-border-subtle text-label text-muted-foreground">
                <th scope="col" className="py-2">Date</th><th scope="col" className="py-2">Déclenchement</th>
                <th scope="col" className="py-2">Taille</th><th scope="col" className="py-2">Durée</th><th scope="col" className="py-2">Résultat</th>
              </tr>
            </thead>
            <tbody>
              {data.history.map((r) => (
                <tr key={r.id} className="border-b border-border-subtle last:border-0 align-top">
                  <td className="py-2">{stamp(r.startedAt)}</td>
                  <td className="py-2">{kindLabel(r)}</td>
                  <td className="py-2">{r.sizeBytes ? formatBytes(r.sizeBytes) : "—"}</td>
                  <td className="py-2">{duration(r)}</td>
                  <td className="py-2">
                    <TonePill tone={r.status === "REUSSIE" ? "success" : r.status === "ECHEC" ? "danger" : "info"}>
                      {r.status === "REUSSIE" ? "Réussie" : r.status === "ECHEC" ? "Échec" : "En cours"}
                    </TonePill>
                    {r.detail && <span className="block text-small text-muted-foreground">{r.detail}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

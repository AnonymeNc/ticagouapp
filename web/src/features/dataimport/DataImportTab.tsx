import { DesktopRecommended } from "@/components/DesktopRecommended";
import { useState } from "react";
import { Link } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import { Download, FileSearch, Upload } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { severityLabels } from "@/features/children/api";
import { TonePill } from "@/features/enrollment/components";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { dataImportApi, type ChildReview, type ImportCounts, type ImportIssue, type ImportPreview } from "./api";

const plural = (n: number, one: string, many: string) => `${n} ${n > 1 ? many : one}`;
const where = (i: ImportIssue) => (i.row === null ? i.sheet : `${i.sheet}, ligne ${i.row}`);

function Issues({ issues }: { issues: ImportIssue[] }) {
  return (
    <ul className="m-0 flex list-none flex-col gap-1 p-0">
      {issues.map((i, index) => (
        <li key={index}><span className="font-semibold">{where(i)}</span> : {i.message}</li>
      ))}
    </ul>
  );
}

function ReviewRow({ child }: { child: ChildReview }) {
  return (
    <tr className="border-b border-border-subtle align-top">
      <td className="py-2 pr-3">
        <span className="font-semibold">{child.firstName} {child.lastName}</span>
        <span className="block text-small text-muted-foreground">{isoToDateFr(child.birthDate)}{child.section ? ` · ${child.section}` : ""}</span>
      </td>
      <td className="py-2 pr-3">
        {child.allergies.length === 0 ? <span className="text-muted-foreground">Aucune</span> : (
          <div className="flex flex-col gap-1">
            {child.allergies.map((a) => (
              <TonePill key={a.allergen} tone={a.severity === "SEVERE" ? "danger" : "warning"}>
                {a.allergen} : {severityLabels[a.severity].toLowerCase()}
              </TonePill>
            ))}
          </div>
        )}
      </td>
      <td className="py-2 pr-3">
        {child.pai ? <TonePill tone="danger">PAI{child.paiReason ? ` : ${child.paiReason}` : ""}</TonePill> : <span className="text-muted-foreground">Pas de PAI</span>}
        {child.diet && <span className="mt-1 block">{child.diet}</span>}
      </td>
      <td className="py-2">
        <ul className="m-0 list-none p-0">{child.pickupPersons.map((p) => <li key={p}>{p}</li>)}</ul>
      </td>
    </tr>
  );
}

/**
 * Paramètres › Reprise des données (ADR 0016) : modèle à remplir, aperçu sans écriture, relecture de la santé et des personnes
 * autorisées, puis import tout ou rien. Direction seulement. Pas de maquette : mise en page des autres rubriques système.
 */
export function DataImportTab() {
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [inputKey, setInputKey] = useState(0);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [reviewed, setReviewed] = useState(false);
  const [done, setDone] = useState<ImportCounts | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  const choose = (chosen: File | null) => {
    setFile(chosen);
    setPreview(null);
    setReviewed(false);
    setDone(null);
    setError(null);
  };

  const analyse = (chosen: File) => run(async () => {
    setReviewed(false);
    setPreview(await dataImportApi.preview(chosen));
  });

  const commit = (chosen: File, fileHash: string) => run(async () => {
    const counts = await dataImportApi.commit(chosen, fileHash);
    setDone(counts);
    setPreview(null);
    setFile(null);
    setInputKey((k) => k + 1);
    await queryClient.invalidateQueries({ queryKey: ["children"] });
  });

  const canImport = preview !== null && preview.errors.length === 0;

  return (
    <>
      <DesktopRecommended what="La reprise de fichiers" />
      <section className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="m-0 text-title-3">Reprise des données</h2>
            <p className="m-0 text-muted-foreground">
              Reprenez vos familles et vos enfants depuis votre ancien fichier. Rien n’est importé avant votre relecture.
            </p>
          </div>
          <Button variant="outline" disabled={busy} onClick={() => void run(dataImportApi.downloadTemplate)}><Download aria-hidden />Télécharger le modèle</Button>
        </div>
        <p className="m-0 text-small text-muted-foreground">
          Ce fichier contient des données de santé : ne l’envoyez pas par e-mail, et supprimez-le de votre poste après la reprise.
          Les contrats, les factures et les pointages passés ne sont pas repris.
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <Input
            key={inputKey}
            type="file"
            aria-label="Fichier de reprise"
            accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            className="max-w-sm"
            // Le fichier corrigé porte le même nom : sans cette remise à zéro, le navigateur ne signalerait aucun changement.
            onClick={(e) => {
              e.currentTarget.value = "";
              choose(null);
            }}
            onChange={(e) => choose(e.target.files?.[0] ?? null)}
          />
          <Button disabled={!file || busy} onClick={() => file && void analyse(file)}><FileSearch aria-hidden />Analyser le fichier</Button>
        </div>
        {error && <Alert>{error}</Alert>}
        {done && (
          <div role="status" className="flex flex-col gap-1 text-success-700">
            <p className="m-0 font-semibold">
              Reprise terminée : {plural(done.children, "enfant", "enfants")} et {plural(done.families, "famille", "familles")} importés.
            </p>
            <p className="m-0">Supprimez le fichier de votre poste, puis ressaisissez les contrats en cours. <Link to="/enfants" className="underline">Voir les enfants</Link></p>
          </div>
        )}
      </section>

      {preview && preview.errors.length > 0 && (
        <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 className="m-0 text-title-3">{plural(preview.errors.length, "erreur à corriger", "erreurs à corriger")}</h2>
          <p className="m-0 text-muted-foreground">Rien n’est importé tant qu’il reste une erreur. Corrigez le fichier, enregistrez-le, puis choisissez-le de nouveau.</p>
          <div role="alert" className="rounded-md bg-danger-100 px-4 py-3 text-danger-700"><Issues issues={preview.errors} /></div>
        </section>
      )}

      {preview && preview.warnings.length > 0 && (
        <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 className="m-0 text-title-3">{plural(preview.warnings.length, "point à vérifier", "points à vérifier")}</h2>
          <div className="rounded-md bg-warning-100 px-4 py-3 text-warning-700"><Issues issues={preview.warnings} /></div>
        </section>
      )}

      {canImport && (
        <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <div>
            <h2 className="m-0 text-title-3">Relecture</h2>
            <p className="m-0 text-muted-foreground">
              {plural(preview.counts.families, "famille", "familles")}, {plural(preview.counts.guardians, "responsable", "responsables")},{" "}
              {plural(preview.counts.children, "enfant", "enfants")}, {plural(preview.counts.allergies, "allergie", "allergies")},{" "}
              {plural(preview.counts.pais, "PAI", "PAI")}, {plural(preview.counts.authorizedPersons, "personne autorisée", "personnes autorisées")}.
            </p>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] border-collapse text-left">
              <thead>
                <tr className="border-b border-border-subtle text-label text-muted-foreground">
                  <th scope="col" className="py-2 pr-3">Enfant</th><th scope="col" className="py-2 pr-3">Allergies</th>
                  <th scope="col" className="py-2 pr-3">PAI et régime</th><th scope="col" className="py-2">Personnes pour le départ</th>
                </tr>
              </thead>
              <tbody>{preview.children.map((c) => <ReviewRow key={c.reference} child={c} />)}</tbody>
            </table>
          </div>
          <label className="flex items-start gap-2">
            <input type="checkbox" className="mt-1" checked={reviewed} onChange={(e) => setReviewed(e.target.checked)} />
            J’ai relu les allergies, les PAI et les personnes autorisées de chaque enfant.
          </label>
          <div>
            <Button disabled={!reviewed || busy || !file} onClick={() => file && void commit(file, preview.fileHash)}>
              <Upload aria-hidden />Importer {plural(preview.counts.children, "enfant", "enfants")}
            </Button>
          </div>
        </section>
      )}
    </>
  );
}

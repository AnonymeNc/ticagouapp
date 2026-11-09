import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Globe, RefreshCw, ShieldCheck } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { TonePill } from "@/features/enrollment/components";
import { errorMessage } from "@/lib/api";
import { formatDate, formatTime } from "@/lib/datetime";
import { portalApi, portalKeys, type Shares } from "./api";
import { SignaturesToRecord } from "./SignaturesToRecord";
import { BookingRulesSection } from "./BookingRulesSection";

const stamp = (iso: string | null) => (iso ? `${formatDate(new Date(iso))} · ${formatTime(new Date(iso))}` : "—");

const shareLabels: { key: keyof Omit<Shares, "paymentInfo" | "mealAbsenceCutoff">; label: string; hint: string }[] = [
  { key: "entries", label: "Transmissions du jour", hint: "Arrivée, départ, repas, sieste, change, activités, mots." },
  { key: "photos", label: "Photos", hint: "Seulement celles marquées « à partager », et si chaque enfant photographié est autorisé." },
  { key: "invoices", label: "Factures et avoirs", hint: "Montants, reste dû et PDF des factures validées." },
  { key: "documents", label: "Documents à signer", hint: "Contrats, avenants et autorisations que vous proposez." },
  { key: "messages", label: "Messages et actualités", hint: "Échanges avec les familles, actualités de la crèche." },
  { key: "absences", label: "Absences signalées par les parents", hint: "À confirmer ici avant qu’elles comptent en facturation." },
];

/**
 * Paramètres › Portail parents (ADR 0010) : activation avec le code de l'hébergeur, ce qui est partagé, état de la
 * synchronisation et désactivation, qui efface tout ce que le portail détient. Écart : pas de maquette pour cet écran,
 * il reprend la présentation des autres rubriques des Paramètres.
 */
export function PortalTab() {
  const queryClient = useQueryClient();
  const status = useQuery({ queryKey: portalKeys.status, queryFn: ({ signal }) => portalApi.status(signal), refetchInterval: 30_000 });
  const data = status.data;
  const [url, setUrl] = useState("");
  const [code, setCode] = useState("");
  const [shares, setShares] = useState<Shares | null>(null);
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  useEffect(() => {
    if (data) setShares(data.shares);
  }, [data?.shares.entries, data?.shares.photos, data?.shares.invoices, data?.shares.documents, data?.shares.messages,
    data?.shares.absences, data?.shares.paymentInfo, data?.shares.mealAbsenceCutoff]); // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (action: () => Promise<unknown>, done?: string) => {
    setError(null);
    setMessage(null);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: portalKeys.status });
      if (done) setMessage(done);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  if (!data || !shares) return status.isError ? <Alert>{errorMessage(status.error)}</Alert> : <p className="text-muted-foreground">Chargement…</p>;

  return (
    <div className="flex flex-col gap-5">
      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex flex-col gap-1">
            <h2 className="m-0 text-title-3">Portail parents</h2>
            <p className="m-0 max-w-3xl text-muted-foreground">
              Application en ligne pour les familles. Le logiciel y envoie seulement ce que vous partagez ci-dessous ; aucune
              information de santé n’en sort, sauf celle que vous partagez vous-même depuis la fiche d’un enfant. Sans
              internet, la crèche fonctionne normalement : l’envoi reprend au retour de la connexion.
            </p>
          </div>
          <TonePill tone={data.state === "ACTIF" ? "success" : data.state === "PURGE_EN_COURS" ? "warning" : "neutral"}>
            {data.state === "ACTIF" ? "Activé" : data.state === "PURGE_EN_COURS" ? "Effacement en cours" : "Désactivé"}
          </TonePill>
        </div>

        {!data.moduleIncluded && (
          <Alert>Le module Portail parents n’est pas compris dans votre abonnement. Rien n’est envoyé.</Alert>
        )}

        {data.state === "DESACTIVE" && data.moduleIncluded && (
          <form
            className="flex flex-wrap items-end gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              void run(() => portalApi.activate(url.trim(), code.trim()), "Portail activé.");
            }}
          >
            <FormField label="Adresse du portail" hint="Fournie par l’hébergeur, en https://" className="min-w-80 flex-1">
              <Input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="https://parents.ticagou.nc" required />
            </FormField>
            <FormField label="Code d’activation" hint="À usage unique" className="w-56">
              <Input value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} placeholder="XXXX-XXXX-XXXX" required />
            </FormField>
            <Button type="submit">
              <Globe aria-hidden />
              Activer le portail
            </Button>
          </form>
        )}

        {data.state !== "DESACTIVE" && (
          <dl className="m-0 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1.5">
            <dt className="text-muted-foreground">Adresse</dt>
            <dd className="m-0">{data.portalUrl}</dd>
            <dt className="text-muted-foreground">Activé le</dt>
            <dd className="m-0">{stamp(data.activatedAt)}</dd>
            <dt className="text-muted-foreground">Dernier envoi réussi</dt>
            <dd className="m-0">{stamp(data.lastSuccessAt)}</dd>
            <dt className="text-muted-foreground">En attente d’envoi</dt>
            <dd className="m-0 tabular-nums">{data.pending}</dd>
            <dt className="text-muted-foreground">Responsables invités</dt>
            <dd className="m-0 tabular-nums">{data.invitedGuardians}</dd>
          </dl>
        )}
        {data.lastError && data.state !== "DESACTIVE" && (
          <p role="alert" className="m-0 rounded-md bg-warning-100 px-4 py-3 text-warning-700">
            Dernière tentative le {stamp(data.lastAttemptAt)} : {data.lastError}
          </p>
        )}
        {data.state !== "DESACTIVE" && (
          <Button variant="outline" className="self-start" onClick={() => void run(async () => {
            const result = await portalApi.sync();
            if (result.error) throw new Error(result.error);
            setMessage(`Synchronisé : ${result.sent} envoi(s), ${result.received} élément(s) reçu(s).`);
          })}>
            <RefreshCw aria-hidden />
            Synchroniser maintenant
          </Button>
        )}
        {error && <Alert>{error}</Alert>}
        {message && <p role="status" className="m-0 rounded-md bg-success-100 px-4 py-3 text-success-700">{message}</p>}
      </section>

      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 className="m-0 text-title-3">Ce qui est partagé avec les familles</h2>
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {shareLabels.map(({ key, label, hint }) => (
            <li key={key}>
              <label className="flex items-start gap-3">
                <input type="checkbox" className="mt-1 size-5" checked={shares[key]}
                  onChange={(e) => setShares({ ...shares, [key]: e.target.checked })} />
                <span className="flex flex-col">
                  <span className="font-semibold">{label}</span>
                  <span className="text-small text-muted-foreground">{hint}</span>
                </span>
              </label>
            </li>
          ))}
        </ul>
        <div className="flex flex-wrap gap-4">
          <FormField label="Coordonnées de paiement affichées" hint="Vide : l’IBAN de la fiche Structure" className="min-w-80 flex-1">
            <Input value={shares.paymentInfo ?? ""} maxLength={300} onChange={(e) => setShares({ ...shares, paymentInfo: e.target.value })} />
          </FormField>
          <FormField
            label="Repas non facturé si l’absence est signalée avant"
            hint="Vide : vous ne le pratiquez pas, et l’application des parents n’en parle pas"
            className="w-80"
          >
            <Input type="time" value={shares.mealAbsenceCutoff ?? ""}
              onChange={(e) => setShares({ ...shares, mealAbsenceCutoff: e.target.value || null })} />
          </FormField>
        </div>
        <Button className="self-start" onClick={() => void run(() => portalApi.shares({
          ...shares, paymentInfo: shares.paymentInfo?.trim() || null, mealAbsenceCutoff: shares.mealAbsenceCutoff || null,
        }), "Partage enregistré.")}>
          <ShieldCheck aria-hidden />
          Enregistrer
        </Button>
      </section>

      {data.state === "ACTIF" && <BookingRulesSection />}

      {data.state === "ACTIF" && <SignaturesToRecord />}

      {data.state === "ACTIF" && (
        <section className="flex flex-col gap-3 rounded-lg border border-danger-100 bg-card p-5 shadow-1">
          <h2 className="m-0 text-title-3">Désactiver le portail</h2>
          <p className="m-0 max-w-3xl text-muted-foreground">
            Plus rien n’est envoyé, et le portail efface tout ce qu’il détient pour la crèche : comptes des parents,
            transmissions, photos, factures, documents et messages. Les données de la crèche restent dans le logiciel.
          </p>
          <div className="flex flex-wrap items-end gap-3">
            <FormField label="Pour confirmer, saisissez DÉSACTIVER" className="w-72">
              <Input value={confirmation} onChange={(e) => setConfirmation(e.target.value)} />
            </FormField>
            <Button variant="destructive" disabled={confirmation.trim().toUpperCase() !== "DÉSACTIVER"}
              onClick={() => void run(async () => {
                const result = await portalApi.deactivate(confirmation);
                setConfirmation("");
                if (result.error) setMessage(`Désactivé. L’effacement sera renvoyé au retour de la connexion (${result.error}).`);
              }, "Portail désactivé et effacé.")}>
              Désactiver et effacer
            </Button>
          </div>
        </section>
      )}
    </div>
  );
}

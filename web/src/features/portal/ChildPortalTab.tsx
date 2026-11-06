import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { FileSignature, MailPlus, Share2, Trash2, UserX } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { TonePill } from "@/features/enrollment/components";
import { contractsApi } from "@/features/contracts/api";
import { entrySummary, kindLabels } from "@/features/kiosk/JournalScreen";
import { errorMessage } from "@/lib/api";
import { formatDate, formatTime, isoToDateFr } from "@/lib/datetime";
import { consentLabels, portalApi, portalKeys, type ConsentType } from "./api";

type ChildLike = {
  id: string;
  firstName: string;
  familyId: string;
  health: { allergies: { allergen: string }[]; diet: string | null; paiActive: boolean; paiReason: string | null };
};

const today = () => new Date().toLocaleDateString("sv-SE", { timeZone: "Pacific/Noumea" });
const stamp = (iso: string) => `${formatDate(new Date(iso))} · ${formatTime(new Date(iso))}`;

/**
 * Fiche enfant › Journée et portail (direction) : transmissions du jour, accès de la famille au portail, informations
 * de santé partagées explicitement (texte figé) et documents proposés à la signature. Écart : pas de maquette dédiée.
 */
export function ChildPortalTab({ child, canEdit }: { child: ChildLike; canEdit: boolean }) {
  const queryClient = useQueryClient();
  const [day, setDay] = useState(today());
  const [error, setError] = useState<string | null>(null);
  const status = useQuery({ queryKey: portalKeys.status, queryFn: ({ signal }) => portalApi.status(signal), enabled: canEdit });
  const journal = useQuery({ queryKey: portalKeys.journal(day, child.id), queryFn: ({ signal }) => portalApi.journal(day, child.id, signal) });
  const access = useQuery({ queryKey: portalKeys.family(child.familyId), queryFn: ({ signal }) => portalApi.family(child.familyId, signal), enabled: canEdit });
  const shares = useQuery({ queryKey: portalKeys.healthShares(child.id), queryFn: ({ signal }) => portalApi.healthShares(child.id, signal), enabled: canEdit });
  const documents = useQuery({ queryKey: portalKeys.documents(child.familyId), queryFn: ({ signal }) => portalApi.documents(child.familyId, signal), enabled: canEdit });
  const contracts = useQuery({ queryKey: ["contracts", child.id], queryFn: ({ signal }) => contractsApi.forChild(child.id, signal), enabled: canEdit });
  const [label, setLabel] = useState("");
  const [text, setText] = useState("");

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: ["portal"] });
      await queryClient.invalidateQueries({ queryKey: ["journal"] });
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const active = status.data?.state === "ACTIF";
  const invited = access.data?.guardians.some((g) => g.invited) ?? false;
  const suggestions = [
    ...child.health.allergies.map((a) => ({ label: "Allergie", text: a.allergen })),
    ...(child.health.diet ? [{ label: "Régime", text: child.health.diet }] : []),
    ...(child.health.paiActive ? [{ label: "PAI", text: child.health.paiReason ?? "PAI en place" }] : []),
  ];
  const unsigned = (contracts.data ?? []).flatMap((c) =>
    c.versions.filter((v) => !v.signedOn).map((v) => ({ contractId: c.id, number: v.number, label: v.number === 0 ? `Contrat ${c.number}` : `Avenant ${v.number} au contrat ${c.number}` })),
  );

  return (
    <div className="flex flex-col gap-5">
      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <h2 className="m-0 text-title-3">Transmissions</h2>
          <FormField label="Jour" className="w-48">
            <Input type="date" value={day} onChange={(e) => setDay(e.target.value)} />
          </FormField>
        </div>
        {(journal.data ?? []).length === 0 ? (
          <p className="m-0 text-muted-foreground">Aucune transmission le {isoToDateFr(day)}. Elles se saisissent sur la tablette, onglet « Journée ».</p>
        ) : (
          <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
            {journal.data!.map((e) => (
              <li key={e.id} className="flex flex-wrap items-center gap-3 border-b border-border-subtle py-1.5">
                <time className="tabular-nums text-muted-foreground">{formatTime(new Date(e.occurredAt))}</time>
                <span className="font-semibold">{kindLabels[e.kind]}</span>
                <span className="grow">{entrySummary(e)}{e.children.length > 1 && ` · avec ${e.children.filter((c) => c.id !== child.id).map((c) => c.firstName).join(", ")}`}</span>
                <span className="text-small text-muted-foreground">{e.authorName}{e.shared ? " · partagé" : " · non partagé"}</span>
                <Button variant="ghost" size="sm" aria-label="Supprimer la transmission" onClick={() => void run(() => portalApi.deleteEntry(e.id))}>
                  <Trash2 aria-hidden />
                </Button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {canEdit && (
        <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="m-0 text-title-3">Portail parents</h2>
            <TonePill tone={active ? "success" : "neutral"}>{active ? "Activé" : "Désactivé"}</TonePill>
          </div>
          {!active && <p className="m-0 text-muted-foreground">Activez le portail dans Paramètres › Portail parents pour inviter la famille.</p>}

          <h3 className="m-0 text-title-3 text-[16px]">Accès de la famille</h3>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {(access.data?.guardians ?? []).map((g) => (
              <li key={g.guardianId} className="flex flex-wrap items-center gap-3">
                <span className="grow">
                  <strong className="font-semibold">{g.firstName} {g.lastName}</strong>
                  <span className="text-muted-foreground"> · {g.email ?? "pas d’adresse e-mail"}</span>
                  {g.invited && g.invitedAt && <span className="text-small text-muted-foreground"> · invité(e) le {formatDate(new Date(g.invitedAt))}</span>}
                </span>
                {g.invited ? (
                  <Button variant="outline" size="sm" onClick={() => void run(() => portalApi.revoke(g.guardianId))}>
                    <UserX aria-hidden />
                    Retirer l’accès
                  </Button>
                ) : (
                  <Button size="sm" disabled={!active || !g.email} onClick={() => void run(() => portalApi.invite(g.guardianId))}>
                    <MailPlus aria-hidden />
                    Inviter au portail
                  </Button>
                )}
              </li>
            ))}
          </ul>

          <h3 className="m-0 text-[16px] font-semibold">Santé partagée avec la famille</h3>
          <p className="m-0 text-small text-muted-foreground">
            Rien de la fiche santé n’est envoyé au portail. Seul le texte écrit ici l’est, tel quel ; il ne change pas si la fiche change.
          </p>
          {(shares.data ?? []).map((s) => (
            <div key={s.id} className="flex flex-wrap items-center gap-3 rounded-md bg-muted px-3 py-2">
              <span className="grow"><strong className="font-semibold">{s.label}</strong> : {s.text}
                <span className="text-small text-muted-foreground"> · {s.sharedBy}, {stamp(s.sharedAt)}</span></span>
              <Button variant="ghost" size="sm" onClick={() => void run(() => portalApi.unshareHealth(s.id))}>Ne plus partager</Button>
            </div>
          ))}
          {suggestions.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {suggestions.map((s) => (
                <Button key={s.label + s.text} variant="outline" size="sm" onClick={() => { setLabel(s.label); setText(s.text); }}>{s.label} : {s.text}</Button>
              ))}
            </div>
          )}
          <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => {
            e.preventDefault();
            void run(async () => {
              await portalApi.shareHealth(child.id, { label: label.trim(), text: text.trim() });
              setLabel("");
              setText("");
            });
          }}>
            <FormField label="Intitulé" className="w-40"><Input value={label} maxLength={60} onChange={(e) => setLabel(e.target.value)} required /></FormField>
            <FormField label="Texte montré à la famille" className="min-w-72 flex-1"><Input value={text} maxLength={300} onChange={(e) => setText(e.target.value)} required /></FormField>
            <Button type="submit" variant="outline" disabled={!active}><Share2 aria-hidden />Partager</Button>
          </form>

          <h3 className="m-0 text-[16px] font-semibold">Documents à signer sur le portail</h3>
          <div className="flex flex-wrap gap-2">
            {(Object.keys(consentLabels) as ConsentType[]).map((type) => (
              <Button key={type} variant="outline" size="sm" disabled={!active || !invited}
                onClick={() => void run(() => portalApi.proposeConsent(child.id, type))}>
                <FileSignature aria-hidden />{consentLabels[type]}
              </Button>
            ))}
            {unsigned.map((u) => (
              <Button key={`${u.contractId}-${u.number}`} variant="outline" size="sm" disabled={!active || !invited}
                onClick={() => void run(() => portalApi.proposeContract(u.contractId, u.number))}>
                <FileSignature aria-hidden />{u.label}
              </Button>
            ))}
          </div>
          {(documents.data ?? []).filter((d) => d.childId === child.id).map((d) => (
            <div key={d.id} className="flex flex-wrap items-center gap-3 border-b border-border-subtle py-1.5">
              <span className="grow">{d.title}<span className="text-small text-muted-foreground"> · proposé le {formatDate(new Date(d.proposedAt))}</span></span>
              <TonePill tone={d.status === "ENREGISTRE" ? "success" : d.status === "SIGNE" ? "info" : d.status === "RETIRE" ? "neutral" : "warning"}>
                {{ A_SIGNER: "À signer", SIGNE: "Signé, à enregistrer", ENREGISTRE: "Signature enregistrée", RETIRE: "Retiré" }[d.status]}
              </TonePill>
              {d.status === "SIGNE" && <Button size="sm" onClick={() => void run(() => portalApi.record(d.id))}>Enregistrer la signature</Button>}
              {(d.status === "A_SIGNER" || d.status === "SIGNE") && (
                <Button variant="ghost" size="sm" onClick={() => void run(() => portalApi.withdraw(d.id))}>Retirer</Button>
              )}
            </div>
          ))}
        </section>
      )}
      {error && <Alert>{error}</Alert>}
    </div>
  );
}

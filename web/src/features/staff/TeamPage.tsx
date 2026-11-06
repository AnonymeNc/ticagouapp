import { useMemo, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, Plus } from "lucide-react";
import { Alert as AlertBox } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { roleLabels, type Role } from "@/features/auth/types";
import { TonePill } from "@/features/enrollment/components";
import { usersApi } from "@/features/settings/api";
import { useSections } from "@/features/structure/useSections";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { selectClass, textOrNull } from "@/lib/formFields";
import { cn } from "@/lib/utils";
import { contractLabels, staffApi, staffKeys, type ContractType, type Member } from "./api";
import { StaffHeader, alertText, initials, useSeesTeam, weekly } from "./components";

/** Personnel › Équipe (maquette « Personnel — équipe ») : chiffres clés, filtres, tableau et alertes. */
export function TeamPage() {
  const seesTeam = useSeesTeam();
  const user = useCurrentUser();
  const sections = useSections();
  const team = useQuery({ queryKey: staffKeys.team, queryFn: ({ signal }) => staffApi.team(signal), enabled: seesTeam });
  const [name, setName] = useState("");
  const [section, setSection] = useState("");
  const [contract, setContract] = useState<ContractType | "">("");
  const [withAlert, setWithAlert] = useState(false);
  const [adding, setAdding] = useState(false);
  const members = useMemo(() => (team.data?.members ?? []).filter((m) =>
    (!name || `${m.firstName} ${m.lastName}`.toLowerCase().includes(name.toLowerCase()))
    && (!section || m.homeSectionId === section)
    && (!contract || m.contractType === contract)
    && (!withAlert || m.alerts.length > 0)), [team.data, name, section, contract, withAlert]);

  if (!seesTeam) return <Navigate to="/personnel/planning" replace />;
  const data = team.data;
  const sectionName = (m: Member) => m.homeSectionId ? sections.data?.find((s) => s.id === m.homeSectionId)?.name ?? "—"
    : m.role === "DIRECTION" ? "Direction" : m.role === "CUISINE" ? "Cuisine" : "Toutes sections";

  const exportList = () => {
    const rows = [["Nom", "Prénom", "Fonction", "Diplôme", "Section", "Contrat", "Durée", "Entrée", "Fin", "Encadrement", "Alertes"]];
    for (const m of members) {
      rows.push([m.lastName, m.firstName, m.jobTitle ?? "", m.diploma ?? "", sectionName(m), contractLabels[m.contractType],
        weekly(m.weeklyMinutes), m.hiredOn ? isoToDateFr(m.hiredOn) : "", m.endsOn ? isoToDateFr(m.endsOn) : "",
        m.countsInRatio ? "oui" : "non", m.alerts.map(alertText).join(" · ")]);
    }
    const csv = "﻿" + rows.map((r) => r.map((c) => (/[;"\n]/.test(c) ? `"${c.replace(/"/g, '""')}"` : c)).join(";")).join("\r\n");
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "personnel.csv";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  return (
    <>
      <StaffHeader
        subtitle={data && `${data.people} personne${data.people > 1 ? "s" : ""}, dont ${data.inRatio} compte${data.inRatio > 1 ? "nt" : ""} dans le taux d’encadrement.`}
        actions={
          <>
            <Button variant="outline" onClick={exportList} disabled={!data}><Download aria-hidden />Exporter la liste</Button>
            {user.role === "DIRECTION" && <Button onClick={() => setAdding(true)}><Plus aria-hidden />Ajouter une personne</Button>}
          </>
        }
      />
      {team.isError && <AlertBox>{errorMessage(team.error)}</AlertBox>}
      {data && (
        <>
          <dl className="m-0 grid grid-cols-2 gap-4 md:grid-cols-4">
            <Kpi label="Personnes" value={data.people} hint={`${data.inRatio} dans l’encadrement, ${data.people - data.inRatio} hors encadrement`} />
            <Kpi label="Expirées" value={data.expired} danger={data.expired > 0}
                 hint={data.members.flatMap((m) => m.alerts.filter((a) => a.state === "EXPIREE").map((a) => `${alertText(a).split(" ")[0]} de ${m.firstName} ${m.lastName}`)).join(", ") || "Aucune"} />
            <Kpi label={`À renouveler sous ${data.alertDays} jours`} value={data.toRenew}
                 hint={data.nextDue ? `Prochaine échéance le ${isoToDateFr(data.nextDue)}` : "Aucune"} />
            <Kpi label="Formations prévues" value={data.plannedTrainings}
                 hint={data.nextTraining && data.nextTrainingOn ? `${data.nextTraining} le ${isoToDateFr(data.nextTrainingOn)}` : "Aucune"} />
          </dl>

          <section aria-labelledby="equipe" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
            <h2 id="equipe" className="sr-only">Équipe</h2>
            <div className="flex flex-wrap items-end gap-3">
              <FormField label="Nom"><Input value={name} onChange={(e) => setName(e.target.value)} className="w-48" /></FormField>
              <FormField label="Section habituelle">
                <select className={selectClass} value={section} onChange={(e) => setSection(e.target.value)}>
                  <option value="">Toutes les sections</option>
                  {sections.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              </FormField>
              <FormField label="Contrat">
                <select className={selectClass} value={contract} onChange={(e) => setContract(e.target.value as ContractType | "")}>
                  <option value="">Tous les contrats</option>
                  {Object.entries(contractLabels).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                </select>
              </FormField>
              <label className="flex h-control items-center gap-2"><input type="checkbox" checked={withAlert} onChange={(e) => setWithAlert(e.target.checked)} />Avec une alerte</label>
            </div>
            <p className="m-0 text-small text-muted-foreground">{members.length} personne{members.length > 1 ? "s" : ""}</p>
            <div className="overflow-x-auto">
              <table className="w-full border-collapse text-left">
                <caption className="sr-only">Personnel de la structure</caption>
                <thead>
                  <tr className="border-b border-border-subtle text-label text-muted-foreground">
                    <th scope="col" className="py-2 pr-3">Nom</th>
                    <th scope="col" className="py-2 pr-3">Fonction</th>
                    <th scope="col" className="py-2 pr-3">Diplôme</th>
                    <th scope="col" className="py-2 pr-3">Section habituelle</th>
                    <th scope="col" className="py-2 pr-3">Contrat</th>
                    <th scope="col" className="py-2">Alertes</th>
                  </tr>
                </thead>
                <tbody>
                  {members.map((m) => (
                    <tr key={m.userId} className="border-b border-border-subtle last:border-0 align-top">
                      <td className="py-2 pr-3">
                        <Link to={`/personnel/${m.userId}`} className="flex items-center gap-2 font-semibold text-lagon-700">
                          <span aria-hidden className="flex size-8 shrink-0 items-center justify-center rounded-full bg-lagon-100 text-caption">{initials(m.firstName, m.lastName)}</span>
                          <span>{m.firstName} {m.lastName}
                            {m.hiredOn && <span className="block text-small font-normal text-muted-foreground">Depuis le {isoToDateFr(m.hiredOn)}</span>}
                          </span>
                        </Link>
                      </td>
                      <td className="py-2 pr-3">{m.jobTitle ?? roleLabels[m.role]}</td>
                      <td className="py-2 pr-3">{m.diploma ?? "—"}</td>
                      <td className="py-2 pr-3">{sectionName(m)}</td>
                      <td className="py-2 pr-3">
                        {contractLabels[m.contractType]}
                        <span className="block text-small text-muted-foreground">
                          {m.weeklyMinutes === null ? "À l’heure" : `${weekly(m.weeklyMinutes)} par semaine`}
                          {m.endsOn && ` · jusqu’au ${isoToDateFr(m.endsOn)}`}
                          {!m.countsInRatio && " · hors encadrement"}
                        </span>
                      </td>
                      <td className="py-2">
                        {m.alerts.length === 0 ? <span className="text-muted-foreground">Aucune</span> : (
                          <div className="flex flex-col gap-1">
                            {m.alerts.map((a, i) => <TonePill key={i} tone={a.state === "EXPIREE" ? "danger" : "warning"}>{alertText(a)}</TonePill>)}
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
      {adding && <AddPersonDialog onClose={() => setAdding(false)} />}
    </>
  );
}

function Kpi({ label, value, hint, danger }: { label: string; value: number; hint: string; danger?: boolean }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-border-subtle bg-card p-4 shadow-1">
      <dt className="text-label text-muted-foreground">{label}</dt>
      <dd className={cn("m-0 text-numeric-lg tabular-nums", danger && "text-danger-700")}>{value}</dd>
      <dd className="m-0 text-small text-muted-foreground">{hint}</dd>
    </div>
  );
}

const ROLES: Role[] = ["EDUCATRICE", "CUISINE", "COMPTABILITE", "DIRECTION"];

/** Ajout d'une personne : son compte (code PIN pour la tablette) puis sa fiche. */
function AddPersonDialog({ onClose }: { onClose: () => void }) {
  const sections = useSections();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ firstName: "", lastName: "", role: "EDUCATRICE" as Role, jobTitle: "", sectionId: "", pin: "", countsInRatio: true });
  const [error, setError] = useState<string | null>(null);
  const set = (patch: Partial<typeof form>) => setForm((f) => ({ ...f, ...patch }));
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Ajouter une personne</DialogTitle>
          <DialogDescription>Elle pointera sur la tablette avec son code PIN. Un accès au PC du bureau se crée dans Paramètres › Utilisateurs.</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Prénom"><Input value={form.firstName} maxLength={80} onChange={(e) => set({ firstName: e.target.value })} /></FormField>
          <FormField label="Nom"><Input value={form.lastName} maxLength={80} onChange={(e) => set({ lastName: e.target.value })} /></FormField>
          <FormField label="Rôle">
            <select className={selectClass} value={form.role} onChange={(e) => set({ role: e.target.value as Role, countsInRatio: e.target.value === "EDUCATRICE" })}>
              {ROLES.map((r) => <option key={r} value={r}>{roleLabels[r]}</option>)}
            </select>
          </FormField>
          <FormField label="Fonction"><Input value={form.jobTitle} maxLength={80} onChange={(e) => set({ jobTitle: e.target.value })} /></FormField>
          <FormField label="Section habituelle">
            <select className={selectClass} value={form.sectionId} onChange={(e) => set({ sectionId: e.target.value })}>
              <option value="">Toutes sections</option>
              {sections.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </FormField>
          <FormField label="Code PIN (4 chiffres)"><Input value={form.pin} inputMode="numeric" maxLength={4} onChange={(e) => set({ pin: e.target.value })} /></FormField>
        </div>
        <label className="flex items-center gap-2"><input type="checkbox" checked={form.countsInRatio} onChange={(e) => set({ countsInRatio: e.target.checked })} />Compte dans le taux d’encadrement</label>
        {error && <AlertBox>{error}</AlertBox>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              if (!form.firstName.trim() || !form.lastName.trim()) return setError("Indiquez le prénom et le nom.");
              if (!/^\d{4}$/.test(form.pin)) return setError("Le code PIN compte 4 chiffres.");
              try {
                const created = await usersApi.create({
                  firstName: form.firstName.trim(), lastName: form.lastName.trim(), role: form.role, login: null,
                  jobTitle: textOrNull(form.jobTitle), homeSectionId: form.sectionId || null, countsInRatio: form.countsInRatio,
                  password: null, pin: form.pin,
                });
                await queryClient.invalidateQueries({ queryKey: ["staff"] });
                navigate(`/personnel/${created.id}`);
              } catch (e) {
                setError(errorMessage(e));
              }
            }}
          >
            Créer et compléter la fiche
          </Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

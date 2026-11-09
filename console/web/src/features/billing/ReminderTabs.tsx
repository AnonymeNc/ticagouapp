import { useState, type ReactNode } from "react";
import { Plus, ShieldAlert, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Pill } from "@/components/ui/pill";
import { formatDay } from "@/features/parc/labels";
import { errorMessage } from "@/lib/api";
import { billingApi, useBillingWrite, useReminderSettings, type ReminderRule, type ReminderSettings } from "./api";
import { today } from "./labels";

function Section({ title, aside, children }: { title: string; aside?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 className="m-0 font-display text-title-2">{title}</h2>
        {aside}
      </div>
      {children}
    </section>
  );
}

const delay = (days: number) => (days < 0 ? `J${days}` : `J+${days}`);

function current(settings: ReminderSettings) {
  const set = settings.sets.find((s) => s.validFrom <= today() && (!s.validTo || s.validTo >= today())) ?? settings.sets[0];
  return set ? { set, rules: settings.rules[set.id] ?? [] } : null;
}

/** Paramètres › Relances (maquette) : règles en vigueur, nouvelle version datée, règles par client, jours fériés. */
export function RemindersTab() {
  const query = useReminderSettings();
  const [editing, setEditing] = useState(false);
  const prepare = useBillingWrite(() => billingApi.prepareReminders());
  if (!query.data) {
    return query.isError ? <p role="alert" className="text-danger-700">{errorMessage(query.error)}</p> : <p className="text-muted-foreground">Chargement…</p>;
  }
  const now = current(query.data);
  if (editing && now) {
    return <RulesEditor base={now.rules} onDone={() => setEditing(false)} />;
  }
  return (
    <div className="grid items-start gap-4 xl:grid-cols-2">
      <Section
        title="Règles de relance"
        aside={
          <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
            <Plus aria-hidden />
            Version au…
          </Button>
        }
      >
        {now && (
          <>
            <p className="m-0 mb-2.5 text-small text-muted-foreground">
              {now.set.label}, en vigueur depuis le {formatDay(now.set.validFrom)}. Les relances sont préparées chaque jour ouvré à 6 h, jamais
              envoyées seules ; un client en retard sur plusieurs factures reçoit un seul message.
            </p>
            <RulesTable rules={now.rules} />
          </>
        )}
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <Button variant="outline" size="sm" disabled={prepare.isPending} onClick={() => prepare.mutate(undefined)}>
            Préparer maintenant
          </Button>
          {prepare.data && (
            <span role="status" className="text-small">
              {prepare.data.messages === 0 ? "Aucune relance due aujourd’hui." : `${prepare.data.messages} relance${prepare.data.messages > 1 ? "s préparées" : " préparée"} dans la boîte d’envoi.`}
            </span>
          )}
          {prepare.isError && <span role="alert" className="text-danger-700">{errorMessage(prepare.error)}</span>}
        </div>
      </Section>
      <ClientRules settings={query.data} />
      <Holidays settings={query.data} />
    </div>
  );
}

function RulesTable({ rules }: { rules: ReminderRule[] }) {
  return (
    <table className="w-full border-collapse">
      <thead className="text-left text-label text-muted-foreground">
        <tr>
          <th className="py-1.5 pr-2">Niveau</th>
          <th className="py-1.5 pr-2 text-right">Délai</th>
          <th className="py-1.5 pr-2">Objet</th>
          <th className="py-1.5 pr-2">Copie à moi</th>
          <th className="py-1.5">Actif</th>
        </tr>
      </thead>
      <tbody>
        {rules.map((r) => (
          <tr key={r.level} className="border-t border-border-subtle align-top">
            <td className="py-1.5 pr-2">
              {r.label}
              {r.proposeSuspension && <div className="text-small text-muted-foreground">propose une suspension</div>}
            </td>
            <td className="py-1.5 pr-2 text-right tabular-nums">{delay(r.days)}</td>
            <td className="py-1.5 pr-2 text-small text-muted-foreground">{r.subject}</td>
            <td className="py-1.5 pr-2">{r.copyToIssuer ? "oui" : "non"}</td>
            <td className="py-1.5"><Pill tone={r.active ? "ok" : "neutral"}>{r.active ? "oui" : "non"}</Pill></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function RulesEditor({ base, onDone }: { base: ReminderRule[]; onDone: () => void }) {
  const [label, setLabel] = useState(`Relances ${today().slice(0, 4)}`);
  const [validFrom, setValidFrom] = useState(today());
  const [rules, setRules] = useState(base.map((r) => ({ ...r })));
  const save = useBillingWrite(billingApi.newReminderVersion);
  const set = (level: number, patch: Partial<ReminderRule>) => setRules((all) => all.map((r) => (r.level === level ? { ...r, ...patch } : r)));
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate({ label, validFrom, rules }, { onSuccess: onDone });
      }}
    >
      <Section title="Nouvelle version des règles">
        <p className="m-0 mb-4 text-muted-foreground">La version en cours s’arrête la veille. Les relances déjà préparées ne changent pas.</p>
        <div className="grid gap-3 md:grid-cols-2">
          <div className="flex flex-col gap-1.5"><Label htmlFor="r-label">Nom</Label><Input id="r-label" required value={label} onChange={(e) => setLabel(e.target.value)} /></div>
          <div className="flex flex-col gap-1.5"><Label htmlFor="r-from">À partir du</Label><Input id="r-from" type="date" min={today()} required value={validFrom} onChange={(e) => setValidFrom(e.target.value)} /></div>
        </div>
        {rules.map((r) => (
          <fieldset key={r.level} className="m-0 mt-4 flex flex-col gap-2 rounded-md border border-border-subtle p-3">
            <legend className="px-1 text-label">Niveau {r.level}</legend>
            <div className="grid gap-2 md:grid-cols-[2fr_1fr]">
              <div className="flex flex-col gap-1"><Label htmlFor={`r${r.level}-nom`}>Nom</Label><Input id={`r${r.level}-nom`} value={r.label} onChange={(e) => set(r.level, { label: e.target.value })} /></div>
              <div className="flex flex-col gap-1"><Label htmlFor={`r${r.level}-j`}>Jours après l’échéance</Label><Input id={`r${r.level}-j`} type="number" value={r.days} onChange={(e) => set(r.level, { days: Number(e.target.value) })} /></div>
            </div>
            <div className="flex flex-col gap-1"><Label htmlFor={`r${r.level}-objet`}>Objet</Label><Input id={`r${r.level}-objet`} value={r.subject} onChange={(e) => set(r.level, { subject: e.target.value })} /></div>
            <div className="flex flex-col gap-1">
              <Label htmlFor={`r${r.level}-texte`}>Texte</Label>
              <textarea id={`r${r.level}-texte`} rows={7} value={r.body} onChange={(e) => set(r.level, { body: e.target.value })} className="w-full rounded-md border border-input bg-card px-3 py-2" />
            </div>
            <div className="flex flex-wrap gap-x-4 gap-y-1">
              <label className="flex items-center gap-1.5"><input type="checkbox" checked={r.active} onChange={(e) => set(r.level, { active: e.target.checked })} /> Actif</label>
              <label className="flex items-center gap-1.5"><input type="checkbox" checked={r.copyToIssuer} onChange={(e) => set(r.level, { copyToIssuer: e.target.checked })} /> Copie à moi</label>
              <label className="flex items-center gap-1.5"><input type="checkbox" checked={r.attachPdf} onChange={(e) => set(r.level, { attachPdf: e.target.checked })} /> PDF joint</label>
              <label className="flex items-center gap-1.5"><input type="checkbox" checked={r.proposeSuspension} onChange={(e) => set(r.level, { proposeSuspension: e.target.checked })} /> Proposer une suspension</label>
            </div>
          </fieldset>
        ))}
        <Variables />
        {save.isError && <p role="alert" className="mt-3 mb-0 text-danger-700">{errorMessage(save.error)}</p>}
        <div className="mt-4 flex gap-2">
          <Button type="submit" disabled={save.isPending}>Enregistrer la version</Button>
          <Button type="button" variant="ghost" onClick={onDone}>Annuler</Button>
        </div>
      </Section>
    </form>
  );
}

const variableHelp: Record<string, string> = {
  bonjour: "« Bonjour Madame Wamytan, » (ou « Bonjour, »)",
  client: "raison sociale",
  creche: "nom de la crèche",
  numero: "numéro(s) des factures",
  factures: "une ligne par facture : numéro, date, échéance, reste dû",
  total: "total restant dû",
  retard: "jours de retard (le plus grand)",
  echeance: "échéance la plus ancienne",
  paiement: "banque, IBAN et référence",
  editeur: "votre raison sociale",
};

function Variables() {
  return (
    <p className="m-0 mt-4 text-small text-muted-foreground">
      Variables :{" "}
      {Object.entries(variableHelp).map(([k, v], i) => (
        <span key={k}>
          {i > 0 && " · "}
          <code className="rounded bg-muted px-1">{`{${k}}`}</code> {v}
        </span>
      ))}
      .
    </p>
  );
}

function ClientRules({ settings }: { settings: ReminderSettings }) {
  const [editing, setEditing] = useState<string | null>(null);
  const special = settings.clients.filter((c) => c.shiftDays > 0 || c.paused || c.note);
  const others = settings.clients.filter((c) => !special.includes(c));
  return (
    <Section title="Règles par client">
      {special.length === 0 && <p className="m-0 mb-2 text-muted-foreground">Tous les clients suivent les règles générales.</p>}
      {special.length > 0 && (
        <table className="w-full border-collapse">
          <thead className="text-left text-label text-muted-foreground">
            <tr><th className="py-1.5 pr-2">Client</th><th className="py-1.5 pr-2">Délai de paiement</th><th className="py-1.5 pr-2">Relances</th><th className="py-1.5">Motif</th></tr>
          </thead>
          <tbody>
            {special.map((c) =>
              editing === c.clientId ? (
                <tr key={c.clientId}><td colSpan={4}><ClientRuleForm client={c} onDone={() => setEditing(null)} /></td></tr>
              ) : (
                <tr key={c.clientId} className="cursor-pointer border-t border-border-subtle hover:bg-lagon-50" onClick={() => setEditing(c.clientId)}>
                  <td className="py-1.5 pr-2">{c.crecheName ?? c.clientName}</td>
                  <td className="py-1.5 pr-2">{c.paymentDays} jours</td>
                  <td className="py-1.5 pr-2">{c.paused ? <Pill tone="warn">suspendues</Pill> : c.shiftDays ? `décalées de ${c.shiftDays} j` : "normales"}</td>
                  <td className="py-1.5 text-small text-muted-foreground">{c.note ?? "—"}</td>
                </tr>
              ),
            )}
          </tbody>
        </table>
      )}
      {editing && !special.some((c) => c.clientId === editing) && (
        <ClientRuleForm client={settings.clients.find((c) => c.clientId === editing)!} onDone={() => setEditing(null)} />
      )}
      {others.length > 0 && !editing && (
        <div className="mt-3 flex items-center gap-2">
          <Label htmlFor="rc-ajout" className="sr-only">Ajouter une règle pour un client</Label>
          <select id="rc-ajout" className="h-8 rounded-md border border-input bg-card px-2 text-small" value="" onChange={(e) => setEditing(e.target.value || null)}>
            <option value="">Ajouter une règle pour…</option>
            {others.map((c) => <option key={c.clientId} value={c.clientId}>{c.crecheName ?? c.clientName}</option>)}
          </select>
        </div>
      )}
      <div role="note" className="mt-4 flex gap-3 rounded-md bg-warning-100 px-4 py-3 text-warning-700">
        <ShieldAlert aria-hidden className="mt-0.5 size-5 shrink-0" />
        <span>La console ne suspend jamais une crèche seule : après la dernière relance, elle vous propose la suspension, que vous déciderez (ordres, phase 5).</span>
      </div>
    </Section>
  );
}

function ClientRuleForm({ client, onDone }: { client: ReminderSettings["clients"][number]; onDone: () => void }) {
  const [shift, setShift] = useState(client.shiftDays);
  const [paused, setPaused] = useState(client.paused);
  const [note, setNote] = useState(client.note ?? "");
  const save = useBillingWrite(() => billingApi.saveClientRule(client.clientId, { shiftDays: shift, paused, note: note || null }));
  return (
    <form className="my-2 flex flex-col gap-2 rounded-md border border-border-subtle p-3" onSubmit={(e) => { e.preventDefault(); save.mutate(undefined, { onSuccess: onDone }); }}>
      <b>{client.crecheName ?? client.clientName}</b>
      <div className="grid gap-2 md:grid-cols-3">
        <div className="flex flex-col gap-1"><Label htmlFor="rc-shift">Décalage (jours)</Label><Input id="rc-shift" type="number" min={0} max={120} value={shift} onChange={(e) => setShift(Number(e.target.value))} /></div>
        <label className="flex items-center gap-1.5 self-end pb-2"><input type="checkbox" checked={paused} onChange={(e) => setPaused(e.target.checked)} /> Relances suspendues</label>
        <div className="flex flex-col gap-1"><Label htmlFor="rc-note">Motif</Label><Input id="rc-note" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Mandat administratif, échéancier…" /></div>
      </div>
      {save.isError && <p role="alert" className="m-0 text-danger-700">{errorMessage(save.error)}</p>}
      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={save.isPending}>Enregistrer</Button>
        <Button type="button" size="sm" variant="ghost" onClick={onDone}>Annuler</Button>
      </div>
    </form>
  );
}

function Holidays({ settings }: { settings: ReminderSettings }) {
  const [day, setDay] = useState("");
  const [label, setLabel] = useState("");
  const add = useBillingWrite(() => billingApi.addHoliday(day, label));
  const remove = useBillingWrite((d: string) => billingApi.removeHoliday(d));
  const upcoming = settings.holidays.filter((h) => h.day >= today());
  return (
    <Section title="Jours fériés" aside={<span className="text-small text-muted-foreground">pas de préparation ces jours-là, ni le week-end</span>}>
      <ul className="m-0 flex list-none flex-col p-0">
        {upcoming.map((h) => (
          <li key={h.day} className="flex items-center gap-3 border-t border-border-subtle py-1.5 first:border-t-0">
            <span className="w-24 tabular-nums">{formatDay(h.day)}</span>
            <span className="flex-1">{h.label}</span>
            <Button variant="ghost" size="icon-sm" aria-label={`Retirer ${h.label} ${formatDay(h.day)}`} onClick={() => remove.mutate(h.day)}><Trash2 aria-hidden /></Button>
          </li>
        ))}
        {upcoming.length === 0 && <li className="text-muted-foreground">Aucun jour férié à venir : ajoutez ceux de l’année.</li>}
      </ul>
      <form className="mt-3 flex flex-wrap items-end gap-2" onSubmit={(e) => { e.preventDefault(); add.mutate(undefined, { onSuccess: () => { setDay(""); setLabel(""); } }); }}>
        <div className="flex flex-col gap-1"><Label htmlFor="f-jour">Jour</Label><Input id="f-jour" type="date" required value={day} onChange={(e) => setDay(e.target.value)} /></div>
        <div className="flex flex-1 flex-col gap-1"><Label htmlFor="f-nom">Nom</Label><Input id="f-nom" required value={label} onChange={(e) => setLabel(e.target.value)} /></div>
        <Button type="submit" variant="outline" disabled={add.isPending}>Ajouter</Button>
      </form>
      {(add.isError || remove.isError) && <p role="alert" className="mt-2 mb-0 text-danger-700">{errorMessage(add.error ?? remove.error)}</p>}
    </Section>
  );
}

/** Paramètres › Modèles d'e-mails : les textes de chaque niveau, et les variables ; on les change par une nouvelle version. */
export function TemplatesTab() {
  const query = useReminderSettings();
  const [editing, setEditing] = useState(false);
  if (!query.data) {
    return <p className="text-muted-foreground">Chargement…</p>;
  }
  const now = current(query.data);
  if (editing && now) {
    return <RulesEditor base={now.rules} onDone={() => setEditing(false)} />;
  }
  return (
    <Section title="Modèles des relances" aside={<Button variant="outline" size="sm" onClick={() => setEditing(true)}>Modifier (nouvelle version)…</Button>}>
      <div className="flex flex-col gap-4">
        {now?.rules.map((r) => (
          <article key={r.level} className="rounded-md border border-border-subtle p-4">
            <div className="flex flex-wrap items-center gap-2">
              <b>{r.label}</b>
              <Pill tone="neutral">{delay(r.days)}</Pill>
              {!r.active && <Pill tone="neutral">inactif</Pill>}
            </div>
            <p className="m-0 mt-2 font-semibold">{r.subject}</p>
            <pre className="m-0 mt-1 font-sans whitespace-pre-wrap text-muted-foreground">{r.body}</pre>
          </article>
        ))}
        <Variables />
        <p className="m-0 text-small text-muted-foreground">
          L’e-mail d’une facture validée suit un texte fixe ; chaque message se relit et se corrige dans la boîte d’envoi avant de partir.
        </p>
      </div>
    </Section>
  );
}

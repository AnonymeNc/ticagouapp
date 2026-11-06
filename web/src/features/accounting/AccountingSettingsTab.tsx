import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowDown, ArrowUp, Plus } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { selectClass } from "@/lib/formFields";
import { accountingApi, accountingKeys, csvColumnLabels, type Account, type AccountingSettings, type CsvColumn } from "./api";

/**
 * Paramètres › Comptabilité : comptes datés par rôle (un changement de compte s'ouvre à une date, l'ancien est terminé
 * la veille), journaux, comptes auxiliaires et format du CSV. Direction et comptabilité.
 */
export function AccountingSettingsTab() {
  return (
    <>
      <AccountsSection />
      <FormatSection />
    </>
  );
}

function AccountsSection() {
  const user = useCurrentUser();
  const queryClient = useQueryClient();
  const accounts = useQuery({ queryKey: accountingKeys.accounts, queryFn: ({ signal }) => accountingApi.accounts(signal) });
  const [changing, setChanging] = useState<Account | null>(null);
  const list = accounts.data ?? [];
  const current = Object.values(
    list.reduce<Record<string, Account[]>>((byRole, a) => ({ ...byRole, [a.role]: [...(byRole[a.role] ?? []), a] }), {}),
  );
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 className="m-0 text-title-3">Plan de comptes</h2>
      <p className="m-0 text-small text-muted-foreground">
        Comptes proposés par défaut, à faire valider par votre expert-comptable. Pour changer de compte, ouvrez le nouveau à
        sa date de début : l’ancien est terminé la veille et reste utilisé pour les pièces plus anciennes.
      </p>
      {accounts.isError && <Alert>{errorMessage(accounts.error)}</Alert>}
      <table className="w-full border-collapse text-left">
        <thead className="text-small text-muted-foreground">
          <tr><th className="py-1 font-normal">Rôle</th><th className="py-1 font-normal">Compte</th><th className="py-1 font-normal">Libellé</th><th className="py-1 font-normal">Validité</th><th /></tr>
        </thead>
        <tbody>
          {current.flatMap((versions) => versions.map((a, i) => (
            <tr key={a.id} className={i === 0 ? "border-t border-border-subtle" : "text-muted-foreground"}>
              <td className="py-1.5">{i === 0 ? a.roleLabel : ""}</td>
              <td className="py-1.5 tabular-nums">{a.account}</td>
              <td className="py-1.5">{a.label}</td>
              <td className="py-1.5 text-small">
                {a.validTo ? `du ${isoToDateFr(a.validFrom)} au ${isoToDateFr(a.validTo)}` : `depuis le ${isoToDateFr(a.validFrom)}`}
              </td>
              <td className="py-1.5 text-right">
                {i === 0 && (user.role === "DIRECTION" || user.role === "COMPTABILITE") && (
                  <Button size="sm" variant="outline" onClick={() => setChanging(a)}><Plus aria-hidden />Changer</Button>
                )}
              </td>
            </tr>
          )))}
        </tbody>
      </table>
      {changing && (
        <ChangeAccount current={changing} onDone={() => {
          setChanging(null);
          void queryClient.invalidateQueries({ queryKey: ["accounting"] });
        }} />
      )}
    </section>
  );
}

/** Nouveau compte pour un rôle à partir d'une date : l'ancien est terminé la veille, en deux appels. */
function ChangeAccount({ current, onDone }: { current: Account; onDone: () => void }) {
  const [account, setAccount] = useState(current.account);
  const [label, setLabel] = useState(current.label);
  const [from, setFrom] = useState("");
  const [error, setError] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: async () => {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || from <= current.validFrom) {
        throw new Error(`Choisissez une date de début après le ${isoToDateFr(current.validFrom)}.`);
      }
      const eve = new Date(`${from}T00:00:00Z`);
      eve.setUTCDate(eve.getUTCDate() - 1);
      await accountingApi.updateAccount(current.id, current.version, { ...current, validTo: eve.toISOString().slice(0, 10) });
      await accountingApi.createAccount({ role: current.role, account: account.trim().toUpperCase(), label, validFrom: from, validTo: null });
    },
    onSuccess: onDone,
    onError: (e) => setError(errorMessage(e)),
  });
  return (
    <form className="flex flex-wrap items-end gap-3 rounded-md bg-muted p-3" aria-label={`Nouveau compte : ${current.roleLabel}`}
      onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate(); }}>
      <FormField label="Compte"><Input value={account} onChange={(e) => setAccount(e.target.value)} className="w-32 tabular-nums" /></FormField>
      <FormField label="Libellé"><Input value={label} onChange={(e) => setLabel(e.target.value)} className="w-64" /></FormField>
      <FormField label="À partir du"><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></FormField>
      <Button type="submit" disabled={save.isPending}>Enregistrer</Button>
      {error && <Alert>{error}</Alert>}
    </form>
  );
}

function FormatSection() {
  const queryClient = useQueryClient();
  const settings = useQuery({ queryKey: accountingKeys.settings, queryFn: ({ signal }) => accountingApi.settings(signal) });
  const [form, setForm] = useState<AccountingSettings | null>(null);
  const [saved, setSaved] = useState(false);
  useEffect(() => {
    if (settings.data) setForm(settings.data);
  }, [settings.data]);
  const save = useMutation({
    mutationFn: accountingApi.updateSettings,
    onSuccess: (data) => {
      queryClient.setQueryData(accountingKeys.settings, data);
      setSaved(true);
    },
  });
  if (!form) return settings.isError ? <Alert>{errorMessage(settings.error)}</Alert> : null;
  const set = (patch: Partial<AccountingSettings>) => { setSaved(false); setForm({ ...form, ...patch }); };
  const move = (column: CsvColumn, delta: number) => {
    const columns = [...form.csvColumns];
    const i = columns.indexOf(column);
    const j = i + delta;
    if (j < 0 || j >= columns.length) return;
    [columns[i], columns[j]] = [columns[j], columns[i]];
    set({ csvColumns: columns });
  };
  const all = Object.keys(csvColumnLabels) as CsvColumn[];
  return (
    <form className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1" aria-label="Journaux et format du CSV"
      onSubmit={(e) => { e.preventDefault(); save.mutate(form); }}>
      <h2 className="m-0 text-title-3">Journaux, comptes auxiliaires et format du CSV</h2>
      <div className="flex flex-wrap gap-4">
        <FormField label="Journal des ventes"><Input value={form.salesJournal} onChange={(e) => set({ salesJournal: e.target.value })} className="w-24" /></FormField>
        <FormField label="Journal de banque"><Input value={form.bankJournal} onChange={(e) => set({ bankJournal: e.target.value })} className="w-24" /></FormField>
        <FormField label="Journal de caisse"><Input value={form.cashJournal} onChange={(e) => set({ cashJournal: e.target.value })} className="w-24" /></FormField>
        <FormField label="Préfixe des familles" hint="F00001, F00002…"><Input value={form.familyPrefix} onChange={(e) => set({ familyPrefix: e.target.value.toUpperCase() })} className="w-24" /></FormField>
        <FormField label="Préfixe des financeurs" hint="O00001…"><Input value={form.funderPrefix} onChange={(e) => set({ funderPrefix: e.target.value.toUpperCase() })} className="w-24" /></FormField>
      </div>
      <div className="flex flex-wrap gap-4">
        <FormField label="Séparateur">
          <select className={selectClass} value={form.csvSeparator} onChange={(e) => set({ csvSeparator: e.target.value as AccountingSettings["csvSeparator"] })}>
            <option value=";">Point-virgule (;)</option><option value=",">Virgule (,)</option><option value="TAB">Tabulation</option>
          </select>
        </FormField>
        <FormField label="Dates">
          <select className={selectClass} value={form.csvDate} onChange={(e) => set({ csvDate: e.target.value as AccountingSettings["csvDate"] })}>
            <option value="JJ/MM/AAAA">JJ/MM/AAAA</option><option value="AAAA-MM-JJ">AAAA-MM-JJ</option><option value="AAAAMMJJ">AAAAMMJJ</option>
          </select>
        </FormField>
        <label className="flex items-center gap-2 self-end pb-2">
          <input type="checkbox" checked={form.csvHeader} onChange={(e) => set({ csvHeader: e.target.checked })} />Ligne d’en-tête
        </label>
      </div>
      <fieldset className="m-0 flex flex-col gap-1 border-0 p-0">
        <legend className="mb-1 text-label">Colonnes du CSV, dans l’ordre</legend>
        <ol className="m-0 flex list-none flex-col gap-1 p-0">
          {form.csvColumns.map((c, i) => (
            <li key={c} className="flex items-center gap-2">
              <span className="w-6 text-right text-muted-foreground tabular-nums">{i + 1}.</span>
              <span className="w-44">{csvColumnLabels[c]}</span>
              <Button type="button" size="sm" variant="ghost" aria-label={`Monter ${csvColumnLabels[c]}`} onClick={() => move(c, -1)}><ArrowUp aria-hidden /></Button>
              <Button type="button" size="sm" variant="ghost" aria-label={`Descendre ${csvColumnLabels[c]}`} onClick={() => move(c, 1)}><ArrowDown aria-hidden /></Button>
              <Button type="button" size="sm" variant="ghost" disabled={form.csvColumns.length === 1} onClick={() => set({ csvColumns: form.csvColumns.filter((x) => x !== c) })}>Retirer</Button>
            </li>
          ))}
        </ol>
        {all.filter((c) => !form.csvColumns.includes(c)).map((c) => (
          <Button key={c} type="button" size="sm" variant="outline" className="self-start" onClick={() => set({ csvColumns: [...form.csvColumns, c] })}>
            <Plus aria-hidden />{csvColumnLabels[c]}
          </Button>
        ))}
      </fieldset>
      {save.isError && <Alert>{errorMessage(save.error)}</Alert>}
      <div className="flex items-center gap-3">
        <Button type="submit" disabled={save.isPending}>Enregistrer</Button>
        {saved && <span className="text-success-700">Enregistré.</span>}
      </div>
      <p className="m-0 text-small text-muted-foreground">Le fichier FEC a toujours ses 18 colonnes réglementaires ; ces réglages ne concernent que le CSV.</p>
    </form>
  );
}

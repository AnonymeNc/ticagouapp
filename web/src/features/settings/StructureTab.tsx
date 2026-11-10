import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ImageUp, Trash2 } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { DateInput } from "@/components/DateInput";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { useStructure, type Structure } from "@/features/structure/useStructure";
import { api, apiBlob, apiUpload, errorMessage } from "@/lib/api";
import { parseDateFr } from "@/lib/datetime";
import { DAY_NAMES, hhmm } from "@/lib/format";
import { isoToField, textOrNull } from "@/lib/formFields";
import { systemApi } from "./systemApi";

/** Banques de Nouvelle-Calédonie proposées (maquette) ; une autre reste saisissable. */
const BANKS = [
  "BCI — Banque Calédonienne d’Investissement",
  "BNC — Banque de Nouvelle-Calédonie",
  "Société Générale Calédonienne de Banque",
  "BNP Paribas Nouvelle-Calédonie",
  "OPT — Centre des chèques postaux",
];

type Day = { open: boolean; opens: string; closes: string };

function formOf(s: Structure) {
  return {
    name: s.name, city: s.city ?? "", ridet: s.ridet ?? "", agrementNumber: s.agrementNumber ?? "",
    agrementDate: isoToField(s.agrementDate), capacity: String(s.capacity), invoiceFooter: s.invoiceFooter ?? "",
    address: s.contact?.address ?? "", postalCode: s.contact?.postalCode ?? "", phone: s.contact?.phone ?? "", email: s.contact?.email ?? "",
    holder: s.bank?.holder ?? "", bankName: s.bank?.bankName ?? "", iban: s.bank?.iban ?? "", bic: s.bank?.bic ?? "",
    printRib: s.bank?.printRib ?? false,
    days: [1, 2, 3, 4, 5, 6].map((d): Day => {
      const h = s.openingHours.find((o) => o.dayOfWeek === d);
      return { open: !!h, opens: h ? hhmm(h.opensAt) : "06:30", closes: h ? hhmm(h.closesAt) : "17:30" };
    }),
  };
}

/** Paramètres › Structure (maquette) : identité et agrément, logo, horaires, coordonnées bancaires, pied de facture. */
export function StructureTab() {
  const structure = useStructure();
  const queryClient = useQueryClient();
  const [form, setForm] = useState<ReturnType<typeof formOf> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [logoVersion, setLogoVersion] = useState(0);
  const [logoUrl, setLogoUrl] = useState<string | null>(null);
  const logoInput = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (structure.data) setForm(formOf(structure.data));
  }, [structure.data]);
  useEffect(() => {
    let url: string | null = null;
    if (structure.data?.hasLogo) {
      void apiBlob("/structure/logo").then(({ blob }) => {
        url = URL.createObjectURL(blob);
        setLogoUrl(url);
      }).catch(() => setLogoUrl(null));
    } else {
      setLogoUrl(null);
    }
    return () => { if (url) URL.revokeObjectURL(url); };
  }, [structure.data?.hasLogo, logoVersion]);

  if (!form || !structure.data) return <p className="text-muted-foreground">Chargement…</p>;
  const set = (patch: Partial<typeof form>) => setForm({ ...form, ...patch });
  const setDay = (i: number, patch: Partial<Day>) => set({ days: form.days.map((d, j) => (j === i ? { ...d, ...patch } : d)) });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["structure"] });

  const save = async () => {
    setError(null);
    setMessage(null);
    const agrementDate = form.agrementDate.trim() ? parseDateFr(form.agrementDate) : null;
    if (form.agrementDate.trim() && !agrementDate) return setError("Date d’agrément au format JJ/MM/AAAA.");
    try {
      await systemApi.updateStructure({
        identity: {
          name: form.name.trim(), city: textOrNull(form.city), ridet: textOrNull(form.ridet), agrementNumber: textOrNull(form.agrementNumber),
          agrementDate, capacity: Number(form.capacity),
        },
        invoiceFooter: textOrNull(form.invoiceFooter),
        openingHours: form.days.map((d, i) => ({ ...d, dayOfWeek: i + 1 })).filter((d) => d.open)
          .map((d) => ({ dayOfWeek: d.dayOfWeek, opensAt: d.opens, closesAt: d.closes })),
        contact: { address: textOrNull(form.address), postalCode: textOrNull(form.postalCode), phone: textOrNull(form.phone), email: textOrNull(form.email) },
        bank: { holder: textOrNull(form.holder), bankName: textOrNull(form.bankName), iban: textOrNull(form.iban), bic: textOrNull(form.bic), printRib: form.printRib },
      });
      await refresh();
      setMessage("Modifications enregistrées.");
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const uploadLogo = async (file: File) => {
    setError(null);
    const data = new FormData();
    data.append("file", file);
    try {
      await apiUpload<void>("/structure/logo", data, "PUT");
      setLogoVersion((v) => v + 1);
      await refresh();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <section className="flex flex-col gap-5 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="m-0 text-title-3">Structure</h2>
          <p className="m-0 text-muted-foreground">Ces informations figurent sur les factures, les attestations et le registre.</p>
        </div>
        <div className="flex gap-2">
          <Button variant="ghost" onClick={() => { setForm(formOf(structure.data!)); setMessage(null); setError(null); }}>Annuler les modifications</Button>
          <Button onClick={() => void save()}>Enregistrer</Button>
        </div>
      </div>
      {error && <Alert>{error}</Alert>}
      {message && <p role="status" className="m-0 text-success-700">{message}</p>}

      <fieldset className="m-0 grid gap-3 border-0 p-0 md:grid-cols-2">
        <legend className="mb-2 text-label">Identité et agrément</legend>
        <FormField label="Nom de la structure" className="md:col-span-2"><Input value={form.name} maxLength={150} onChange={(e) => set({ name: e.target.value })} /></FormField>
        <FormField label="Adresse" className="md:col-span-2"><Input value={form.address} maxLength={300} onChange={(e) => set({ address: e.target.value })} /></FormField>
        <FormField label="Code postal"><Input value={form.postalCode} maxLength={10} onChange={(e) => set({ postalCode: e.target.value })} /></FormField>
        <FormField label="Commune"><Input value={form.city} maxLength={100} onChange={(e) => set({ city: e.target.value })} /></FormField>
        <FormField label="Téléphone"><Input value={form.phone} maxLength={30} onChange={(e) => set({ phone: e.target.value })} /></FormField>
        <FormField label="Courriel"><Input value={form.email} maxLength={254} onChange={(e) => set({ email: e.target.value })} /></FormField>
        <FormField label="Numéro RIDET" hint="7 chiffres, éventuellement suivis de .001"><Input value={form.ridet} onChange={(e) => set({ ridet: e.target.value })} /></FormField>
        <FormField label="Numéro d’agrément"><Input value={form.agrementNumber} maxLength={50} onChange={(e) => set({ agrementNumber: e.target.value })} /></FormField>
        <FormField label="Date d’agrément"><DateInput value={form.agrementDate} placeholder="JJ/MM/AAAA" onChange={(e) => set({ agrementDate: e.target.value })} /></FormField>
        <FormField label="Capacité agréée (places)" hint="Le total des places des sections ne peut pas la dépasser.">
          <Input value={form.capacity} inputMode="numeric" onChange={(e) => set({ capacity: e.target.value })} />
        </FormField>
      </fieldset>

      <div className="flex flex-wrap items-center gap-4">
        <div className="flex size-20 items-center justify-center overflow-hidden rounded-lg border border-border-subtle bg-muted text-title-2 text-lagon-700">
          {logoUrl ? <img src={logoUrl} alt="Logo de la structure" className="max-h-full max-w-full" /> : structure.data.name.split(/\s+/).map((w) => w[0]).join("").slice(0, 2).toUpperCase()}
        </div>
        <div className="flex flex-col gap-2">
          <span className="text-label">Logo</span>
          <span className="text-small text-muted-foreground">PNG, JPEG ou SVG, carré, 512 px conseillés. Affiché en haut des factures et des attestations.</span>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => logoInput.current?.click()}><ImageUp aria-hidden />{structure.data.hasLogo ? "Remplacer le logo" : "Ajouter un logo"}</Button>
            {structure.data.hasLogo && (
              <Button variant="ghost" size="sm" onClick={async () => { await api<void>("/structure/logo", { method: "DELETE" }); await refresh(); }}>
                <Trash2 aria-hidden />Retirer
              </Button>
            )}
          </div>
          <input ref={logoInput} type="file" accept="image/png,image/jpeg,image/svg+xml" className="hidden"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) void uploadLogo(f); e.target.value = ""; }} />
        </div>
      </div>

      <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
        <legend className="mb-2 text-label">Horaires d’ouverture</legend>
        {/* Lignes et colonnes aérées : les heures d'ouverture et de fermeture ne se touchent plus. */}
        <table className="w-fit border-separate border-spacing-x-0 border-spacing-y-2 text-left">
          <thead>
            <tr className="text-label text-muted-foreground"><th className="pr-6">Jour</th><th className="pr-6">Ouvert</th><th className="pr-6">Ouverture</th><th>Fermeture</th></tr>
          </thead>
          <tbody>
            {form.days.map((d, i) => (
              <tr key={i}>
                <td className="pr-6">{DAY_NAMES[i]}</td>
                <td className="pr-6"><input type="checkbox" aria-label={`${DAY_NAMES[i]} ouvert`} checked={d.open} onChange={(e) => setDay(i, { open: e.target.checked })} /></td>
                <td className="pr-6">{d.open ? <Input type="time" className="w-32" aria-label={`Ouverture ${DAY_NAMES[i]}`} value={d.opens} onChange={(e) => setDay(i, { opens: e.target.value })} /> : <span className="text-muted-foreground">Fermé</span>}</td>
                <td>{d.open && <Input type="time" className="w-32" aria-label={`Fermeture ${DAY_NAMES[i]}`} value={d.closes} onChange={(e) => setDay(i, { closes: e.target.value })} />}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="m-0 text-small text-muted-foreground">Les jours fériés et fermetures exceptionnelles se géreront dans le calendrier de la structure.</p>
      </fieldset>

      <fieldset className="m-0 grid gap-3 border-0 p-0 md:grid-cols-2">
        <legend className="mb-2 text-label">Coordonnées bancaires</legend>
        <FormField label="Titulaire du compte"><Input value={form.holder} maxLength={150} onChange={(e) => set({ holder: e.target.value })} /></FormField>
        <FormField label="Banque">
          <Input list="banques-nc" value={form.bankName} maxLength={100} onChange={(e) => set({ bankName: e.target.value })} />
        </FormField>
        <datalist id="banques-nc">{BANKS.map((b) => <option key={b} value={b} />)}</datalist>
        <FormField label="IBAN" hint="Contrôlé à l’enregistrement (clé)."><Input value={form.iban} maxLength={40} onChange={(e) => set({ iban: e.target.value })} /></FormField>
        <FormField label="BIC"><Input value={form.bic} maxLength={11} onChange={(e) => set({ bic: e.target.value })} /></FormField>
        <label className="flex items-center gap-2 md:col-span-2"><input type="checkbox" checked={form.printRib} onChange={(e) => set({ printRib: e.target.checked })} />Imprimer le RIB en bas de chaque facture</label>
      </fieldset>

      <FormField label="Texte en pied de facture" hint="Apparaît sur toutes les factures émises à partir de l’enregistrement.">
        <Textarea rows={4} maxLength={2000} value={form.invoiceFooter} onChange={(e) => set({ invoiceFooter: e.target.value })} />
      </FormField>
    </section>
  );
}

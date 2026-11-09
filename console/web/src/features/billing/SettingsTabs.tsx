import { useState, type FormEvent, type ReactNode } from "react";
import { AlertTriangle, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Pill } from "@/components/ui/pill";
import { formatDay, moduleLabels } from "@/features/parc/labels";
import { errorMessage } from "@/lib/api";
import { billingApi, useBillingSettings, useBillingWrite, type Issuer, type PriceItem, type PriceList, type TgcNature } from "./api";
import { natureLabels, rate, today, xpf } from "./labels";

const tiers = ["MICRO", "STRUCTURE", "GRANDE"] as const;
const kinds: { kind: PriceItem["kind"]; title: string }[] = [
  { kind: "FORMULE", title: "Formules (par mois)" },
  { kind: "MODULE", title: "Modules à la carte (par mois)" },
  { kind: "EQUIPEMENT", title: "Équipement supplémentaire (par mois)" },
  { kind: "SUPPORT", title: "Support (par mois)" },
  { kind: "PRESTATION", title: "Prestations ponctuelles" },
];

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

/** Barème : la version en vigueur, les précédentes, et une nouvelle version à une date future (jamais de retouche). */
export function PriceListTab() {
  const query = useBillingSettings();
  const [editing, setEditing] = useState(false);
  if (query.isPending) {
    return <p className="text-muted-foreground">Chargement…</p>;
  }
  if (query.isError) {
    return <p role="alert" className="text-danger-700">{errorMessage(query.error)}</p>;
  }
  const [latest, ...older] = query.data.versions;
  if (editing && latest) {
    return <PriceListEditor base={latest.list} items={latest.items} onDone={() => setEditing(false)} />;
  }
  return (
    <div className="flex flex-col gap-4">
      {latest && (
        <Section
          title={latest.list.label}
          aside={
            <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
              <Plus aria-hidden />
              Nouvelle version au…
            </Button>
          }
        >
          <PriceListView list={latest.list} items={latest.items} />
        </Section>
      )}
      {older.map((v) => (
        <details key={v.list.id} className="rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <summary className="cursor-pointer font-semibold">
            {v.list.label} · du {formatDay(v.list.validFrom)} au {formatDay(v.list.validTo)}
          </summary>
          <div className="mt-3">
            <PriceListView list={v.list} items={v.items} />
          </div>
        </details>
      ))}
    </div>
  );
}

function PriceListView({ list, items }: { list: PriceList; items: PriceItem[] }) {
  return (
    <div className="flex flex-col gap-4">
      <p className="m-0 text-muted-foreground">
        En vigueur depuis le {formatDay(list.validFrom)}
        {list.validTo ? ` jusqu’au ${formatDay(list.validTo)}` : ""}. Tranches : Micro jusqu’à {list.microMax} places, Structure jusqu’à{" "}
        {list.structureMax}, Grande jusqu’à {list.grandeMax} (au-delà : sur devis). Compris : {list.includedDesktops} PC et {list.includedTablets}{" "}
        tablettes. Remises : engagement 3 ans {rate(list.engagement3Discount)}, structure supplémentaire {rate(list.extraStructureDiscount)}. Les
        contrats gardent le prix de la version en vigueur à leur signature.
      </p>
      {kinds.map(({ kind, title }) => {
        const rows = items.filter((i) => i.kind === kind);
        if (rows.length === 0) {
          return null;
        }
        const byTier = kind === "FORMULE" || kind === "MODULE";
        const codes = [...new Set(rows.map((r) => r.code))];
        return (
          <div key={kind}>
            <h3 className="m-0 mb-1.5 text-title-3">{title}</h3>
            <table className="w-full border-collapse">
              <thead className="text-left text-label text-muted-foreground">
                <tr>
                  <th className="py-1.5 pr-3">Libellé</th>
                  {byTier ? tiers.map((t) => <th key={t} className="py-1.5 pr-3 text-right">{t.charAt(0) + t.slice(1).toLowerCase()}</th>) : <th className="py-1.5 pr-3 text-right">Prix HT</th>}
                  <th className="py-1.5">{kind === "FORMULE" ? "Modules compris" : "TGC"}</th>
                </tr>
              </thead>
              <tbody>
                {codes.map((code) => {
                  const first = rows.find((r) => r.code === code)!;
                  return (
                    <tr key={code} className="border-t border-border-subtle">
                      <td className="py-1.5 pr-3">{byTier ? first.label.replace(/ (Micro|Structure|Grande)$/, "") : first.label}</td>
                      {byTier ? (
                        tiers.map((t) => (
                          <td key={t} className="py-1.5 pr-3 text-right tabular-nums">
                            {xpf(rows.find((r) => r.code === code && r.tier === t)?.price)}
                          </td>
                        ))
                      ) : (
                        <td className="py-1.5 pr-3 text-right tabular-nums">
                          {xpf(first.price)}
                          {first.unit ? ` / ${first.unit}` : ""}
                        </td>
                      )}
                      <td className="py-1.5 text-small text-muted-foreground">
                        {kind === "FORMULE"
                          ? (first.includes ?? "").split(",").filter(Boolean).map((m) => moduleLabels[m] ?? m).join(", ") || "base seule"
                          : natureLabels[first.tgcNature]}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        );
      })}
    </div>
  );
}

function PriceListEditor({ base, items: initial, onDone }: { base: PriceList; items: PriceItem[]; onDone: () => void }) {
  const nextYear = `${Number(today().slice(0, 4)) + 1}-01-01`;
  const [list, setList] = useState({ ...base, label: `Barème ${nextYear.slice(0, 4)}`, validFrom: nextYear });
  const [items, setItems] = useState(initial.map((i) => ({ ...i })));
  const save = useBillingWrite(billingApi.newPriceList);
  const num = (key: keyof PriceList) => (
    <Input id={`b-${key}`} type="number" min={0} value={list[key] as number} onChange={(e) => setList((l) => ({ ...l, [key]: Number(e.target.value) }))} />
  );
  // Remises stockées en dixièmes de pour cent (100 = 10 %), saisies en pour cent.
  const pct = (key: "engagement3Discount" | "extraStructureDiscount") => (
    <Input id={`b-${key}`} type="number" step="0.1" min={0} max={100} value={list[key] / 10} onChange={(e) => setList((l) => ({ ...l, [key]: Math.round(Number(e.target.value) * 10) }))} />
  );
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const { id: _id, validTo: _to, ...rest } = list;
    save.mutate({ ...rest, items }, { onSuccess: onDone });
  };
  return (
    <form onSubmit={submit}>
      <Section title="Nouvelle version du barème">
        <p className="m-0 mb-4 text-muted-foreground">
          La version en cours s’arrêtera la veille. Les contrats déjà signés gardent leur prix ; les nouveaux prennent celui-ci.
        </p>
        <div className="grid gap-3 md:grid-cols-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="b-label">Nom</Label>
            <Input id="b-label" required value={list.label} onChange={(e) => setList((l) => ({ ...l, label: e.target.value }))} />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="b-from">En vigueur à partir du</Label>
            <Input id="b-from" type="date" required min={today()} value={list.validFrom} onChange={(e) => setList((l) => ({ ...l, validFrom: e.target.value }))} />
          </div>
          <div className="flex flex-col gap-1.5"><Label htmlFor="b-microMax">Micro : places au plus</Label>{num("microMax")}</div>
          <div className="flex flex-col gap-1.5"><Label htmlFor="b-structureMax">Structure : places au plus</Label>{num("structureMax")}</div>
          <div className="flex flex-col gap-1.5"><Label htmlFor="b-grandeMax">Grande : places au plus</Label>{num("grandeMax")}</div>
          <div className="flex flex-col gap-1.5"><Label htmlFor="b-includedDesktops">PC compris</Label>{num("includedDesktops")}</div>
          <div className="flex flex-col gap-1.5"><Label htmlFor="b-includedTablets">Tablettes comprises</Label>{num("includedTablets")}</div>
          <div className="flex flex-col gap-1.5"><Label htmlFor="b-engagement3Discount">Remise 3 ans (%)</Label>{pct("engagement3Discount")}</div>
          <div className="flex flex-col gap-1.5"><Label htmlFor="b-extraStructureDiscount">Remise structure en plus (%)</Label>{pct("extraStructureDiscount")}</div>
        </div>
        <table className="mt-4 w-full border-collapse">
          <thead className="text-left text-label text-muted-foreground">
            <tr>
              <th className="py-1.5 pr-3">Prix</th>
              <th className="py-1.5 pr-3">Tranche</th>
              <th className="py-1.5 text-right">F HT</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, i) => (
              <tr key={`${item.kind}-${item.code}-${item.tier}`} className="border-t border-border-subtle">
                <td className="py-1 pr-3">
                  <Label htmlFor={`b-p${i}`} className="font-normal">
                    {item.label}
                  </Label>
                </td>
                <td className="py-1 pr-3 text-small text-muted-foreground">{item.tier ? item.tier.charAt(0) + item.tier.slice(1).toLowerCase() : item.unit ?? "mois"}</td>
                <td className="py-1">
                  <Input id={`b-p${i}`} type="number" min={0} className="ml-auto w-32 text-right" value={item.price} onChange={(e) => setItems((all) => all.map((x, j) => (j === i ? { ...x, price: Number(e.target.value) } : x)))} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {save.isError && <p role="alert" className="mt-3 mb-0 text-danger-700">{errorMessage(save.error)}</p>}
        <div className="mt-4 flex gap-2">
          <Button type="submit" disabled={save.isPending}>Enregistrer la version</Button>
          <Button type="button" variant="ghost" onClick={onDone}>Annuler</Button>
        </div>
      </Section>
    </form>
  );
}

const issuerFields: { key: keyof Issuer; label: string; hint?: string; type?: string }[] = [
  { key: "name", label: "Raison sociale" },
  { key: "legalForm", label: "Forme juridique" },
  { key: "ridet", label: "RIDET" },
  { key: "tgcNumber", label: "Numéro TGC" },
  { key: "address", label: "Adresse" },
  { key: "phone", label: "Téléphone" },
  { key: "email", label: "E-mail", type: "email" },
  { key: "copyEmail", label: "Copie des envois", type: "email", hint: "facturation@… : reçoit une copie de chaque e-mail envoyé." },
  { key: "bank", label: "Banque" },
  { key: "iban", label: "IBAN" },
  { key: "paymentTerms", label: "Conditions de paiement", hint: "Imprimées sur chaque facture." },
  { key: "latePenalties", label: "Pénalités de retard", hint: "Mention obligatoire : à faire valider par le comptable." },
  { key: "footer", label: "Pied de page" },
];

/** Mentions de l'éditeur (recopiées dans chaque facture à sa validation) et taux de TGC datés. */
export function IssuerTab() {
  const query = useBillingSettings();
  if (!query.data) {
    return query.isError ? <p role="alert" className="text-danger-700">{errorMessage(query.error)}</p> : <p className="text-muted-foreground">Chargement…</p>;
  }
  return (
    <div className="grid items-start gap-4 lg:grid-cols-[3fr_2fr]">
      <IssuerForm issuer={query.data.issuer} missing={query.data.missingMentions} />
      <TgcRates />
    </div>
  );
}

function IssuerForm({ issuer, missing }: { issuer: Issuer; missing: string[] }) {
  const [form, setForm] = useState(issuer);
  const [saved, setSaved] = useState(false);
  const save = useBillingWrite(billingApi.saveIssuer);
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate(form, { onSuccess: () => setSaved(true) });
      }}
    >
      <Section title="Mentions de l’éditeur">
        {missing.length > 0 && (
          <div role="status" className="mb-4 flex gap-3 rounded-md bg-warning-100 px-4 py-3 text-warning-700">
            <AlertTriangle aria-hidden className="mt-0.5 size-5 shrink-0" />
            <span>Aucune facture ne peut être validée sans : {missing.join(", ")}.</span>
          </div>
        )}
        <p className="m-0 mb-4 text-small text-muted-foreground">
          Recopiées dans chaque facture à sa validation : modifier ici ne change pas les factures déjà émises. Mentions à faire valider par le
          comptable.
        </p>
        <div className="grid gap-3 md:grid-cols-2">
          {issuerFields.map((f) => (
            <div key={f.key} className={f.key === "address" || f.key === "paymentTerms" || f.key === "latePenalties" || f.key === "footer" ? "flex flex-col gap-1.5 md:col-span-2" : "flex flex-col gap-1.5"}>
              <Label htmlFor={`e-${f.key}`}>{f.label}</Label>
              <Input
                id={`e-${f.key}`}
                type={f.type ?? "text"}
                value={form[f.key] ?? ""}
                onChange={(e) => {
                  setSaved(false);
                  setForm((x) => ({ ...x, [f.key]: e.target.value || null }));
                }}
              />
              {f.hint && <span className="text-small text-muted-foreground">{f.hint}</span>}
            </div>
          ))}
        </div>
        {save.isError && <p role="alert" className="mt-3 mb-0 text-danger-700">{errorMessage(save.error)}</p>}
        <div className="mt-4 flex items-center gap-3">
          <Button type="submit" disabled={save.isPending}>Enregistrer</Button>
          {saved && <span role="status" className="text-success-700">Mentions enregistrées.</span>}
        </div>
      </Section>
    </form>
  );
}

function TgcRates() {
  const query = useBillingSettings();
  const [nature, setNature] = useState<TgcNature>("ABONNEMENT");
  const [value, setValue] = useState(11);
  const [from, setFrom] = useState(today());
  const [note, setNote] = useState("");
  const add = useBillingWrite(billingApi.newRate);
  const rates = query.data?.tgcRates ?? [];
  return (
    <Section title="Taux de TGC">
      <table className="w-full border-collapse">
        <thead className="text-left text-label text-muted-foreground">
          <tr>
            <th className="py-1.5 pr-2">Nature</th>
            <th className="py-1.5 pr-2 text-right">Taux</th>
            <th className="py-1.5">Validité</th>
          </tr>
        </thead>
        <tbody>
          {rates.map((r) => (
            <tr key={r.id} className="border-t border-border-subtle align-top">
              <td className="py-1.5 pr-2">{natureLabels[r.nature]}</td>
              <td className="py-1.5 pr-2 text-right tabular-nums">{rate(r.rate)}</td>
              <td className="py-1.5">
                du {formatDay(r.validFrom)}
                {r.validTo ? ` au ${formatDay(r.validTo)}` : <> <Pill tone="ok">en cours</Pill></>}
                {r.note && <div className="text-small text-muted-foreground">{r.note}</div>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <form
        className="mt-4 flex flex-col gap-3 border-t border-border-subtle pt-4"
        onSubmit={(e) => {
          e.preventDefault();
          add.mutate({ nature, rate: Math.round(value * 10), validFrom: from, note: note || null });
        }}
      >
        <h3 className="m-0 text-title-3">Nouveau taux</h3>
        <div className="grid grid-cols-3 gap-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="t-nature">Nature</Label>
            <select id="t-nature" className="h-9 rounded-md border border-input bg-card px-2" value={nature} onChange={(e) => setNature(e.target.value as TgcNature)}>
              {(Object.keys(natureLabels) as TgcNature[]).map((n) => <option key={n} value={n}>{natureLabels[n]}</option>)}
            </select>
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="t-taux">Taux (%)</Label>
            <Input id="t-taux" type="number" step="0.1" min={0} max={100} value={value} onChange={(e) => setValue(Number(e.target.value))} />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="t-from">À partir du</Label>
            <Input id="t-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </div>
        </div>
        <Input aria-label="Note" placeholder="Note (référence du texte, avis du comptable)" value={note} onChange={(e) => setNote(e.target.value)} />
        {add.isError && <p role="alert" className="m-0 text-danger-700">{errorMessage(add.error)}</p>}
        <Button type="submit" variant="outline" className="self-start" disabled={add.isPending}>Ajouter le taux</Button>
      </form>
    </Section>
  );
}

import { useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router";
import { AlertTriangle, Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Pill } from "@/components/ui/pill";
import { formatDay, moduleLabels } from "@/features/parc/labels";
import { errorMessage } from "@/lib/api";
import { cn } from "@/lib/utils";
import { billingApi, useBillingWrite, useContract, useInvoices, type Contract, type ContractInput, type ContractSaved, type Quote } from "./api";
import { planLabels, statePill, tierLabels, today, xpf } from "./labels";
import { ordersApi, useOrderAction } from "@/features/orders/api";

const modules = ["INSCRIPTIONS", "FACTURATION", "COMPTABILITE", "PERSONNEL", "REPAS", "PORTAIL_PARENTS"];

function Card({ title, aside, children, className }: { title: string; aside?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cn("rounded-lg border border-border-subtle bg-card p-5 shadow-1", className)}>
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-3">
        <h2 className="m-0 font-display text-title-3">{title}</h2>
        {aside && <span className="text-small text-muted-foreground">{aside}</span>}
      </div>
      {children}
    </section>
  );
}

/** Contrat d'abonnement de la crèche : devis en direct, signature, avenant, résiliation, prochaine facture. */
export function ContractCard({ slug, places }: { slug: string; places: number | null }) {
  const query = useContract(slug);
  const [editing, setEditing] = useState(false);
  const [saved, setSaved] = useState<ContractSaved | null>(null);
  const prepare = useBillingWrite(() => billingApi.prepareNext(slug));
  const [ending, setEnding] = useState("");
  const terminate = useBillingWrite((endsOn: string) => billingApi.terminate(slug, endsOn));

  if (query.isPending) {
    return <p className="text-muted-foreground">Chargement du contrat…</p>;
  }
  if (query.isError) {
    return <p role="alert" className="text-danger-700">{errorMessage(query.error)}</p>;
  }
  const view = query.data;
  const c = view.current;
  if (editing) {
    return (
      <ContractForm slug={slug} places={places} current={c} onDone={(s) => { setSaved(s); setEditing(false); }} onCancel={() => setEditing(false)} />
    );
  }
  if (!c) {
    return (
      <Card title="Contrat d’abonnement">
        <p className="m-0 mb-3 text-muted-foreground">Pas encore de contrat : il fixe le prix (barème du jour de signature) et prépare les factures.</p>
        <Button onClick={() => setEditing(true)}>Saisir le contrat</Button>
      </Card>
    );
  }
  return (
    <div className="grid items-start gap-4 lg:grid-cols-2">
      <Card title="Contrat d’abonnement" aside={view.priceListLabel ?? undefined}>
        {saved && (saved.complementInvoiceId || saved.toCredit > 0) && (
          <div role="status" className="mb-3 rounded-md bg-info-100 px-3 py-2 text-info-700">
            {saved.complementInvoiceId && (
              <>Complément au prorata préparé en brouillon : <Link className="underline" to={`/factures?id=${saved.complementInvoiceId}`}>le voir</Link>. </>
            )}
            {saved.toCredit > 0 && `${xpf(saved.toCredit)} HT à rendre par un avoir sur la facture ${saved.toCreditInvoiceNumber}.`}
          </div>
        )}
        <dl className="m-0 grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1.5">
          <dt className="text-muted-foreground">Formule</dt>
          <dd className="m-0 font-semibold">{planLabels[c.plan]} {view.monthly ? tierLabels[view.monthly.tier] : ""} · {c.places} places</dd>
          <dt className="text-muted-foreground">Modules ajoutés</dt>
          <dd className="m-0 font-semibold">{c.modules.length ? c.modules.map((m) => moduleLabels[m] ?? m).join(", ") : "aucun"}</dd>
          <dt className="text-muted-foreground">Équipement en plus</dt>
          <dd className="m-0 font-semibold">{c.extraDesktops} PC · {c.extraTablets} tablette{c.extraTablets > 1 ? "s" : ""}{c.premiumSupport ? " · support Premium" : ""}</dd>
          <dt className="text-muted-foreground">Engagement</dt>
          <dd className="m-0 font-semibold">{c.engagementYears} an{c.engagementYears > 1 ? "s" : ""} · paiement {c.rhythm === "ANNUEL" ? "annuel d’avance" : "mensuel"}</dd>
          <dt className="text-muted-foreground">Depuis le</dt>
          <dd className="m-0 font-semibold">{formatDay(c.startsOn)}{c.endsOn ? ` · fin le ${formatDay(c.endsOn)}` : ""} (signé le {formatDay(c.signedOn)})</dd>
          <dt className="text-muted-foreground">Prix</dt>
          <dd className="m-0 font-semibold">{xpf(view.monthly?.total)} HT par mois · {xpf(view.period?.total)} HT par {c.rhythm === "ANNUEL" ? "an" : "mois"}</dd>
        </dl>
        {view.next && (
          <p className="m-0 mt-3 text-small text-muted-foreground">
            Prochaine période : {formatDay(view.next.from)} – {formatDay(view.next.to)}, brouillon préparé automatiquement à partir du {formatDay(view.next.preparedFrom)}.
          </p>
        )}
        {(prepare.isError || terminate.isError) && <p role="alert" className="mt-2 mb-0 text-danger-700">{errorMessage(prepare.error ?? terminate.error)}</p>}
        {prepare.data && (
          <p role="status" className="mt-2 mb-0 text-success-700">
            Brouillon préparé : <Link className="underline" to={`/factures?id=${prepare.data.summary.id}`}>{prepare.data.summary.subject}</Link>
          </p>
        )}
        <div className="mt-4 flex flex-wrap items-center gap-2">
          <Button size="sm" variant="outline" onClick={() => setEditing(true)}>Avenant ou correction…</Button>
          {view.next && <Button size="sm" variant="outline" disabled={prepare.isPending} onClick={() => prepare.mutate(undefined)}>Préparer la prochaine facture</Button>}
          {!c.endsOn && (
            <span className="ml-auto flex items-center gap-2">
              <Input aria-label="Date de fin du contrat" type="date" className="h-8 w-40" value={ending} onChange={(e) => setEnding(e.target.value)} />
              <Button size="sm" variant="ghost" disabled={!ending || terminate.isPending} onClick={() => terminate.mutate(ending)}>Résilier</Button>
            </span>
          )}
        </div>
      </Card>
      {view.expected && (
        <Card title="Abonnement attendu de l’instance">
          <dl className="m-0 grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1.5">
            <dt className="text-muted-foreground">Modules</dt>
            <dd className="m-0 font-semibold">{view.expected.modules.map((m) => moduleLabels[m] ?? m).join(", ") || "base seule"}</dd>
            <dt className="text-muted-foreground">Places, PC, tablettes</dt>
            <dd className="m-0 font-semibold">{view.expected.places} · {view.expected.desktops} · {view.expected.tablets}</dd>
            <dt className="text-muted-foreground">Payé jusqu’au</dt>
            <dd className="m-0 font-semibold">{view.expected.validUntil ? formatDay(view.expected.validUntil) : "aucune période validée"}</dd>
          </dl>
          {view.expected.differences.length === 0 ? (
            <p className="m-0 mt-3 flex items-center gap-2 text-success-700"><Check aria-hidden className="size-4" /> L’instance est alignée sur le contrat.</p>
          ) : (
            <ul className="m-0 mt-3 flex list-none flex-col gap-1 p-0 text-warning-700">
              {view.expected.differences.map((d) => (
                <li key={d} className="flex gap-2"><AlertTriangle aria-hidden className="mt-0.5 size-4 shrink-0" />{d}</li>
              ))}
            </ul>
          )}
          {view.expected.differences.length > 0 && view.expected.validUntil && <AlignButton slug={slug} />}
          {!view.expected.validUntil && (
            <p className="m-0 mt-3 text-small text-muted-foreground">Rien à reporter tant qu’aucune période d’abonnement n’est payée.</p>
          )}
        </Card>
      )}
    </div>
  );
}

function ContractForm({ slug, places, current, onDone, onCancel }: { slug: string; places: number | null; current: Contract | null; onDone: (s: ContractSaved) => void; onCancel: () => void }) {
  const [form, setForm] = useState<ContractInput>(() => ({
    plan: current?.plan ?? "GESTION",
    places: current?.places ?? places ?? 20,
    modules: current?.modules ?? [],
    extraDesktops: current?.extraDesktops ?? 0,
    extraTablets: current?.extraTablets ?? 0,
    premiumSupport: current?.premiumSupport ?? false,
    engagementYears: current?.engagementYears ?? 1,
    rhythm: current?.rhythm ?? "ANNUEL",
    extraStructure: current?.extraStructure ?? false,
    freeDiscount: current?.freeDiscount ?? 0,
    freeDiscountReason: current?.freeDiscountReason ?? null,
    startsOn: current?.startsOn ?? today(),
    signedOn: current?.signedOn ?? today(),
    changeOn: current ? today() : null,
  }));
  const [quote, setQuote] = useState<Quote[] | null>(null);
  const [quoteError, setQuoteError] = useState<string | null>(null);
  const save = useBillingWrite((input: ContractInput) => billingApi.saveContract(slug, input));
  const set = <K extends keyof ContractInput>(key: K, value: ContractInput[K]) => setForm((f) => ({ ...f, [key]: value }));

  useEffect(() => {
    const timer = window.setTimeout(() => {
      billingApi.quote(slug, form).then((q) => { setQuote(q); setQuoteError(null); }, (e) => { setQuote(null); setQuoteError(errorMessage(e)); });
    }, 250);
    return () => window.clearTimeout(timer);
  }, [slug, form]);

  const select = "h-9 rounded-md border border-input bg-card px-3";
  const field = (id: string, label: string, input: ReactNode, hint?: string) => (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      {input}
      {hint && <span className="text-small text-muted-foreground">{hint}</span>}
    </div>
  );

  return (
    <div className="grid items-start gap-4 lg:grid-cols-[3fr_2fr]">
      <form onSubmit={(e) => { e.preventDefault(); save.mutate(form, { onSuccess: onDone }); }}>
        <Card title={current ? "Avenant ou correction du contrat" : "Nouveau contrat"}>
          <div className="grid gap-3 md:grid-cols-3">
            {field("c-plan", "Formule", <select id="c-plan" className={select} value={form.plan} onChange={(e) => set("plan", e.target.value as ContractInput["plan"])}>
              {Object.entries(planLabels).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>)}
            {field("c-places", "Places agréées", <Input id="c-places" type="number" min={1} value={form.places} onChange={(e) => set("places", Number(e.target.value))} />)}
            {field("c-rythme", "Paiement", <select id="c-rythme" className={select} value={form.rhythm} onChange={(e) => set("rhythm", e.target.value as ContractInput["rhythm"])}>
              <option value="ANNUEL">Annuel d’avance</option>
              <option value="MENSUEL">Mensuel</option>
            </select>)}
            {field("c-pc", "PC en plus", <Input id="c-pc" type="number" min={0} value={form.extraDesktops} onChange={(e) => set("extraDesktops", Number(e.target.value))} />)}
            {field("c-tab", "Tablettes en plus", <Input id="c-tab" type="number" min={0} value={form.extraTablets} onChange={(e) => set("extraTablets", Number(e.target.value))} />)}
            {field("c-eng", "Engagement", <select id="c-eng" className={select} value={form.engagementYears} onChange={(e) => set("engagementYears", Number(e.target.value) as 1 | 3)}>
              <option value={1}>1 an</option>
              <option value={3}>3 ans</option>
            </select>)}
          </div>
          <fieldset className="m-0 mt-4 border-0 p-0">
            <legend className="mb-1.5 text-label">Modules ajoutés à la formule</legend>
            <div className="flex flex-wrap gap-x-4 gap-y-1.5">
              {modules.map((m) => (
                <label key={m} className="flex items-center gap-1.5">
                  <input type="checkbox" checked={form.modules.includes(m)} onChange={(e) => set("modules", e.target.checked ? [...form.modules, m] : form.modules.filter((x) => x !== m))} />
                  {moduleLabels[m]}
                </label>
              ))}
            </div>
          </fieldset>
          <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1.5">
            <label className="flex items-center gap-1.5"><input type="checkbox" checked={form.premiumSupport} onChange={(e) => set("premiumSupport", e.target.checked)} /> Support Premium</label>
            <label className="flex items-center gap-1.5"><input type="checkbox" checked={form.extraStructure} onChange={(e) => set("extraStructure", e.target.checked)} /> Structure supplémentaire du même client</label>
          </div>
          <div className="mt-3 grid gap-3 md:grid-cols-3">
            {field("c-remise", "Remise libre (%)", <Input id="c-remise" type="number" step="0.1" min={0} max={100} value={form.freeDiscount / 10} onChange={(e) => set("freeDiscount", Math.round(Number(e.target.value) * 10))} />)}
            <div className="md:col-span-2">
              {field("c-motif", "Motif de la remise", <Input id="c-motif" value={form.freeDiscountReason ?? ""} required={form.freeDiscount > 0} onChange={(e) => set("freeDiscountReason", e.target.value || null)} />)}
            </div>
            {field("c-debut", "Début", <Input id="c-debut" type="date" value={form.startsOn} onChange={(e) => set("startsOn", e.target.value)} />)}
            {field("c-signe", "Signé le", <Input id="c-signe" type="date" value={form.signedOn} onChange={(e) => set("signedOn", e.target.value)} />, "Fixe la version du barème.")}
            {current && field("c-effet", "Effet de l’avenant", <Input id="c-effet" type="date" value={form.changeOn ?? ""} onChange={(e) => set("changeOn", e.target.value || null)} />, "Si la période est déjà facturée : complément ou avoir au prorata.")}
          </div>
          {save.isError && <p role="alert" className="mt-3 mb-0 text-danger-700">{errorMessage(save.error)}</p>}
          <div className="mt-4 flex gap-2">
            <Button type="submit" disabled={save.isPending}>Enregistrer le contrat</Button>
            <Button type="button" variant="ghost" onClick={onCancel}>Annuler</Button>
          </div>
        </Card>
      </form>
      <Card title="Devis" aside={quote?.[0] ? `tranche ${tierLabels[quote[0].tier]}` : undefined}>
        {quoteError && <p role="alert" className="m-0 text-danger-700">{quoteError}</p>}
        {quote && (
          <>
            {quote[0].onQuote && <p className="m-0 mb-2 text-warning-700">Au-delà de la plus grande tranche : prix sur devis, ajustez par une remise libre.</p>}
            <table className="w-full border-collapse">
              <tbody>
                {quote[1].lines.map((l) => (
                  <tr key={l.code} className="border-b border-border-subtle">
                    <td className="py-1.5 pr-2">{l.label}<div className="text-small text-muted-foreground">{l.detail}</div></td>
                    <td className="py-1.5 text-right tabular-nums">{xpf(l.amount)}</td>
                  </tr>
                ))}
                <tr className="font-bold">
                  <td className="py-1.5">Total HT {form.rhythm === "ANNUEL" ? "par an" : "par mois"}</td>
                  <td className="py-1.5 text-right tabular-nums">{xpf(quote[1].total)}</td>
                </tr>
              </tbody>
            </table>
            <p className="m-0 mt-2 text-small text-muted-foreground">Soit {xpf(quote[0].total)} HT par mois, TGC en sus.</p>
          </>
        )}
      </Card>
    </div>
  );
}

/** Factures de la crèche, avec le solde. */
export function CrecheInvoices({ slug }: { slug: string }) {
  const query = useInvoices();
  const rows = (query.data ?? []).filter((s) => s.crecheSlug === slug);
  const due = rows.filter((s) => s.type === "FACTURE" && s.status === "VALIDEE").reduce((sum, s) => sum + Math.max(0, s.remaining), 0);
  return (
    <Card title="Factures" aside={`Solde dû : ${xpf(due)}`}>
      {rows.length === 0 ? (
        <p className="m-0 text-muted-foreground">Aucune facture pour cette crèche.</p>
      ) : (
        <table className="w-full border-collapse">
          <thead className="text-left text-label text-muted-foreground">
            <tr><th className="py-1.5 pr-2">Numéro</th><th className="py-1.5 pr-2">Objet</th><th className="py-1.5 pr-2">Émise</th><th className="py-1.5 pr-2 text-right">TTC</th><th className="py-1.5 pr-2 text-right">Reste dû</th><th className="py-1.5">État</th></tr>
          </thead>
          <tbody>
            {rows.map((s) => {
              const pill = statePill(s);
              return (
                <tr key={s.id} className="border-t border-border-subtle">
                  <td className="py-1.5 pr-2"><Link className="font-semibold hover:underline" to={`/factures?id=${s.id}`}>{s.number ?? "brouillon"}</Link></td>
                  <td className="py-1.5 pr-2">{s.subject}</td>
                  <td className="py-1.5 pr-2 tabular-nums">{s.issuedOn ? formatDay(s.issuedOn) : "—"}</td>
                  <td className="py-1.5 pr-2 text-right tabular-nums">{xpf(s.totalTtc)}</td>
                  <td className="py-1.5 pr-2 text-right tabular-nums">{s.status === "BROUILLON" || s.type === "AVOIR" ? "—" : xpf(s.remaining)}</td>
                  <td className="py-1.5"><Pill tone={pill.tone}>{pill.label}</Pill></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      <div className="mt-3 flex gap-2">
        <Link to={`/factures?nouvelle=${slug}`} className="text-lagon-600 hover:underline">Nouvelle facture</Link>
        <span className="text-muted-foreground">·</span>
        <Link to={`/factures?paiement=${slug}`} className="text-lagon-600 hover:underline">Saisir un paiement</Link>
      </div>
    </Card>
  );
}

/** Ordre « abonnement » : formule, modules, places, PC, tablettes et échéance payée, reportés dans l'instance (code redemandé). */
function AlignButton({ slug }: { slug: string }) {
  const give = useOrderAction(() => ordersApi.give({ type: "ABONNEMENT", slug, reason: "Abonnement aligné sur le contrat" }));
  return (
    <div className="mt-3 flex flex-wrap items-center gap-2">
      <Button size="sm" disabled={give.isPending || give.isSuccess} onClick={() => give.mutate(undefined)}>Reporter le contrat dans l’instance</Button>
      {give.isSuccess && <span role="status" className="text-small text-success-700">Ordre transmis : l’hôte l’exécute dans la minute.</span>}
      {give.isError && <span role="alert" className="text-small text-danger-700">{errorMessage(give.error)}</span>}
    </div>
  );
}

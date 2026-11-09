import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { Link, useSearchParams } from "react-router";
import { Download, Info, Plus, Trash2 } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Pill } from "@/components/ui/pill";
import { useConfirmed } from "@/features/auth/Confirm";
import { useParc } from "@/features/parc/api";
import { formatDay } from "@/features/parc/labels";
import { errorMessage } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { cn } from "@/lib/utils";
import {
  billingApi,
  useBillingSettings,
  useBillingWrite,
  useInvoice,
  useInvoices,
  type DraftInput,
  type InvoiceDetail,
  type InvoiceSummary,
  type LineInput,
  type TgcNature,
} from "./api";
import { methodLabels, natureLabels, rate, statePill, today, xpf } from "./labels";

type Filter = "toutes" | "brouillons" | "envoyer" | "retard" | "payees" | "avoirs";
type Panel = { kind: "nouvelle"; slug: string | null } | { kind: "modifier"; id: string } | { kind: "paiement"; slug: string | null } | { kind: "avoir"; id: string } | null;

const filters: { id: Filter; label: string; match: (s: InvoiceSummary) => boolean }[] = [
  { id: "toutes", label: "Toutes", match: () => true },
  { id: "brouillons", label: "Brouillons", match: (s) => s.state === "BROUILLON" },
  { id: "envoyer", label: "À envoyer", match: (s) => s.status === "VALIDEE" && !s.sentAt && s.type === "FACTURE" && s.remaining > 0 },
  { id: "retard", label: "En retard", match: (s) => s.state === "RETARD" },
  { id: "payees", label: "Payées", match: (s) => s.state === "PAYEE" },
  { id: "avoirs", label: "Avoirs", match: (s) => s.type === "AVOIR" },
];

/** Factures (maquette « Factures ») : liste filtrée, aperçu, brouillons à valider, paiements et avoirs. */
export function InvoicesPage() {
  const query = useInvoices();
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState<Filter>("toutes");
  const [panel, setPanel] = useState<Panel>(() =>
    params.get("nouvelle") != null ? { kind: "nouvelle", slug: params.get("nouvelle") || null } : params.get("paiement") != null ? { kind: "paiement", slug: params.get("paiement") || null } : null,
  );
  const selected = params.get("id");
  const select = (id: string | null) => {
    setPanel(null);
    setParams(id ? { id } : {}, { replace: true });
  };
  const confirmed = useConfirmed();
  const validateAll = useBillingWrite((ids: string[]) => confirmed(() => billingApi.validateMany(ids)));

  const invoices = query.data ?? [];
  const drafts = invoices.filter((s) => s.state === "BROUILLON");
  const shown = useMemo(() => invoices.filter(filters.find((f) => f.id === filter)!.match), [invoices, filter]);
  const year = new Date().getFullYear();

  return (
    <>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="m-0 font-display text-display">Factures</h1>
          <p className="mt-1 text-muted-foreground">
            Exercice {year} · numérotation F{year}-0001 et suivantes, avoirs AV{year}-…
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => setPanel({ kind: "paiement", slug: null })}>
            Saisir un paiement
          </Button>
          <Button onClick={() => setPanel({ kind: "nouvelle", slug: null })}>
            <Plus aria-hidden />
            Nouvelle facture
          </Button>
        </div>
      </div>

      {drafts.length > 0 && (
        <div role="status" className="flex gap-3 rounded-md bg-info-100 px-4 py-3 text-info-700">
          <Info aria-hidden className="mt-0.5 size-5 shrink-0" />
          <div>
            <b>
              {drafts.length} brouillon{drafts.length > 1 ? "s" : ""}
            </b>{" "}
            attend{drafts.length > 1 ? "ent" : ""} votre validation. Une fois validée, une facture n’est plus modifiable : on la corrige par
            un avoir. Son e-mail est préparé dans la boîte d’envoi.
            <div className="mt-2 flex flex-wrap items-center gap-3">
              <Button size="sm" disabled={validateAll.isPending} onClick={() => validateAll.mutate(drafts.map((d) => d.id))}>
                Valider {drafts.length > 1 ? `les ${drafts.length} brouillons` : "le brouillon"}
              </Button>
              {validateAll.data && (
                <span>
                  {validateAll.data.filter((r) => r.number).map((r) => r.number).join(", ") || "Aucune facture validée."}
                  {validateAll.data.some((r) => r.error) && ` · ${validateAll.data.find((r) => r.error)!.error}`}
                </span>
              )}
              {validateAll.isError && <span className="text-danger-700">{errorMessage(validateAll.error)}</span>}
            </div>
          </div>
        </div>
      )}

      <div role="group" aria-label="Filtrer" className="flex flex-wrap gap-2">
        {filters.map((f) => {
          const count = invoices.filter(f.match).length;
          return (
            <button
              key={f.id}
              type="button"
              aria-pressed={filter === f.id}
              onClick={() => setFilter(f.id)}
              className={cn("rounded-full border border-border bg-card px-3 py-1 text-label", filter === f.id && "border-lagon-700 bg-lagon-700 text-white")}
            >
              {f.label}
              {f.id !== "toutes" && f.id !== "payees" && f.id !== "avoirs" ? ` · ${count}` : ""}
            </button>
          );
        })}
      </div>

      <div className="grid items-start gap-4 xl:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)]">
        <section className="rounded-lg border border-border-subtle bg-card p-2 shadow-1">
          {query.isPending ? (
            <p className="m-0 p-4 text-muted-foreground">Chargement…</p>
          ) : shown.length === 0 ? (
            <p className="m-0 p-4 text-muted-foreground">{invoices.length === 0 ? "Aucune facture pour l’instant." : "Aucune facture dans ce filtre."}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full border-collapse">
                <thead className="border-b border-border-subtle text-left text-label text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2.5">Numéro</th>
                    <th className="px-3 py-2.5">Client</th>
                    <th className="px-3 py-2.5">Objet</th>
                    <th className="px-3 py-2.5">Émise</th>
                    <th className="px-3 py-2.5">Échéance</th>
                    <th className="px-3 py-2.5 text-right">TTC</th>
                    <th className="px-3 py-2.5 text-right">Reste dû</th>
                    <th className="px-3 py-2.5">État</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((s) => {
                    const pill = statePill(s);
                    return (
                      <tr
                        key={s.id}
                        className={cn("cursor-pointer hover:bg-lagon-50", selected === s.id && "bg-lagon-50")}
                        onClick={() => select(s.id)}
                      >
                        <td className="border-b border-border-subtle px-3 py-2.5 whitespace-nowrap">
                          <button type="button" className="font-semibold hover:underline" onClick={(e) => (e.stopPropagation(), select(s.id))}>
                            {s.number ?? <span className="font-normal text-muted-foreground">brouillon</span>}
                          </button>
                        </td>
                        <td className="border-b border-border-subtle px-3 py-2.5">{s.crecheName ?? s.clientName}</td>
                        <td className="border-b border-border-subtle px-3 py-2.5">{s.subject}</td>
                        <td className="border-b border-border-subtle px-3 py-2.5 tabular-nums">{s.issuedOn ? formatDay(s.issuedOn) : "—"}</td>
                        <td className="border-b border-border-subtle px-3 py-2.5 tabular-nums">{s.dueOn ? formatDay(s.dueOn) : "—"}</td>
                        <td className="border-b border-border-subtle px-3 py-2.5 text-right tabular-nums">{s.type === "AVOIR" ? `-${xpf(s.totalTtc)}` : xpf(s.totalTtc)}</td>
                        <td className={cn("border-b border-border-subtle px-3 py-2.5 text-right tabular-nums", s.state === "RETARD" && "font-bold")}>
                          {s.status === "BROUILLON" || s.type === "AVOIR" ? "—" : xpf(s.remaining)}
                        </td>
                        <td className="border-b border-border-subtle px-3 py-2.5">
                          <Pill tone={pill.tone}>{pill.label}</Pill>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </section>

        <div className="flex flex-col gap-3">
          {panel?.kind === "nouvelle" && <InvoiceForm slug={panel.slug} onDone={(id) => select(id)} onCancel={() => setPanel(null)} />}
          {panel?.kind === "modifier" && <EditInvoice id={panel.id} onDone={(id) => select(id)} onCancel={() => setPanel(null)} />}
          {panel?.kind === "paiement" && <PaymentForm slug={panel.slug} onDone={() => setPanel(null)} />}
          {panel?.kind === "avoir" && <CreditForm id={panel.id} onDone={(id) => select(id)} onCancel={() => setPanel(null)} />}
          {!panel && selected && (
            <InvoicePreview
              id={selected}
              onEdit={() => setPanel({ kind: "modifier", id: selected })}
              onCredit={() => setPanel({ kind: "avoir", id: selected })}
              onPay={(slug) => setPanel({ kind: "paiement", slug })}
              onDeleted={() => select(null)}
            />
          )}
          {!panel && !selected && (
            <section className="rounded-lg border border-dashed border-border bg-card/60 p-6 text-muted-foreground">
              Choisissez une facture pour voir son aperçu, ses paiements et ses envois.
            </section>
          )}
        </div>
      </div>
    </>
  );
}

// --- Aperçu -----------------------------------------------------------------------------------------------------

function InvoicePreview({ id, onEdit, onCredit, onPay, onDeleted }: { id: string; onEdit: () => void; onCredit: () => void; onPay: (slug: string | null) => void; onDeleted: () => void }) {
  const query = useInvoice(id);
  const confirmed = useConfirmed();
  const validate = useBillingWrite((invoiceId: string) => confirmed(() => billingApi.validate(invoiceId)));
  const remove = useBillingWrite((invoiceId: string) => billingApi.deleteDraft(invoiceId));
  const pause = useBillingWrite((input: { id: string; paused: boolean }) => billingApi.pause(input.id, input.paused));

  if (query.isPending) {
    return <p className="text-muted-foreground">Chargement de la facture…</p>;
  }
  if (query.isError) {
    return (
      <p role="alert" className="text-danger-700">
        {errorMessage(query.error)}
      </p>
    );
  }
  const d = query.data;
  const s = d.summary;
  const draft = s.status === "BROUILLON";
  const error = validate.error ?? remove.error ?? pause.error;
  return (
    <>
      <Paper detail={d} />
      {error && (
        <p role="alert" className="m-0 text-danger-700">
          {errorMessage(error)}
        </p>
      )}
      <div className="flex flex-wrap items-center gap-2">
        {draft ? (
          <>
            <Button size="sm" disabled={validate.isPending} onClick={() => validate.mutate(id)}>
              Valider la facture
            </Button>
            <Button size="sm" variant="outline" onClick={onEdit}>
              Modifier
            </Button>
            <Button size="sm" variant="ghost" onClick={() => remove.mutate(id, { onSuccess: onDeleted })}>
              <Trash2 aria-hidden />
              Supprimer le brouillon
            </Button>
          </>
        ) : (
          <>
            <a className={buttonVariants({ variant: "outline", size: "sm" })} href={`/api/v1/factures/${id}/pdf`} target="_blank" rel="noreferrer">
              <Download aria-hidden />
              PDF
            </a>
            {s.type === "FACTURE" && s.remaining > 0 && (
              <Button size="sm" variant="outline" onClick={() => onPay(s.crecheSlug)}>
                Saisir un paiement
              </Button>
            )}
            {s.type === "FACTURE" && d.creditable.some((l) => l.creditable > 0) && (
              <Button size="sm" variant="outline" onClick={onCredit}>
                Avoir…
              </Button>
            )}
            {s.type === "FACTURE" && s.remaining > 0 && (
              <Button size="sm" variant="ghost" className="ml-auto" onClick={() => pause.mutate({ id, paused: !s.remindersPaused })}>
                {s.remindersPaused ? "Reprendre les relances" : "Suspendre les relances"}
              </Button>
            )}
          </>
        )}
      </div>
      <History detail={d} />
    </>
  );
}

/** Aperçu façon papier (maquette) ; le PDF validé fait foi. */
function Paper({ detail: d }: { detail: InvoiceDetail }) {
  const s = d.summary;
  const credit = s.type === "AVOIR";
  return (
    <article aria-label={`Aperçu ${credit ? "de l’avoir" : "de la facture"} ${s.number ?? "brouillon"}`} className="rounded-md border border-border bg-white p-7 text-small text-ink shadow-2">
      <div className="mb-5 flex justify-between gap-4">
        <div>
          <b className="font-display text-title-2 text-lagon-700">{d.issuer.name ?? "Ti’Cagou"}</b>
          <div className="text-muted-foreground">{[d.issuer.ridet ? `RIDET ${d.issuer.ridet}` : null, d.issuer.address].filter(Boolean).join(" · ") || "Mentions à compléter"}</div>
        </div>
        <div className="text-right">
          <b className="text-title-3">
            {credit ? "Avoir" : "Facture"} {s.number ?? "(brouillon)"}
          </b>
          <div>{s.issuedOn ? `Émise le ${formatDay(s.issuedOn)}` : "Numéro et date à la validation"}</div>
          {s.dueOn && !credit && <div>Échéance le {formatDay(s.dueOn)}</div>}
        </div>
      </div>
      <p className="m-0">
        <b>{d.client.name}</b>
        {d.client.crecheName && d.client.crecheName !== d.client.name && <><br />{d.client.crecheName}</>}
        {d.client.address && <><br />{d.client.address}</>}
      </p>
      <p className="m-0 mt-2 text-muted-foreground">
        {s.subject}
        {s.correctsNumber && ` · corrige ${s.correctsNumber}`}
      </p>
      <table className="mt-3.5 w-full border-collapse">
        <thead className="text-left text-muted-foreground">
          <tr>
            <th className="py-1.5 pr-2 font-normal">Désignation</th>
            <th className="py-1.5 pr-2 text-right font-normal">Qté</th>
            <th className="py-1.5 text-right font-normal">Montant HT</th>
          </tr>
        </thead>
        <tbody>
          {d.lines.map((l) => (
            <tr key={l.id} className="border-t border-border-subtle align-top">
              <td className="py-1.5 pr-2">
                {l.label}
                {l.detail && <div className="text-muted-foreground">{l.detail}</div>}
              </td>
              <td className="py-1.5 pr-2 text-right tabular-nums">{l.quantity}</td>
              <td className="py-1.5 text-right tabular-nums">{xpf(l.amount)}</td>
            </tr>
          ))}
          <tr className="border-t border-border">
            <td className="py-1.5">Total HT</td>
            <td />
            <td className="py-1.5 text-right tabular-nums">{xpf(s.totalHt)}</td>
          </tr>
          {d.rates.map((r) => (
            <tr key={r.rate}>
              <td className="py-1">TGC {rate(r.rate)}</td>
              <td />
              <td className="py-1 text-right tabular-nums">{xpf(r.tgc)}</td>
            </tr>
          ))}
          <tr className="font-bold">
            <td className="py-1.5">Total TTC</td>
            <td />
            <td className="py-1.5 text-right tabular-nums">{xpf(s.totalTtc)}</td>
          </tr>
        </tbody>
      </table>
      {!credit && s.status === "VALIDEE" && (
        <p className="m-0 mt-3 text-muted-foreground">
          {s.paid > 0 && `Déjà réglé : ${xpf(s.paid)} · `}
          {s.credited > 0 && `Avoirs : ${xpf(s.credited)} · `}
          Reste dû : {xpf(s.remaining)}
          {d.issuer.iban && (
            <>
              <br />
              Virement : {[d.issuer.bank, `IBAN ${d.issuer.iban}`, `référence ${s.number}`].filter(Boolean).join(" · ")}
            </>
          )}
        </p>
      )}
      {d.note && <p className="m-0 mt-3">{credit ? `Motif : ${d.note}` : d.note}</p>}
    </article>
  );
}

function History({ detail: d }: { detail: InvoiceDetail }) {
  const items: { at: string; text: ReactNode }[] = [];
  if (d.validatedAt) {
    items.push({ at: d.validatedAt, text: `Validée par ${d.validatedBy ?? "?"} · empreinte du PDF ${d.pdfSha256?.slice(0, 12)}…` });
  }
  d.allocations.forEach((a) => items.push({ at: `${a.receivedOn}T12:00:00Z`, text: `Paiement ${methodLabels[a.method] ?? a.method} ${a.reference ? `(${a.reference}) ` : ""}: ${xpf(a.amount)}` }));
  d.credits.forEach((c) => items.push({ at: c.issuedOn ? `${c.issuedOn}T12:00:00Z` : "", text: `Avoir ${c.number} : ${xpf(c.totalTtc)}` }));
  d.messages.forEach((m) =>
    items.push({
      at: m.sentAt ?? m.createdAt,
      text: (
        <>
          E-mail « {m.subject} » :{" "}
          {m.status === "ENVOYE" ? `envoyé à ${m.to}` : m.status === "ECARTE" ? "écarté" : <Link to="/envoi" className="text-lagon-600 hover:underline">en attente dans la boîte d’envoi</Link>}
        </>
      ),
    }),
  );
  if (items.length === 0) {
    return null;
  }
  return (
    <section className="rounded-lg border border-border-subtle bg-card p-4 shadow-1">
      <h2 className="m-0 mb-2 text-title-3">Historique</h2>
      <ul className="m-0 flex list-none flex-col gap-1.5 p-0 text-small">
        {items
          .sort((a, b) => a.at.localeCompare(b.at))
          .map((item, i) => (
            <li key={i}>
              <span className="text-muted-foreground tabular-nums">{item.at ? formatDateTime(new Date(item.at)).slice(0, 10) : ""}</span> · {item.text}
            </li>
          ))}
      </ul>
    </section>
  );
}

// --- Formulaires ------------------------------------------------------------------------------------------------

function FormCard({ title, children, onSubmit }: { title: string; children: ReactNode; onSubmit: (e: FormEvent) => void }) {
  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 className="m-0 font-display text-title-2">{title}</h2>
      {children}
    </form>
  );
}

function CrecheSelect({ id, value, onChange }: { id: string; value: string; onChange: (slug: string) => void }) {
  const parc = useParc();
  return (
    <select id={id} required className="h-9 rounded-md border border-input bg-card px-3" value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">Choisir…</option>
      {(parc.data?.creches ?? []).map((c) => (
        <option key={c.slug} value={c.slug}>
          {c.name}
          {c.clientName && c.clientName !== c.name ? ` (${c.clientName})` : ""}
        </option>
      ))}
    </select>
  );
}

const emptyLine = (): LineInput => ({ label: "", detail: null, quantity: 1, unitPrice: 0, tgcNature: "PRESTATION" });

function EditInvoice({ id, onDone, onCancel }: { id: string; onDone: (id: string) => void; onCancel: () => void }) {
  const query = useInvoice(id);
  if (!query.data) {
    return <p className="text-muted-foreground">Chargement…</p>;
  }
  return <InvoiceForm initial={query.data} slug={query.data.summary.crecheSlug} onDone={onDone} onCancel={onCancel} />;
}

export function InvoiceForm({ initial, slug, onDone, onCancel }: { initial?: InvoiceDetail; slug: string | null; onDone: (id: string) => void; onCancel: () => void }) {
  const settings = useBillingSettings();
  const [form, setForm] = useState<DraftInput>(() => ({
    crecheSlug: slug ?? "",
    subject: initial?.summary.subject ?? "",
    periodFrom: initial?.summary.periodFrom ?? null,
    periodTo: initial?.summary.periodTo ?? null,
    note: initial?.note ?? null,
    lines: initial?.lines.map((l) => ({ label: l.label, detail: l.detail, quantity: l.quantity, unitPrice: l.unitPrice, tgcNature: l.tgcNature })) ?? [emptyLine()],
  }));
  const save = useBillingWrite((input: DraftInput) => (initial ? billingApi.updateDraft(initial.summary.id, input) : billingApi.createDraft(input)));
  const current = settings.data?.versions.find((v) => !v.list.validTo || v.list.validTo >= today()) ?? settings.data?.versions[0];
  const services = current?.items.filter((i) => i.kind === "PRESTATION") ?? [];
  const setLine = (index: number, patch: Partial<LineInput>) => setForm((f) => ({ ...f, lines: f.lines.map((l, i) => (i === index ? { ...l, ...patch } : l)) }));
  const totalHt = form.lines.reduce((sum, l) => sum + l.quantity * l.unitPrice, 0);

  return (
    <FormCard
      title={initial ? "Modifier le brouillon" : "Nouvelle facture"}
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate(form, { onSuccess: (d) => onDone(d.summary.id) });
      }}
    >
      <div className="grid gap-3 md:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="fa-creche">Crèche</Label>
          {initial ? <span className="py-1.5 font-semibold">{initial.summary.crecheName}</span> : <CrecheSelect id="fa-creche" value={form.crecheSlug} onChange={(v) => setForm((f) => ({ ...f, crecheSlug: v }))} />}
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="fa-objet">Objet</Label>
          <Input id="fa-objet" required value={form.subject} onChange={(e) => setForm((f) => ({ ...f, subject: e.target.value }))} placeholder="Mise en route + formation" />
        </div>
      </div>
      <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
        <legend className="mb-1 text-label">Lignes</legend>
        {form.lines.map((line, i) => (
          <div key={i} className="grid grid-cols-[minmax(0,1fr)_64px_110px_120px_auto] items-end gap-2">
            <div className="flex flex-col gap-1">
              <Label htmlFor={`fa-l${i}`} className="sr-only">
                Désignation de la ligne {i + 1}
              </Label>
              <Input id={`fa-l${i}`} required placeholder="Désignation" value={line.label} onChange={(e) => setLine(i, { label: e.target.value })} />
              <Input aria-label={`Détail de la ligne ${i + 1}`} placeholder="Détail (facultatif)" value={line.detail ?? ""} onChange={(e) => setLine(i, { detail: e.target.value || null })} />
            </div>
            <Input aria-label={`Quantité de la ligne ${i + 1}`} type="number" min={1} value={line.quantity} onChange={(e) => setLine(i, { quantity: Number(e.target.value) })} />
            <Input aria-label={`Prix unitaire HT de la ligne ${i + 1}`} type="number" value={line.unitPrice} onChange={(e) => setLine(i, { unitPrice: Number(e.target.value) })} />
            <select aria-label={`Nature de la ligne ${i + 1}`} className="h-9 rounded-md border border-input bg-card px-2" value={line.tgcNature} onChange={(e) => setLine(i, { tgcNature: e.target.value as TgcNature })}>
              {(Object.keys(natureLabels) as TgcNature[]).map((n) => (
                <option key={n} value={n}>
                  {natureLabels[n]}
                </option>
              ))}
            </select>
            <Button type="button" variant="ghost" size="icon-sm" aria-label={`Retirer la ligne ${i + 1}`} disabled={form.lines.length === 1} onClick={() => setForm((f) => ({ ...f, lines: f.lines.filter((_, j) => j !== i) }))}>
              <Trash2 aria-hidden />
            </Button>
          </div>
        ))}
        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" variant="outline" size="sm" onClick={() => setForm((f) => ({ ...f, lines: [...f.lines, emptyLine()] }))}>
            <Plus aria-hidden />
            Ligne libre
          </Button>
          <select
            aria-label="Ajouter une prestation du barème"
            className="h-8 rounded-md border border-input bg-card px-2 text-small"
            value=""
            onChange={(e) => {
              const item = services.find((s) => s.code === e.target.value);
              if (item) {
                const line = { label: item.label, detail: null, quantity: 1, unitPrice: item.price, tgcNature: item.tgcNature };
                setForm((f) => ({ ...f, lines: f.lines.length === 1 && !f.lines[0].label ? [line] : [...f.lines, line] }));
              }
            }}
          >
            <option value="">Prestation du barème…</option>
            {services.map((s) => (
              <option key={s.code} value={s.code}>
                {s.label} — {xpf(s.price)}
                {s.unit ? ` / ${s.unit}` : ""}
              </option>
            ))}
          </select>
          <span className="ml-auto font-semibold">Total HT : {xpf(totalHt)}</span>
        </div>
      </fieldset>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="fa-note">Mention (facultatif)</Label>
        <Input id="fa-note" value={form.note ?? ""} onChange={(e) => setForm((f) => ({ ...f, note: e.target.value || null }))} />
      </div>
      {save.isError && (
        <p role="alert" className="m-0 text-danger-700">
          {errorMessage(save.error)}
        </p>
      )}
      <div className="flex gap-2">
        <Button type="submit" disabled={save.isPending}>
          Enregistrer le brouillon
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Annuler
        </Button>
      </div>
      <p className="m-0 text-small text-muted-foreground">La TGC est calculée au taux du jour d’émission, à la validation.</p>
    </FormCard>
  );
}

export function PaymentForm({ slug, onDone }: { slug: string | null; onDone: () => void }) {
  const invoices = useInvoices();
  const parc = useParc();
  const [creche, setCreche] = useState(slug ?? "");
  const [receivedOn, setReceivedOn] = useState(today());
  const [amount, setAmount] = useState(0);
  const [method, setMethod] = useState("VIREMENT");
  const [reference, setReference] = useState("");
  const [manual, setManual] = useState(false);
  const [split, setSplit] = useState<Record<string, number>>({});
  const pay = useBillingWrite(billingApi.pay);
  const clientName = parc.data?.creches.find((c) => c.slug === creche)?.clientName;
  const open = (invoices.data ?? []).filter((s) => s.type === "FACTURE" && s.status === "VALIDEE" && s.remaining > 0 && (s.crecheSlug === creche || (clientName && s.clientName === clientName)));
  useEffect(() => setSplit({}), [creche]);
  const allocated = Object.values(split).reduce((a, b) => a + b, 0);

  return (
    <FormCard
      title="Saisir un paiement"
      onSubmit={(e) => {
        e.preventDefault();
        pay.mutate(
          {
            crecheSlug: creche,
            receivedOn,
            amount,
            method,
            reference: reference || null,
            note: null,
            allocations: manual ? Object.entries(split).filter(([, v]) => v > 0).map(([invoiceId, v]) => ({ invoiceId, amount: v })) : [],
          },
          { onSuccess: onDone },
        );
      }}
    >
      <div className="grid gap-3 md:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="pa-creche">Crèche</Label>
          <CrecheSelect id="pa-creche" value={creche} onChange={setCreche} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="pa-date">Reçu le</Label>
          <Input id="pa-date" type="date" required max={today()} value={receivedOn} onChange={(e) => setReceivedOn(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="pa-montant">Montant (F)</Label>
          <Input id="pa-montant" type="number" min={1} required value={amount || ""} onChange={(e) => setAmount(Number(e.target.value))} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="pa-moyen">Moyen</Label>
          <select id="pa-moyen" className="h-9 rounded-md border border-input bg-card px-3" value={method} onChange={(e) => setMethod(e.target.value)}>
            {Object.entries(methodLabels).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
        </div>
        <div className="flex flex-col gap-1.5 md:col-span-2">
          <Label htmlFor="pa-ref">Référence</Label>
          <Input id="pa-ref" value={reference} onChange={(e) => setReference(e.target.value)} placeholder="Libellé du virement, n° de chèque" />
        </div>
      </div>
      {creche && (
        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
          <legend className="mb-1 text-label">Affectation</legend>
          <label className="flex items-center gap-2">
            <input type="radio" name="affectation" checked={!manual} onChange={() => setManual(false)} /> Aux factures les plus anciennes d’abord
          </label>
          <label className="flex items-center gap-2">
            <input type="radio" name="affectation" checked={manual} onChange={() => setManual(true)} /> Facture par facture
          </label>
          {manual &&
            (open.length === 0 ? (
              <p className="m-0 text-muted-foreground">Aucune facture à régler : le montant sera un trop-perçu.</p>
            ) : (
              open.map((s) => (
                <div key={s.id} className="flex items-center gap-3">
                  <Label htmlFor={`pa-${s.id}`} className="flex-1 font-normal">
                    {s.number} · {s.subject} · reste {xpf(s.remaining)}
                  </Label>
                  <Input id={`pa-${s.id}`} type="number" min={0} max={s.remaining} className="w-32" value={split[s.id] ?? ""} onChange={(e) => setSplit((m) => ({ ...m, [s.id]: Number(e.target.value) }))} />
                </div>
              ))
            ))}
          {manual && <p className="m-0 text-small text-muted-foreground">Affecté : {xpf(allocated)} · trop-perçu : {xpf(Math.max(0, amount - allocated))}</p>}
        </fieldset>
      )}
      {pay.isError && (
        <p role="alert" className="m-0 text-danger-700">
          {errorMessage(pay.error)}
        </p>
      )}
      <div className="flex gap-2">
        <Button type="submit" disabled={pay.isPending || !creche || amount <= 0}>
          Enregistrer le paiement
        </Button>
        <Button type="button" variant="ghost" onClick={onDone}>
          Annuler
        </Button>
      </div>
    </FormCard>
  );
}

function CreditForm({ id, onDone, onCancel }: { id: string; onDone: (id: string) => void; onCancel: () => void }) {
  const query = useInvoice(id);
  const confirmed = useConfirmed();
  const [amounts, setAmounts] = useState<Record<string, number>>({});
  const [reason, setReason] = useState("");
  const credit = useBillingWrite((input: { lines: { lineId: string; amount: number }[]; reason: string }) => confirmed(() => billingApi.credit(id, input.lines, input.reason)));
  if (!query.data) {
    return <p className="text-muted-foreground">Chargement…</p>;
  }
  const lines = query.data.creditable;
  const total = Object.values(amounts).reduce((a, b) => a + b, 0);
  return (
    <FormCard
      title={`Avoir sur la facture ${query.data.summary.number}`}
      onSubmit={(e) => {
        e.preventDefault();
        credit.mutate(
          { lines: Object.entries(amounts).filter(([, v]) => v > 0).map(([lineId, amount]) => ({ lineId, amount })), reason },
          { onSuccess: (d) => onDone(d.summary.id) },
        );
      }}
    >
      <p className="m-0 text-small text-muted-foreground">Montants hors taxe à rendre, ligne par ligne ; la TGC suit le taux de la facture. L’avoir est validé tout de suite.</p>
      {lines.map((l) => (
        <div key={l.lineId} className="flex items-center gap-3">
          <Label htmlFor={`av-${l.lineId}`} className="flex-1 font-normal">
            {l.label}
            <span className="block text-small text-muted-foreground">
              {xpf(l.amount)} HT{l.alreadyCredited > 0 ? `, déjà rendus ${xpf(l.alreadyCredited)}` : ""} · {xpf(l.creditable)} au plus
            </span>
          </Label>
          <Input id={`av-${l.lineId}`} type="number" min={0} max={l.creditable} disabled={l.creditable === 0} className="w-32" value={amounts[l.lineId] ?? ""} onChange={(e) => setAmounts((m) => ({ ...m, [l.lineId]: Number(e.target.value) }))} />
        </div>
      ))}
      <Button type="button" variant="outline" size="sm" className="self-start" onClick={() => setAmounts(Object.fromEntries(lines.map((l) => [l.lineId, l.creditable])))}>
        Tout rendre (annuler la facture)
      </Button>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="av-motif">Motif</Label>
        <Input id="av-motif" required value={reason} onChange={(e) => setReason(e.target.value)} />
      </div>
      <p className="m-0 font-semibold">À rendre : {xpf(total)} HT</p>
      {credit.isError && (
        <p role="alert" className="m-0 text-danger-700">
          {errorMessage(credit.error)}
        </p>
      )}
      <div className="flex gap-2">
        <Button type="submit" disabled={credit.isPending || total <= 0 || !reason.trim()}>
          Émettre l’avoir
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel}>
          Annuler
        </Button>
      </div>
    </FormCard>
  );
}

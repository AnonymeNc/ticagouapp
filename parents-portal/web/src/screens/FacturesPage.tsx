import { useQuery } from "@tanstack/react-query";
import { ChevronRight, FileDown } from "lucide-react";
import { errorMessage, fileUrl, portal } from "../api";
import { Badge, Banner, Page } from "../components";
import { dateFr, monthYear, xpf } from "../format";
import { useSession } from "../session";

/**
 * Factures (maquette « Factures et paiement »). Écart : pas de paiement en ligne dans cette version ; les coordonnées
 * de virement de la crèche remplacent le choix du moyen de paiement. L'attestation annuelle n'est pas encore envoyée.
 */
export function FacturesPage() {
  const { family } = useSession();
  const invoices = useQuery({ queryKey: ["invoices"], queryFn: ({ signal }) => portal.invoices(signal) });
  const list = (invoices.data ?? []).filter((i) => i.structureId === family.structureId);
  const due = list.find((i) => i.data.kind === "FACTURE" && i.data.remaining > 0);
  const others = list.filter((i) => i !== due);

  return (
    <Page title="Factures">
      {invoices.isError && <Banner tone="danger">{errorMessage(invoices.error)}</Banner>}
      {list.length === 0 && !invoices.isPending && <p className="m-0 text-ink-muted">Aucune facture pour le moment.</p>}

      {due && (
        <section aria-label="Facture à payer" className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-surface p-4 shadow-[var(--shadow-1)]">
          <div className="flex items-center justify-between gap-3">
            <h2 className="m-0 text-[19px] font-semibold">{monthYear(due.data.month)}</h2>
            <Badge tone="warning">À payer</Badge>
          </div>
          <div className="flex flex-col">
            <span className="tabular font-display text-[32px] leading-[38px] font-semibold">{xpf(due.data.remaining)}</span>
            {due.data.dueOn && <span className="tabular text-[15px] text-ink-muted">À régler avant le {dateFr(due.data.dueOn)}</span>}
          </div>
          {(due.data.lines ?? []).length > 0 && (
            <dl className="m-0 flex flex-col gap-1.5 border-t border-border-subtle pt-3">
              {due.data.lines!.map((l, i) => (
                <div key={i} className="flex justify-between gap-3 text-[16px]">
                  <dt>{l.label}</dt>
                  <dd className="tabular m-0">{xpf(l.amount)}</dd>
                </div>
              ))}
              <div className="flex justify-between gap-3 border-t border-border-subtle pt-1.5 text-[16px] font-semibold">
                <dt>Total</dt>
                <dd className="tabular m-0">{xpf(due.data.total)}</dd>
              </div>
            </dl>
          )}
          <div className="flex flex-col gap-1 rounded-md bg-sunken p-3 text-[16px]">
            <span className="font-semibold">Pour payer</span>
            <span>{family.paymentInfo ?? "Réglez à l’accueil de la crèche (chèque, espèces) ou par virement."}</span>
            <span className="text-[15px] text-ink-muted">Référence à indiquer : {due.data.number}</span>
          </div>
          {due.hasFile && (
            <a href={fileUrl(family.structureId, due.id)} target="_blank" rel="noreferrer"
              className="flex min-h-touch items-center justify-center gap-2 rounded-lg border border-border-control text-[17px] font-semibold no-underline">
              <FileDown aria-hidden className="size-5" />Voir la facture (PDF)
            </a>
          )}
        </section>
      )}

      {others.length > 0 && (
        <>
          <h2 className="m-0 text-[19px] font-semibold">{due ? "Factures précédentes" : "Vos factures"}</h2>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {others.map((i) => (
              <li key={i.id}>
                <a href={i.hasFile ? fileUrl(family.structureId, i.id) : undefined} target="_blank" rel="noreferrer"
                  className="flex min-h-[64px] items-center gap-3 rounded-lg border border-border-subtle bg-surface px-4 text-ink no-underline">
                  <span className="flex grow flex-col">
                    <span className="text-[17px] font-semibold">{i.data.kind === "AVOIR" ? `Avoir ${i.data.number}` : monthYear(i.data.month)}</span>
                    <span className="tabular text-[15px] text-ink-muted">{xpf(i.data.total)}</span>
                  </span>
                  {i.data.kind === "AVOIR" ? <Badge tone="info">Avoir</Badge> : i.data.remaining > 0 ? <Badge tone="warning">À payer</Badge> : <Badge tone="success" check>Payée</Badge>}
                  <ChevronRight aria-hidden className="size-5 text-ink-muted" />
                </a>
              </li>
            ))}
          </ul>
        </>
      )}
    </Page>
  );
}

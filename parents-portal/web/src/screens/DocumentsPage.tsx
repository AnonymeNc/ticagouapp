import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { ChevronRight, FileSignature, FileText } from "lucide-react";
import { errorMessage, fileUrl, portal } from "../api";
import { Badge, Banner, Page } from "../components";
import { dateFr } from "../format";
import { useSession } from "../session";

export const documentsKey = ["documents"] as const;

/**
 * Documents et autorisations (maquette « Documents et autorisations »). Écart : la rubrique « De la crèche » (menus,
 * projet pédagogique) n'est pas encore alimentée par le logiciel.
 */
export function DocumentsPage() {
  const { family } = useSession();
  const documents = useQuery({ queryKey: documentsKey, queryFn: ({ signal }) => portal.documents(signal) });
  const list = (documents.data ?? []).filter((d) => d.structureId === family.structureId);
  const toSign = list.filter((d) => d.data.status === "A_SIGNER");
  const done = list.filter((d) => d.data.status !== "A_SIGNER");

  return (
    <Page title="Documents" subtitle="Lisez et signez directement depuis votre téléphone.">
      {documents.isError && <Banner tone="danger">{errorMessage(documents.error)}</Banner>}
      <h2 className="m-0 text-[19px] font-semibold">À signer <span className="tabular font-normal text-ink-muted">· {toSign.length}</span></h2>
      {toSign.length === 0 ? (
        <p className="m-0 text-ink-muted">Rien à signer pour le moment.</p>
      ) : (
        toSign.map((d) => (
          <Link key={d.id} to={`/documents/${family.structureId}/${d.id}`}
            className="flex min-h-[72px] items-center gap-3 rounded-lg border-2 border-lagon-600 bg-surface px-4 py-3 text-ink no-underline">
            <FileSignature aria-hidden className="size-6 shrink-0 text-lagon-700" />
            <span className="flex grow flex-col gap-1">
              <span className="text-[17px] font-semibold">{d.data.title}</span>
              <span className="text-[15px] text-ink-muted">{d.data.kind === "CONTRAT" ? "Contrat d’accueil" : "Autorisation"}</span>
              <Badge tone="warning">À signer</Badge>
            </span>
            <ChevronRight aria-hidden className="size-5 text-ink-muted" />
          </Link>
        ))
      )}

      {done.length > 0 && (
        <>
          <h2 className="m-0 text-[19px] font-semibold">Déjà signés</h2>
          <ul className="m-0 flex list-none flex-col gap-2 p-0">
            {done.map((d) => (
              <li key={d.id}>
                <a href={d.hasFile ? fileUrl(family.structureId, d.id) : undefined} target="_blank" rel="noreferrer"
                  className="flex min-h-[64px] items-center gap-3 rounded-lg border border-border-subtle bg-surface px-4 py-2 text-ink no-underline">
                  <FileText aria-hidden className="size-5 shrink-0 text-ink-muted" />
                  <span className="flex grow flex-col">
                    <span className="text-[17px] font-semibold">{d.data.title}</span>
                    {d.data.signedAt && <span className="tabular text-[15px] text-ink-muted">Répondu le {dateFr(d.data.signedAt)}</span>}
                  </span>
                  {d.data.status === "REFUSE" ? <Badge tone="neutral">Refusée</Badge> : <Badge tone="success" check>Signé</Badge>}
                </a>
              </li>
            ))}
          </ul>
        </>
      )}
    </Page>
  );
}

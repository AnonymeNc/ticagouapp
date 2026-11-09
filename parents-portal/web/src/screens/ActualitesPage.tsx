import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { ChevronLeft, FileText, Megaphone } from "lucide-react";
import { errorMessage, fileUrl, portal, type NewsItem } from "../api";
import { Banner, Page } from "../components";
import { dateFr, todayIso } from "../format";
import { useSession } from "../session";

export const newsKey = ["news"] as const;

/** Jour de publication à Nouméa (l'instant est en UTC). */
export const publishedOn = (item: NewsItem) => dateFr(todayIso(new Date(item.data.publishedAt)));

/** Actualités de la crèche de la famille choisie, les plus récentes d'abord. */
export function useNews() {
  const { family } = useSession();
  const news = useQuery({ queryKey: newsKey, queryFn: ({ signal }) => portal.news(signal), refetchInterval: 5 * 60_000 });
  return { ...news, list: (news.data ?? []).filter((n) => n.structureId === family.structureId) };
}

/**
 * Actualités de la crèche (lot 1 de l'application parents). Écart : pas d'écran dédié dans la maquette « app parents
 * mobile » ; la carte reprend le style des cartes du fil de la journée.
 */
export function ActualitesPage() {
  const news = useNews();
  return (
    <Page title="Actualités" subtitle="Les nouvelles de la crèche : fêtes, sorties, menus, informations.">
      <Link to="/" className="inline-flex min-h-11 items-center gap-1 self-start text-[16px] font-semibold text-lagon-700 no-underline">
        <ChevronLeft aria-hidden className="size-5" />La journée
      </Link>
      {news.isError && <Banner tone="danger">{errorMessage(news.error)}</Banner>}
      {news.list.length === 0 ? (
        <p className="m-0 text-ink-muted">{news.isPending ? "Chargement…" : "Pas d’actualité pour le moment."}</p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-3 p-0">
          {news.list.map((n) => <li key={n.id}><NewsCard item={n} /></li>)}
        </ul>
      )}
    </Page>
  );
}

export function NewsCard({ item, compact = false }: { item: NewsItem; compact?: boolean }) {
  const { title, body, author, fileName } = item.data;
  const url = item.structureId ? fileUrl(item.structureId, item.id) : undefined;
  const isImage = item.fileType?.startsWith("image/");
  return (
    <article className="flex flex-col gap-2 rounded-lg border border-border-subtle bg-surface p-4 shadow-[var(--shadow-1)]">
      <div className="flex items-center gap-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-lagon-50 text-lagon-700">
          <Megaphone aria-hidden className="size-5" />
        </span>
        <div className="flex flex-col">
          <h3 className="m-0 text-[17px] font-semibold">{title}</h3>
          <span className="tabular text-[14px] text-ink-muted">{author} · {publishedOn(item)}</span>
        </div>
      </div>
      <p className={compact ? "m-0 line-clamp-3 text-[16px] whitespace-pre-wrap" : "m-0 text-[16px] whitespace-pre-wrap"}>{body}</p>
      {item.hasFile && url && isImage && !compact && (
        <img src={url} alt={fileName ?? title} loading="lazy" className="w-full rounded-md border border-border-subtle object-cover" />
      )}
      {item.hasFile && url && (!isImage || compact) && (
        <a href={url} target="_blank" rel="noreferrer"
          className="flex min-h-11 items-center gap-2 self-start rounded-md text-[16px] font-semibold text-lagon-700">
          <FileText aria-hidden className="size-5" />{fileName ?? "Pièce jointe"}
        </a>
      )}
    </article>
  );
}

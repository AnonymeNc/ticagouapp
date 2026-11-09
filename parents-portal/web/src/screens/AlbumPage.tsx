import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { ChevronLeft, Download } from "lucide-react";
import { errorMessage, fileUrl, portal } from "../api";
import { Banner, Page, cn } from "../components";
import { dateFr, time, todayIso } from "../format";
import { useSession } from "../session";

const isVideo = (p: { fileType: string | null; data: { kind: string } }) => p.data.kind === "VIDEO" || !!p.fileType?.startsWith("video/");

/**
 * Album de l'enfant (lot 1 de l'application parents) : photos et vidéos de 30 s partagées par l'équipe, regroupées par
 * jour, téléchargeables une à une. Le portail les garde 60 jours (ADR 0010, 0019). Maquette « Parents — Album photos ».
 */
export function AlbumPage() {
  const { family, child } = useSession();
  const photos = useQuery({
    queryKey: ["photos", family.structureId, child?.id],
    queryFn: ({ signal }) => portal.photos(family.structureId, child!.id, signal),
    enabled: child !== null,
  });
  if (!child) return <Banner>Aucun enfant n’est encore partagé par la crèche.</Banner>;

  const byDay = new Map<string, NonNullable<typeof photos.data>>();
  for (const p of photos.data ?? []) {
    const day = p.at ? todayIso(new Date(p.at)) : p.data.day;
    byDay.set(day, [...(byDay.get(day) ?? []), p]);
  }

  return (
    <Page title={`Les photos de ${child.firstName}`} subtitle="Photos et vidéos gardées 60 jours sur l’application : téléchargez celles que vous aimez.">
      <Link to="/" className="inline-flex min-h-11 items-center gap-1 self-start text-[16px] font-semibold text-lagon-700 no-underline">
        <ChevronLeft aria-hidden className="size-5" />La journée
      </Link>
      {photos.isError && <Banner tone="danger">{errorMessage(photos.error)}</Banner>}
      {byDay.size === 0 && <p className="m-0 text-ink-muted">{photos.isPending ? "Chargement…" : "Pas encore de photo ni de vidéo."}</p>}
      {[...byDay.entries()].map(([day, list]) => (
        <section key={day} aria-label={dateFr(day)} className="flex flex-col gap-2">
          <h2 className="m-0 text-[17px] font-semibold">{day === todayIso() ? "Aujourd’hui" : dateFr(day)}</h2>
          <ul className="m-0 grid list-none grid-cols-2 gap-2 p-0">
            {list.map((p) => {
              const url = fileUrl(family.structureId, p.id);
              const label = p.data.text ?? (isVideo(p) ? "Vidéo de la journée" : "Photo de la journée");
              return (
                <li key={p.id} className="relative">
                  {isVideo(p) ? (
                    <video src={url} controls playsInline preload="metadata" aria-label={`${label}, ${p.at ? time(p.at) : ""}`}
                      className="aspect-square w-full rounded-md border border-border-subtle bg-black object-cover" />
                  ) : (
                    <a href={url} target="_blank" rel="noreferrer" aria-label={`${label}, ${p.at ? time(p.at) : ""}`}>
                      <img src={url} alt={label} loading="lazy" className="aspect-square w-full rounded-md border border-border-subtle object-cover" />
                    </a>
                  )}
                  <a href={url} download={`${child.firstName}-${day}-${p.id.slice(0, 6)}`}
                    aria-label={`Télécharger : ${label}`}
                    className={cn("absolute right-1.5 flex size-11 items-center justify-center rounded-full bg-surface/90 text-lagon-700 shadow-[var(--shadow-1)]",
                      isVideo(p) ? "top-1.5" : "bottom-1.5")}>
                    <Download aria-hidden className="size-5" />
                  </a>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </Page>
  );
}

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Baby, Camera, DoorOpen, HeartPulse, MessageSquareText, Moon, Palette, UtensilsCrossed, Pill } from "lucide-react";
import { errorMessage, fileUrl, portal, type AttendanceData, type EntryData, type Item } from "../api";
import { Badge, Banner, ChildAvatar, Logo, SectionBadge, cn } from "../components";
import { dateFr, duration, longDay, time, todayIso } from "../format";
import { useSession } from "../session";

const meals: Record<string, string> = { COLLATION: "Collation", DEJEUNER: "Déjeuner", GOUTER: "Goûter", BIBERON: "Biberon" };
const quantities: Record<string, string> = { TOUT: "Tout mangé", BIEN: "Bien mangé", PEU: "Un peu mangé", RIEN: "Rien mangé" };
const bottleQuantities: Record<string, string> = { TOUT: "Tout bu", BIEN: "Presque tout", PEU: "Un peu bu", RIEN: "Rien bu" };
const changes: Record<string, string> = { MOUILLEE: "Couche mouillée", SELLES: "Selles", PROPRE: "Couche propre", POT: "Pot / toilettes" };
const icons = { REPAS: UtensilsCrossed, SIESTE: Moon, CHANGE: Baby, MEDICAMENT: Pill, ACTIVITE: Palette, PHOTO: Camera, MOT: MessageSquareText };

/** « 150 ml, tout bu » pour un biberon, « Bien mangé » pour un repas. */
function mealResult(details: Record<string, string>) {
  if (details.meal !== "BIBERON") return quantities[details.quantity];
  const drunk = bottleQuantities[details.quantity];
  return details.volumeMl ? `${details.volumeMl} ml · ${drunk.toLowerCase()}` : drunk;
}

const isEntry = (item: Item<AttendanceData | EntryData>): item is Item<EntryData> => item.type === "ENTRY";

/**
 * Fil de la journée (maquette « Fil de la journée ») : l'enfant, son état, l'essentiel en bref, puis les transmissions
 * de la plus récente à la plus ancienne. Écarts : le régime et les personnes autorisées de la maquette ne sont pas
 * affichés (données de la crèche qui ne sortent pas, ADR 0010) ; les informations de santé que la direction a
 * choisi de partager apparaissent dans un encadré.
 */
export function JourneePage() {
  const { me, family, child, select } = useSession();
  const [day, setDay] = useState(todayIso());
  const feed = useQuery({
    queryKey: ["day", family.structureId, child?.id, day],
    queryFn: ({ signal }) => portal.day(family.structureId, child!.id, day, signal),
    enabled: child !== null,
    refetchInterval: 60_000,
  });

  if (!child) return <Banner>Aucun enfant n’est encore partagé par la crèche.</Banner>;
  const items = feed.data?.items ?? [];
  const attendance = items.find((i) => i.type === "ATTENDANCE") as Item<AttendanceData> | undefined;
  const entries = items.filter(isEntry);
  const status = attendance?.data.departedAt ? "parti" : attendance?.data.arrivedAt ? "present" : "attendu";
  const last = [...entries].sort((a, b) => (b.at ?? "").localeCompare(a.at ?? ""))[0];

  const meal = [...entries].reverse().find((e) => e.data.kind === "REPAS");
  const sleep = entries.filter((e) => e.data.kind === "SIESTE" && e.data.details.to)
    .reduce((sum, e) => {
      const [fh, fm] = e.data.details.from.split(":").map(Number);
      const [th, tm] = e.data.details.to.split(":").map(Number);
      return sum + Math.max(0, th * 60 + tm - fh * 60 - fm);
    }, 0);
  const activities = entries.filter((e) => e.data.kind === "ACTIVITE").map((e) => e.data.text).filter(Boolean);
  const all = me.families.flatMap((f) => f.children.map((c) => ({ family: f, child: c })));

  return (
    <>
      <header className="flex items-center gap-3 px-4 pt-4 pb-1">
        <Logo />
        <div className="flex flex-col">
          <span className="text-[16px] font-semibold">{family.structureName}</span>
          <span className="text-[14px] text-ink-muted">{longDay(day)}</span>
        </div>
      </header>

      <div className="flex flex-col gap-5 px-4 pt-3 pb-6">
        {all.length > 1 && (
          <div role="group" aria-label="Enfant" className="flex gap-2 overflow-x-auto">
            {all.map(({ family: f, child: c }) => (
              <button key={c.id} type="button" aria-pressed={c.id === child.id} onClick={() => select(f.structureId, c.id)}
                className={cn("min-h-11 shrink-0 rounded-full border px-4 text-[16px] font-semibold",
                  c.id === child.id ? "border-2 border-lagon-600 bg-lagon-100 text-lagon-700" : "border-border-control bg-surface")}>
                {c.firstName}
              </button>
            ))}
          </div>
        )}

        <section aria-label="Votre enfant" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-surface p-4 shadow-[var(--shadow-1)]">
          <div className="flex items-center gap-3">
            <ChildAvatar child={child} structureId={family.structureId} />
            <div className="flex flex-col gap-1">
              <span className="text-[19px]"><strong className="font-semibold">{child.firstName}</strong> {child.lastInitial ? `${child.lastInitial}.` : ""}</span>
              <SectionBadge section={child.section} />
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            {status === "present" && <Badge tone="success">À la crèche</Badge>}
            {status === "parti" && <Badge tone="neutral">Rentré(e) à la maison</Badge>}
            {status === "attendu" && <Badge tone="info">Pas encore arrivé(e)</Badge>}
            {attendance?.data.arrivedAt && (
              <span className="tabular text-[15px] text-ink-muted">
                Arrivée {time(attendance.data.arrivedAt)}{attendance.data.departedAt && ` · départ ${time(attendance.data.departedAt)}`}
              </span>
            )}
          </div>
        </section>

        {child.sharedHealth.length > 0 && (
          <section aria-label="Santé partagée par la crèche" className="flex gap-3 rounded-lg bg-info-100 p-4 text-info-700">
            <HeartPulse aria-hidden className="mt-0.5 size-5 shrink-0" />
            <ul className="m-0 flex list-none flex-col gap-1 p-0 text-[16px]">
              {child.sharedHealth.map((h) => <li key={h.label + h.text}><strong>{h.label}</strong> : {h.text}</li>)}
            </ul>
          </section>
        )}

        <div className="flex flex-col gap-1">
          <h1 className="m-0 font-display text-[24px] leading-[30px] font-semibold">La journée de {child.firstName}</h1>
          {last?.at && <span className="tabular text-[15px] text-ink-muted">Mis à jour à {time(last.at)} par l’équipe</span>}
          <label className="mt-1 flex items-center gap-2 text-[15px] text-ink-muted">
            Autre jour
            <select value={day} onChange={(e) => setDay(e.target.value)} className="h-10 rounded-md border border-border-control bg-surface px-2 text-[16px] text-ink">
              {[...new Set([todayIso(), ...(feed.data?.days ?? [])])].map((d) => (
                <option key={d} value={d}>{d === todayIso() ? "Aujourd’hui" : dateFr(d)}</option>
              ))}
            </select>
          </label>
        </div>

        {feed.isError && <Banner tone="danger">{errorMessage(feed.error)}</Banner>}

        {entries.length > 0 && (
          <section aria-label="En bref" className="grid grid-cols-2 gap-3">
            <Brief icon={UtensilsCrossed} label="Repas" value={meal ? mealResult(meal.data.details) ?? "—" : "—"} />
            <Brief icon={Moon} label="Sieste" value={sleep > 0 ? duration("00:00", `${String(Math.floor(sleep / 60)).padStart(2, "0")}:${String(sleep % 60).padStart(2, "0")}`) ?? "—" : "—"} />
            <Brief icon={Baby} label="Changes" value={String(entries.filter((e) => e.data.kind === "CHANGE").length)} />
            <Brief icon={Palette} label="Activités" value={activities.length ? activities.join(", ") : "—"} />
          </section>
        )}

        <h2 className="m-0 text-[19px] font-semibold">Au fil de la journée</h2>
        {items.length === 0 ? (
          <p className="m-0 text-ink-muted">{feed.isPending ? "Chargement…" : "Rien pour le moment : les transmissions de l’équipe apparaîtront ici."}</p>
        ) : (
          <ol className="m-0 flex list-none flex-col gap-0 p-0">
            {[...entries].sort((a, b) => (b.at ?? "").localeCompare(a.at ?? "")).map((e) => (
              <Entry key={e.id} item={e} structureId={family.structureId} />
            ))}
            {attendance?.data.arrivedAt && (
              <TimelineRow at={attendance.data.arrivedAt} icon={DoorOpen} title="Arrivée" />
            )}
          </ol>
        )}
      </div>
    </>
  );
}

function Brief({ icon: Icon, label, value }: { icon: typeof Moon; label: string; value: string }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-border-subtle bg-surface p-3">
      <span className="flex size-9 items-center justify-center rounded-full bg-lagon-50 text-lagon-700"><Icon aria-hidden className="size-[22px]" /></span>
      <span className="text-[14px] text-ink-muted">{label}</span>
      <span className="tabular text-[17px] font-semibold">{value}</span>
    </div>
  );
}

function TimelineRow({ at, icon: Icon, title, children }: { at: string; icon: typeof Moon; title: string; children?: React.ReactNode }) {
  return (
    <li className="grid grid-cols-[52px_36px_1fr] gap-2 pb-4">
      <span className="tabular pt-1.5 text-[15px] text-ink-muted">{time(at)}</span>
      <span className="flex size-9 items-center justify-center rounded-full bg-lagon-100 text-lagon-700"><Icon aria-hidden className="size-[20px]" /></span>
      <div className="flex flex-col gap-1.5 pt-1">
        <h3 className="m-0 text-[17px] font-semibold">{title}</h3>
        {children}
      </div>
    </li>
  );
}

function Entry({ item, structureId }: { item: Item<EntryData>; structureId: string }) {
  const { kind, details, text } = item.data;
  const title = kind === "REPAS" ? meals[details.meal] ?? "Repas" : kind === "SIESTE" ? "Sieste" : kind === "CHANGE" ? "Change"
    : kind === "MEDICAMENT" ? "Médicament donné" : kind === "PHOTO" ? "Nouvelle photo" : kind === "MOT" ? "Un mot de l’équipe" : text ?? "Activité";
  return (
    <TimelineRow at={item.at!} icon={icons[kind]} title={kind === "ACTIVITE" ? "Activité" : title}>
      {kind === "REPAS" && details.quantity && <Badge tone={details.quantity === "RIEN" ? "warning" : "success"} check={details.quantity !== "RIEN"}>{mealResult(details)}</Badge>}
      {kind === "SIESTE" && (
        <p className="tabular m-0 text-[16px]">{details.to ? `${details.from} → ${details.to}, soit ${duration(details.from, details.to)}.` : `Endormi(e) à ${details.from}.`}</p>
      )}
      {kind === "CHANGE" && <p className="m-0 text-[16px]">{changes[details.change]}</p>}
      {kind === "MEDICAMENT" && <p className="m-0 text-[16px]">{details.dose ? `${details.medicine}, ${details.dose}` : details.medicine}</p>}
      {text && <p className="m-0 text-[16px]">{text}</p>}
      {kind === "PHOTO" && item.hasFile && (
        <img src={fileUrl(structureId, item.id)} alt={text ?? "Photo de la journée"} loading="lazy"
          className="w-full rounded-md border border-border-subtle object-cover" />
      )}
    </TimelineRow>
  );
}

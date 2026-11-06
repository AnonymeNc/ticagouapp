import { useState } from "react";
import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { SectionBadge } from "@/features/children/components";
import { useSections } from "@/features/structure/useSections";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { DAY_NAMES, formatDays, formatMonthLong, formatMonthShort } from "@/lib/format";
import { cn } from "@/lib/utils";
import { OPEN_STAGES, enrollmentApi, enrollmentKeys, type CapacityTest, type RequestCard } from "./api";
import { EnrollmentHeader, TonePill, type Tone } from "./components";

const cellTone = (free: number) =>
  free <= 0 ? "bg-danger-100 text-danger-700" : free === 1 ? "bg-warning-100 text-warning-700" : "bg-success-100 text-success-700";
const cellLabel = (free: number) => (free < 0 ? `Complet (${-free} en trop)` : free === 0 ? "Complet" : free === 1 ? "1 libre" : `${free} libres`);

/**
 * Vue Capacité (maquette Inscriptions › Capacité) : places libres par section, jour et mois sur six mois.
 * Les contrats signés, les sorties prévues, les passages de section et les places proposées sont comptés.
 */
export function CapacityPage() {
  const capacity = useQuery({ queryKey: enrollmentKeys.capacity, queryFn: ({ signal }) => enrollmentApi.capacity(signal) });
  const board = useQuery({ queryKey: enrollmentKeys.board({ includeClosed: false }), queryFn: ({ signal }) => enrollmentApi.board({}, signal) });
  const sections = useSections();
  const testable = (board.data?.requests ?? []).filter((r) => OPEN_STAGES.includes(r.stage) && r.stage !== "PLACE_PROPOSEE");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = testable.find((r) => r.id === selectedId) ?? testable[0];

  return (
    <>
      <EnrollmentHeader subtitle="Places libres par section et par jour ; contrats signés, sorties prévues et passages de section déjà comptés." />

      <div className="grid grid-cols-1 items-start gap-4 xl:grid-cols-[minmax(0,1fr)_340px]">
        <section className="flex min-w-0 flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          {capacity.isError && <p className="m-0 text-danger-700">{errorMessage(capacity.error)}</p>}
          {capacity.data && (
            <>
              <h2 className="m-0 text-title-3">
                Places libres · {formatMonthLong(capacity.data.months[0])} à {formatMonthLong(capacity.data.months[capacity.data.months.length - 1])}
              </h2>
              <p className="m-0 text-muted-foreground">
                Les enfants changent de section selon leur âge au premier jour de chaque mois ; une entrée ou une sortie en cours de mois occupe le mois entier.
              </p>
              <div className="overflow-x-auto">
                <table className="w-full border-separate border-spacing-1 text-small tabular-nums">
                  <caption className="sr-only">Places libres par section, jour et mois</caption>
                  <thead>
                    <tr>
                      <th scope="col" className="text-left text-muted-foreground">Section · jour</th>
                      {capacity.data.months.map((m) => (
                        <th key={m} scope="col" className="px-2 text-right text-muted-foreground">{formatMonthShort(m)}</th>
                      ))}
                    </tr>
                  </thead>
                  {capacity.data.sections.map((s) => (
                    <tbody key={s.sectionId}>
                      <tr>
                        <th colSpan={capacity.data.months.length + 1} scope="rowgroup" className="pt-3 text-left">
                          <span className="inline-flex items-center gap-3">
                            <SectionBadge section={sections.data?.find((x) => x.id === s.sectionId)} />
                            <span className="font-normal text-muted-foreground">Agrément {s.capacity} places</span>
                          </span>
                        </th>
                      </tr>
                      {s.days.map((d) => (
                        <tr key={d.dayOfWeek}>
                          <th scope="row" className="pr-2 text-left font-normal">{DAY_NAMES[d.dayOfWeek - 1]}</th>
                          {d.free.map((free, i) => {
                            const highlighted = selected && selected.sectionId === s.sectionId && selected.days.includes(d.dayOfWeek)
                              && capacity.data.months[i] >= selected.desiredStart.slice(0, 7);
                            return (
                              <td
                                key={i}
                                className={cn("rounded-sm px-2 py-1.5 text-right font-semibold", cellTone(free), highlighted && "outline-2 -outline-offset-2 outline-lagon-600")}
                              >
                                {cellLabel(free)}
                              </td>
                            );
                          })}
                        </tr>
                      ))}
                    </tbody>
                  ))}
                </table>
              </div>
              <ul aria-label="Légende" className="m-0 flex list-none flex-wrap gap-4 p-0 text-small">
                <li className="flex items-center gap-2"><span aria-hidden className="size-3 rounded-sm bg-success-100" />2 places libres ou plus</li>
                <li className="flex items-center gap-2"><span aria-hidden className="size-3 rounded-sm bg-warning-100" />1 place libre</li>
                <li className="flex items-center gap-2"><span aria-hidden className="size-3 rounded-sm bg-danger-100" />Complet</li>
                <li className="flex items-center gap-2"><span aria-hidden className="size-3 rounded-sm outline-2 outline-lagon-600" />Jours demandés par la demande testée</li>
              </ul>
            </>
          )}
        </section>

        <section aria-labelledby="tester" className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 id="tester" className="m-0 text-title-3">Tester une demande</h2>
          <p className="m-0 text-muted-foreground">Choisissez une demande : ses jours sont encadrés dans la grille et le résultat s’affiche ci-dessous.</p>
          {selected && <Result request={selected} />}
          {testable.length === 0 && board.isSuccess && <p className="m-0 text-muted-foreground">Aucune demande en attente de place.</p>}
          <div role="radiogroup" aria-label="Demandes à tester" className="flex flex-col gap-2">
            {testable.map((r) => (
              <label
                key={r.id}
                className={cn(
                  "flex cursor-pointer items-center gap-2 rounded-md px-3 py-2",
                  selected?.id === r.id ? "border-2 border-lagon-600 bg-lagon-100" : "border border-border-subtle",
                )}
              >
                <input type="radio" name="demande-testee" className="sr-only" checked={selected?.id === r.id} onChange={() => setSelectedId(r.id)} />
                <span className="min-w-0 flex-1">
                  <span className="block font-semibold">{r.childFirstName} {r.childLastName}</span>
                  <span className="block text-small text-muted-foreground tabular-nums">{formatDays(r.days)} · entrée {isoToDateFr(r.desiredStart)}</span>
                </span>
                <SectionBadge section={sections.data?.find((x) => x.id === r.sectionId)} />
              </label>
            ))}
          </div>
          {selected && <Link to={`/inscriptions/${selected.id}`} className="text-label text-lagon-700">Ouvrir la fiche de la demande</Link>}
        </section>
      </div>
    </>
  );
}

function Result({ request }: { request: RequestCard }) {
  const test: CapacityTest | null = request.capacity;
  if (!test) return null;
  const month = formatMonthLong(request.desiredStart.slice(0, 7));
  const full = test.fullDays.map((d) => DAY_NAMES[d - 1].toLowerCase());
  const n = request.days.length;
  const views: Record<CapacityTest["verdict"], { tone: Tone; title: string; text: string }> = {
    ACCEPTABLE: {
      tone: "success",
      title: "Demande acceptable",
      text: `${n > 1 ? `Les ${n} jours demandés ont` : "Le jour demandé a"} une place libre de ${month} à la fin de la période.`,
    },
    LATER: {
      tone: "warning",
      title: `Possible à partir de ${test.firstMonth ? formatMonthLong(test.firstMonth) : ""}`,
      text: `${full.length ? `En ${month}, complet le ${full.join(" et le ")}. ` : ""}Proposez une entrée en ${test.firstMonth ? formatMonthLong(test.firstMonth) : ""} ou un autre jour.`,
    },
    NONE: { tone: "danger", title: "Pas de place sur la période", text: "Aucun mois des six prochains n’a une place libre chaque jour demandé." },
    OUT_OF_RANGE: { tone: "info", title: "Entrée hors période", text: "L’entrée souhaitée est au-delà des six mois calculés." },
  };
  const view = views[test.verdict];
  return (
    <div role="status" className="flex flex-col gap-1">
      <TonePill tone={view.tone}>{view.title}</TonePill>
      <p className="m-0 text-small">{view.text}</p>
    </div>
  );
}

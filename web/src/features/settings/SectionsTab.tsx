import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowRight, Plus, Trash2, TriangleAlert } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { TonePill } from "@/features/enrollment/components";
import type { Section } from "@/features/structure/useSections";
import { formatAge } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { systemApi, systemKeys, type SectionsOverview } from "./systemApi";

const age = (months: number | null, fallback: string) => (months === null ? fallback : formatAge(months));

/**
 * Paramètres › Sections et tranches d'âge (maquette) : places, inscrits, section suivante, règles de passage et
 * passages à valider. Les tranches ne doivent ni se chevaucher ni laisser de trou.
 */
export function SectionsTab() {
  const queryClient = useQueryClient();
  const overview = useQuery({ queryKey: systemKeys.sections, queryFn: ({ signal }) => systemApi.sections(signal) });
  const [editing, setEditing] = useState<Section | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const data = overview.data;
  const refresh = async (next?: SectionsOverview) => {
    if (next) queryClient.setQueryData(systemKeys.sections, next);
    await queryClient.invalidateQueries({ queryKey: ["sections"] });
  };
  const run = async (action: () => Promise<SectionsOverview | unknown>) => {
    setError(null);
    try {
      const result = await action();
      await refresh(result && typeof result === "object" && "passages" in result ? (result as SectionsOverview) : undefined);
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  const sorted = data ? [...data.sections].sort((a, b) => a.section.minAgeMonths - b.section.minAgeMonths) : [];
  const nameOf = (id: string | null) => data?.sections.find((s) => s.section.id === id)?.section.name;

  return (
    <>
      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="m-0 text-title-3">Sections et tranches d’âge</h2>
            <p className="m-0 text-muted-foreground">Chaque enfant est rattaché à une section selon son âge ; le passage se propose automatiquement.</p>
          </div>
          <Button onClick={() => setEditing("new")}><Plus aria-hidden />Ajouter une section</Button>
        </div>
        {error && <Alert>{error}</Alert>}
        {data?.gaps.map((g) => (
          <p key={g} className="m-0 flex items-center gap-2 rounded-md bg-warning-100 px-3 py-2 text-warning-700"><TriangleAlert aria-hidden className="size-4" />{g}</p>
        ))}
        {data && (
          <table className="w-full border-collapse text-left tabular-nums">
            <caption className="sr-only">Sections de la structure</caption>
            <thead>
              <tr className="border-b border-border-subtle text-label text-muted-foreground">
                <th scope="col" className="py-2">Section</th>
                <th scope="col" className="py-2">Âge d’entrée</th>
                <th scope="col" className="py-2">Âge de sortie</th>
                <th scope="col" className="py-2 text-right">Places</th>
                <th scope="col" className="py-2 text-right">Inscrits</th>
                <th scope="col" className="py-2">Passage</th>
                <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {sorted.map(({ section: s, enrolled }, i) => (
                <tr key={s.id} className="border-b border-border-subtle last:border-0">
                  <td className="py-2"><span aria-hidden className="mr-2 inline-block size-3 rounded-full" style={{ backgroundColor: s.color }} />{s.name}</td>
                  <td className="py-2">{age(s.minAgeMonths, "Naissance")}</td>
                  <td className="py-2">{age(s.maxAgeMonths, "Entrée à l’école")}</td>
                  <td className="py-2 text-right">{s.capacity}</td>
                  <td className={enrolled >= s.capacity ? "py-2 text-right font-semibold text-warning-700" : "py-2 text-right"}>{enrolled}</td>
                  <td className="py-2 text-muted-foreground">{sorted[i + 1] ? `Vers ${sorted[i + 1].section.name}` : "Sortie de la structure"}</td>
                  <td className="py-2 text-right whitespace-nowrap">
                    <Button variant="ghost" size="sm" onClick={() => setEditing(s)}>Modifier</Button>
                    {enrolled === 0 && (
                      <Button variant="ghost" size="sm" className="text-danger-700" aria-label={`Supprimer la section ${s.name}`}
                        onClick={() => window.confirm(`Supprimer la section « ${s.name} » ?`) && void run(() => systemApi.deleteSection(s.id))}>
                        <Trash2 aria-hidden />
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
              <tr className="font-semibold">
                <td className="py-2" colSpan={3}>Total</td>
                <td className="py-2 text-right">{data.totalPlaces} / {data.capacity}</td>
                <td colSpan={3} className="py-2 text-small font-normal text-muted-foreground">Le total ne peut pas dépasser la capacité inscrite sur l’agrément.</td>
              </tr>
            </tbody>
          </table>
        )}
      </section>

      {data && (
        <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <h2 className="m-0 text-title-3">Règles de passage</h2>
          {([
            ["propose", "Proposer le passage dès qu’un enfant atteint l’âge de sortie"],
            ["firstOfMonth", "Regrouper les passages au 1er de chaque mois"],
            ["blockFull", "Bloquer un passage si la section d’arrivée est complète"],
          ] as const).map(([key, label]) => (
            <label key={key} className="flex items-center gap-2">
              <input type="checkbox" checked={data.rules[key]} onChange={(e) => void run(() => systemApi.passageRules({ ...data.rules, [key]: e.target.checked }))} />
              {label}
            </label>
          ))}
          {data.rules.propose && (
            data.passages.length === 0 ? <p className="m-0 text-muted-foreground">Aucun passage à prévoir dans le mois qui vient.</p> : (
              <ul className="m-0 flex list-none flex-col gap-2 p-0" aria-label="Passages à valider">
                {data.passages.map((p) => (
                  <li key={p.childId} className="flex flex-wrap items-center justify-between gap-2 rounded-md bg-muted/50 px-3 py-2">
                    <span className="flex items-center gap-2">
                      <strong>{p.name}</strong> · {formatAge(p.ageMonths)} le {isoToDateFr(p.on)} ·
                      {nameOf(p.fromSectionId)} <ArrowRight aria-hidden className="size-4" /> {p.toSectionId ? nameOf(p.toSectionId) : "sortie de la structure"}
                      {p.blocked && <TonePill tone="warning">Section complète</TonePill>}
                    </span>
                    {p.toSectionId && !p.blocked && (
                      <Button size="sm" variant="outline" onClick={() => void run(() => systemApi.applyPassage(p.childId, p.toSectionId!))}>Valider le passage</Button>
                    )}
                  </li>
                ))}
              </ul>
            )
          )}
        </section>
      )}
      {editing && (
        <SectionDialog section={editing === "new" ? null : editing} order={data?.sections.length ?? 0}
          onClose={() => setEditing(null)} onSaved={async () => { setEditing(null); await queryClient.invalidateQueries({ queryKey: ["sections"] }); }} />
      )}
    </>
  );
}

const COLORS = ["#9e2f66", "#924510", "#3d479b", "#0b6e72", "#5b6b1e", "#7a3fa0"];

function SectionDialog({ section, order, onClose, onSaved }: { section: Section | null; order: number; onClose: () => void; onSaved: () => Promise<void> }) {
  const [name, setName] = useState(section?.name ?? "");
  const [min, setMin] = useState(String(section?.minAgeMonths ?? 0));
  const [max, setMax] = useState(section?.maxAgeMonths === null || section?.maxAgeMonths === undefined ? "" : String(section.maxAgeMonths));
  const [capacity, setCapacity] = useState(String(section?.capacity ?? 12));
  const [color, setColor] = useState(section?.color ?? COLORS[order % COLORS.length]);
  const [error, setError] = useState<string | null>(null);
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{section ? `Modifier « ${section.name} »` : "Ajouter une section"}</DialogTitle>
          <DialogDescription>Âges en mois. Laissez l’âge de sortie vide pour la dernière section (entrée à l’école).</DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Nom" className="col-span-2"><Input value={name} maxLength={60} onChange={(e) => setName(e.target.value)} /></FormField>
          <FormField label="Âge d’entrée (mois)"><Input value={min} inputMode="numeric" onChange={(e) => setMin(e.target.value)} /></FormField>
          <FormField label="Âge de sortie (mois)"><Input value={max} inputMode="numeric" onChange={(e) => setMax(e.target.value)} /></FormField>
          <FormField label="Places"><Input value={capacity} inputMode="numeric" onChange={(e) => setCapacity(e.target.value)} /></FormField>
          <FormField label="Couleur"><Input type="color" value={color} onChange={(e) => setColor(e.target.value)} /></FormField>
        </div>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button
            onClick={async () => {
              const body = { name: name.trim(), minAgeMonths: Number(min), maxAgeMonths: max.trim() ? Number(max) : null, capacity: Number(capacity),
                color: color.toLowerCase(), displayOrder: section?.displayOrder ?? order };
              try {
                await (section ? systemApi.updateSection(section.id, body) : systemApi.createSection(body));
                await onSaved();
              } catch (e) {
                setError(errorMessage(e));
              }
            }}
          >
            Enregistrer
          </Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

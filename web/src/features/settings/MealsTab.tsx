import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { mealKeys, mealsApi, TAGS, type Diet } from "@/features/meals/api";
import { errorMessage } from "@/lib/api";

/** Paramètres › Repas et régimes : heures du repas et du goûter, régimes et ce qu'ils excluent. */
export function MealsTab() {
  const queryClient = useQueryClient();
  const settings = useQuery({ queryKey: mealKeys.settings, queryFn: ({ signal }) => mealsApi.settings(signal) });
  const diets = useQuery({ queryKey: mealKeys.diets, queryFn: ({ signal }) => mealsApi.diets(signal) });
  const [lunch, setLunch] = useState("");
  const [snack, setSnack] = useState("");
  const [editing, setEditing] = useState<Diet | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (settings.data) {
      setLunch(settings.data.lunchTime.slice(0, 5));
      setSnack(settings.data.snackTime.slice(0, 5));
    }
  }, [settings.data]);

  const saveTimes = async () => {
    setError(null);
    setSaved(false);
    try {
      await mealsApi.updateSettings({ lunchTime: lunch, snackTime: snack });
      await queryClient.invalidateQueries({ queryKey: mealKeys.all });
      setSaved(true);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const remove = async (diet: Diet) => {
    if (!window.confirm(`Supprimer le régime « ${diet.label} » ? Les fiches qui le portent le gardent en texte libre, sans contrôle des menus.`)) return;
    try {
      await mealsApi.deleteDiet(diet.id);
      await queryClient.invalidateQueries({ queryKey: mealKeys.diets });
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <>
      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 className="m-0 text-title-3">Heures de service</h2>
        <p className="m-0 max-w-3xl text-muted-foreground">
          Un repas est compté quand l’enfant est présent à l’heure du repas, un goûter à l’heure du goûter. Avant ces heures, le
          comptage du jour s’appuie sur les contrats.
        </p>
        <div className="flex flex-wrap items-end gap-3">
          <FormField label="Heure du repas"><Input type="time" value={lunch} onChange={(e) => setLunch(e.target.value)} /></FormField>
          <FormField label="Heure du goûter"><Input type="time" value={snack} onChange={(e) => setSnack(e.target.value)} /></FormField>
          <Button onClick={() => void saveTimes()} disabled={!settings.data}>Enregistrer</Button>
        </div>
        {saved && <p role="status" className="m-0 text-success-700">Heures enregistrées.</p>}
      </section>

      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="m-0 text-title-3">Régimes</h2>
            <p className="m-0 max-w-3xl text-muted-foreground">
              Chaque régime indique les étiquettes de plats qu’il exclut. Les menus signalent une variante manquante quand le plat
              du jour en contient une et qu’un enfant inscrit suit ce régime.
            </p>
          </div>
          <Button onClick={() => setEditing("new")}><Plus aria-hidden />Ajouter un régime</Button>
        </div>
        {error && <Alert>{error}</Alert>}
        <table className="w-full border-collapse text-left">
          <caption className="sr-only">Régimes</caption>
          <thead>
            <tr className="border-b border-border-subtle text-label text-muted-foreground">
              <th scope="col" className="py-2">Régime</th>
              <th scope="col" className="py-2">Exclut</th>
              <th scope="col" className="py-2 text-right">Enfants inscrits</th>
              <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {diets.data?.map((d) => (
              <tr key={d.id} className="border-b border-border-subtle last:border-0">
                <td className="py-2">{d.label}</td>
                <td className="py-2">{d.excludes.join(", ") || "—"}</td>
                <td className="py-2 text-right tabular-nums">{d.children}</td>
                <td className="py-2 text-right whitespace-nowrap">
                  <Button variant="ghost" size="sm" onClick={() => setEditing(d)}>Modifier</Button>
                  <Button variant="ghost" size="sm" className="text-danger-700" aria-label={`Supprimer le régime ${d.label}`} onClick={() => void remove(d)}>
                    <Trash2 aria-hidden />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      {editing && (
        <DietDialog
          diet={editing === "new" ? null : editing}
          nextOrder={diets.data?.length ?? 0}
          onClose={() => setEditing(null)}
          onDone={() => queryClient.invalidateQueries({ queryKey: mealKeys.diets })}
        />
      )}
    </>
  );
}

function DietDialog({ diet, nextOrder, onClose, onDone }: { diet: Diet | null; nextOrder: number; onClose: () => void; onDone: () => Promise<unknown> }) {
  const [label, setLabel] = useState(diet?.label ?? "");
  const [excludes, setExcludes] = useState<string[]>(diet?.excludes ?? []);
  const [error, setError] = useState<string | null>(null);
  const submit = async () => {
    if (!label.trim()) return setError("Indiquez le nom du régime.");
    const body = { label: label.trim(), excludes, displayOrder: diet?.displayOrder ?? nextOrder };
    try {
      if (diet) await mealsApi.updateDiet(diet.id, body);
      else await mealsApi.createDiet(body);
      await onDone();
      onClose();
    } catch (e) {
      setError(errorMessage(e));
    }
  };
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{diet ? "Modifier le régime" : "Ajouter un régime"}</DialogTitle>
          <DialogDescription>Cochez ce que ce régime exclut.</DialogDescription>
        </DialogHeader>
        <FormField label="Régime"><Input value={label} maxLength={60} onChange={(e) => setLabel(e.target.value)} /></FormField>
        <fieldset className="m-0 grid grid-cols-2 gap-2 border-0 p-0 sm:grid-cols-3">
          <legend className="mb-1 text-label">Exclut</legend>
          {TAGS.map((t) => (
            <label key={t} className="flex items-center gap-2">
              <input type="checkbox" checked={excludes.includes(t)} onChange={(e) => setExcludes(e.target.checked ? [...excludes, t] : excludes.filter((x) => x !== t))} />
              {t}
            </label>
          ))}
        </fieldset>
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button onClick={() => void submit()}>Enregistrer</Button>
          <Button variant="ghost" onClick={onClose}>Annuler</Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

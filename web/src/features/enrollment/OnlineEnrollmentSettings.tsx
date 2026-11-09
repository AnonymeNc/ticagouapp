import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, Globe, Plus } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { selectClass } from "@/lib/formFields";
import { onlineApi, onlineKeys, type QuestionKind } from "./online";

const kindLabels: Record<QuestionKind, string> = { TEXTE: "Texte libre", OUI_NON: "Oui / non", CHOIX: "Choix dans une liste" };

/**
 * Paramètres › Pré-inscription en ligne (lot 3, ADR 0020) : ouverture du formulaire public, texte d'accueil, questions
 * posées aux familles, et ce qu'il faut copier sur le site de la crèche (lien, bouton, cadre).
 */
export function OnlineEnrollmentSettings() {
  const queryClient = useQueryClient();
  const settings = useQuery({ queryKey: onlineKeys.settings, queryFn: ({ signal }) => onlineApi.settings(signal) });
  const questions = useQuery({ queryKey: onlineKeys.questions, queryFn: ({ signal }) => onlineApi.questions(signal) });
  const [intro, setIntro] = useState<string | null>(null);
  const [draft, setDraft] = useState({ label: "", kind: "TEXTE" as QuestionKind, options: "", required: false });
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  if (!settings.data) return null;
  const url = `${window.location.origin}/preinscription`;
  const snippets = [
    { label: "Lien", code: url },
    { label: "Bouton", code: `<a href="${url}" style="display:inline-block;padding:12px 20px;border-radius:10px;background:#0b6e72;color:#fff;text-decoration:none">Pré-inscrire mon enfant</a>` },
    { label: "Cadre dans une page", code: `<iframe src="${url}" title="Pré-inscription" style="width:100%;height:1400px;border:0"></iframe>` },
  ];

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null);
    setMessage(null);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: ["enrollment"] });
      setMessage(done);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const save = (enabled: boolean) => run(() => onlineApi.updateSettings({ enabled, intro: intro ?? settings.data!.intro }),
    enabled ? "Formulaire ouvert aux familles." : "Formulaire fermé.");

  return (
    <div className="flex flex-col gap-6">
      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 className="m-0 flex items-center gap-2 text-title-3"><Globe aria-hidden className="size-5" />Formulaire public</h2>
        <p className="m-0 max-w-3xl text-muted-foreground">
          Les familles déposent leur demande sans compte. Elle arrive dans le tableau des inscriptions, marquée « En ligne ».
          Les demandes sans suite sont effacées 12 mois après le dernier changement d’étape.
        </p>
        <p className="m-0 font-semibold">{settings.data.enabled ? "Ouvert aux familles." : "Fermé : la page indique de contacter la crèche."}</p>
        <FormField label="Texte d’accueil" hint="Facultatif : places, délais, pièces à prévoir.">
          <Textarea rows={3} maxLength={1000} value={intro ?? settings.data.intro ?? ""} onChange={(e) => setIntro(e.target.value)} />
        </FormField>
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => void save(true)}>{settings.data.enabled ? "Enregistrer" : "Ouvrir le formulaire"}</Button>
          {settings.data.enabled && <Button variant="outline" onClick={() => void save(false)}>Fermer le formulaire</Button>}
        </div>
        {settings.data.enabled && (
          <div className="flex flex-col gap-2">
            <h3 className="m-0 text-label">Sur le site de la crèche</h3>
            {snippets.map((s) => (
              <div key={s.label} className="flex items-center gap-2">
                <span className="w-44 shrink-0 text-small text-muted-foreground">{s.label}</span>
                <code className="grow truncate rounded-md bg-muted px-2 py-1 text-small">{s.code}</code>
                <Button size="sm" variant="ghost" aria-label={`Copier : ${s.label}`} onClick={() => void navigator.clipboard?.writeText(s.code)}>
                  <Copy aria-hidden />
                </Button>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 className="m-0 text-title-3">Questions posées aux familles</h2>
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {(questions.data ?? []).map((q) => (
            <li key={q.id} className="flex flex-wrap items-center gap-3 border-b border-border-subtle py-2">
              <span className="grow">
                <strong className="font-semibold">{q.label}</strong>
                <span className="text-small text-muted-foreground"> · {kindLabels[q.kind]}{q.options.length > 0 && ` (${q.options.join(", ")})`}
                  {q.required ? " · obligatoire" : ""}{q.validTo ? ` · close le ${isoToDateFr(q.validTo)}` : ""}</span>
              </span>
              {!q.validTo && <Button size="sm" variant="ghost" onClick={() => void run(() => onlineApi.retireQuestion(q.id), "Question retirée du formulaire.")}>Ne plus poser</Button>}
            </li>
          ))}
          {(questions.data ?? []).length === 0 && <li className="text-muted-foreground">Aucune question en plus des critères de priorité.</li>}
        </ul>
        <div className="grid gap-3 md:grid-cols-[1fr_220px]">
          <FormField label="Nouvelle question"><Input value={draft.label} maxLength={200} onChange={(e) => setDraft({ ...draft, label: e.target.value })} /></FormField>
          <FormField label="Réponse">
            <select className={selectClass} value={draft.kind} onChange={(e) => setDraft({ ...draft, kind: e.target.value as QuestionKind })}>
              {(Object.keys(kindLabels) as QuestionKind[]).map((k) => <option key={k} value={k}>{kindLabels[k]}</option>)}
            </select>
          </FormField>
          {draft.kind === "CHOIX" && (
            <FormField label="Choix proposés" hint="Un par ligne" className="md:col-span-2">
              <Textarea rows={3} value={draft.options} onChange={(e) => setDraft({ ...draft, options: e.target.value })} />
            </FormField>
          )}
        </div>
        <label className="flex items-center gap-2">
          <input type="checkbox" className="size-5" checked={draft.required} onChange={(e) => setDraft({ ...draft, required: e.target.checked })} />
          Réponse obligatoire
        </label>
        <Button className="self-start" disabled={!draft.label.trim()} onClick={() => void run(async () => {
          await onlineApi.addQuestion({ label: draft.label.trim(), kind: draft.kind, required: draft.required,
            options: draft.options.split("\n").map((o) => o.trim()).filter(Boolean), displayOrder: (questions.data ?? []).length + 1 });
          setDraft({ label: "", kind: "TEXTE", options: "", required: false });
        }, "Question ajoutée au formulaire.")}>
          <Plus aria-hidden />Ajouter la question
        </Button>
      </section>
      {message && <p role="status" className="m-0 text-success-700">{message}</p>}
      {error && <Alert>{error}</Alert>}
    </div>
  );
}

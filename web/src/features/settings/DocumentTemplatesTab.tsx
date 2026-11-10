import { useEffect, useRef, useState } from "react";
import { scrollIntoContent } from "@/app/layout/scroll";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, FilePlus2, Plus } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { childrenApi } from "@/features/children/api";
import { staffApi } from "@/features/staff/api";
import { api, apiBlob, apiUpload, errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { selectClass } from "@/lib/formFields";

type Subject = "ENFANT" | "PERSONNEL";
type Template = { id: string; subject: Subject; title: string; body: string; version: number; validFrom: string; author: string; retired: boolean };
type Field = { key: string; label: string };
type Settings = { signatory: string | null; hasStamp: boolean };

const subjectLabels: Record<Subject, string> = { ENFANT: "Pour un enfant", PERSONNEL: "Pour un membre du personnel" };
const keys = { list: ["document-templates"] as const, settings: ["document-settings"] as const };

/**
 * Paramètres › Modèles de documents (lot 6) : attestations, certificats, contrat de travail ; champs de fusion,
 * versions datées, signataire et image de signature et cachet ; production du PDF pour un enfant ou un membre du
 * personnel. Écart : pas de maquette dédiée.
 */
export function DocumentTemplatesTab() {
  const queryClient = useQueryClient();
  const templates = useQuery({ queryKey: keys.list, queryFn: ({ signal }) => api<Template[]>("/document-templates", { signal }) });
  const settings = useQuery({ queryKey: keys.settings, queryFn: ({ signal }) => api<Settings>("/document-settings", { signal }) });
  const [editing, setEditing] = useState<{ id: string | null; subject: Subject; title: string; body: string } | null>(null);
  // Le formulaire s'ouvre sous la liste : on l'amène à l'écran (« Modifier » ou « Nouveau modèle »).
  const editor = useRef<HTMLElement>(null);
  const opened = editing ? editing.id ?? "nouveau" : null;
  useEffect(() => {
    if (opened) scrollIntoContent(editor.current);
  }, [opened]);
  const fields = useQuery({
    queryKey: ["document-fields", editing?.subject],
    queryFn: ({ signal }) => api<Field[]>(`/document-templates/fields?subject=${editing!.subject}`, { signal }),
    enabled: editing !== null,
  });
  const [subjectFor, setSubjectFor] = useState<Record<string, string>>({});
  const [signatory, setSignatory] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const body = useRef<HTMLTextAreaElement>(null);
  const live = (templates.data ?? []).filter((t) => !t.retired);
  const needsChildren = live.some((t) => t.subject === "ENFANT");
  const children = useQuery({ queryKey: ["children", { docs: true }], queryFn: ({ signal }) => childrenApi.list({}, signal), enabled: needsChildren });
  const team = useQuery({ queryKey: ["staff", "team"], queryFn: ({ signal }) => staffApi.team(signal), enabled: live.some((t) => t.subject === "PERSONNEL") });

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null);
    setMessage(null);
    try {
      await action();
      await queryClient.invalidateQueries({ queryKey: keys.list });
      await queryClient.invalidateQueries({ queryKey: keys.settings });
      setMessage(done);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const insert = (key: string) => {
    if (!editing) return;
    const area = body.current;
    const token = `{{${key}}}`;
    const at = area?.selectionStart ?? editing.body.length;
    setEditing({ ...editing, body: editing.body.slice(0, at) + token + editing.body.slice(area?.selectionEnd ?? at) });
  };

  const save = () => run(async () => {
    if (!editing) return;
    if (editing.id) await api(`/document-templates/${editing.id}`, { method: "PUT", body: { title: editing.title, body: editing.body } });
    else await api("/document-templates", { method: "POST", body: { subject: editing.subject, title: editing.title, body: editing.body } });
    setEditing(null);
  }, editing?.id ? "Nouvelle version enregistrée, en vigueur dès aujourd’hui." : "Modèle créé.");

  const download = (t: Template) => run(async () => {
    const { blob, fileName } = await apiBlob(`/document-templates/${t.id}/pdf?subjectId=${subjectFor[t.id]}`);
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = fileName ?? "document.pdf";
    link.click();
  }, "Document produit.");

  const subjects = (t: Template) => t.subject === "ENFANT"
    ? (children.data ?? []).map((c) => ({ id: c.id, label: `${c.firstName} ${c.lastName}` }))
    : (team.data?.members ?? []).filter((m) => m.active).map((m) => ({ id: m.userId, label: `${m.firstName} ${m.lastName}` }));

  return (
    <div className="flex flex-col gap-6">
      {message && <p role="status" className="m-0 rounded-md bg-success-100 px-3 py-2 text-success-700">{message}</p>}
      {error && <Alert>{error}</Alert>}

      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <h2 className="m-0 text-title-3">Signature des documents</h2>
        <div className="flex flex-wrap items-end gap-3">
          <FormField label="Signataire" hint="Vide : la personne qui produit le document" className="min-w-72 grow">
            <Input value={signatory ?? settings.data?.signatory ?? ""} maxLength={120} onChange={(e) => setSignatory(e.target.value)} />
          </FormField>
          <Button variant="outline" onClick={() => void run(() => api("/document-settings", { method: "PUT", body: { signatory: signatory ?? settings.data?.signatory ?? null } }), "Signataire enregistré.")}>Enregistrer</Button>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <span>{settings.data?.hasStamp ? "Image de signature et cachet enregistrée." : "Pas d’image de signature et cachet."}</span>
          <label className="cursor-pointer text-lagon-700 underline">
            {settings.data?.hasStamp ? "Remplacer l’image" : "Ajouter une image (PNG ou JPEG)"}
            <input type="file" accept="image/png,image/jpeg" className="sr-only" onChange={(e) => {
              const file = e.target.files?.[0];
              if (!file) return;
              const form = new FormData();
              form.append("file", file);
              void run(() => apiUpload("/document-settings/stamp", form, "PUT"), "Image enregistrée.");
            }} />
          </label>
          {settings.data?.hasStamp && <Button size="sm" variant="ghost" onClick={() => void run(() => api("/document-settings/stamp", { method: "DELETE" }), "Image retirée.")}>Retirer</Button>}
        </div>
      </section>

      <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="m-0 grow text-title-3">Modèles</h2>
          {(templates.data ?? []).length === 0 && (
            <Button variant="outline" onClick={() => void run(() => api("/document-templates/starters", { method: "POST" }), "Modèles de départ créés : adaptez-les.")}>
              <FilePlus2 aria-hidden />Créer les modèles de départ
            </Button>
          )}
          <Button onClick={() => setEditing({ id: null, subject: "ENFANT", title: "", body: "" })}><Plus aria-hidden />Nouveau modèle</Button>
        </div>
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {live.map((t) => (
            <li key={t.id} className="flex flex-wrap items-center gap-3 border-b border-border-subtle py-2">
              <span className="grow">
                <strong className="font-semibold">{t.title}</strong>
                <span className="text-small text-muted-foreground"> · {subjectLabels[t.subject]} · version {t.version} du {isoToDateFr(t.validFrom)}</span>
              </span>
              <select aria-label={`Pour qui : ${t.title}`} className={selectClass + " w-56"} value={subjectFor[t.id] ?? ""}
                onChange={(e) => setSubjectFor({ ...subjectFor, [t.id]: e.target.value })}>
                <option value="">{t.subject === "ENFANT" ? "Choisir un enfant…" : "Choisir une personne…"}</option>
                {subjects(t).map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}
              </select>
              <Button size="sm" disabled={!subjectFor[t.id]} onClick={() => void download(t)}><Download aria-hidden />Produire le PDF</Button>
              <Button size="sm" variant="ghost" onClick={() => setEditing({ id: t.id, subject: t.subject, title: t.title, body: t.body })}>Modifier</Button>
              <Button size="sm" variant="ghost" onClick={() => void run(() => api(`/document-templates/${t.id}`, { method: "DELETE" }), "Modèle retiré.")}>Retirer</Button>
            </li>
          ))}
          {live.length === 0 && <li className="text-muted-foreground">Aucun modèle pour le moment.</li>}
        </ul>
      </section>

      {editing && (
        <section ref={editor} aria-label="Édition du modèle" className="flex flex-col gap-3 rounded-lg border border-lagon-600 bg-card p-5 shadow-1">
          <h2 className="m-0 text-title-3">{editing.id ? "Modifier le modèle" : "Nouveau modèle"}</h2>
          {!editing.id && (
            <FormField label="Pour">
              <select className={selectClass} value={editing.subject} onChange={(e) => setEditing({ ...editing, subject: e.target.value as Subject })}>
                {(Object.keys(subjectLabels) as Subject[]).map((s) => <option key={s} value={s}>{subjectLabels[s]}</option>)}
              </select>
            </FormField>
          )}
          <FormField label="Titre"><Input value={editing.title} maxLength={120} onChange={(e) => setEditing({ ...editing, title: e.target.value })} /></FormField>
          <FormField label="Texte" hint="Une ligne vide sépare les paragraphes ; « # » en début de paragraphe en fait un titre.">
            <Textarea ref={body} rows={12} value={editing.body} onChange={(e) => setEditing({ ...editing, body: e.target.value })} />
          </FormField>
          <div className="flex flex-wrap gap-1" aria-label="Champs à insérer">
            {(fields.data ?? []).map((f) => (
              <Button key={f.key} size="sm" variant="outline" type="button" onClick={() => insert(f.key)} title={`{{${f.key}}}`}>{f.label}</Button>
            ))}
          </div>
          <div className="flex gap-2">
            <Button disabled={!editing.title.trim() || !editing.body.trim()} onClick={() => void save()}>Enregistrer le modèle</Button>
            <Button variant="ghost" onClick={() => setEditing(null)}>Annuler</Button>
          </div>
        </section>
      )}
    </div>
  );
}

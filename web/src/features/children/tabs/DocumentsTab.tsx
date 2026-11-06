import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Download, Lock, Plus, Trash2 } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { formatDate, isoToDateFr } from "@/lib/datetime";
import { dateToIso, optionalDateFr, requiredText, selectClass } from "@/lib/formFields";
import { categoryLabels, childrenApi, type ChildDocument, type DocumentCategory } from "../api";
import { useRefreshChild, type ChildTabProps } from "../ChildRecordPage";
import { FormActions, Panel } from "./common";

const MAX_BYTES = 10 * 1024 * 1024;

/** « 312 Ko », « 1,2 Mo » */
export function formatSize(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} Ko`;
  return `${(bytes / 1024 / 1024).toLocaleString("fr-FR", { maximumFractionDigits: 1 })} Mo`;
}

export function DocumentsTab({ child, canEdit }: ChildTabProps) {
  const refresh = useRefreshChild(child.id);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const today = todayIso();

  const run = async (action: () => Promise<unknown>) => {
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <Panel
      actions={
        canEdit && (
          <Button variant="outline" onClick={() => setAdding(true)}>
            <Plus aria-hidden />
            Ajouter un document
          </Button>
        )
      }
    >
      <p className="m-0 inline-flex items-center gap-2 text-small text-muted-foreground">
        <Lock aria-hidden className="size-4" />
        Les documents sont chiffrés sur le serveur de la crèche ; chaque téléchargement est inscrit au journal.
      </p>
      {error && <Alert>{error}</Alert>}
      {child.documents.length === 0 ? (
        <p className="m-0 text-muted-foreground">Aucun document joint.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-left">
            <thead>
              <tr className="border-b border-border-subtle text-label text-muted-foreground">
                <th scope="col" className="py-2 pr-4">Document</th>
                <th scope="col" className="py-2 pr-4">Catégorie</th>
                <th scope="col" className="py-2 pr-4">Ajouté le</th>
                <th scope="col" className="py-2 pr-4">Taille</th>
                <th scope="col" className="py-2 pr-4">Validité</th>
                <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {child.documents.map((doc) => (
                <tr key={doc.id} className="border-b border-border-subtle last:border-0">
                  <td className="py-2 pr-4">
                    <span className="block font-semibold">{doc.name}</span>
                    <span className="text-small text-muted-foreground">{doc.fileName}</span>
                  </td>
                  <td className="py-2 pr-4">{categoryLabels[doc.category]}</td>
                  <td className="py-2 pr-4 tabular-nums" title={`Par ${doc.createdBy}`}>{formatDate(new Date(doc.createdAt))}</td>
                  <td className="py-2 pr-4 tabular-nums">{formatSize(doc.sizeBytes)}</td>
                  <td className="py-2 pr-4 tabular-nums">
                    <Validity doc={doc} today={today} />
                  </td>
                  <td className="py-2 text-right whitespace-nowrap">
                    <Button variant="outline" size="sm" aria-label={`Télécharger : ${doc.name}`} onClick={() => void run(() => childrenApi.downloadDocument(doc))}>
                      <Download aria-hidden />
                      Télécharger
                    </Button>
                    {canEdit && (
                      <Button variant="ghost" size="sm" aria-label={`Supprimer : ${doc.name}`}
                        onClick={() => void run(async () => {
                          if (!window.confirm(`Supprimer définitivement « ${doc.name} » ?`)) return;
                          await childrenApi.deleteDocument(doc.id);
                          await refresh();
                        })}>
                        <Trash2 aria-hidden />
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Dialog open={adding} onOpenChange={setAdding}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Ajouter un document</DialogTitle>
            <DialogDescription>PDF, JPEG ou PNG, 10 Mo maximum. Le fichier est chiffré sur le serveur.</DialogDescription>
          </DialogHeader>
          {adding && <UploadForm child={child} onDone={() => setAdding(false)} />}
        </DialogContent>
      </Dialog>
    </Panel>
  );
}

function Validity({ doc, today }: { doc: ChildDocument; today: string }) {
  if (!doc.validUntil) return <>—</>;
  if (doc.validUntil < today) return <span className="text-danger-700">Expiré le {isoToDateFr(doc.validUntil)}</span>;
  return <>Jusqu’au {isoToDateFr(doc.validUntil)}</>;
}

const schema = z.object({
  name: requiredText(150, "Donnez un nom au document."),
  category: z.enum(["CONTRAT", "SANTE", "AUTORISATIONS", "FAMILLE", "AUTRE"]),
  validUntil: optionalDateFr,
});

function UploadForm({ child, onDone }: { child: ChildTabProps["child"]; onDone: () => void }) {
  const refresh = useRefreshChild(child.id);
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const form = useForm<z.infer<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: { name: "", category: "SANTE", validUntil: "" },
  });
  const errors = form.formState.errors;

  const onSubmit = form.handleSubmit(async (v) => {
    setError(null);
    if (!file) {
      setFileError("Choisissez un fichier.");
      return;
    }
    if (file.size > MAX_BYTES) {
      setFileError("Le document doit peser au plus 10 Mo.");
      return;
    }
    try {
      await childrenApi.addDocument(child.id, {
        file,
        name: v.name,
        category: v.category as DocumentCategory,
        validUntil: dateToIso(v.validUntil),
      });
      await refresh();
      onDone();
    } catch (e) {
      setError(errorMessage(e));
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
      <FormField label="Fichier" error={fileError ?? undefined}>
        <Input
          type="file"
          accept="application/pdf,image/jpeg,image/png"
          onChange={(e) => {
            const chosen = e.target.files?.[0] ?? null;
            setFile(chosen);
            setFileError(null);
            if (chosen && !form.getValues("name")) form.setValue("name", chosen.name.replace(/\.[^.]+$/, ""));
          }}
        />
      </FormField>
      <FormField label="Nom du document" error={errors.name?.message}>
        <Input {...form.register("name")} />
      </FormField>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <FormField label="Catégorie">
          <select className={selectClass} {...form.register("category")}>
            {Object.entries(categoryLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </FormField>
        <FormField label="Valable jusqu’au" hint="JJ/MM/AAAA, facultatif" error={errors.validUntil?.message}>
          <Input inputMode="numeric" {...form.register("validUntil")} />
        </FormField>
      </div>
      {error && <Alert>{error}</Alert>}
      <FormActions pending={form.formState.isSubmitting} onCancel={onDone} label="Ajouter" />
    </form>
  );
}

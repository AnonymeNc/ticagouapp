import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { CircleCheck, Plus, Trash2, TriangleAlert } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { useAuthorizedImage } from "@/hooks/useAuthorizedImage";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { nullToEmpty, optionalPhone, requiredText, textOrNull } from "@/lib/formFields";
import { initials } from "@/lib/initials";
import { childrenApi, type AuthorizedPerson } from "../api";
import { useRefreshChild, type ChildTabProps } from "../ChildRecordPage";
import { RelationshipOptions } from "../NewChildPage";
import { FormActions, Panel } from "./common";

export function AuthorizedPersonsTab({ child, canEdit }: ChildTabProps) {
  const [editing, setEditing] = useState<AuthorizedPerson | "new" | null>(null);

  return (
    <Panel
      actions={
        canEdit && (
          <Button variant="outline" onClick={() => setEditing("new")}>
            <Plus aria-hidden />
            Ajouter une personne
          </Button>
        )
      }
    >
      <p className="m-0">
        Seules ces personnes et les responsables légaux peuvent venir chercher {child.firstName}.
      </p>
      {child.authorizedPersons.length === 0 ? (
        <p className="m-0 text-muted-foreground">Aucune personne autorisée en dehors des responsables légaux.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-left">
            <thead>
              <tr className="border-b border-border-subtle text-label text-muted-foreground">
                <th scope="col" className="py-2 pr-4">Personne</th>
                <th scope="col" className="py-2 pr-4">Lien</th>
                <th scope="col" className="py-2 pr-4">Téléphone</th>
                <th scope="col" className="py-2 pr-4">Pièce d’identité</th>
                <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {child.authorizedPersons.map((p) => (
                <tr key={p.id} className="border-b border-border-subtle last:border-0">
                  <td className="py-2 pr-4">
                    <div className="flex items-center gap-3">
                      <PersonPhoto person={p} version={child.version} />
                      <span><strong className="font-semibold">{p.firstName}</strong> {p.lastName}</span>
                    </div>
                  </td>
                  <td className="py-2 pr-4">{p.relationship}</td>
                  <td className="py-2 pr-4 tabular-nums">{p.phone ?? "—"}</td>
                  <td className="py-2 pr-4">
                    {p.idVerifiedOn ? (
                      <span className="inline-flex items-center gap-1 text-success-700 tabular-nums" title={p.idVerifiedBy ? `Par ${p.idVerifiedBy}` : undefined}>
                        <CircleCheck aria-hidden className="size-4" />
                        Vérifiée le {isoToDateFr(p.idVerifiedOn)}
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 text-warning-700">
                        <TriangleAlert aria-hidden className="size-4" />
                        À vérifier au premier passage
                      </span>
                    )}
                  </td>
                  <td className="py-2 text-right">
                    {canEdit && <Button variant="outline" size="sm" onClick={() => setEditing(p)}>Modifier</Button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Dialog open={editing !== null} onOpenChange={(open) => !open && setEditing(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editing === "new" ? "Ajouter une personne autorisée" : "Modifier la personne autorisée"}</DialogTitle>
            <DialogDescription>Personne pouvant venir chercher {child.firstName}.</DialogDescription>
          </DialogHeader>
          {editing !== null && (
            <PersonForm child={child} person={editing === "new" ? null : editing} onDone={() => setEditing(null)} />
          )}
        </DialogContent>
      </Dialog>
    </Panel>
  );
}

function PersonPhoto({ person, version }: { person: AuthorizedPerson; version: number }) {
  const photo = useAuthorizedImage(person.hasPhoto ? `/authorized-persons/${person.id}/photo` : null, version);
  const name = `${person.firstName} ${person.lastName}`;
  return (
    <span role="img" aria-label={photo ? `Photo de ${name}` : `Initiales de ${name}`}
      className="inline-flex size-9 shrink-0 items-center justify-center overflow-hidden rounded-full bg-lagon-100 text-small font-bold text-lagon-700">
      {photo ? <img src={photo} alt="" className="size-full object-cover" /> : initials(name)}
    </span>
  );
}

const schema = z.object({
  firstName: requiredText(80, "Indiquez le prénom."),
  lastName: requiredText(80, "Indiquez le nom."),
  relationship: requiredText(60, "Indiquez le lien avec l’enfant."),
  phone: optionalPhone,
  idVerified: z.boolean(),
});

function PersonForm({ child, person, onDone }: { child: ChildTabProps["child"]; person: AuthorizedPerson | null; onDone: () => void }) {
  const refresh = useRefreshChild(child.id);
  const [error, setError] = useState<string | null>(null);
  const [photo, setPhoto] = useState<File | null>(null);
  const form = useForm<z.infer<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: {
      firstName: person?.firstName ?? "",
      lastName: person?.lastName ?? "",
      relationship: person?.relationship ?? "",
      phone: nullToEmpty(person?.phone),
      idVerified: Boolean(person?.idVerifiedOn),
    },
  });
  const errors = form.formState.errors;

  const onSubmit = form.handleSubmit(async (v) => {
    setError(null);
    const body = { ...v, phone: textOrNull(v.phone) };
    try {
      const saved = person
        ? await childrenApi.updateAuthorizedPerson(person.id, body)
        : await childrenApi.addAuthorizedPerson(child.id, body);
      if (photo) await childrenApi.uploadAuthorizedPersonPhoto(saved.id, photo);
      await refresh();
      onDone();
    } catch (e) {
      setError(errorMessage(e));
    }
  });

  const remove = async () => {
    if (!person) return;
    try {
      await childrenApi.deleteAuthorizedPerson(person.id);
      await refresh();
      onDone();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <form onSubmit={onSubmit} noValidate className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <FormField label="Prénom" error={errors.firstName?.message}>
        <Input autoFocus {...form.register("firstName")} />
      </FormField>
      <FormField label="Nom" error={errors.lastName?.message}>
        <Input {...form.register("lastName")} />
      </FormField>
      <FormField label="Lien avec l’enfant" error={errors.relationship?.message}>
        <Input list="liens" {...form.register("relationship")} />
      </FormField>
      <RelationshipOptions />
      <FormField label="Téléphone" error={errors.phone?.message}>
        <Input inputMode="tel" {...form.register("phone")} />
      </FormField>
      <FormField label="Photo" hint="JPEG ou PNG, 2 Mo maximum. Aide l’équipe à reconnaître la personne." className="sm:col-span-2">
        <Input type="file" accept="image/jpeg,image/png" onChange={(e) => setPhoto(e.target.files?.[0] ?? null)} />
      </FormField>
      <label className="inline-flex items-center gap-2 sm:col-span-2">
        <input type="checkbox" {...form.register("idVerified")} className="size-4 accent-lagon-600" />
        Pièce d’identité vérifiée (date et nom de la personne qui vérifie enregistrés)
      </label>
      {error && <div className="sm:col-span-2"><Alert>{error}</Alert></div>}
      <div className="flex items-center justify-between sm:col-span-2">
        <FormActions pending={form.formState.isSubmitting} onCancel={onDone} />
        {person && (
          <Button type="button" variant="ghost" className="text-danger-700" onClick={() => void remove()}>
            <Trash2 aria-hidden />
            Retirer
          </Button>
        )}
      </div>
    </form>
  );
}

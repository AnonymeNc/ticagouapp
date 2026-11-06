import { useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Camera } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useSections } from "@/features/structure/useSections";
import { ageInMonths, formatAge, todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { dateToIso, isoToField, nullToEmpty, optionalDateFr, optionalText, requiredDateFr, requiredText, selectClass, textOrNull } from "@/lib/formFields";
import { childrenApi } from "../api";
import { ChildAvatar, SectionBadge } from "../components";
import { useRefreshChild, type ChildTabProps } from "../ChildRecordPage";
import { Facts, FormActions, Panel } from "./common";

const schema = z
  .object({
    firstName: requiredText(80, "Indiquez le prénom."),
    lastName: requiredText(80, "Indiquez le nom."),
    birthDate: requiredDateFr("Indiquez la date de naissance."),
    sectionId: z.string(),
    entryDate: optionalDateFr,
    exitDate: optionalDateFr,
    homeLanguages: optionalText(200),
  })
  .refine((v) => !v.exitDate || !v.entryDate || parseDateFr(v.exitDate)! >= parseDateFr(v.entryDate)!, {
    message: "La sortie ne peut pas précéder l’entrée.",
    path: ["exitDate"],
  });

type Values = z.infer<typeof schema>;

export function IdentityTab({ child, canEdit, editing, onEditingChange }: ChildTabProps & {
  editing: boolean;
  onEditingChange: (editing: boolean) => void;
}) {
  const sections = useSections();
  const refresh = useRefreshChild(child.id);
  const section = sections.data?.find((s) => s.id === child.sectionId);
  const photoInput = useRef<HTMLInputElement>(null);
  const [photoError, setPhotoError] = useState<string | null>(null);

  async function uploadPhoto(file: File | undefined) {
    if (!file) return;
    setPhotoError(null);
    try {
      await childrenApi.uploadPhoto(child.id, file);
      await refresh();
    } catch (e) {
      setPhotoError(errorMessage(e));
    }
  }

  return (
    <div className="flex flex-wrap items-start gap-4">
      <Panel title="Photo" className="max-w-[340px] flex-[1_1_260px]">
        <div className="flex items-center gap-4">
          <ChildAvatar child={child} section={section} size="lg" photoVersion={child.version} />
          {canEdit && (
            <>
              <input
                ref={photoInput}
                type="file"
                accept="image/jpeg,image/png"
                className="sr-only"
                aria-label="Choisir une photo"
                onChange={(e) => void uploadPhoto(e.target.files?.[0])}
              />
              <Button variant="outline" onClick={() => photoInput.current?.click()}>
                <Camera aria-hidden />
                Changer la photo
              </Button>
            </>
          )}
        </div>
        {photoError && <Alert>{photoError}</Alert>}
        <p className="m-0 text-small text-muted-foreground">
          Affichée uniquement dans Ti’Cagou, selon l’autorisation photo signée.
        </p>
      </Panel>

      <Panel title="État civil et accueil" className="flex-[2_1_420px]">
        {editing ? (
          <IdentityForm child={child} onDone={() => onEditingChange(false)} />
        ) : (
          <>
            <Facts
              items={[
                ["Prénom", child.firstName],
                ["Nom", child.lastName],
                ["Date de naissance", `${isoToDateFr(child.birthDate)} · ${formatAge(ageInMonths(child.birthDate, todayIso()))}`],
                ["Section", <SectionBadge key="s" section={section} />],
                ["Date d’entrée", child.entryDate && isoToDateFr(child.entryDate)],
                ["Date de sortie", child.exitDate && isoToDateFr(child.exitDate)],
                ["Numéro de dossier", child.fileNumber],
                ["Langues parlées à la maison", child.homeLanguages],
              ]}
            />
            {canEdit && (
              <div>
                <Button variant="outline" onClick={() => onEditingChange(true)}>Modifier</Button>
              </div>
            )}
          </>
        )}
      </Panel>
    </div>
  );
}

function IdentityForm({ child, onDone }: { child: ChildTabProps["child"]; onDone: () => void }) {
  const sections = useSections();
  const refresh = useRefreshChild(child.id);
  const [error, setError] = useState<string | null>(null);
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      firstName: child.firstName,
      lastName: child.lastName,
      birthDate: isoToField(child.birthDate),
      sectionId: child.sectionId ?? "",
      entryDate: isoToField(child.entryDate),
      exitDate: isoToField(child.exitDate),
      homeLanguages: nullToEmpty(child.homeLanguages),
    },
  });
  const errors = form.formState.errors;

  const onSubmit = form.handleSubmit(async (v) => {
    setError(null);
    try {
      await childrenApi.updateIdentity(child.id, {
        firstName: v.firstName,
        lastName: v.lastName,
        birthDate: dateToIso(v.birthDate)!,
        sectionId: v.sectionId || null,
        entryDate: dateToIso(v.entryDate),
        exitDate: dateToIso(v.exitDate),
        homeLanguages: textOrNull(v.homeLanguages),
      });
      await refresh();
      onDone();
    } catch (e) {
      setError(errorMessage(e));
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <FormField label="Prénom" error={errors.firstName?.message}>
        <Input autoFocus {...form.register("firstName")} />
      </FormField>
      <FormField label="Nom" error={errors.lastName?.message}>
        <Input {...form.register("lastName")} />
      </FormField>
      <FormField label="Date de naissance" hint="JJ/MM/AAAA" error={errors.birthDate?.message}>
        <Input inputMode="numeric" {...form.register("birthDate")} />
      </FormField>
      <FormField label="Section" error={errors.sectionId?.message}>
        <select className={selectClass} {...form.register("sectionId")}>
          <option value="">Non affecté</option>
          {sections.data?.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
      </FormField>
      <FormField label="Date d’entrée" hint="JJ/MM/AAAA" error={errors.entryDate?.message}>
        <Input inputMode="numeric" {...form.register("entryDate")} />
      </FormField>
      <FormField label="Date de sortie" hint="Dernier jour d’accueil" error={errors.exitDate?.message}>
        <Input inputMode="numeric" {...form.register("exitDate")} />
      </FormField>
      <FormField label="Langues parlées à la maison" error={errors.homeLanguages?.message} className="sm:col-span-2">
        <Input {...form.register("homeLanguages")} />
      </FormField>
      {error && <div className="sm:col-span-2"><Alert>{error}</Alert></div>}
      <div className="sm:col-span-2">
        <FormActions pending={form.formState.isSubmitting} onCancel={onDone} />
      </div>
    </form>
  );
}

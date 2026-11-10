import type { ReactNode } from "react";
import { useFieldArray, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Plus, Trash2 } from "lucide-react";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { DateInput } from "@/components/DateInput";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import {
  adminSchema,
  agrementSchema,
  describeMonths,
  identitySchema,
  sectionColors,
  sectionsSchema,
  type AdminValues,
  type AgrementValues,
  type IdentityValues,
  type SectionValues,
} from "./setupSchemas";

type StepProps<T> = {
  defaultValues: T;
  onBack: () => void;
  onNext: (values: T) => void;
};

export function StepTitle({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="mb-6">
      <h1 className="m-0 font-display text-title-1">{title}</h1>
      {children && <p className="mt-2 mb-0 text-muted-foreground">{children}</p>}
    </div>
  );
}

export function StepActions({ onBack, nextLabel = "Continuer", pending = false }: {
  onBack?: () => void;
  nextLabel?: string;
  pending?: boolean;
}) {
  return (
    <div className="mt-8 flex items-center justify-between gap-3">
      {onBack ? (
        <Button type="button" variant="ghost" onClick={onBack}>
          Retour
        </Button>
      ) : (
        <span />
      )}
      <Button type="submit" className="h-10 px-6" disabled={pending}>
        {nextLabel}
      </Button>
    </div>
  );
}

// --- Bienvenue ---------------------------------------------------

export function WelcomeStep({ onNext }: { onNext: () => void }) {
  return (
    <>
      <StepTitle title="Bienvenue dans Ti’Cagou">
        Quelques questions pour préparer le logiciel de votre structure. Comptez cinq minutes ; tout restera
        modifiable ensuite dans Paramètres.
      </StepTitle>
      <Button className="h-10 px-6" onClick={onNext} autoFocus>
        Commencer
      </Button>
    </>
  );
}

// --- Structure ---------------------------------------------------------------------

export function IdentityStep({ defaultValues, onBack, onNext }: StepProps<IdentityValues>) {
  const form = useForm<IdentityValues>({ resolver: zodResolver(identitySchema), defaultValues });
  const errors = form.formState.errors;
  return (
    <form onSubmit={form.handleSubmit(onNext)} noValidate>
      <StepTitle title="Comment s’appelle votre structure ?">
        Ce nom figurera sur les factures et les attestations.
      </StepTitle>
      <div className="flex flex-col gap-4">
        <FormField label="Nom de la structure" error={errors.name?.message}>
          <Input autoFocus placeholder="Les P’tits Niaoulis" {...form.register("name")} />
        </FormField>
        <FormField label="Commune" error={errors.city?.message}>
          <Input placeholder="Dumbéa" {...form.register("city")} />
        </FormField>
      </div>
      <StepActions onBack={onBack} />
    </form>
  );
}

export function AgrementStep({ defaultValues, onBack, onNext }: StepProps<AgrementValues>) {
  const form = useForm<AgrementValues>({ resolver: zodResolver(agrementSchema), defaultValues });
  const errors = form.formState.errors;
  return (
    <form onSubmit={form.handleSubmit(onNext)} noValidate>
      <StepTitle title="Votre agrément">
        Ces informations se trouvent sur l’arrêté d’agrément délivré par la province.
      </StepTitle>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <FormField label="Capacité agréée (places)" error={errors.capacity?.message} className="sm:col-span-2">
          <Input inputMode="numeric" autoFocus className="max-w-40" {...form.register("capacity")} />
        </FormField>
        <FormField label="Numéro d’agrément" error={errors.agrementNumber?.message}>
          <Input {...form.register("agrementNumber")} />
        </FormField>
        <FormField label="Date d’agrément" hint="JJ/MM/AAAA" error={errors.agrementDate?.message}>
          <DateInput inputMode="numeric" placeholder="15/01/2024" {...form.register("agrementDate")} />
        </FormField>
        <FormField label="RIDET" hint="Facultatif" error={errors.ridet?.message} className="sm:col-span-2">
          <Input placeholder="1234567.001" className="max-w-60" {...form.register("ridet")} />
        </FormField>
      </div>
      <StepActions onBack={onBack} />
    </form>
  );
}

export function SectionsStep({ defaultValues, capacity, onBack, onNext }: StepProps<SectionValues[]> & {
  capacity: number;
}) {
  const form = useForm<{ sections: SectionValues[] }>({
    resolver: zodResolver(sectionsSchema(capacity)),
    defaultValues: { sections: defaultValues },
  });
  const { fields, append, remove } = useFieldArray({ control: form.control, name: "sections" });
  const sections = form.watch("sections");
  const total = sections.reduce((sum, s) => sum + (Number(s.capacity) || 0), 0);
  const errors = form.formState.errors.sections;

  return (
    <form onSubmit={form.handleSubmit((v) => onNext(v.sections))} noValidate>
      <StepTitle title="Vos sections">
        Chaque enfant sera rattaché à une section selon son âge. Les âges sont en mois ; laissez l’âge de sortie
        vide pour « jusqu’à l’entrée à l’école ».
      </StepTitle>
      <ul className="m-0 flex list-none flex-col gap-3 p-0">
        {fields.map((field, index) => {
          const e = errors?.[index];
          return (
            <li key={field.id} className="rounded-md border border-border-subtle bg-background p-4">
              <fieldset className="m-0 grid grid-cols-2 gap-3 border-0 p-0 sm:grid-cols-[1fr_auto_auto_auto]">
                <legend className="sr-only">Section {index + 1}</legend>
                <FormField label="Nom" error={e?.name?.message} className="col-span-2 sm:col-span-1">
                  <Input {...form.register(`sections.${index}.name`)} />
                </FormField>
                <FormField label="Entrée (mois)" hint={describeMonths(sections[index]?.minAgeMonths ?? "")} error={e?.minAgeMonths?.message}>
                  <Input inputMode="numeric" className="w-24" {...form.register(`sections.${index}.minAgeMonths`)} />
                </FormField>
                <FormField label="Sortie (mois)" hint={describeMonths(sections[index]?.maxAgeMonths ?? "") || "École"} error={e?.maxAgeMonths?.message}>
                  <Input inputMode="numeric" className="w-24" {...form.register(`sections.${index}.maxAgeMonths`)} />
                </FormField>
                <FormField label="Places" error={e?.capacity?.message}>
                  <Input inputMode="numeric" className="w-20" {...form.register(`sections.${index}.capacity`)} />
                </FormField>
              </fieldset>
              <div className="mt-3 flex items-center justify-between">
                <div role="radiogroup" aria-label={`Couleur de la section ${index + 1}`} className="flex gap-2">
                  {sectionColors.map((color) => (
                    <button
                      key={color}
                      type="button"
                      role="radio"
                      aria-checked={sections[index]?.color === color}
                      aria-label={color}
                      onClick={() => form.setValue(`sections.${index}.color`, color)}
                      className={cn(
                        "size-6 rounded-full border-2 border-transparent",
                        sections[index]?.color === color && "ring-2 ring-ink ring-offset-2",
                      )}
                      style={{ backgroundColor: color }}
                    />
                  ))}
                </div>
                {fields.length > 1 && (
                  <Button type="button" variant="ghost" size="sm" onClick={() => remove(index)}>
                    <Trash2 aria-hidden />
                    Retirer
                  </Button>
                )}
              </div>
            </li>
          );
        })}
      </ul>
      <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
        <Button
          type="button"
          variant="outline"
          onClick={() =>
            append({
              name: "",
              minAgeMonths: "",
              maxAgeMonths: "",
              capacity: "",
              color: sectionColors[fields.length % sectionColors.length],
            })
          }
        >
          <Plus aria-hidden />
          Ajouter une section
        </Button>
        <span className={cn("font-semibold tabular-nums", total > capacity ? "text-danger-700" : "text-muted-foreground")}>
          Total {total} / {capacity} places
        </span>
      </div>
      {(errors?.root?.message ?? errors?.message) && (
        <p role="alert" className="mt-3 text-small font-semibold text-danger-700">
          {errors?.root?.message ?? errors?.message}
        </p>
      )}
      <StepActions onBack={onBack} />
    </form>
  );
}

export function AdminStep({ defaultValues, onBack, onNext }: StepProps<AdminValues>) {
  const form = useForm<AdminValues>({ resolver: zodResolver(adminSchema), defaultValues });
  const errors = form.formState.errors;
  return (
    <form onSubmit={form.handleSubmit(onNext)} noValidate>
      <StepTitle title="Votre compte de direction">
        Il permet de tout régler, dont les comptes de l’équipe. Choisissez un mot de passe personnel,
        que vous ne partagez pas.
      </StepTitle>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <FormField label="Prénom" error={errors.firstName?.message}>
          <Input autoFocus autoComplete="given-name" {...form.register("firstName")} />
        </FormField>
        <FormField label="Nom" error={errors.lastName?.message}>
          <Input autoComplete="family-name" {...form.register("lastName")} />
        </FormField>
        <FormField label="Identifiant" hint="Exemple : nathalie.kaloi" error={errors.login?.message} className="sm:col-span-2">
          <Input autoComplete="username" {...form.register("login")} />
        </FormField>
        <FormField label="Mot de passe" hint="Au moins 10 caractères" error={errors.password?.message}>
          <Input type="password" autoComplete="new-password" {...form.register("password")} />
        </FormField>
        <FormField label="Confirmation" error={errors.confirmation?.message}>
          <Input type="password" autoComplete="new-password" {...form.register("confirmation")} />
        </FormField>
      </div>
      <StepActions onBack={onBack} />
    </form>
  );
}

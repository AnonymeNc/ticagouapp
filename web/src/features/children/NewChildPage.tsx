import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useSections } from "@/features/structure/useSections";
import { errorMessage } from "@/lib/api";
import {
  dateToIso,
  optionalDateFr,
  optionalEmail,
  optionalPhone,
  optionalText,
  requiredDateFr,
  requiredText,
  selectClass,
  textOrNull,
} from "@/lib/formFields";
import { cn } from "@/lib/utils";
import { childrenApi, familiesApi } from "./api";

const schema = z
  .object({
    firstName: requiredText(80, "Indiquez le prénom."),
    lastName: requiredText(80, "Indiquez le nom."),
    birthDate: requiredDateFr("Indiquez la date de naissance."),
    sectionId: z.string(),
    entryDate: optionalDateFr,
    homeLanguages: optionalText(200),
    familyMode: z.enum(["new", "existing"]),
    familyId: z.string(),
    familyLabel: optionalText(120),
    address: optionalText(200),
    postalCode: optionalText(10),
    city: optionalText(100),
    guardianFirstName: optionalText(80),
    guardianLastName: optionalText(80),
    relationship: optionalText(60),
    mobilePhone: optionalPhone,
    email: optionalEmail,
    legalGuardian: z.boolean(),
    payer: z.boolean(),
  })
  .superRefine((v, ctx) => {
    if (v.familyMode === "existing" && !v.familyId) {
      ctx.addIssue({ code: "custom", path: ["familyId"], message: "Choisissez une famille." });
    }
    if (v.familyMode === "new") {
      if (!v.guardianFirstName) ctx.addIssue({ code: "custom", path: ["guardianFirstName"], message: "Indiquez le prénom." });
      if (!v.guardianLastName) ctx.addIssue({ code: "custom", path: ["guardianLastName"], message: "Indiquez le nom." });
      if (!v.relationship) ctx.addIssue({ code: "custom", path: ["relationship"], message: "Indiquez le lien avec l’enfant." });
    }
  });

type Values = z.infer<typeof schema>;

/** Ajout d'un enfant (en attendant le module Inscriptions, qui créera les enfants depuis les demandes). */
export function NewChildPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const sections = useSections();
  const [error, setError] = useState<string | null>(null);
  const [familyQuery, setFamilyQuery] = useState("");
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      firstName: "", lastName: "", birthDate: "", sectionId: "", entryDate: "", homeLanguages: "",
      familyMode: "new", familyId: "", familyLabel: "", address: "", postalCode: "", city: "",
      guardianFirstName: "", guardianLastName: "", relationship: "Mère", mobilePhone: "", email: "",
      legalGuardian: true, payer: true,
    },
  });
  const errors = form.formState.errors;
  const mode = form.watch("familyMode");
  const families = useQuery({
    queryKey: ["families", familyQuery],
    queryFn: ({ signal }) => familiesApi.search(familyQuery, signal),
    enabled: mode === "existing",
  });

  const onSubmit = form.handleSubmit(async (v) => {
    setError(null);
    const child = {
      firstName: v.firstName,
      lastName: v.lastName,
      birthDate: dateToIso(v.birthDate)!,
      sectionId: v.sectionId || null,
      entryDate: dateToIso(v.entryDate),
      exitDate: null,
      homeLanguages: textOrNull(v.homeLanguages),
    };
    try {
      const created = await childrenApi.create(
        v.familyMode === "existing"
          ? { child, familyId: v.familyId }
          : {
              child,
              family: {
                family: {
                  label: v.familyLabel.trim() || `Famille ${v.lastName.trim()}`,
                  address: textOrNull(v.address),
                  postalCode: textOrNull(v.postalCode),
                  city: textOrNull(v.city),
                  situation: null,
                  notes: null,
                },
                guardian: {
                  firstName: v.guardianFirstName,
                  lastName: v.guardianLastName,
                  relationship: v.relationship,
                  legalGuardian: v.legalGuardian,
                  payer: v.payer,
                  mobilePhone: textOrNull(v.mobilePhone),
                  workPhone: null,
                  email: textOrNull(v.email),
                  employer: null,
                  displayOrder: 0,
                },
              },
            },
      );
      await queryClient.invalidateQueries({ queryKey: ["children"] });
      navigate(`/enfants/${created.id}`, { replace: true });
    } catch (e) {
      setError(errorMessage(e));
    }
  });

  return (
    <>
      <Link to="/enfants" className="inline-flex items-center gap-1.5 text-label text-lagon-700">
        <ArrowLeft aria-hidden className="size-4" />
        Retour à la liste des enfants
      </Link>
      <h1 className="m-0 font-display text-display">Nouvel enfant</h1>

      <form onSubmit={onSubmit} noValidate className="flex max-w-3xl flex-col gap-6">
        <fieldset className="m-0 grid grid-cols-1 gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1 sm:grid-cols-2">
          <legend className="px-1 text-title-3">L’enfant</legend>
          <FormField label="Prénom" error={errors.firstName?.message}>
            <Input autoFocus {...form.register("firstName")} />
          </FormField>
          <FormField label="Nom" error={errors.lastName?.message}>
            <Input {...form.register("lastName")} />
          </FormField>
          <FormField label="Date de naissance" hint="JJ/MM/AAAA" error={errors.birthDate?.message}>
            <Input inputMode="numeric" placeholder="22/07/2024" {...form.register("birthDate")} />
          </FormField>
          <FormField label="Section" error={errors.sectionId?.message}>
            <select className={selectClass} {...form.register("sectionId")}>
              <option value="">Non affecté</option>
              {sections.data?.map((s) => (
                <option key={s.id} value={s.id}>{s.name}</option>
              ))}
            </select>
          </FormField>
          <FormField label="Date d’entrée" hint="JJ/MM/AAAA" error={errors.entryDate?.message}>
            <Input inputMode="numeric" {...form.register("entryDate")} />
          </FormField>
          <FormField label="Langues parlées à la maison" error={errors.homeLanguages?.message}>
            <Input placeholder="Français, drehu" {...form.register("homeLanguages")} />
          </FormField>
        </fieldset>

        <fieldset className="m-0 flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
          <legend className="px-1 text-title-3">La famille</legend>
          <div role="radiogroup" aria-label="Famille" className="flex flex-wrap gap-2">
            {([["new", "Nouvelle famille"], ["existing", "Frère ou sœur déjà inscrit"]] as const).map(([value, label]) => (
              <label
                key={value}
                className={cn(
                  "inline-flex h-control cursor-pointer items-center gap-2 rounded-full border px-3 text-label",
                  mode === value ? "border-lagon-600 bg-lagon-100 text-lagon-700" : "border-input",
                )}
              >
                <input type="radio" value={value} {...form.register("familyMode")} className="accent-lagon-600" />
                {label}
              </label>
            ))}
          </div>

          {mode === "existing" ? (
            <div className="flex flex-col gap-3">
              <FormField label="Rechercher la famille" hint="Nom de famille ou d’un responsable">
                <Input value={familyQuery} onChange={(e) => setFamilyQuery(e.target.value)} />
              </FormField>
              <ul className="m-0 flex max-h-64 list-none flex-col gap-1 overflow-y-auto p-0">
                {families.data?.map((f) => (
                  <li key={f.id}>
                    <label className="flex cursor-pointer items-center gap-3 rounded-md border border-border-subtle px-3 py-2 has-[:checked]:border-lagon-600 has-[:checked]:bg-lagon-50">
                      <input type="radio" value={f.id} {...form.register("familyId")} className="accent-lagon-600" />
                      <span>
                        <strong className="font-semibold">{f.label}</strong>
                        <span className="block text-small text-muted-foreground">
                          {[f.guardianNames.join(", "), f.city].filter(Boolean).join(" · ")}
                        </span>
                      </span>
                    </label>
                  </li>
                ))}
                {families.data?.length === 0 && <li className="text-muted-foreground">Aucune famille trouvée.</li>}
              </ul>
              {errors.familyId && <p className="m-0 text-small font-semibold text-danger-700">{errors.familyId.message}</p>}
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <FormField label="Prénom du responsable" error={errors.guardianFirstName?.message}>
                <Input {...form.register("guardianFirstName")} />
              </FormField>
              <FormField label="Nom du responsable" error={errors.guardianLastName?.message}>
                <Input {...form.register("guardianLastName")} />
              </FormField>
              <FormField label="Lien avec l’enfant" error={errors.relationship?.message}>
                <Input list="liens" {...form.register("relationship")} />
              </FormField>
              <FormField label="Téléphone portable" error={errors.mobilePhone?.message}>
                <Input inputMode="tel" placeholder="77 12 34" {...form.register("mobilePhone")} />
              </FormField>
              <FormField label="E-mail" error={errors.email?.message} className="sm:col-span-2">
                <Input type="email" {...form.register("email")} />
              </FormField>
              <div className="flex flex-wrap gap-6 sm:col-span-2">
                <label className="inline-flex items-center gap-2">
                  <input type="checkbox" {...form.register("legalGuardian")} className="size-4 accent-lagon-600" />
                  Responsable légal
                </label>
                <label className="inline-flex items-center gap-2">
                  <input type="checkbox" {...form.register("payer")} className="size-4 accent-lagon-600" />
                  Reçoit les factures (payeur)
                </label>
              </div>
              <FormField label="Adresse du foyer" error={errors.address?.message} className="sm:col-span-2">
                <Input {...form.register("address")} />
              </FormField>
              <FormField label="Code postal" error={errors.postalCode?.message}>
                <Input inputMode="numeric" placeholder="98800" {...form.register("postalCode")} />
              </FormField>
              <FormField label="Commune" error={errors.city?.message}>
                <Input placeholder="Nouméa" {...form.register("city")} />
              </FormField>
              <FormField label="Nom de la famille" hint="Par défaut : « Famille » suivi du nom de l’enfant" error={errors.familyLabel?.message} className="sm:col-span-2">
                <Input {...form.register("familyLabel")} />
              </FormField>
            </div>
          )}
        </fieldset>
        <RelationshipOptions />

        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button type="submit" disabled={form.formState.isSubmitting}>
            {form.formState.isSubmitting ? "Enregistrement…" : "Créer la fiche"}
          </Button>
          <Button type="button" variant="ghost" onClick={() => navigate("/enfants")}>
            Annuler
          </Button>
        </div>
      </form>
    </>
  );
}

/** Suggestions pour le champ « lien avec l'enfant ». */
export function RelationshipOptions() {
  return (
    <datalist id="liens">
      {["Mère", "Père", "Tutrice", "Tuteur", "Grand-mère", "Grand-père", "Tante", "Oncle", "Famille d’accueil"].map((l) => (
        <option key={l} value={l} />
      ))}
    </datalist>
  );
}

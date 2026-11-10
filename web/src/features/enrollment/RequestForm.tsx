import { useEffect, useState, type ReactNode } from "react";
import { useForm, type UseFormReturn } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useQuery } from "@tanstack/react-query";
import { FormField } from "@/components/FormField";
import { DateInput } from "@/components/DateInput";
import { Input } from "@/components/ui/input";
import { familiesApi } from "@/features/children/api";
import { RelationshipOptions } from "@/features/children/NewChildPage";
import type { Section } from "@/features/structure/useSections";
import { ageInMonths, formatAge, todayIso } from "@/lib/age";
import { parseDateFr } from "@/lib/datetime";
import { DAY_NAMES, span } from "@/lib/format";
import {
  dateToIso,
  isoToField,
  nullToEmpty,
  optionalDateFr,
  optionalEmail,
  optionalPhone,
  requiredDateFr,
  requiredText,
  selectClass,
  textOrNull,
} from "@/lib/formFields";
import { cn } from "@/lib/utils";
import type { Criterion, RequestFormBody, RequestRecord } from "./api";

const schema = z
  .object({
    childFirstName: requiredText(80, "Indiquez le prénom."),
    childLastName: requiredText(80, "Indiquez le nom."),
    born: z.enum(["oui", "non"]),
    birthDate: optionalDateFr,
    desiredStart: requiredDateFr("Indiquez la date d’entrée souhaitée."),
    sectionId: z.string(),
    careType: z.enum(["REGULIER", "OCCASIONNEL"]),
    arrivesAt: z.string(),
    leavesAt: z.string(),
    days: z.array(z.number()).min(1, "Choisissez au moins un jour."),
    guardianFirstName: requiredText(80, "Indiquez le prénom du responsable."),
    guardianLastName: requiredText(80, "Indiquez le nom du responsable."),
    relationship: requiredText(60, "Indiquez le lien avec l’enfant."),
    phone: optionalPhone,
    email: optionalEmail,
    familyId: z.string(),
    criteria: z.array(z.string()),
  })
  .superRefine((v, ctx) => {
    if (!v.birthDate) {
      ctx.addIssue({
        code: "custom",
        path: ["birthDate"],
        message: v.born === "oui" ? "Indiquez la date de naissance." : "Indiquez la date de naissance prévue.",
      });
    }
    const birth = parseDateFr(v.birthDate);
    if (birth && v.born === "oui" && birth > todayIso()) {
      ctx.addIssue({
        code: "custom",
        path: ["birthDate"],
        message: "La date de naissance est dans le futur : pour un enfant à naître, choisissez « À naître ».",
      });
    }
    if (v.arrivesAt && v.leavesAt && span(v.arrivesAt, v.leavesAt) <= 0) {
      ctx.addIssue({ code: "custom", path: ["leavesAt"], message: "Le départ doit suivre l’arrivée." });
    }
    if (!v.phone && !v.email) {
      ctx.addIssue({ code: "custom", path: ["phone"], message: "Indiquez un téléphone ou un e-mail pour recontacter la famille." });
    }
  });

export type RequestValues = z.infer<typeof schema>;
export type RequestFormApi = UseFormReturn<RequestValues>;

export function useRequestForm(record?: RequestRecord) {
  return useForm<RequestValues>({
    resolver: zodResolver(schema),
    defaultValues: record
      ? {
          childFirstName: record.childFirstName,
          childLastName: record.childLastName,
          born: record.birthDate ? "oui" : "non",
          birthDate: isoToField(record.birthDate ?? record.expectedBirthDate),
          desiredStart: isoToField(record.desiredStart),
          sectionId: nullToEmpty(record.sectionId),
          careType: record.careType,
          arrivesAt: record.arrivesAt?.slice(0, 5) ?? "",
          leavesAt: record.leavesAt?.slice(0, 5) ?? "",
          days: record.days,
          guardianFirstName: record.guardianFirstName,
          guardianLastName: record.guardianLastName,
          relationship: record.relationship,
          phone: nullToEmpty(record.phone),
          email: nullToEmpty(record.email),
          familyId: nullToEmpty(record.familyId),
          criteria: record.criteria,
        }
      : {
          childFirstName: "", childLastName: "", born: "oui", birthDate: "", desiredStart: "", sectionId: "",
          careType: "REGULIER", arrivesAt: "07:30", leavesAt: "16:30", days: [], guardianFirstName: "",
          guardianLastName: "", relationship: "Mère", phone: "", email: "", familyId: "", criteria: [],
        },
  });
}

export function toBody(v: RequestValues): RequestFormBody {
  const birth = dateToIso(v.birthDate);
  return {
    childFirstName: v.childFirstName,
    childLastName: v.childLastName,
    birthDate: v.born === "oui" ? birth : null,
    expectedBirthDate: v.born === "non" ? birth : null,
    sectionId: v.sectionId || null,
    desiredStart: dateToIso(v.desiredStart)!,
    careType: v.careType,
    arrivesAt: v.arrivesAt || null,
    leavesAt: v.leavesAt || null,
    days: [...v.days].sort(),
    guardianFirstName: v.guardianFirstName,
    guardianLastName: v.guardianLastName,
    relationship: v.relationship,
    phone: textOrNull(v.phone),
    email: textOrNull(v.email),
    familyId: v.familyId || null,
    criteria: v.criteria,
  };
}

/** Section dont la tranche d'âge contient l'âge de l'enfant à l'entrée souhaitée. */
export function sectionForAge(sections: Section[], ageMonths: number) {
  return sections.find((s) => ageMonths >= s.minAgeMonths && (s.maxAgeMonths === null || ageMonths < s.maxAgeMonths));
}

function Block({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <h2 className="m-0 text-title-3">{title}</h2>
      {children}
    </section>
  );
}

/** Rubriques Enfant, Accueil souhaité et Famille de la fiche demande. */
export function RequestFormSections({ form, sections, disabled, familyLabel }: {
  form: RequestFormApi;
  sections: Section[];
  disabled?: boolean;
  familyLabel?: string | null;
}) {
  const errors = form.formState.errors;
  const [born, birthDate, desiredStart, sectionId, arrivesAt, leavesAt, days] = form.watch([
    "born", "birthDate", "desiredStart", "sectionId", "arrivesAt", "leavesAt", "days",
  ]);
  const birthIso = parseDateFr(birthDate);
  const startIso = parseDateFr(desiredStart);
  const age = birthIso && startIso ? ageInMonths(birthIso, startIso) : null;
  const suggested = age !== null ? sectionForAge(sections, age) : undefined;

  // Section proposée d'après l'âge, tant que la directrice n'en a pas choisi une autre.
  useEffect(() => {
    if (suggested && !form.getFieldState("sectionId").isDirty && !disabled) {
      form.setValue("sectionId", suggested.id);
    }
  }, [suggested, form, disabled]);

  const perDay = arrivesAt && leavesAt ? Math.max(0, span(arrivesAt, leavesAt)) : 0;
  const weekly = (perDay * days.length) / 60;
  const toggleDay = (d: number) =>
    form.setValue("days", days.includes(d) ? days.filter((x) => x !== d) : [...days, d], { shouldDirty: true, shouldValidate: form.formState.isSubmitted });

  return (
    <fieldset disabled={disabled} className="m-0 flex min-w-0 flex-col gap-4 border-0 p-0">
      <Block title="Enfant">
        <div role="radiogroup" aria-label="L’enfant est" className="flex gap-2">
          {(["oui", "non"] as const).map((v) => (
            <label key={v} className={cn("inline-flex h-control items-center gap-2 rounded-md border px-3", born === v ? "border-lagon-600 bg-lagon-100 text-lagon-700" : "border-input")}>
              <input type="radio" value={v} {...form.register("born")} className="accent-lagon-600" />
              {v === "oui" ? "Né" : "À naître"}
            </label>
          ))}
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <FormField label="Prénom" error={errors.childFirstName?.message}>
            <Input {...form.register("childFirstName")} />
          </FormField>
          <FormField label="Nom" error={errors.childLastName?.message}>
            <Input {...form.register("childLastName")} />
          </FormField>
          <FormField label={born === "oui" ? "Date de naissance" : "Naissance prévue le"} error={errors.birthDate?.message}>
            <DateInput placeholder="JJ/MM/AAAA" inputMode="numeric" {...form.register("birthDate")} />
          </FormField>
        </div>
        {age !== null && (
          <p className="m-0 text-muted-foreground">
            Âge à l’entrée souhaitée : <strong className="text-foreground">{formatAge(age)}</strong>.
            {suggested ? <> Section correspondante : <strong className="text-foreground">{suggested.name}</strong>.</> : " Aucune section ne correspond à cet âge."}
          </p>
        )}
      </Block>

      <Block title="Accueil souhaité">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-4">
          <FormField label="Date d’entrée souhaitée" error={errors.desiredStart?.message}>
            <DateInput placeholder="JJ/MM/AAAA" inputMode="numeric" {...form.register("desiredStart")} />
          </FormField>
          <FormField label="Section prévue">
            <select className={selectClass} {...form.register("sectionId")}>
              <option value="">À déterminer</option>
              {sections.map((s) => (
                <option key={s.id} value={s.id}>{s.name}</option>
              ))}
            </select>
          </FormField>
          <FormField label="Arrivée">
            <Input type="time" step={900} {...form.register("arrivesAt")} />
          </FormField>
          <FormField label="Départ" error={errors.leavesAt?.message}>
            <Input type="time" step={900} {...form.register("leavesAt")} />
          </FormField>
        </div>
        <FormField label="Type d’accueil" className="max-w-60">
          <select className={selectClass} {...form.register("careType")}>
            <option value="REGULIER">Régulier</option>
            <option value="OCCASIONNEL">Occasionnel</option>
          </select>
        </FormField>
        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
          <legend className="mb-2 p-0 text-label">Jours souhaités</legend>
          <div className="flex flex-wrap gap-2">
            {[1, 2, 3, 4, 5].map((d) => (
              <button
                key={d}
                type="button"
                aria-pressed={days.includes(d)}
                onClick={() => toggleDay(d)}
                className={cn(
                  "inline-flex h-control items-center rounded-md border px-3 text-label",
                  days.includes(d) ? "border-lagon-600 bg-lagon-100 text-lagon-700" : "border-input bg-card",
                )}
              >
                {DAY_NAMES[d - 1]}
              </button>
            ))}
          </div>
          {errors.days && <p className="m-0 text-small font-semibold text-danger-700">{errors.days.message}</p>}
          {weekly > 0 && <p className="m-0 text-muted-foreground tabular-nums">{weekly.toLocaleString("fr-FR")} h par semaine</p>}
        </fieldset>
        {sectionId === "" && !suggested && <p className="m-0 text-small text-muted-foreground">Choisissez la section pour vérifier les places libres.</p>}
      </Block>

      <Block title="Famille">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <FormField label="Prénom du responsable" error={errors.guardianFirstName?.message}>
            <Input {...form.register("guardianFirstName")} />
          </FormField>
          <FormField label="Nom du responsable" error={errors.guardianLastName?.message}>
            <Input {...form.register("guardianLastName")} />
          </FormField>
          <FormField label="Lien avec l’enfant" error={errors.relationship?.message}>
            <Input list="liens" {...form.register("relationship")} />
          </FormField>
          <RelationshipOptions />
          <FormField label="Téléphone" error={errors.phone?.message}>
            <Input inputMode="tel" {...form.register("phone")} />
          </FormField>
          <FormField label="E-mail" error={errors.email?.message} className="sm:col-span-2">
            <Input type="email" {...form.register("email")} />
          </FormField>
        </div>
        <FamilyPicker form={form} initialLabel={familyLabel ?? null} />
      </Block>
    </fieldset>
  );
}

/** Rattachement facultatif à une famille déjà accueillie (fratrie). */
function FamilyPicker({ form, initialLabel }: { form: RequestFormApi; initialLabel: string | null }) {
  const familyId = form.watch("familyId");
  const [query, setQuery] = useState("");
  const [label, setLabel] = useState(initialLabel);
  const results = useQuery({
    queryKey: ["families", query],
    queryFn: ({ signal }) => familiesApi.search(query, signal),
    enabled: query.trim().length >= 2,
  });
  if (familyId) {
    return (
      <p className="m-0 flex flex-wrap items-center gap-3">
        <span>Famille déjà accueillie : <strong>{label ?? "famille rattachée"}</strong></span>
        <button type="button" className="text-label text-lagon-700 underline" onClick={() => form.setValue("familyId", "", { shouldDirty: true })}>
          Détacher
        </button>
      </p>
    );
  }
  return (
    <div className="flex flex-col gap-2">
      <FormField label="Fratrie déjà accueillie ? Rechercher la famille" hint="Facultatif : tapez au moins deux lettres du nom.">
        <Input type="search" value={query} onChange={(e) => setQuery(e.target.value)} />
      </FormField>
      {results.data && results.data.length > 0 && (
        <ul className="m-0 flex list-none flex-col gap-1 p-0">
          {results.data.map((f) => (
            <li key={f.id}>
              <button
                type="button"
                className="text-left text-lagon-700 underline"
                onClick={() => {
                  form.setValue("familyId", f.id, { shouldDirty: true });
                  setLabel(f.label);
                  setQuery("");
                }}
              >
                {f.label}{f.guardianNames.length > 0 && ` (${f.guardianNames.join(", ")})`}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Critères de priorité cochés et score. */
export function CriteriaFields({ form, criteria, disabled, ranking }: {
  form: RequestFormApi;
  criteria: Criterion[];
  disabled?: boolean;
  ranking?: { rank: number; of: number } | null;
}) {
  const checked = form.watch("criteria");
  const score = criteria.filter((c) => checked.includes(c.id)).reduce((sum, c) => sum + c.points, 0);
  const toggle = (id: string) =>
    form.setValue("criteria", checked.includes(id) ? checked.filter((x) => x !== id) : [...checked, id], { shouldDirty: true });
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-border-subtle bg-card p-5 shadow-1">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="m-0 text-title-3">Critères de priorité</h2>
        <p className="m-0 text-label tabular-nums">
          Score {score}
          {ranking && ranking.of > 1 && (
            <span className="font-normal text-muted-foreground"> · {ranking.rank === 1 ? "1re" : `${ranking.rank}e`} sur {ranking.of} demandes de la section pour ce mois</span>
          )}
        </p>
      </div>
      <fieldset disabled={disabled} className="m-0 flex flex-col gap-2 border-0 p-0">
        <legend className="sr-only">Critères de priorité</legend>
        {criteria.map((c) => (
          <label key={c.id} className="flex items-center gap-3">
            <input type="checkbox" checked={checked.includes(c.id)} onChange={() => toggle(c.id)} className="size-4 accent-lagon-600" />
            <span className="flex-1">{c.label}</span>
            <span className="text-label text-muted-foreground tabular-nums">+{c.points}</span>
          </label>
        ))}
      </fieldset>
      <p className="m-0 text-small text-muted-foreground">Les critères et leurs points se règlent dans Paramètres › Critères d’inscription.</p>
    </section>
  );
}

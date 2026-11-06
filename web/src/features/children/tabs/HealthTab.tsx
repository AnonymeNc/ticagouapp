import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useFieldArray, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Download, Plus, Trash2 } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/api";
import { isoToDateFr, parseDateFr } from "@/lib/datetime";
import { dateToIso, isoToField, nullToEmpty, optionalDateFr, optionalText, requiredText, selectClass, textOrNull } from "@/lib/formFields";
import { cn } from "@/lib/utils";
import { mealsApi, textureLabels } from "@/features/meals/api";
import { childrenApi, severityLabels, type ChildRecord, type Severity } from "../api";

/** Choix « Autre » : régime saisi en texte libre, hors de la liste. */
const OTHER = "autre";
import { useRefreshChild, type ChildTabProps } from "../ChildRecordPage";
import { Facts, FormActions, Panel } from "./common";

export function HealthTab({ child, canEdit }: ChildTabProps) {
  const [editing, setEditing] = useState(false);
  if (editing) return <HealthForm child={child} onDone={() => setEditing(false)} />;

  const h = child.health;
  const paiDocument = child.documents.find((d) => d.id === h.paiDocumentId);
  const instructions = (h.instructions ?? "").split("\n").map((l) => l.trim()).filter(Boolean);

  return (
    <div className="flex flex-col gap-4">
      {canEdit && (
        <div>
          <Button variant="outline" onClick={() => setEditing(true)}>Modifier les informations de santé</Button>
        </div>
      )}
      <div className="grid grid-cols-[repeat(auto-fit,minmax(320px,1fr))] gap-4">
        <Panel title="Allergies">
          {h.allergies.length === 0 ? (
            <p className="m-0 text-muted-foreground">Aucune allergie connue.</p>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {h.allergies.map((a) => (
                <li key={a.allergen} className="flex items-start justify-between gap-3 rounded-md bg-danger-100 px-3 py-2">
                  <span>
                    <strong className="font-semibold text-danger-700">{a.allergen}</strong>
                    {a.reaction && <span className="block text-small text-ink">{a.reaction}</span>}
                  </span>
                  <SeverityBadge severity={a.severity} />
                </li>
              ))}
            </ul>
          )}
          <h4 className="m-0 mt-2 text-label">Régime alimentaire</h4>
          {h.diet ? (
            <p className="m-0">
              <strong className="font-semibold">{h.diet}</strong>
              {!h.dietId && <span className="text-small text-muted-foreground"> · hors de la liste des régimes, menus non contrôlés</span>}
              {h.dietDetails && <span className="block text-small text-muted-foreground">{h.dietDetails}</span>}
            </p>
          ) : (
            <p className="m-0 text-muted-foreground">Aucun régime particulier.</p>
          )}
          <h4 className="m-0 mt-2 text-label">Repas</h4>
          <p className="m-0">
            Texture : {textureLabels[h.mealTexture ?? "NORMALE"].toLowerCase()}
            {h.packedLunch && <strong className="block font-semibold text-warning-700">Panier repas fourni par la famille : ne rien servir d’autre, goûter compris.</strong>}
          </p>
        </Panel>

        <Panel
          title="Projet d’accueil individualisé (PAI)"
          actions={h.paiActive && <span className="rounded-full bg-info-100 px-2 py-0.5 text-caption text-info-700">PAI actif</span>}
        >
          {h.paiActive ? (
            <>
              <Facts
                items={[
                  ["Motif", h.paiReason],
                  ["Validité", h.paiValidFrom || h.paiValidUntil
                    ? `${h.paiValidFrom ? isoToDateFr(h.paiValidFrom) : "…"} → ${h.paiValidUntil ? isoToDateFr(h.paiValidUntil) : "…"}`
                    : null],
                  ["Trousse d’urgence", h.paiKitLocation],
                  ["Contenu de la trousse", h.paiKitDetails],
                ]}
              />
              {paiDocument ? (
                <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-border-subtle px-3 py-2">
                  <span>{paiDocument.fileName}</span>
                  <Button variant="outline" size="sm" onClick={() => void childrenApi.downloadDocument(paiDocument)}>
                    <Download aria-hidden />
                    Télécharger
                  </Button>
                </div>
              ) : (
                <p className="m-0 text-small text-muted-foreground">Document du PAI : à joindre dans l’onglet Documents (catégorie Santé).</p>
              )}
            </>
          ) : (
            <p className="m-0 text-muted-foreground">Pas de PAI.</p>
          )}
          {h.emergencyProtocol && (
            <p className="m-0 rounded-md bg-danger-100 px-3 py-2 text-danger-700">
              <strong className="font-semibold">En cas de réaction : </strong>
              {h.emergencyProtocol}
            </p>
          )}
        </Panel>

        <Panel
          title="Vaccins"
          actions={h.vaccinationCheckedOn && <span className="text-small text-muted-foreground tabular-nums">Carnet vérifié le {isoToDateFr(h.vaccinationCheckedOn)}</span>}
        >
          {h.vaccinations.length === 0 ? (
            <p className="m-0 text-muted-foreground">Aucun vaccin renseigné.</p>
          ) : (
            <table className="w-full border-collapse text-left">
              <thead>
                <tr className="border-b border-border-subtle text-label text-muted-foreground">
                  <th scope="col" className="py-1.5 pr-4">Vaccin</th>
                  <th scope="col" className="py-1.5">Dernière dose</th>
                </tr>
              </thead>
              <tbody>
                {h.vaccinations.map((v) => (
                  <tr key={v.vaccine} className="border-b border-border-subtle last:border-0">
                    <td className="py-1.5 pr-4">{v.vaccine}</td>
                    <td className="py-1.5 tabular-nums">{v.lastDoseOn ? isoToDateFr(v.lastDoseOn) : "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>

        <Panel title="Médecin et consignes">
          <Facts items={[["Médecin traitant", h.doctorName], ["Téléphone du cabinet", h.doctorPhone], ["Adresse", h.doctorAddress]]} />
          {instructions.length > 0 && (
            <ul className="m-0 flex flex-col gap-1 pl-5">
              {instructions.map((line) => <li key={line}>{line}</li>)}
            </ul>
          )}
        </Panel>
      </div>
    </div>
  );
}

function SeverityBadge({ severity }: { severity: Severity }) {
  return (
    <span className={cn("shrink-0 rounded-full px-2 py-0.5 text-caption",
      severity === "SEVERE" ? "bg-danger-600 text-white" : severity === "MODEREE" ? "bg-warning-100 text-warning-700" : "bg-muted text-muted-foreground")}>
      {severityLabels[severity]}
    </span>
  );
}

const schema = z
  .object({
    allergies: z.array(z.object({
      allergen: requiredText(100, "Indiquez l’allergène."),
      reaction: optionalText(300),
      severity: z.enum(["LEGERE", "MODEREE", "SEVERE"]),
    })),
    dietChoice: z.string(),
    diet: optionalText(100),
    dietDetails: optionalText(500),
    mealTexture: z.enum(["NORMALE", "HACHEE", "MIXEE"]),
    packedLunch: z.boolean(),
    paiActive: z.boolean(),
    paiReason: optionalText(200),
    paiValidFrom: optionalDateFr,
    paiValidUntil: optionalDateFr,
    paiKitLocation: optionalText(200),
    paiKitDetails: optionalText(500),
    paiDocumentId: z.string(),
    emergencyProtocol: optionalText(500),
    vaccinations: z.array(z.object({ vaccine: requiredText(100, "Indiquez le vaccin."), lastDoseOn: optionalDateFr })),
    vaccinationCheckedOn: optionalDateFr,
    doctorName: optionalText(120),
    doctorPhone: optionalText(30),
    doctorAddress: optionalText(200),
    instructions: optionalText(2000),
  })
  .refine((v) => !v.paiValidFrom || !v.paiValidUntil || parseDateFr(v.paiValidUntil)! >= parseDateFr(v.paiValidFrom)!, {
    message: "La fin de validité ne peut pas précéder le début.",
    path: ["paiValidUntil"],
  });

type Values = z.infer<typeof schema>;

function HealthForm({ child, onDone }: { child: ChildRecord; onDone: () => void }) {
  const refresh = useRefreshChild(child.id);
  const [error, setError] = useState<string | null>(null);
  const h = child.health;
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      allergies: h.allergies.map((a) => ({ allergen: a.allergen, reaction: nullToEmpty(a.reaction), severity: a.severity })),
      dietChoice: h.dietId ?? (h.diet ? OTHER : ""),
      diet: h.dietId ? "" : nullToEmpty(h.diet),
      dietDetails: nullToEmpty(h.dietDetails),
      mealTexture: h.mealTexture ?? "NORMALE",
      packedLunch: h.packedLunch ?? false,
      paiActive: h.paiActive,
      paiReason: nullToEmpty(h.paiReason),
      paiValidFrom: isoToField(h.paiValidFrom),
      paiValidUntil: isoToField(h.paiValidUntil),
      paiKitLocation: nullToEmpty(h.paiKitLocation),
      paiKitDetails: nullToEmpty(h.paiKitDetails),
      paiDocumentId: h.paiDocumentId ?? "",
      emergencyProtocol: nullToEmpty(h.emergencyProtocol),
      vaccinations: h.vaccinations.map((v) => ({ vaccine: v.vaccine, lastDoseOn: isoToField(v.lastDoseOn) })),
      vaccinationCheckedOn: isoToField(h.vaccinationCheckedOn),
      doctorName: nullToEmpty(h.doctorName),
      doctorPhone: nullToEmpty(h.doctorPhone),
      doctorAddress: nullToEmpty(h.doctorAddress),
      instructions: nullToEmpty(h.instructions),
    },
  });
  const allergies = useFieldArray({ control: form.control, name: "allergies" });
  const vaccinations = useFieldArray({ control: form.control, name: "vaccinations" });
  const errors = form.formState.errors;
  const paiActive = form.watch("paiActive");
  const dietChoice = form.watch("dietChoice");
  const diets = useQuery({ queryKey: ["diets"], queryFn: ({ signal }) => mealsApi.diets(signal) });
  const healthDocuments = child.documents.filter((d) => d.category === "SANTE");

  const onSubmit = form.handleSubmit(async (v) => {
    setError(null);
    try {
      await childrenApi.updateHealth(child.id, {
        allergies: v.allergies.map((a) => ({ allergen: a.allergen, reaction: textOrNull(a.reaction), severity: a.severity })),
        dietId: v.dietChoice && v.dietChoice !== OTHER ? v.dietChoice : null,
        diet: v.dietChoice === OTHER ? textOrNull(v.diet) : v.dietChoice ? diets.data?.find((d) => d.id === v.dietChoice)?.label ?? null : null,
        dietDetails: textOrNull(v.dietDetails),
        mealTexture: v.mealTexture,
        packedLunch: v.packedLunch,
        paiActive: v.paiActive,
        paiReason: textOrNull(v.paiReason),
        paiValidFrom: dateToIso(v.paiValidFrom),
        paiValidUntil: dateToIso(v.paiValidUntil),
        paiKitLocation: textOrNull(v.paiKitLocation),
        paiKitDetails: textOrNull(v.paiKitDetails),
        paiDocumentId: v.paiDocumentId || null,
        emergencyProtocol: textOrNull(v.emergencyProtocol),
        vaccinations: v.vaccinations.map((x) => ({ vaccine: x.vaccine, lastDoseOn: dateToIso(x.lastDoseOn) })),
        vaccinationCheckedOn: dateToIso(v.vaccinationCheckedOn),
        doctorName: textOrNull(v.doctorName),
        doctorPhone: textOrNull(v.doctorPhone),
        doctorAddress: textOrNull(v.doctorAddress),
        instructions: textOrNull(v.instructions),
      });
      await refresh();
      onDone();
    } catch (e) {
      setError(errorMessage(e));
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
      <Panel title="Allergies">
        {allergies.fields.map((field, i) => (
          <fieldset key={field.id} className="m-0 grid grid-cols-1 gap-3 border-0 p-0 sm:grid-cols-[1fr_2fr_auto_auto]">
            <legend className="sr-only">Allergie {i + 1}</legend>
            <FormField label="Allergène" error={errors.allergies?.[i]?.allergen?.message}>
              <Input {...form.register(`allergies.${i}.allergen`)} />
            </FormField>
            <FormField label="Réaction connue" error={errors.allergies?.[i]?.reaction?.message}>
              <Input {...form.register(`allergies.${i}.reaction`)} />
            </FormField>
            <FormField label="Gravité">
              <select className={selectClass} {...form.register(`allergies.${i}.severity`)}>
                {Object.entries(severityLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </FormField>
            <Button type="button" variant="ghost" className="self-end" aria-label={`Retirer l’allergie ${i + 1}`} onClick={() => allergies.remove(i)}>
              <Trash2 aria-hidden />
            </Button>
          </fieldset>
        ))}
        <div>
          <Button type="button" variant="outline" onClick={() => allergies.append({ allergen: "", reaction: "", severity: "SEVERE" })}>
            <Plus aria-hidden />
            Ajouter une allergie
          </Button>
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <FormField label="Régime alimentaire" hint="La liste se règle dans Paramètres › Repas et régimes.">
            <select className={selectClass} {...form.register("dietChoice")}>
              <option value="">Aucun régime</option>
              {diets.data?.map((d) => <option key={d.id} value={d.id}>{d.label}</option>)}
              <option value={OTHER}>Autre (texte libre, menus non contrôlés)</option>
            </select>
          </FormField>
          {dietChoice === OTHER && (
            <FormField label="Régime" error={errors.diet?.message}>
              <Input {...form.register("diet")} />
            </FormField>
          )}
          <FormField label="Précisions sur le régime" error={errors.dietDetails?.message}>
            <Input {...form.register("dietDetails")} />
          </FormField>
          <FormField label="Texture des repas" hint="Pour les bébés : mixé ou haché.">
            <select className={selectClass} {...form.register("mealTexture")}>
              {Object.entries(textureLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          </FormField>
          <label className="inline-flex items-center gap-2 sm:col-span-2">
            <input type="checkbox" {...form.register("packedLunch")} className="size-4 accent-lagon-600" />
            Panier repas fourni par la famille (PAI) : la cuisine ne prépare ni repas ni goûter
          </label>
        </div>
      </Panel>

      <Panel title="PAI et urgence">
        <label className="inline-flex items-center gap-2">
          <input type="checkbox" {...form.register("paiActive")} className="size-4 accent-lagon-600" />
          L’enfant a un projet d’accueil individualisé (PAI)
        </label>
        {paiActive && (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <FormField label="Motif" error={errors.paiReason?.message} className="sm:col-span-2">
              <Input {...form.register("paiReason")} />
            </FormField>
            <FormField label="Valide du" hint="JJ/MM/AAAA" error={errors.paiValidFrom?.message}>
              <Input inputMode="numeric" {...form.register("paiValidFrom")} />
            </FormField>
            <FormField label="Jusqu’au" hint="JJ/MM/AAAA" error={errors.paiValidUntil?.message}>
              <Input inputMode="numeric" {...form.register("paiValidUntil")} />
            </FormField>
            <FormField label="Emplacement de la trousse d’urgence" error={errors.paiKitLocation?.message}>
              <Input {...form.register("paiKitLocation")} />
            </FormField>
            <FormField label="Contenu de la trousse" error={errors.paiKitDetails?.message}>
              <Input {...form.register("paiKitDetails")} />
            </FormField>
            <FormField label="Document du PAI" hint={healthDocuments.length === 0 ? "Joignez d’abord le PAI dans l’onglet Documents (catégorie Santé)." : undefined} className="sm:col-span-2">
              <select className={selectClass} {...form.register("paiDocumentId")}>
                <option value="">Aucun</option>
                {healthDocuments.map((d) => <option key={d.id} value={d.id}>{d.name} ({d.fileName})</option>)}
              </select>
            </FormField>
          </div>
        )}
        <FormField label="En cas de réaction" hint="Affiché en tête de fiche, avec le numéro du responsable." error={errors.emergencyProtocol?.message}>
          <Input placeholder="Suivre le protocole du PAI, appeler le 15" {...form.register("emergencyProtocol")} />
        </FormField>
      </Panel>

      <Panel title="Vaccins">
        {vaccinations.fields.map((field, i) => (
          <fieldset key={field.id} className="m-0 grid grid-cols-1 gap-3 border-0 p-0 sm:grid-cols-[2fr_1fr_auto]">
            <legend className="sr-only">Vaccin {i + 1}</legend>
            <FormField label="Vaccin" error={errors.vaccinations?.[i]?.vaccine?.message}>
              <Input {...form.register(`vaccinations.${i}.vaccine`)} />
            </FormField>
            <FormField label="Dernière dose" hint="JJ/MM/AAAA" error={errors.vaccinations?.[i]?.lastDoseOn?.message}>
              <Input inputMode="numeric" {...form.register(`vaccinations.${i}.lastDoseOn`)} />
            </FormField>
            <Button type="button" variant="ghost" className="self-center" aria-label={`Retirer le vaccin ${i + 1}`} onClick={() => vaccinations.remove(i)}>
              <Trash2 aria-hidden />
            </Button>
          </fieldset>
        ))}
        <div className="flex flex-wrap items-end gap-4">
          <Button type="button" variant="outline" onClick={() => vaccinations.append({ vaccine: "", lastDoseOn: "" })}>
            <Plus aria-hidden />
            Ajouter un vaccin
          </Button>
          <FormField label="Carnet vérifié le" hint="JJ/MM/AAAA" error={errors.vaccinationCheckedOn?.message}>
            <Input inputMode="numeric" className="w-36" {...form.register("vaccinationCheckedOn")} />
          </FormField>
        </div>
      </Panel>

      <Panel title="Médecin et consignes">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <FormField label="Médecin traitant" error={errors.doctorName?.message}>
            <Input {...form.register("doctorName")} />
          </FormField>
          <FormField label="Téléphone du cabinet" error={errors.doctorPhone?.message}>
            <Input inputMode="tel" {...form.register("doctorPhone")} />
          </FormField>
          <FormField label="Adresse du cabinet" error={errors.doctorAddress?.message} className="sm:col-span-2">
            <Input {...form.register("doctorAddress")} />
          </FormField>
          <FormField label="Consignes" hint="Une consigne par ligne" error={errors.instructions?.message} className="sm:col-span-2">
            <Textarea rows={4} {...form.register("instructions")} />
          </FormField>
        </div>
      </Panel>

      {error && <Alert>{error}</Alert>}
      <FormActions pending={form.formState.isSubmitting} onCancel={onDone} />
    </form>
  );
}

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { CircleCheck, CircleX } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { dateToIso, isoToField, nullToEmpty, optionalDateFr, optionalText, textOrNull } from "@/lib/formFields";
import { childrenApi, consentLabels, type ConsentType } from "../api";
import { useRefreshChild, type ChildTabProps } from "../ChildRecordPage";
import { FormActions, Panel } from "./common";

const TYPES: ConsentType[] = ["PHOTOS", "SORTIES", "SOINS_URGENCE"];

const descriptions: Record<ConsentType, string> = {
  PHOTOS: "Prises de vue de l’enfant (cahier de vie, affichage, diffusion).",
  SORTIES: "Promenades et sorties avec la section.",
  SOINS_URGENCE: "Soins, transport par le SAMU et hospitalisation en cas d’urgence.",
};

export function ConsentsTab({ child, canEdit }: ChildTabProps) {
  const [editing, setEditing] = useState(false);
  if (editing) return <ConsentsForm child={child} onDone={() => setEditing(false)} />;
  const byType = new Map(child.consents.map((c) => [c.type, c]));

  return (
    <div className="flex flex-col gap-4">
      {canEdit && (
        <div>
          <Button variant="outline" onClick={() => setEditing(true)}>Modifier les autorisations</Button>
        </div>
      )}
      <div className="grid grid-cols-[repeat(auto-fit,minmax(280px,1fr))] gap-4">
        {TYPES.map((type) => {
          const consent = byType.get(type);
          return (
            <Panel key={type} title={consentLabels[type]}>
              <p className="m-0 text-muted-foreground">{consent?.details ?? descriptions[type]}</p>
              {!consent ? (
                <span className="self-start rounded-full bg-warning-100 px-2 py-0.5 text-caption text-warning-700">Non renseignée</span>
              ) : consent.granted ? (
                <span className="inline-flex items-center gap-1 self-start rounded-full bg-success-100 px-2 py-0.5 text-caption text-success-700">
                  <CircleCheck aria-hidden className="size-3.5" />
                  Accordée
                </span>
              ) : (
                <span className="inline-flex items-center gap-1 self-start rounded-full bg-danger-100 px-2 py-0.5 text-caption text-danger-700">
                  <CircleX aria-hidden className="size-3.5" />
                  Refusée
                </span>
              )}
              {consent?.signedOn && (
                <p className="m-0 text-small text-muted-foreground tabular-nums">
                  Signée le {isoToDateFr(consent.signedOn)}
                  {consent.signedBy && ` par ${consent.signedBy}`}
                </p>
              )}
            </Panel>
          );
        })}
      </div>
    </div>
  );
}

const schema = z.object({
  consents: z.array(z.object({
    type: z.enum(["PHOTOS", "SORTIES", "SOINS_URGENCE"]),
    granted: z.enum(["oui", "non"]),
    details: optionalText(500),
    signedOn: optionalDateFr,
    signedBy: optionalText(170),
  })),
});

function ConsentsForm({ child, onDone }: { child: ChildTabProps["child"]; onDone: () => void }) {
  const refresh = useRefreshChild(child.id);
  const [error, setError] = useState<string | null>(null);
  const byType = new Map(child.consents.map((c) => [c.type, c]));
  const form = useForm<z.infer<typeof schema>>({
    resolver: zodResolver(schema),
    defaultValues: {
      consents: TYPES.map((type) => {
        const c = byType.get(type);
        return {
          type,
          granted: c?.granted ? "oui" : "non",
          details: nullToEmpty(c?.details),
          signedOn: isoToField(c?.signedOn),
          signedBy: nullToEmpty(c?.signedBy),
        };
      }),
    },
  });
  const errors = form.formState.errors;

  const onSubmit = form.handleSubmit(async (v) => {
    setError(null);
    try {
      await childrenApi.updateConsents(child.id, v.consents.map((c) => ({
        type: c.type,
        granted: c.granted === "oui",
        details: textOrNull(c.details),
        signedOn: dateToIso(c.signedOn),
        signedBy: textOrNull(c.signedBy),
      })));
      await refresh();
      onDone();
    } catch (e) {
      setError(errorMessage(e));
    }
  });

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
      {TYPES.map((type, i) => (
        <Panel key={type} title={consentLabels[type]}>
          <fieldset className="m-0 flex gap-4 border-0 p-0">
            <legend className="sr-only">{consentLabels[type]}</legend>
            <label className="inline-flex items-center gap-2">
              <input type="radio" value="oui" {...form.register(`consents.${i}.granted`)} className="accent-lagon-600" />
              Accordée
            </label>
            <label className="inline-flex items-center gap-2">
              <input type="radio" value="non" {...form.register(`consents.${i}.granted`)} className="accent-lagon-600" />
              Refusée
            </label>
          </fieldset>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-[2fr_1fr_1fr]">
            <FormField label="Précisions" error={errors.consents?.[i]?.details?.message}>
              <Input placeholder={descriptions[type]} {...form.register(`consents.${i}.details`)} />
            </FormField>
            <FormField label="Signée le" hint="JJ/MM/AAAA" error={errors.consents?.[i]?.signedOn?.message}>
              <Input inputMode="numeric" {...form.register(`consents.${i}.signedOn`)} />
            </FormField>
            <FormField label="Par" error={errors.consents?.[i]?.signedBy?.message}>
              <Input {...form.register(`consents.${i}.signedBy`)} />
            </FormField>
          </div>
        </Panel>
      ))}
      {error && <Alert>{error}</Alert>}
      <FormActions pending={form.formState.isSubmitting} onCancel={onDone} />
    </form>
  );
}

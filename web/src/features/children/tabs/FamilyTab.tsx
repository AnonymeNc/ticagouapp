import { confirmIrreversible } from "@/lib/native/device";
import { useState } from "react";
import { useNavigate } from "react-router";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useQueryClient } from "@tanstack/react-query";
import { Download, Plus, Trash2 } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { FormField } from "@/components/FormField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import { nullToEmpty, optionalEmail, optionalPhone, optionalText, requiredText, textOrNull } from "@/lib/formFields";
import { initials } from "@/lib/initials";
import { familiesApi, type Guardian } from "../api";
import { useRefreshChild, type ChildTabProps } from "../ChildRecordPage";
import { RelationshipOptions } from "../NewChildPage";
import { Facts, FormActions, Panel } from "./common";

export function FamilyTab({ child, canEdit }: ChildTabProps) {
  const family = child.family;
  const [editingGuardian, setEditingGuardian] = useState<Guardian | "new" | null>(null);
  const [editingFamily, setEditingFamily] = useState(false);
  const payers = family.guardians.filter((g) => g.payer);
  const siblings = family.children.filter((c) => c.id !== child.id);
  const address = [family.address, [family.postalCode, family.city].filter(Boolean).join(" ")].filter(Boolean).join(", ");

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-[repeat(auto-fit,minmax(300px,1fr))] gap-4">
        {family.guardians.map((g) => (
          <Panel key={g.id}>
            <div className="flex items-start gap-3">
              <span aria-hidden className="flex size-10 shrink-0 items-center justify-center rounded-full bg-lagon-100 text-label text-lagon-700">
                {initials(`${g.firstName} ${g.lastName}`)}
              </span>
              <div className="flex-1">
                <h3 className="m-0 text-title-3">{g.firstName} {g.lastName}</h3>
                <p className="m-0 text-muted-foreground">
                  {g.relationship}
                  {g.legalGuardian && " · responsable légal"}
                </p>
                {g.payer && <span className="mt-1 inline-flex h-6 items-center rounded-full bg-success-100 px-2 text-caption text-success-700">Payeur</span>}
              </div>
              {canEdit && <Button variant="outline" size="sm" onClick={() => setEditingGuardian(g)}>Modifier</Button>}
            </div>
            <Facts
              items={[
                ["Portable", g.mobilePhone],
                ["Travail", g.workPhone],
                ["E-mail", g.email && <a href={`mailto:${g.email}`}>{g.email}</a>],
                ["Employeur", g.employer],
              ]}
            />
          </Panel>
        ))}
      </div>
      {canEdit && (
        <div>
          <Button variant="outline" onClick={() => setEditingGuardian("new")}>
            <Plus aria-hidden />
            Ajouter un responsable
          </Button>
        </div>
      )}

      <div className="grid grid-cols-[repeat(auto-fit,minmax(300px,1fr))] gap-4">
        <Panel
          title="Situation familiale"
          actions={canEdit && !editingFamily && <Button variant="outline" size="sm" onClick={() => setEditingFamily(true)}>Modifier</Button>}
        >
          {editingFamily ? (
            <FamilyForm child={child} onDone={() => setEditingFamily(false)} />
          ) : (
            <Facts
              items={[
                ["Famille", family.label],
                ["Situation", family.situation],
                ["Adresse du foyer", address],
                ["Fratrie", siblings.length > 0 ? siblings.map((s) => `${s.firstName} (${isoToDateFr(s.birthDate)})`).join(", ") : null],
                ["Notes", family.notes],
              ]}
            />
          )}
        </Panel>
        <Panel title="Payeur">
          <Facts items={[["Payeur principal", payers.map((p) => `${p.firstName} ${p.lastName}`).join(", ") || "À désigner"]]} />
          <p className="m-0 text-small text-muted-foreground">
            Le mode de règlement et l’adresse de facturation se régleront avec le module Facturation.
          </p>
        </Panel>
      </div>

      {canEdit && <FamilyDataPanel child={child} />}

      <Dialog open={editingGuardian !== null} onOpenChange={(open) => !open && setEditingGuardian(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editingGuardian === "new" ? "Ajouter un responsable" : "Modifier le responsable"}</DialogTitle>
            <DialogDescription>Adulte du foyer de {child.firstName}.</DialogDescription>
          </DialogHeader>
          {editingGuardian !== null && (
            <GuardianForm
              child={child}
              guardian={editingGuardian === "new" ? null : editingGuardian}
              onDone={() => setEditingGuardian(null)}
            />
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

const guardianSchema = z.object({
  firstName: requiredText(80, "Indiquez le prénom."),
  lastName: requiredText(80, "Indiquez le nom."),
  relationship: requiredText(60, "Indiquez le lien avec l’enfant."),
  legalGuardian: z.boolean(),
  payer: z.boolean(),
  mobilePhone: optionalPhone,
  workPhone: optionalPhone,
  email: optionalEmail,
  employer: optionalText(150),
});

function GuardianForm({ child, guardian, onDone }: { child: ChildTabProps["child"]; guardian: Guardian | null; onDone: () => void }) {
  const refresh = useRefreshChild(child.id);
  const [error, setError] = useState<string | null>(null);
  const form = useForm<z.infer<typeof guardianSchema>>({
    resolver: zodResolver(guardianSchema),
    defaultValues: {
      firstName: guardian?.firstName ?? "",
      lastName: guardian?.lastName ?? child.lastName,
      relationship: guardian?.relationship ?? "",
      legalGuardian: guardian?.legalGuardian ?? true,
      payer: guardian?.payer ?? false,
      mobilePhone: nullToEmpty(guardian?.mobilePhone),
      workPhone: nullToEmpty(guardian?.workPhone),
      email: nullToEmpty(guardian?.email),
      employer: nullToEmpty(guardian?.employer),
    },
  });
  const errors = form.formState.errors;

  const onSubmit = form.handleSubmit(async (v) => {
    setError(null);
    const body = {
      ...v,
      mobilePhone: textOrNull(v.mobilePhone),
      workPhone: textOrNull(v.workPhone),
      email: textOrNull(v.email),
      employer: textOrNull(v.employer),
      displayOrder: guardian?.displayOrder ?? child.family.guardians.length,
    };
    try {
      if (guardian) await familiesApi.updateGuardian(guardian.id, body);
      else await familiesApi.addGuardian(child.family.id, body);
      await refresh();
      onDone();
    } catch (e) {
      setError(errorMessage(e));
    }
  });

  const remove = async () => {
    if (!guardian) return;
    setError(null);
    try {
      await familiesApi.deleteGuardian(guardian.id);
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
      <FormField label="Téléphone portable" error={errors.mobilePhone?.message}>
        <Input inputMode="tel" {...form.register("mobilePhone")} />
      </FormField>
      <FormField label="Téléphone travail" error={errors.workPhone?.message}>
        <Input inputMode="tel" {...form.register("workPhone")} />
      </FormField>
      <FormField label="E-mail" error={errors.email?.message}>
        <Input type="email" {...form.register("email")} />
      </FormField>
      <FormField label="Employeur" error={errors.employer?.message} className="sm:col-span-2">
        <Input {...form.register("employer")} />
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
      {error && <div className="sm:col-span-2"><Alert>{error}</Alert></div>}
      <div className="flex items-center justify-between sm:col-span-2">
        <FormActions pending={form.formState.isSubmitting} onCancel={onDone} />
        {guardian && (
          <Button type="button" variant="ghost" className="text-danger-700" onClick={() => void remove()}>
            <Trash2 aria-hidden />
            Retirer
          </Button>
        )}
      </div>
    </form>
  );
}

const familySchema = z.object({
  label: requiredText(120, "Indiquez le nom de la famille."),
  address: optionalText(200),
  postalCode: optionalText(10),
  city: optionalText(100),
  situation: optionalText(500),
  notes: optionalText(2000),
});

function FamilyForm({ child, onDone }: { child: ChildTabProps["child"]; onDone: () => void }) {
  const refresh = useRefreshChild(child.id);
  const [error, setError] = useState<string | null>(null);
  const f = child.family;
  const form = useForm<z.infer<typeof familySchema>>({
    resolver: zodResolver(familySchema),
    defaultValues: {
      label: f.label,
      address: nullToEmpty(f.address),
      postalCode: nullToEmpty(f.postalCode),
      city: nullToEmpty(f.city),
      situation: nullToEmpty(f.situation),
      notes: nullToEmpty(f.notes),
    },
  });
  const errors = form.formState.errors;
  const onSubmit = form.handleSubmit(async (v) => {
    setError(null);
    try {
      await familiesApi.update(f.id, {
        label: v.label,
        address: textOrNull(v.address),
        postalCode: textOrNull(v.postalCode),
        city: textOrNull(v.city),
        situation: textOrNull(v.situation),
        notes: textOrNull(v.notes),
      });
      await refresh();
      onDone();
    } catch (e) {
      setError(errorMessage(e));
    }
  });
  return (
    <form onSubmit={onSubmit} noValidate className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <FormField label="Nom de la famille" error={errors.label?.message} className="sm:col-span-2">
        <Input {...form.register("label")} />
      </FormField>
      <FormField label="Situation" hint="Ex. parents en vie commune, autorité parentale conjointe" error={errors.situation?.message} className="sm:col-span-2">
        <Input {...form.register("situation")} />
      </FormField>
      <FormField label="Adresse" error={errors.address?.message} className="sm:col-span-2">
        <Input {...form.register("address")} />
      </FormField>
      <FormField label="Code postal" error={errors.postalCode?.message}>
        <Input inputMode="numeric" {...form.register("postalCode")} />
      </FormField>
      <FormField label="Commune" error={errors.city?.message}>
        <Input {...form.register("city")} />
      </FormField>
      <FormField label="Notes" error={errors.notes?.message} className="sm:col-span-2">
        <Textarea rows={3} {...form.register("notes")} />
      </FormField>
      {error && <div className="sm:col-span-2"><Alert>{error}</Alert></div>}
      <div className="sm:col-span-2">
        <FormActions pending={form.formState.isSubmitting} onCancel={onDone} />
      </div>
    </form>
  );
}

/** Export complet et suppression définitive des données du foyer (direction). */
function FamilyDataPanel({ child }: { child: ChildTabProps["child"] }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [exporting, setExporting] = useState(false);
  const [purgeOpen, setPurgeOpen] = useState(false);
  const [confirmation, setConfirmation] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const family = child.family;

  const exportData = async () => {
    setExporting(true);
    setError(null);
    try {
      await familiesApi.export(family.id);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setExporting(false);
    }
  };

  const purge = async () => {
    setPending(true);
    setError(null);
    try {
      if (!(await confirmIrreversible("Effacer les données de la famille"))) {
        setPending(false);
        return;
      }
      await familiesApi.purge(family.id, confirmation);
      queryClient.removeQueries({ queryKey: ["child"] });
      await queryClient.invalidateQueries({ queryKey: ["children"] });
      navigate("/enfants", { replace: true });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPending(false);
    }
  };

  return (
    <Panel title="Données de la famille">
      <p className="m-0 text-muted-foreground">
        La famille peut demander une copie de toutes ses données, puis leur suppression après la sortie des enfants.
        Ces opérations sont inscrites au journal.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" onClick={() => void exportData()} disabled={exporting}>
          <Download aria-hidden />
          {exporting ? "Préparation…" : "Exporter toutes les données"}
        </Button>
        <Button variant="outline" className="border-danger-700 text-danger-700" onClick={() => setPurgeOpen(true)}>
          <Trash2 aria-hidden />
          Supprimer définitivement…
        </Button>
      </div>
      {error && !purgeOpen && <Alert>{error}</Alert>}

      <Dialog open={purgeOpen} onOpenChange={(open) => { setPurgeOpen(open); setError(null); setConfirmation(""); }}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Supprimer les données de {family.label}</DialogTitle>
            <DialogDescription>
              Responsables, enfants ({family.children.map((c) => c.firstName).join(", ")}), santé, autorisations,
              personnes autorisées, photos et documents seront effacés. Cette action est définitive.
            </DialogDescription>
          </DialogHeader>
          <p className="m-0 rounded-md bg-warning-100 px-3 py-2 text-warning-700">
            Pensez à exporter les données avant, si la famille en veut une copie.
          </p>
          <FormField label={`Pour confirmer, saisissez « ${family.label} »`}>
            <Input value={confirmation} onChange={(e) => setConfirmation(e.target.value)} autoComplete="off" />
          </FormField>
          {error && <Alert>{error}</Alert>}
          <div className="flex gap-3">
            <Button
              className="bg-danger-600 hover:bg-danger-700"
              disabled={pending || confirmation.trim().toLowerCase() !== family.label.trim().toLowerCase()}
              onClick={() => void purge()}
            >
              {pending ? "Suppression…" : "Supprimer définitivement"}
            </Button>
            <Button variant="ghost" onClick={() => setPurgeOpen(false)}>Annuler</Button>
          </div>
        </DialogContent>
      </Dialog>
    </Panel>
  );
}

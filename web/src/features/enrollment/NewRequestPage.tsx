import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { useSections } from "@/features/structure/useSections";
import { todayIso } from "@/lib/age";
import { errorMessage } from "@/lib/api";
import { enrollmentApi, enrollmentKeys } from "./api";
import { CriteriaFields, RequestFormSections, toBody, useRequestForm } from "./RequestForm";
import { NoTariffNotice } from "./NoTariffNotice";

/** Saisie d'une demande reçue au bureau (appel, visite, courrier). */
export function NewRequestPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const sections = useSections();
  const criteria = useQuery({ queryKey: enrollmentKeys.criteria, queryFn: ({ signal }) => enrollmentApi.criteria(signal) });
  const form = useRequestForm();
  const [error, setError] = useState<string | null>(null);

  const onSubmit = form.handleSubmit(async (values) => {
    setError(null);
    try {
      const { id } = await enrollmentApi.create(toBody(values));
      await queryClient.invalidateQueries({ queryKey: ["enrollment-board"] });
      navigate(`/inscriptions/${id}`);
    } catch (e) {
      setError(errorMessage(e));
    }
  });

  return (
    <>
      <Link to="/inscriptions" className="inline-flex items-center gap-1.5 text-label text-lagon-700">
        <ArrowLeft aria-hidden className="size-4" />
        Retour aux demandes
      </Link>
      <h1 className="m-0 font-display text-display">Nouvelle demande</h1>
      <NoTariffNotice />
      <form onSubmit={onSubmit} noValidate className="flex max-w-4xl flex-col gap-4">
        <RequestFormSections form={form} sections={sections.data ?? []} />
        {criteria.data && <CriteriaFields form={form} criteria={criteria.data.filter((c) => !c.validTo || c.validTo >= todayIso())} />}
        {error && <Alert>{error}</Alert>}
        <div className="flex gap-3">
          <Button type="submit" disabled={form.formState.isSubmitting}>
            {form.formState.isSubmitting ? "Enregistrement…" : "Enregistrer la demande"}
          </Button>
          <Button type="button" variant="ghost" onClick={() => navigate("/inscriptions")}>Annuler</Button>
        </div>
      </form>
    </>
  );
}

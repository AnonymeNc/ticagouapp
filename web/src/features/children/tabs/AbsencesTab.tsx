import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Trash2 } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { AbsenceDialog, absencePeriod } from "@/features/billing/AbsenceDialog";
import { billingApi, billingKeys, type Absence } from "@/features/billing/api";
import { TonePill } from "@/features/enrollment/components";
import { errorMessage } from "@/lib/api";
import { isoToDateFr } from "@/lib/datetime";
import type { ChildTabProps } from "../ChildRecordPage";
import { Panel } from "./common";

/** Fiche enfant › Présences : absences signalées, leur motif et leur justificatif. */
export function AbsencesTab({ child }: ChildTabProps) {
  const user = useCurrentUser();
  const canEdit = user.role === "DIRECTION" || user.role === "EDUCATRICE";
  const queryClient = useQueryClient();
  const absences = useQuery({ queryKey: billingKeys.absences(child.id), queryFn: ({ signal }) => billingApi.absences(child.id, signal) });
  const [editing, setEditing] = useState<Absence | "new" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ["billing"] });

  return (
    <Panel
      title="Absences"
      actions={canEdit && <Button variant="outline" onClick={() => setEditing("new")}><Plus aria-hidden />Signaler une absence</Button>}
    >
      {error && <Alert>{error}</Alert>}
      {absences.isError && <Alert>{errorMessage(absences.error)}</Alert>}
      {absences.data?.length === 0 && <p className="m-0 text-muted-foreground">Aucune absence signalée.</p>}
      {absences.data && absences.data.length > 0 && (
        <table className="w-full border-collapse text-left">
          <thead>
            <tr className="border-b border-border-subtle text-label text-muted-foreground">
              <th scope="col" className="py-2 pr-3">Période</th>
              <th scope="col" className="py-2 pr-3">Motif</th>
              <th scope="col" className="py-2 pr-3">Prévenue le</th>
              <th scope="col" className="py-2 pr-3">Justificatif</th>
              <th scope="col" className="py-2"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {absences.data.map((a) => (
              <tr key={a.id} className="border-b border-border-subtle last:border-0">
                <td className="py-2 pr-3">{absencePeriod(a)}</td>
                <td className="py-2 pr-3">
                  {a.reason}
                  <div className="mt-1"><TonePill tone={a.deductible ? "success" : "neutral"}>{a.deductible ? "Déduite" : "Non déduite"}</TonePill></div>
                </td>
                <td className="py-2 pr-3">{isoToDateFr(a.notifiedOn)}</td>
                <td className="py-2 pr-3">
                  {a.documentName ?? (a.proofRequired ? <TonePill tone="warning">À joindre</TonePill> : "—")}
                </td>
                <td className="py-2 text-right whitespace-nowrap">
                  {a.invoiced ? <span className="text-small text-muted-foreground">Mois facturé</span> : canEdit && (
                    <>
                      <Button variant="ghost" size="sm" onClick={() => setEditing(a)}>Modifier</Button>
                      <Button variant="ghost" size="sm" className="text-danger-700" aria-label={`Supprimer l’absence ${absencePeriod(a)}`}
                        onClick={async () => {
                          if (!window.confirm(`Supprimer l’absence ${absencePeriod(a)} ?`)) return;
                          setError(null);
                          try {
                            await billingApi.deleteAbsence(a.id);
                            await refresh();
                          } catch (e) {
                            setError(errorMessage(e));
                          }
                        }}>
                        <Trash2 aria-hidden />
                      </Button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {editing && (
        <AbsenceDialog
          childId={child.id}
          absence={editing === "new" ? undefined : editing}
          documents={child.documents.map((d) => ({ id: d.id, name: d.name }))}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); void refresh(); }}
        />
      )}
    </Panel>
  );
}

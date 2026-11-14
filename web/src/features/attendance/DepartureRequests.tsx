/**
 * Départs bloqués à valider à distance (maquettes B1 et B5) : la tablette a demandé l’accord de la direction pour une
 * personne absente de la liste. La direction autorise ou refuse, toujours avec un motif, tracé au journal. La santé de
 * l’enfant reste visible dans la feuille (allergies, PAI, régime).
 */
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSearchParams } from "react-router";
import { ChevronRight, TriangleAlert, UserX } from "lucide-react";
import { Alert } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { api, errorMessage } from "@/lib/api";
import { formatTime } from "@/lib/datetime";

export type DepartureRequest = {
  id: string;
  childId: string;
  childName: string;
  sectionName: string | null;
  allergies: string[];
  pai: boolean;
  diet: string | null;
  personName: string;
  relationship: string;
  idChecked: boolean;
  requestedBy: string;
  deviceName: string | null;
  requestedAt: string;
  status: "PENDING" | "APPROVED" | "REFUSED" | "CANCELLED";
};

const KEY = ["departure-requests"] as const;

/** Demandes en attente ; relues toutes les 15 secondes (et à l’ouverture d’une notification). */
export function useDepartureRequests() {
  const { role } = useCurrentUser();
  return useQuery({
    queryKey: KEY,
    queryFn: ({ signal }) => api<DepartureRequest[]>("/attendance/departure-requests", { signal }),
    enabled: role === "DIRECTION",
    refetchInterval: 15_000,
  });
}

/** Bandeau rouge en tête du tableau de bord et des présences, une ligne par demande. */
export function DepartureRequestsBanner() {
  const requests = useDepartureRequests();
  const [params, setParams] = useSearchParams();
  const openId = params.get("depart");
  const open = requests.data?.find((r) => r.id === openId) ?? null;
  if (!requests.data?.length && !open) return null;
  const select = (id: string | null) => {
    const next = new URLSearchParams(params);
    if (id) next.set("depart", id);
    else next.delete("depart");
    setParams(next, { replace: true });
  };
  return (
    <>
      <div className="flex flex-col gap-2">
        {(requests.data ?? []).map((r) => (
          <button
            key={r.id}
            type="button"
            onClick={() => select(r.id)}
            className="flex items-start gap-3 rounded-lg bg-danger-100 px-4 py-3 text-left text-danger-700 hover:bg-danger-100/80"
          >
            <UserX aria-hidden className="mt-0.5 size-5 shrink-0" />
            <span className="min-w-0 flex-1">
              <strong className="block">Départ à valider</strong>
              {r.childName} — personne absente de la liste, {r.deviceName ? `tablette ${r.deviceName}` : "à l’accueil"} depuis {formatTime(new Date(r.requestedAt))}
            </span>
            <ChevronRight aria-hidden className="mt-0.5 size-4 shrink-0" />
          </button>
        ))}
      </div>
      <DecisionSheet request={open} onClose={() => select(null)} />
    </>
  );
}

function DecisionSheet({ request, onClose }: { request: DepartureRequest | null; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [reason, setReason] = useState("");
  const decide = useMutation({
    mutationFn: (approve: boolean) =>
      api<DepartureRequest>(`/attendance/departure-requests/${request!.id}/decision`, { method: "POST", body: { approve, reason: reason.trim() } }),
    onSuccess: () => {
      setReason("");
      void queryClient.invalidateQueries({ queryKey: KEY });
      void queryClient.invalidateQueries({ queryKey: ["attendance"] });
      onClose();
    },
  });
  const health = request ? [...request.allergies, ...(request.pai ? ["PAI"] : []), ...(request.diet ? [request.diet] : [])] : [];
  return (
    <Dialog open={request !== null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        {request && (
          <>
            <DialogHeader>
              <DialogTitle>Départ bloqué</DialogTitle>
              <DialogDescription>
                {request.deviceName ? `Tablette ${request.deviceName}` : "Tablette"} · {formatTime(new Date(request.requestedAt))} · {request.requestedBy}
              </DialogDescription>
            </DialogHeader>
            <div className="flex flex-wrap items-center gap-2">
              <strong>{request.childName}</strong>
              {request.sectionName && <span className="text-small text-muted-foreground">{request.sectionName}</span>}
              {health.map((h) => (
                <span key={h} className="inline-flex items-center gap-1 rounded-md bg-danger-100 px-2 py-0.5 text-caption text-danger-700">
                  <TriangleAlert aria-hidden className="size-3" />
                  {h}
                </span>
              ))}
            </div>
            <div className="rounded-md bg-muted p-3 text-small">
              <p className="m-0"><strong>Personne présente</strong> : {request.personName}, {request.relationship}</p>
              <p className="m-0"><strong>Pièce d’identité</strong> : {request.idChecked ? "vue par l’équipe" : "non vue"}</p>
              <p className="m-0"><strong>N’est pas</strong> dans les personnes autorisées.</p>
            </div>
            <label className="flex flex-col gap-1.5">
              <span className="text-label">Motif de votre décision (obligatoire)</span>
              <Textarea value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Ex. : le père a confirmé par téléphone à 09:40" />
            </label>
            {decide.isError && <Alert>{errorMessage(decide.error)}</Alert>}
            <div className="grid grid-cols-2 gap-2">
              <Button variant="outline" className="h-12 text-danger-700" disabled={!reason.trim() || decide.isPending} onClick={() => decide.mutate(false)}>
                Refuser le départ
              </Button>
              <Button className="h-12" disabled={!reason.trim() || decide.isPending} onClick={() => decide.mutate(true)}>
                Autoriser
              </Button>
            </div>
            <p className="m-0 text-small text-muted-foreground">Décision tracée à votre nom dans le journal ; la tablette la reçoit aussitôt.</p>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

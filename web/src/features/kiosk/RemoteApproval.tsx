/**
 * Directrice absente de l’accueil : la tablette demande son accord à distance (maquette B5, ADR 0026). La direction est
 * prévenue sur son téléphone et décide avec un motif ; la tablette suit la réponse. Autorisé : le départ est enregistré
 * par le serveur, au nom de la directrice. Il faut une connexion : hors ligne, seul le code PIN sur place reste possible.
 */
import { useEffect, useState } from "react";
import { BellRing, CircleCheck, CircleX } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import { TouchButton } from "./components";
import { useKiosk } from "./KioskContext";

type RequestStatus = { id: string; status: "PENDING" | "APPROVED" | "REFUSED" | "CANCELLED"; decidedBy: string | null; reason: string | null };

const POLL_MS = 3_000;

export function RemoteApproval({
  attendanceId,
  childFirstName,
  personName,
  relationship,
  idChecked,
  onApproved,
}: {
  attendanceId: string;
  childFirstName: string;
  personName: string;
  relationship: string;
  idChecked: boolean;
  onApproved: () => void;
}) {
  const { online, session, refresh } = useKiosk();
  const [request, setRequest] = useState<RequestStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!request || request.status !== "PENDING") return;
    const timer = setInterval(async () => {
      try {
        const next = await api<RequestStatus>(`/kiosk/departure-requests/${request.id}`, { auth: "device" });
        setRequest(next);
        if (next.status === "APPROVED") {
          refresh();
          onApproved();
        }
      } catch {
        // Coupure passagère : on réessaie au tour suivant.
      }
    }, POLL_MS);
    return () => clearInterval(timer);
  }, [request, refresh, onApproved]);

  const ask = async () => {
    if (!personName.trim() || !relationship.trim()) {
      setError("Indiquez d’abord qui se présente et son lien avec l’enfant.");
      return;
    }
    setError(null);
    try {
      setRequest(await api<RequestStatus>("/kiosk/departure-requests", {
        method: "POST",
        auth: "device",
        body: { attendanceId, personName: personName.trim(), relationship: relationship.trim(), idChecked, requestedBy: session?.displayName ?? "Accueil" },
      }));
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const cancel = async () => {
    if (request) await api(`/kiosk/departure-requests/${request.id}`, { method: "DELETE", auth: "device" }).catch(() => {});
    setRequest(null);
  };

  if (!online) {
    return <p className="m-0 text-touch-body text-muted-foreground">Hors connexion : la validation à distance n’est pas possible, seul le code sur place l’est.</p>;
  }
  if (request?.status === "PENDING") {
    return (
      <div role="status" className="flex w-full flex-col gap-3 rounded-lg bg-info-100 p-4 text-info-700">
        <p className="m-0 flex items-center gap-2 text-touch-label"><BellRing aria-hidden className="size-6" />Demande envoyée à la direction</p>
        <p className="m-0 text-touch-body">Gardez {childFirstName} avec vous en attendant la réponse.</p>
        <TouchButton variant="secondary" onClick={() => void cancel()}>Annuler la demande</TouchButton>
      </div>
    );
  }
  if (request?.status === "REFUSED") {
    return (
      <div role="alert" className="flex w-full flex-col gap-2 rounded-lg bg-danger-100 p-4 text-danger-700">
        <p className="m-0 flex items-center gap-2 text-touch-label"><CircleX aria-hidden className="size-6" />Départ refusé par {request.decidedBy}</p>
        {request.reason && <p className="m-0 text-touch-body">« {request.reason} »</p>}
        <p className="m-0 text-touch-body">{childFirstName} reste à la crèche.</p>
      </div>
    );
  }
  if (request?.status === "APPROVED") {
    return (
      <p role="status" className="m-0 flex items-center gap-2 text-touch-label text-success-700">
        <CircleCheck aria-hidden className="size-6" />Départ autorisé par {request.decidedBy}
      </p>
    );
  }
  return (
    <div className="flex w-full flex-col gap-2">
      <TouchButton variant="secondary" onClick={() => void ask()}>
        <BellRing aria-hidden className="size-6" />
        Directrice absente ? Demander son accord à distance
      </TouchButton>
      {error && <p role="alert" className="m-0 text-touch-body text-danger-700">{error}</p>}
    </div>
  );
}

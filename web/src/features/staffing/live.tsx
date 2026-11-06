import { createContext, useContext, useState, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CircleCheck, TriangleAlert, X } from "lucide-react";
import { api } from "@/lib/api";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { attendanceKeys } from "@/features/attendance/api";
import type { RatioStatus } from "@/features/kiosk/ratio";
import { useStaffingStream } from "./stream";

export type Load = {
  children: number;
  adults: number;
  required: number;
  coveredPlaces: number;
  status: RatioStatus;
  missingRule: boolean;
};

export type StaffingSnapshot = {
  at: string;
  day: string;
  global: Load;
  sections: {
    sectionId: string;
    name: string;
    color: string;
    load: Load;
    staff: { userId: string; displayName: string; initials: string }[];
  }[];
  rules: string[];
  alertDesktop: boolean;
};

export type StaffingAlert = {
  sectionId: string | null;
  sectionName: string;
  started: boolean;
  children: number;
  adults: number;
  required: number;
  at: string;
};

type Live = { snapshot: StaffingSnapshot | null; connected: boolean };

const LiveContext = createContext<Live>({ snapshot: null, connected: false });
const SNAPSHOT_KEY = ["staffing"] as const;

/**
 * Encadrement en temps réel au poste bureau : un seul flux SSE pour la session, partagé par le tableau de bord ;
 * il rafraîchit aussi les présences et prévient la direction d'un dépassement (si l'option est activée).
 */
export function StaffingLiveProvider({ children }: { children: ReactNode }) {
  const user = useCurrentUser();
  const queryClient = useQueryClient();
  const [connected, setConnected] = useState(false);
  const [alerts, setAlerts] = useState<StaffingAlert[]>([]);
  const snapshot = useQuery({
    queryKey: SNAPSHOT_KEY,
    queryFn: ({ signal }) => api<StaffingSnapshot>("/staffing", { signal }),
    refetchInterval: connected ? false : 60_000,
  });

  useStaffingStream({
    auth: true,
    enabled: true,
    onStatus: setConnected,
    onEvent: (event) => {
      if (event.name === "ratio") queryClient.setQueryData(SNAPSHOT_KEY, event.data as StaffingSnapshot);
      if (event.name === "attendance") {
        void queryClient.invalidateQueries({ queryKey: attendanceKeys.all });
        // Les repas du jour sont comptés d'après les présences.
        void queryClient.invalidateQueries({ queryKey: ["meals"] });
      }
      if (event.name === "alert") {
        const alert = event.data as StaffingAlert;
        setAlerts((current) => [...current.filter((a) => a.sectionId !== alert.sectionId), alert].slice(-4));
      }
    },
  });

  const showAlerts = user.role === "DIRECTION" && (snapshot.data?.alertDesktop ?? true);

  return (
    <LiveContext.Provider value={{ snapshot: snapshot.data ?? null, connected }}>
      {children}
      {showAlerts && alerts.length > 0 && (
        <div className="fixed right-6 bottom-6 z-50 flex w-[380px] flex-col gap-2">
          {alerts.map((alert) => (
            <div
              key={`${alert.sectionId}-${alert.at}`}
              role="alert"
              className={`flex items-start gap-3 rounded-lg border p-4 shadow-3 ${alert.started ? "border-warning-700 bg-warning-100 text-warning-700" : "border-success-600 bg-success-100 text-success-700"}`}
            >
              {alert.started ? <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0" /> : <CircleCheck aria-hidden className="mt-0.5 size-5 shrink-0" />}
              <p className="m-0 grow text-body text-foreground">
                <strong>{alert.started ? "Encadrement insuffisant" : "Encadrement rétabli"}</strong> —{" "}
                {alert.sectionId ? `section ${alert.sectionName}` : "toute la structure"} : {alert.children} enfant
                {alert.children > 1 ? "s" : ""} pour {alert.adults} adulte{alert.adults > 1 ? "s" : ""}, {alert.required} requis.
              </p>
              <button
                type="button"
                aria-label="Fermer l’alerte"
                onClick={() => setAlerts((current) => current.filter((a) => a !== alert))}
                className="text-muted-foreground"
              >
                <X aria-hidden className="size-4" />
              </button>
            </div>
          ))}
        </div>
      )}
    </LiveContext.Provider>
  );
}

export function useStaffingLive() {
  return useContext(LiveContext);
}

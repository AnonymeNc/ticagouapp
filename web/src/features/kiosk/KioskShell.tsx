import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { Outlet } from "react-router";
import { RefreshCw, TriangleAlert, Users, X } from "lucide-react";
import { useSetupStatus } from "@/features/auth/useSetupStatus";
import { systemApi, systemKeys } from "@/features/settings/systemApi";
import { ServerStatusChip } from "@/features/health/ServerStatusChip";
import { useNow } from "@/hooks/useNow";
import { formatLongDate, formatTime } from "@/lib/datetime";
import { cn } from "@/lib/utils";
import { KioskProvider, useKiosk } from "./KioskContext";
import { PairingPage } from "./PairingPage";
import { PinLogin } from "./PinLogin";
import { adultsLabel, statusLabels } from "./ratio";
import { ratioView } from "./view";

/**
 * Coquille de la tablette d'accueil (/kiosk) : plein écran, cibles tactiles ≥ 56 px, bandeau hors connexion,
 * encadrement en direct, session par PIN. Maquette : docs/design/Pointage tablette accueil.html.
 */
export function KioskShell() {
  useEffect(() => {
    // PWA : manifeste propre au kiosque ; service worker seulement en contexte sécurisé (HTTPS ou localhost).
    const link = document.createElement("link");
    link.rel = "manifest";
    link.href = "/manifest.webmanifest";
    document.head.appendChild(link);
    if (import.meta.env.PROD && window.isSecureContext && location.protocol.startsWith("http") && "serviceWorker" in navigator) {
      navigator.serviceWorker.register("/sw.js", { scope: "/kiosk" }).catch(() => {});
    }
    return () => link.remove();
  }, []);

  return (
    <KioskProvider>
      <KioskFrame />
    </KioskProvider>
  );
}

function KioskFrame() {
  const now = useNow();
  const status = useSetupStatus();
  const { device, session, online, outbox, today, logout, retry, dismiss } = useKiosk();
  const time = formatTime(now);
  const ratio = today ? ratioView(today).global : null;
  const pending = outbox.pending.length;
  // Licence en lecture seule (ADR 0011) : les pointages sont refusés, la liste des personnes autorisées reste consultable.
  const license = useQuery({
    queryKey: [...systemKeys.licenseStatus, "device"],
    queryFn: ({ signal }) => systemApi.licenseStatus(signal, "device"),
    enabled: !!device && online,
    staleTime: 15 * 60_000,
    refetchInterval: 60 * 60_000,
  });
  const readOnly = license.data?.state === "LECTURE_SEULE" || license.data?.state === "EVALUATION_TERMINEE";

  return (
    <div className="flex h-screen flex-col overflow-hidden bg-background text-foreground">
      <header className="flex h-touch-lg flex-none items-center gap-5 border-b border-border-subtle bg-card px-6">
        <div className="flex flex-col gap-0.5">
          <span className="text-touch-label">{today?.structureName ?? device?.structureName ?? status.data?.structureName ?? "Ti’Cagou"}</span>
          <span className="text-base leading-5 text-muted-foreground">{formatLongDate(now)}</span>
        </div>
        <time dateTime={time} className="text-numeric-lg tabular-nums">
          {time}
        </time>
        <div className="grow" />
        <ServerStatusChip size="touch" />
        {ratio && device ? (
          <div
            role="status"
            aria-label={`${statusLabels[ratio.status]}, ${adultsLabel(ratio)}`}
            className={cn(
              "flex h-12 items-center gap-2.5 rounded-full px-4",
              ratio.status === "INSUFFISANT"
                ? "border border-warning-700 bg-warning-500 font-semibold text-ink"
                : ratio.status === "LIMITE"
                  ? "bg-warning-100 text-warning-700"
                  : "bg-success-100 text-success-700",
            )}
          >
            {ratio.status === "INSUFFISANT" ? <TriangleAlert aria-hidden className="size-5" /> : <Users aria-hidden className="size-5" />}
            <span className="flex flex-col leading-5">
              <span className="text-base font-semibold">{statusLabels[ratio.status]}</span>
              <span className="text-small">{adultsLabel(ratio)}</span>
            </span>
          </div>
        ) : (
          <div role="status" className="flex h-12 items-center gap-2.5 rounded-full bg-muted px-4 text-muted-foreground">
            <Users aria-hidden className="size-5" />
            <span className="text-[17px] font-semibold">Encadrement</span>
            <span className="text-base">en attente des pointages</span>
          </div>
        )}
        {session && (
          <button
            type="button"
            onClick={logout}
            className="flex h-touch items-center gap-2.5 rounded-full border border-input bg-card py-0 pr-4 pl-2 text-left"
          >
            <span aria-hidden className="flex size-10 items-center justify-center rounded-full bg-lagon-100 text-base font-bold text-lagon-700">
              {session.displayName.split(" ").map((p) => p.charAt(0)).join("").slice(0, 2).toUpperCase()}
            </span>
            <span className="flex flex-col leading-5">
              <span className="text-base font-semibold">{session.displayName}</span>
              <span className="text-base text-muted-foreground">Changer d’utilisateur</span>
            </span>
          </button>
        )}
      </header>

      {!online && (
        <div role="alert" className="flex min-h-16 flex-none items-center gap-3 bg-warning-500 px-6 py-2 text-ink">
          <TriangleAlert aria-hidden className="size-6 shrink-0" />
          <span className="grow text-touch-label font-normal">
            <strong className="font-semibold">Hors connexion.</strong> Internet ne répond pas. Les
            pointages restent sur cette tablette et partent dès son retour.
            {outbox.lastSyncAt && <> Dernière synchronisation à {formatTime(outbox.lastSyncAt)}.</>}
            {pending > 0 && <> {pending} pointage{pending > 1 ? "s" : ""} en attente d’envoi.</>}
          </span>
          {device && (
            <button
              type="button"
              onClick={retry}
              className="flex h-touch items-center gap-2 rounded-md border border-ink/30 bg-card px-4 text-touch-label"
            >
              <RefreshCw aria-hidden className="size-5" />
              Relancer
            </button>
          )}
        </div>
      )}

      {readOnly && (
        <div role="alert" className="flex min-h-16 flex-none items-center gap-3 bg-danger-700 px-6 py-2 text-white">
          <TriangleAlert aria-hidden className="size-6 shrink-0" />
          <span className="grow text-touch-label font-normal">
            <strong className="font-semibold">Abonnement échu : Ti’Cagou est en lecture seule.</strong> Notez les arrivées et
            les départs sur papier. Touchez un enfant présent pour vérifier les personnes autorisées à le chercher.
          </span>
        </div>
      )}

      {outbox.rejected.map(({ op, result }) => (
        <div key={op.clientId} role="alert" className="flex flex-none items-center gap-3 bg-danger-100 px-6 py-2 text-danger-700">
          <TriangleAlert aria-hidden className="size-6 shrink-0" />
          <span className="grow text-touch-label font-normal">
            <strong className="font-semibold">Pointage refusé par le serveur.</strong> {result.message}
          </span>
          <button type="button" aria-label="Fermer le message" onClick={() => dismiss(op.clientId)} className="flex size-touch items-center justify-center">
            <X aria-hidden className="size-6" />
          </button>
        </div>
      ))}

      <main className="min-h-0 flex-1 overflow-y-auto px-6 pb-6">
        {!device ? <PairingPage /> : session ? <Outlet /> : <PinLogin />}
      </main>
    </div>
  );
}

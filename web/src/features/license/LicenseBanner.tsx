import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { systemApi, systemKeys, type LicenseStatus } from "@/features/settings/systemApi";
import { cn } from "@/lib/utils";

/** Bandeau visible de tous (ADR 0011) : information un mois avant, orange puis rouge pendant la grâce, rouge en lecture seule. */
export function bannerTone(status: LicenseStatus): "info" | "warning" | "danger" | null {
  switch (status.state) {
    case "ACTIVE":
      return null;
    case "EVALUATION":
      return status.daysLeft <= 7 ? "warning" : "info";
    case "A_RENOUVELER":
      return status.daysLeft <= 7 ? "warning" : "info";
    case "EXPIREE_GRACE":
      return status.daysLeft <= 10 ? "danger" : "warning";
    default:
      return "danger";
  }
}

const tones = {
  info: "border-info-700/20 bg-info-100 text-info-700",
  warning: "border-warning-700/20 bg-warning-100 text-warning-700",
  danger: "border-danger-700/20 bg-danger-100 text-danger-700 font-semibold",
};

export function LicenseBanner() {
  const user = useCurrentUser();
  const status = useQuery({
    queryKey: systemKeys.licenseStatus,
    queryFn: ({ signal }) => systemApi.licenseStatus(signal),
    staleTime: 15 * 60_000,
    refetchInterval: 60 * 60_000,
  });
  if (!status.data?.message) return null;
  const tone = bannerTone(status.data);
  if (!tone) return null;
  return (
    <div role={tone === "danger" ? "alert" : "status"} className={cn("flex flex-wrap items-center gap-3 border-b px-6 py-2", tones[tone])}>
      <span>{status.data.message}</span>
      {user.role === "DIRECTION" && (
        <Link to="/parametres?onglet=appareils" className="underline underline-offset-2">Voir l’abonnement</Link>
      )}
    </div>
  );
}

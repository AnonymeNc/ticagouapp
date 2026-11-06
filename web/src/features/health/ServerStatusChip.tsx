import { CircleCheck, CircleDashed, TriangleAlert, WifiOff } from "lucide-react";
import { cn } from "@/lib/utils";
import { useServerHealth, type ServerState } from "./useServerHealth";

const variants: Record<ServerState, { label: string; className: string; icon: typeof CircleCheck }> = {
  connected: { label: "Connecté", className: "bg-success-100 text-success-700", icon: CircleCheck },
  degraded: { label: "Base de données indisponible", className: "bg-warning-100 text-warning-700", icon: TriangleAlert },
  offline: { label: "Hors connexion", className: "bg-warning-100 text-warning-700", icon: WifiOff },
  checking: { label: "Connexion au serveur…", className: "bg-muted text-muted-foreground", icon: CircleDashed },
};

type Props = {
  /** « touch » : taille tablette (kiosque). */
  size?: "default" | "touch";
  className?: string;
};

export function ServerStatusChip({ size = "default", className }: Props) {
  const state = useServerHealth();
  const { label, className: tone, icon: Icon } = variants[state];
  return (
    <span
      role="status"
      data-state={state}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full font-semibold",
        size === "touch" ? "h-10 gap-2 px-3.5 text-base" : "px-2.5 py-1 text-small",
        tone,
        className,
      )}
    >
      <Icon aria-hidden className={size === "touch" ? "size-5" : "size-4"} />
      {label}
    </span>
  );
}

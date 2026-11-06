import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";

/** Modules optionnels de l'abonnement ; la base (enfants, présences, encadrement) est toujours comprise (ADR 0015). */
export type LicenseModule = "INSCRIPTIONS" | "FACTURATION" | "COMPTABILITE" | "PERSONNEL" | "REPAS" | "PORTAIL_PARENTS";

export const moduleLabels: Record<LicenseModule, string> = {
  INSCRIPTIONS: "Inscriptions et contrats",
  FACTURATION: "Facturation et statistiques",
  COMPTABILITE: "Comptabilité",
  PERSONNEL: "Personnel",
  REPAS: "Repas",
  PORTAIL_PARENTS: "Portail parents",
};

/**
 * Modules compris dans l'abonnement. Tant que la réponse n'est pas là (ou si le serveur ne la donne pas), tout est
 * considéré comme compris : on ne masque rien par erreur.
 */
export function useModules(auth: true | "device" = true) {
  const query = useQuery({
    queryKey: ["license", "modules", auth],
    queryFn: ({ signal }) => api<{ modules: LicenseModule[] }>("/system/modules", { signal, auth }),
    staleTime: 5 * 60_000,
    retry: false,
  });
  const modules = query.data?.modules;
  return { has: (module: LicenseModule) => !modules || modules.includes(module) };
}

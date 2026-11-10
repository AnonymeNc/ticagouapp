import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";

/**
 * Rubriques que l'éditeur peut masquer à la demande de la crèche (ADR 0022) : menus, onglets et cartes à ne pas afficher.
 * Tout est compris dans la formule (ADR 0021) : c'est un simple affichage, rien n'est refusé.
 */
export type Rubrique = "INSCRIPTIONS" | "FACTURATION" | "COMPTABILITE" | "PERSONNEL" | "REPAS" | "PORTAIL_PARENTS";

type Rubriques = { hidden: Rubrique[]; labels: string[] };

/** Tant que la réponse n'est pas là (ou en cas d'erreur), tout est affiché : on ne masque rien par erreur. */
export function useRubriques(auth: true | "device" = true) {
  const query = useQuery({
    queryKey: ["rubriques", auth],
    queryFn: ({ signal }) => api<Rubriques>("/rubriques", { signal, auth }),
    staleTime: 5 * 60_000,
    retry: false,
  });
  const hidden = query.data?.hidden ?? [];
  return {
    shown: (rubrique: Rubrique | undefined) => !rubrique || !hidden.includes(rubrique),
    labels: query.data?.labels ?? [],
  };
}

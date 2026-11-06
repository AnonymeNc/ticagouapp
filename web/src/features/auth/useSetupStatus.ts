import { useQuery } from "@tanstack/react-query";
import { getSetupStatus } from "@/lib/api";

export const setupStatusKey = ["setup-status"] as const;

/** Le serveur est-il installé ? (public, sert aussi à afficher le nom de la structure sur la tablette) */
export function useSetupStatus() {
  return useQuery({
    queryKey: setupStatusKey,
    queryFn: ({ signal }) => getSetupStatus(signal),
    retry: false,
    staleTime: 60_000,
  });
}

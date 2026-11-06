import { useQuery } from "@tanstack/react-query";
import { getHealth } from "@/lib/api";

export type ServerState = "connected" | "degraded" | "offline" | "checking";

const POLL_MS = 15_000;

/** Interroge régulièrement le service pour la pastille de connexion. */
export function useServerHealth(): ServerState {
  const query = useQuery({
    queryKey: ["health"],
    queryFn: ({ signal }) => getHealth(signal),
    refetchInterval: POLL_MS,
    retry: false,
  });

  if (query.isPending) return "checking";
  if (query.isError) return "offline";
  return query.data.status === "UP" ? "connected" : "degraded";
}

import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { TriangleAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useCurrentUser } from "@/features/auth/AuthProvider";
import { settingsApi } from "@/features/settings/api";
import { todayIso } from "@/lib/age";

/**
 * Rappel à la direction : sans barème des tarifs en vigueur (ou à venir), aucun contrat ne peut être établi, ni donc
 * aucune inscription aboutir. Rien n'est affiché tant que la liste n'est pas chargée.
 */
export function NoTariffNotice() {
  const user = useCurrentUser();
  const isDirection = user.role === "DIRECTION";
  const tariffs = useQuery({
    queryKey: ["tariff-schedules"],
    queryFn: ({ signal }) => settingsApi.tariffs(signal),
    enabled: isDirection,
  });
  const today = todayIso();
  const usable = tariffs.data?.some((t) => !t.validTo || t.validTo >= today);
  if (!isDirection || !tariffs.data || usable) return null;
  return (
    <div role="status" className="flex flex-wrap items-center gap-3 rounded-lg border border-warning-500 bg-warning-100 px-4 py-3 text-warning-700">
      <TriangleAlert aria-hidden className="size-5 shrink-0" />
      <p className="m-0 grow">
        <strong className="font-semibold">Barème des tarifs à saisir.</strong>{" "}
        {tariffs.data.length === 0 ? "Aucun barème n’est saisi" : "Aucun barème ne s’applique aujourd’hui ni après"} : les demandes
        s’enregistrent, mais aucun contrat d’accueil ne pourra être établi.
      </p>
      <Button asChild variant="outline" size="sm">
        <Link to="/parametres?onglet=bareme">Saisir le barème</Link>
      </Button>
    </div>
  );
}

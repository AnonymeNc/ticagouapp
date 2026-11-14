import { Monitor } from "lucide-react";

/**
 * Écran utilisable partout mais pensé pour un grand écran (import, assistant d’installation, sauvegardes) : sur
 * téléphone et tablette, un bandeau le rappelle. Rien n’est interdit (plan des applications mobiles, phase 3).
 */
export function DesktopRecommended({ what }: { what: string }) {
  return (
    <p role="note" className="m-0 flex items-start gap-2 rounded-md bg-info-100 px-3 py-2.5 text-small text-info-700 lg:hidden print:hidden">
      <Monitor aria-hidden className="mt-0.5 size-4 shrink-0" />
      <span>
        <strong className="font-semibold">Recommandé sur ordinateur.</strong> {what} reste possible ici, mais un grand
        écran est plus confortable.
      </span>
    </p>
  );
}

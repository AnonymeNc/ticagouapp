import { useQuery } from "@tanstack/react-query";
import { Link, Navigate } from "react-router";
import { Smartphone } from "lucide-react";
import { FocusLayout } from "@/components/FocusLayout";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { isNativeApp } from "@/lib/native/platform";
import type { Compat } from "@/lib/native/annuaire";

/**
 * « https://<crèche>/app » : adresse du QR « Ouvrir sur mon téléphone » (ADR 0025). Lu par l’appareil photo d’un
 * téléphone sans l’application, il mène ici : installer Ti’Cagou Direction, ou continuer dans le navigateur.
 */
export function PhoneLandingPage() {
  const compat = useQuery({
    queryKey: ["app-compat", "instance"],
    queryFn: ({ signal }) => api<Compat>("/app/compat", { signal, auth: false }),
    retry: false,
  });
  if (isNativeApp()) return <Navigate to="/" replace />;
  return (
    <FocusLayout>
      <div className="flex flex-col items-center gap-4 text-center">
        <span className="grid size-20 place-items-center rounded-3xl bg-lagon-100 text-lagon-700"><Smartphone aria-hidden className="size-10" /></span>
        <h1 className="m-0 font-display text-title-1">Ti’Cagou dans votre poche</h1>
        <p className="m-0 text-muted-foreground">
          {compat.data?.creche ? <><strong>{compat.data.creche}</strong> sur </> : "Votre crèche sur "}
          votre téléphone : installez l’application <strong>Ti’Cagou Direction</strong> depuis l’App Store ou Google Play,
          puis touchez « Scanner le QR de la crèche » ou tapez le code ci-dessous.
        </p>
        {compat.data?.code && (
          <p className="m-0">
            Code de la crèche :{" "}
            <strong className="font-display text-title-2 tracking-[0.15em]">{compat.data.code}</strong>
          </p>
        )}
        <Button asChild variant="outline" className="w-full">
          <Link to="/">Continuer dans le navigateur</Link>
        </Button>
        <p className="m-0 text-small text-muted-foreground">Vous êtes parent ? Votre application est Ti’Cagou Parents.</p>
      </div>
    </FocusLayout>
  );
}

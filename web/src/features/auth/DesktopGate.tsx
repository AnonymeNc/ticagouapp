import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router";
import { FocusLayout } from "@/components/FocusLayout";
import { AppLock } from "@/features/creche/AppLock";
import { useAuth } from "./AuthProvider";
import { MfaEnrollPage } from "./MfaEnrollPage";
import { useSetupStatus } from "./useSetupStatus";

export function Splash() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-background" aria-busy="true">
      <span className="font-display text-title-1 text-lagon-700">Ti’Cagou</span>
    </div>
  );
}

/**
 * Accès au bureau : service joignable → crèche créée (sinon assistant) → connecté (sinon connexion) → double
 * authentification enregistrée.
 */
export function DesktopGate({ children }: { children: ReactNode }) {
  const status = useSetupStatus();
  const { state } = useAuth();
  const location = useLocation();

  if (status.isPending) return <Splash />;
  if (status.isError) {
    return (
      <FocusLayout>
        <h1 className="m-0 font-display text-title-1">Ti’Cagou ne répond pas</h1>
        <p className="mt-2 mb-0 text-muted-foreground">
          Vérifiez la connexion internet de ce poste, puis rechargez la page. Les tablettes continuent de pointer hors
          connexion.
        </p>
      </FocusLayout>
    );
  }
  if (!status.data.initialized) return <Navigate to="/setup" replace />;
  if (state.status === "loading") return <Splash />;
  if (state.status === "anonymous") return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  if (state.status === "authenticated" && state.user.mfaEnrollmentRequired) return <MfaEnrollPage />;
  return <AppLock>{children}</AppLock>;
}

import { useEffect } from "react";
import { Outlet, useLocation } from "react-router";
import { LicenseBanner } from "@/features/license/LicenseBanner";
import { StaffingLiveProvider } from "@/features/staffing/live";
import { Sidebar } from "./Sidebar";
import { Topbar } from "./Topbar";
import { CONTENT_SCROLL_ID, scrollContentToTop } from "./scroll";

/** Coquille du poste bureau : barre latérale + barre supérieure + contenu défilant, encadrement en temps réel. */
export function AppShell() {
  const { pathname } = useLocation();
  // La zone qui défile reste la même d'une page à l'autre : sans cela, la nouvelle page s'ouvrait au milieu.
  useEffect(() => scrollContentToTop(), [pathname]);
  return (
    <StaffingLiveProvider>
    <div className="flex h-screen overflow-hidden bg-background text-foreground print:block print:h-auto print:overflow-visible print:bg-white">
      <div className="contents print:hidden">
        <Sidebar />
      </div>
      <div className="flex min-w-0 flex-1 flex-col print:block">
        <div className="print:hidden">
          <Topbar />
          <LicenseBanner />
        </div>
        {/* « relative » : les éléments en position absolue du contenu (légendes « sr-only » des tableaux) se placent dans
            la zone qui défile ; sans lui, ils débordent de la fenêtre et la page entière gagne une seconde barre de défilement. */}
        <div id={CONTENT_SCROLL_ID} className="relative min-h-0 flex-1 overflow-y-auto print:static print:overflow-visible">
          <main className="mx-auto flex w-full max-w-[1360px] flex-col gap-8 p-6 print:max-w-none print:gap-4 print:p-0">
            <Outlet />
          </main>
        </div>
      </div>
    </div>
    </StaffingLiveProvider>
  );
}

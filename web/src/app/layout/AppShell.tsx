import { Outlet } from "react-router";
import { LicenseBanner } from "@/features/license/LicenseBanner";
import { StaffingLiveProvider } from "@/features/staffing/live";
import { Sidebar } from "./Sidebar";
import { Topbar } from "./Topbar";

/** Coquille du poste bureau : barre latérale + barre supérieure + contenu défilant, encadrement en temps réel. */
export function AppShell() {
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
        <div className="min-h-0 flex-1 overflow-y-auto print:overflow-visible">
          <main className="mx-auto flex w-full max-w-[1360px] flex-col gap-8 p-6 print:max-w-none print:gap-4 print:p-0">
            <Outlet />
          </main>
        </div>
      </div>
    </div>
    </StaffingLiveProvider>
  );
}

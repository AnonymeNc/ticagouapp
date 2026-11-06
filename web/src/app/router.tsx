import { Outlet, type RouteObject } from "react-router";
import { AttendancePage } from "@/features/attendance/AttendancePage";
import { BillingMonthPage } from "@/features/billing/BillingMonthPage";
import { CollectionsPage } from "@/features/billing/CollectionsPage";
import { FunderRequestsPage } from "@/features/billing/FunderRequestsPage";
import { InvoicePage } from "@/features/billing/InvoicePage";
import { AuthProvider } from "@/features/auth/AuthProvider";
import { DesktopGate } from "@/features/auth/DesktopGate";
import { ChildRecordPage } from "@/features/children/ChildRecordPage";
import { ChildrenListPage } from "@/features/children/ChildrenListPage";
import { NewChildPage } from "@/features/children/NewChildPage";
import { ContractEditPage, ContractForChildPage, ContractFromRequestPage } from "@/features/contracts/ContractWizardPage";
import { DashboardPage } from "@/features/dashboard/DashboardPage";
import { CapacityPage } from "@/features/enrollment/CapacityPage";
import { EnrollmentBoardPage } from "@/features/enrollment/EnrollmentBoardPage";
import { EnrollmentRequestPage } from "@/features/enrollment/EnrollmentRequestPage";
import { NewRequestPage } from "@/features/enrollment/NewRequestPage";
import { LoginPage } from "@/features/auth/LoginPage";
import { KitchenPage } from "@/features/meals/KitchenPage";
import { MenusPage } from "@/features/meals/MenusPage";
import { QuarterPage } from "@/features/meals/QuarterPage";
import { KioskHomePage } from "@/features/kiosk/KioskHomePage";
import { KioskShell } from "@/features/kiosk/KioskShell";
import { SettingsPage } from "@/features/settings/SettingsPage";
import { MessagesPage } from "@/features/portal/MessagesPage";
import { PlanningPage } from "@/features/staff/PlanningPage";
import { StaffRecordPage } from "@/features/staff/StaffRecordPage";
import { TeamPage } from "@/features/staff/TeamPage";
import { TimesheetsPage } from "@/features/staff/TimesheetsPage";
import { SetupWizard } from "@/features/setup/SetupWizard";
import { AppShell } from "./layout/AppShell";
import { PlaceholderPage } from "./layout/PlaceholderPage";
import { navItems } from "./navigation";
import { AccountingExportPage } from "@/features/accounting/AccountingExportPage";
import { ReportingPage } from "@/features/reporting/ReportingPage";

export const routes: RouteObject[] = [
  {
    // Tablette appairée : session par PIN, jamais conservée ; file d'attente hors connexion (ADR 0005)
    path: "/kiosk",
    element: <KioskShell />,
    children: [{ index: true, element: <KioskHomePage /> }],
  },
  {
    // Bureau, dans le navigateur : session conservée par un cookie HttpOnly
    element: (
      <AuthProvider persist>
        <Outlet />
      </AuthProvider>
    ),
    children: [
      { path: "/setup", element: <SetupWizard /> },
      { path: "/login", element: <LoginPage /> },
      {
        path: "/",
        element: (
          <DesktopGate>
            <AppShell />
          </DesktopGate>
        ),
        children: [
          { path: "enfants", element: <ChildrenListPage /> },
          { path: "enfants/nouveau", element: <NewChildPage /> },
          { path: "enfants/:id", element: <ChildRecordPage /> },
          { path: "enfants/:id/contrat/nouveau", element: <ContractForChildPage /> },
          { path: "inscriptions", element: <EnrollmentBoardPage /> },
          { path: "inscriptions/capacite", element: <CapacityPage /> },
          { path: "inscriptions/nouvelle", element: <NewRequestPage /> },
          { path: "inscriptions/:id", element: <EnrollmentRequestPage /> },
          { path: "inscriptions/:requestId/contrat", element: <ContractFromRequestPage /> },
          { path: "contrats/:contractId/modifier", element: <ContractEditPage /> },
          { path: "parametres", element: <SettingsPage /> },
          { index: true, element: <DashboardPage /> },
          { path: "presences", element: <AttendancePage /> },
          { path: "repas", element: <KitchenPage /> },
          { path: "repas/menus", element: <MenusPage /> },
          { path: "repas/recapitulatif", element: <QuarterPage /> },
          { path: "facturation", element: <BillingMonthPage /> },
          { path: "facturation/factures/:id", element: <InvoicePage /> },
          { path: "facturation/financeurs", element: <FunderRequestsPage /> },
          { path: "facturation/encaissements", element: <CollectionsPage /> },
          { path: "facturation/comptabilite", element: <AccountingExportPage /> },
          { path: "statistiques", element: <ReportingPage /> },
          { path: "personnel", element: <TeamPage /> },
          { path: "personnel/planning", element: <PlanningPage /> },
          { path: "personnel/heures", element: <TimesheetsPage /> },
          { path: "personnel/:id", element: <StaffRecordPage /> },
          { path: "messages", element: <MessagesPage /> },
          ...navItems
            .filter(({ path }) => !["/", "/presences", "/enfants", "/inscriptions", "/repas", "/facturation", "/personnel", "/parametres", "/messages", "/statistiques"].includes(path))
            .map(({ path, label }) => ({ path: path.slice(1), element: <PlaceholderPage title={label} /> })),
        ],
      },
    ],
  },
];

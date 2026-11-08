import { Navigate, Route, Routes, useLocation, useSearchParams } from "react-router";
import { AppShell } from "@/app/AppShell";
import { PlannedPage } from "@/app/PlannedPage";
import { navItems } from "@/app/navigation";
import { SettingsPage } from "@/features/audit/SettingsPage";
import { AuthProvider, type AuthState } from "@/features/auth/AuthProvider";
import { LoginPage } from "@/features/auth/LoginPage";
import { SetupPage } from "@/features/auth/SetupPage";
import { DashboardPage } from "@/features/dashboard/DashboardPage";

/** Console de l'éditeur : connexion (mot de passe + code), puis la coquille et ses écrans. */
export default function App() {
  return <AuthProvider>{(state) => <Gate state={state} />}</AuthProvider>;
}

function Gate({ state }: { state: AuthState }) {
  const location = useLocation();
  const [params] = useSearchParams();
  if (!state.operator) {
    if (state.setupRequired && location.pathname === "/premier-compte") {
      return <SetupPage />;
    }
    return <LoginPage key={state.login.step} notice={params.get("cree") ? "Compte créé. Connectez-vous pour enregistrer la double authentification." : undefined} />;
  }
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<DashboardPage />} />
        <Route path="parametres" element={<SettingsPage />} />
        {navItems
          .filter((item) => item.path !== "/" && item.path !== "/parametres")
          .map((item) => (
            <Route key={item.path} path={item.path.slice(1)} element={<PlannedPage item={item} />} />
          ))}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}

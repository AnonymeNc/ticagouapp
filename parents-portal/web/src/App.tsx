import { useState } from "react";
import { QueryClient, QueryClientProvider, useQueryClient } from "@tanstack/react-query";
import { Navigate, Outlet, RouterProvider, createBrowserRouter, type RouteObject } from "react-router";
import { errorMessage, portal } from "./api";
import { Banner, BottomNav } from "./components";
import { AbsencePage } from "./screens/AbsencePage";
import { ActualitesPage } from "./screens/ActualitesPage";
import { AlbumPage } from "./screens/AlbumPage";
import { DocumentsPage } from "./screens/DocumentsPage";
import { DemandesPage } from "./screens/DemandesPage";
import { FacturesPage } from "./screens/FacturesPage";
import { JourneePage } from "./screens/JourneePage";
import { LoginPage } from "./screens/LoginPage";
import { MessagesPage } from "./screens/MessagesPage";
import { NotificationsPage } from "./screens/NotificationsPage";
import { disablePush } from "./push";
import { SignaturePage } from "./screens/SignaturePage";
import { SessionProvider, isUnauthenticated, useMe } from "./session";

/** Coquille connectée : session du parent, contenu de la rubrique, barre de navigation du bas. */
function Shell() {
  const me = useMe();
  const queryClient = useQueryClient();
  if (me.isPending) return <p className="p-6 text-ink-muted">Chargement…</p>;
  if (isUnauthenticated(me.error)) return <Navigate to="/connexion" replace />;
  if (me.isError) return <div className="p-4"><Banner tone="danger">{errorMessage(me.error)}</Banner></div>;
  const data = me.data!;
  if (data.families.length === 0) {
    return <div className="p-4"><Banner>Votre accès a été retiré par la crèche. Rapprochez-vous de la direction.</Banner></div>;
  }
  const unread = data.families.reduce((n, f) => n + f.unreadMessages, 0);
  const toSign = data.families.reduce((n, f) => n + f.documentsToSign, 0);
  return (
    <SessionProvider me={data}>
      <div className="mx-auto flex min-h-dvh max-w-[480px] flex-col bg-bg">
        <main className="flex grow flex-col">
          <Outlet />
          <button type="button" className="mx-auto mb-4 min-h-11 text-[15px] text-ink-muted underline"
            onClick={async () => {
              // Un téléphone partagé ne reçoit plus rien après la déconnexion.
              await disablePush();
              await portal.logout().catch(() => {});
              queryClient.removeQueries();
              window.location.assign("/connexion");
            }}>
            Se déconnecter ({data.email})
          </button>
        </main>
        <BottomNav unread={unread} toSign={toSign} />
      </div>
    </SessionProvider>
  );
}

export const routes: RouteObject[] = [
  { path: "/connexion", element: <LoginPage /> },
  {
    element: <Shell />,
    children: [
      { index: true, element: <JourneePage /> },
      { path: "actualites", element: <ActualitesPage /> },
      { path: "album", element: <AlbumPage /> },
      { path: "messages", element: <MessagesPage /> },
      { path: "notifications", element: <NotificationsPage /> },
      { path: "absence", element: <AbsencePage /> },
      { path: "demandes", element: <DemandesPage /> },
      { path: "factures", element: <FacturesPage /> },
      { path: "documents", element: <DocumentsPage /> },
      { path: "documents/:structureId/:documentId", element: <SignaturePage /> },
      { path: "*", element: <Navigate to="/" replace /> },
    ],
  },
];

export default function App() {
  const [queryClient] = useState(() => new QueryClient({ defaultOptions: { queries: { retry: (n, e) => !isUnauthenticated(e) && n < 2 } } }));
  const [router] = useState(() => createBrowserRouter(routes));
  return (
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  );
}


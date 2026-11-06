import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryRouter, RouterProvider } from "react-router";
import { routes } from "@/app/router";
import type { Me, SessionResponse } from "@/features/auth/types";

/** Rend l'application complète à une URL donnée, sans cache de requêtes partagé. */
export function renderAt(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const result = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { ...result, router };
}

type Reply = { status?: number; body?: unknown } | ((body: unknown) => { status?: number; body?: unknown });

/**
 * Serveur simulé : clés « MÉTHODE /chemin » relatives à /api/v1 (sans paramètres de requête).
 * Une route absente répond 404 ; `calls` garde la trace des requêtes reçues.
 */
export function mockApi(routes: Record<string, Reply>) {
  const calls: { key: string; body: unknown; headers: Record<string, string> }[] = [];
  const spy = vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = new URL(String(input), "http://localhost");
    const key = `${init?.method ?? "GET"} ${url.pathname.replace(/^\/api\/v1/, "")}`;
    const body = init?.body instanceof FormData ? init.body : init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ key, body, headers: (init?.headers ?? {}) as Record<string, string> });
    const route = routes[key];
    const reply = typeof route === "function" ? route(body) : route;
    if (!reply) return new Response(JSON.stringify({ code: "not_found", detail: key }), { status: 404 });
    const status = reply.status ?? 200;
    return new Response(status === 204 ? null : JSON.stringify(reply.body ?? {}), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  });
  return { spy, calls };
}

export const director: Me = {
  id: "u-1",
  structureId: "s-1",
  firstName: "Nathalie",
  lastName: "Kaloï",
  role: "DIRECTION",
  authMethod: "PASSWORD",
  lastLoginAt: null,
  mfaEnabled: false,
  mfaEnrollmentRequired: false,
};

export function session(user: Me = director, refreshToken = "refresh-2"): SessionResponse {
  return { accessToken: `access-${refreshToken}`, expiresIn: 900, refreshToken, user };
}

export const healthUp = {
  status: "UP",
  version: "dev",
  serverTime: "2026-10-30T09:15:00+11:00",
  zone: "Pacific/Noumea",
  database: "UP",
};

export const installed = { initialized: true, structureName: "Les P’tits Niaoulis" };

export const structure = {
  id: "s-1",
  name: "Les P’tits Niaoulis",
  city: "Dumbéa",
  ridet: null,
  agrementNumber: null,
  agrementDate: null,
  capacity: 48,
  invoiceFooter: null,
  hasLogo: false,
  openingHours: [],
  version: 0,
};

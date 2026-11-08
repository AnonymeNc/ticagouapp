import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import App from "@/App";

export type Call = { path: string; method: string; headers: Record<string, string>; body: unknown };
export type Handler = (call: Call) => { status?: number; body?: unknown };

/** Faux serveur de la console : `"GET /parc"` → réponse ; les appels sont gardés pour les vérifier. */
export function mockServer(routes: Record<string, Handler>) {
  const calls: Call[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    const path = url.replace(/^.*\/api\/v1/, "");
    const call: Call = {
      path,
      method: init?.method ?? "GET",
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    };
    calls.push(call);
    const handler = routes[`${call.method} ${path}`] ?? routes[`${call.method} ${path.split("?")[0]}`];
    const { status = 200, body } = handler ? handler(call) : { status: 404, body: { code: "not_found", detail: "Inconnu" } };
    return new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  });
  return calls;
}

export const operator = { id: "1", login: "jordan", displayName: "Jordan Moniot" };

export const connected = () => ({ body: { setupRequired: false, login: { step: "CONNECTE", secret: null, uri: null }, operator } });

export function renderApp(path = "/") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

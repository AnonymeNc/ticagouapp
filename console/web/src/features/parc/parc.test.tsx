import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { buildTodos } from "@/features/dashboard/DashboardPage";
import { connected, mockServer, renderApp } from "@/test/mockServer";
import type { Detail, Parc, Row } from "./api";
import { ago, backupPill, subscriptionPill } from "./labels";
import { slugify } from "./ParcPage";

// Jour à Nouméa (comme la console), décalé de {@code offset} jours.
const day = (offset: number) => new Intl.DateTimeFormat("en-CA", { timeZone: "Pacific/Noumea" }).format(new Date(Date.now() + offset * 86_400_000));

function row(overrides: Partial<Row>): Row {
  return {
    slug: "x",
    name: "X",
    city: null,
    clientName: null,
    lifecycle: "CLIENT",
    health: { level: "OK", reasons: [] },
    lastContactAt: new Date().toISOString(),
    version: "1.5.0",
    plan: "Gestion",
    modules: ["INSCRIPTIONS", "FACTURATION", "PORTAIL_PARENTS"],
    places: 20,
    activeChildren: 18,
    licenseState: "ACTIVE",
    validUntil: day(200),
    deadline: null,
    desktops: 1,
    desktopLimit: 1,
    tablets: 2,
    tabletLimit: 2,
    backupAt: new Date().toISOString(),
    backupStale: false,
    diskUsedPercent: 40,
    container: "healthy",
    alerts: [],
    lastCallAt: null,
    balanceDue: 0,
    daysLate: 0,
    ...overrides,
  };
}

const lagon = row({
  slug: "lagon-bleu",
  name: "Lagon Bleu",
  city: "Mont-Dore",
  health: { level: "BAD", reasons: ["Sauvegarde"] },
  plan: "Gestion",
  modules: ["INSCRIPTIONS", "FACTURATION", "PORTAIL_PARENTS", "REPAS"],
  licenseState: "EXPIREE_GRACE",
  validUntil: day(-9),
  deadline: day(21),
  backupAt: new Date(Date.now() - 52 * 3_600_000).toISOString(),
  backupStale: true,
  alerts: [{ id: 7, kind: "SAUVEGARDE", label: "Sauvegarde", detail: "Dernière sauvegarde réussie le 07/11/2026 à 03:41.", openedAt: new Date().toISOString(), closedAt: null }],
});
const paita = row({ slug: "paita", name: "Crèche municipale de Païta", health: { level: "WARN", reasons: ["PC au-delà du contrat (3/2)"] }, desktops: 3, desktopLimit: 2 });
const kids = row({ slug: "kids-club", name: "Kids Club Bourail", licenseState: "EVALUATION", plan: "Évaluation", deadline: day(5), validUntil: null });
const tiare = row({ slug: "tiare", name: "Crèche Tiare" });

const parc: Parc = {
  creches: [lagon, paita, kids, tiare],
  summary: { total: 4, ok: 2, warn: 1, bad: 1, off: 0, evaluationsEnding: 1, renewals: 1, versionsBehind: 0, latestVersion: "1.5.0", unpaid: 0 },
  sources: { supervisionAt: new Date().toISOString(), supervisionAttemptAt: new Date().toISOString(), supervisionError: null, hostAt: new Date().toISOString(), hostError: null, unlinkedSites: ["Ancienne crèche"], rejectedSites: [], hostDiskFree: null, hostDiskTotal: null },
};

function detail(overrides: Partial<Detail> = {}): Detail {
  return {
    row: lagon,
    domain: "lagon-bleu.ticagou.nc",
    clientSince: "2025-11-01",
    createdAt: "2025-11-01T00:00:00Z",
    supervisionName: "Micro-crèche Lagon Bleu",
    hostSeenAt: new Date().toISOString(),
    client: { id: "c1", name: "lagon-bleu", kind: "PRIVE", ridet: null, address: null, directorName: null, directorEmail: null, directorPhone: null, billingEmail: null, reminderEmail: null, paymentDays: 30 },
    status: { version: "1.5.0", database: "UP", usage: { activeChildren: 12, places: 12, attendances7d: 118, activeUsers30d: 5, lastDirectionLoginOn: "2026-11-08", familyInvoicesLastMonth: 12 } },
    subscription: { number: "A-12", plan: "Gestion — jusqu’à 12 places", places: 12, desktops: 1, tablets: 2, validUntil: lagon.validUntil!, modules: "INSCRIPTIONS,FACTURATION,PORTAIL_PARENTS,REPAS" },
    history: lagon.alerts,
    points: [],
    notes: [],
    journal: [],
    ...overrides,
  };
}

describe("parc des crèches", () => {
  beforeEach(() => {
    document.cookie = "XSRF-TOKEN=jeton-csrf";
  });

  it("une ligne par crèche, filtres comptés, pastilles de la maquette", async () => {
    mockServer({ "GET /auth/state": connected, "GET /parc": () => ({ body: parc }) });
    const user = userEvent.setup();
    renderApp("/parc");

    const table = await screen.findByRole("table");
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((r) => within(r).getByRole("link").textContent)).toEqual(["Lagon Bleu", "Crèche municipale de Païta", "Crèche Tiare", "Kids Club Bourail"]);
    expect(rows[0]).toHaveTextContent("Gestion+ Repas");
    expect(rows[0]).toHaveTextContent("Grâce");
    expect(rows[0]).toHaveTextContent("Retard 52 h");
    expect(within(rows[0]).getByRole("img", { name: "En alerte" })).toBeInTheDocument();
    expect(rows[1]).toHaveTextContent("3/2");
    expect(screen.getByRole("status")).toHaveTextContent("Ancienne crèche");

    await user.click(screen.getByRole("button", { name: "En alerte · 1" }));
    expect(within(screen.getByRole("table")).getAllByRole("row")).toHaveLength(2);
    await user.click(screen.getByRole("button", { name: "Évaluation · 1" }));
    expect(within(screen.getByRole("table")).getByRole("link")).toHaveTextContent("Kids Club Bourail");
    await user.click(screen.getByRole("button", { name: "À renouveler · 1" }));
    expect(within(screen.getByRole("table")).getByRole("link")).toHaveTextContent("Lagon Bleu");
  });

  it("nouvelle crèche : un prospect, identifiant proposé depuis le nom", async () => {
    const calls = mockServer({
      "GET /auth/state": connected,
      "GET /parc": () => ({ body: parc }),
      "POST /parc": (call) => ({ status: 201, body: detail({ row: row({ slug: (call.body as { slug: string }).slug, name: "Kids Club Bourail", lifecycle: "PROSPECT", health: { level: "OFF", reasons: ["Prospect : pas encore d’instance"] } }) }) }),
      "GET /parc/kids-club-bourail": () => ({ body: detail({ row: row({ slug: "kids-club-bourail", name: "Kids Club Bourail", lifecycle: "PROSPECT" }) }) }),
    });
    const user = userEvent.setup();
    renderApp("/parc");
    await user.click(await screen.findByRole("button", { name: "Nouvelle crèche" }));
    await user.type(screen.getByLabelText("Nom"), "Kids Club Bourail");
    expect(screen.getByLabelText("Identifiant")).toHaveValue("kids-club-bourail");
    await user.type(screen.getByLabelText("Commune"), "Bourail");
    await user.click(screen.getByRole("button", { name: "Enregistrer le prospect" }));
    expect(await screen.findByRole("heading", { name: /Kids Club Bourail/ })).toBeInTheDocument();
    expect(calls.find((c) => c.method === "POST")!.body).toEqual({ slug: "kids-club-bourail", name: "Kids Club Bourail", city: "Bourail" });
  });

  it("fiche : santé, contrat, fiche client enregistrée et appel noté", async () => {
    let current = detail();
    const calls = mockServer({
      "GET /auth/state": connected,
      "GET /parc": () => ({ body: parc }),
      "GET /parc/lagon-bleu": () => ({ body: current }),
      "PUT /parc/lagon-bleu": (call) => {
        const form = call.body as { name: string; client: Detail["client"] };
        current = detail({ client: { ...form.client, id: "c1" }, row: { ...lagon, name: form.name } });
        return { body: current };
      },
      "POST /parc/lagon-bleu/notes": (call) => {
        const note = call.body as { kind: "APPEL"; text: string };
        current = { ...current, notes: [{ id: 1, at: new Date().toISOString(), operatorLogin: "jordan", minutes: null, ...note }] };
        return { status: 201, body: current };
      },
    });
    const user = userEvent.setup();
    renderApp("/parc/lagon-bleu");

    expect(await screen.findByRole("heading", { name: /Lagon Bleu/ })).toBeInTheDocument();
    expect(screen.getByText("non prévue (un seul hébergeur)")).toBeInTheDocument();
    expect(screen.getAllByText("118").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "Suspendre…" })).toBeEnabled();

    await user.click(screen.getByRole("tab", { name: "Abonnement" }));
    expect(screen.getByText("Inscriptions, Facturation, Portail parents, Repas")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Contacts et notes" }));
    await user.clear(screen.getByLabelText("Raison sociale"));
    await user.type(screen.getByLabelText("Raison sociale"), "SARL Lagon Bleu");
    await user.selectOptions(screen.getByLabelText("Type"), "COLLECTIVITE");
    expect(screen.getByLabelText("Délai de paiement (jours)")).toHaveValue(45);
    await user.type(screen.getByLabelText("E-mail de facturation"), "compta@lagon-bleu.nc");
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));
    expect(await screen.findByText("Fiche enregistrée.")).toBeInTheDocument();
    const put = calls.find((c) => c.method === "PUT")!;
    expect(put.headers["X-XSRF-TOKEN"]).toBe("jeton-csrf");
    expect(put.body).toMatchObject({
      name: "Lagon Bleu",
      lifecycle: "CLIENT",
      client: { name: "SARL Lagon Bleu", kind: "COLLECTIVITE", paymentDays: 45, billingEmail: "compta@lagon-bleu.nc", ridet: null },
    });

    await user.click(screen.getByRole("button", { name: "Noter un appel" }));
    expect(screen.getByRole("radio", { name: "Appel" })).toBeChecked();
    await user.type(screen.getByLabelText("Texte"), "Relance du renouvellement");
    await user.click(screen.getByRole("button", { name: "Noter l’appel" }));
    expect(await screen.findByText("Relance du renouvellement")).toBeInTheDocument();
    expect(calls.find((c) => c.path === "/parc/lagon-bleu/notes")!.body).toEqual({ kind: "APPEL", text: "Relance du renouvellement" });
  });

  it("la recherche ouvre la fiche de la crèche choisie", async () => {
    const calls = mockServer({
      "GET /auth/state": connected,
      "GET /parc": () => ({ body: parc }),
      "GET /recherche": () => ({ body: [{ slug: "lagon-bleu", name: "Lagon Bleu", city: "Mont-Dore", clientName: "SARL Lagon Bleu", level: "BAD" }] }),
      "GET /parc/lagon-bleu": () => ({ body: detail() }),
    });
    const user = userEvent.setup();
    renderApp("/");
    await user.type(await screen.findByRole("combobox", { name: "Rechercher une crèche" }), "lagon");
    expect(await screen.findByRole("option", { name: /Lagon Bleu/ })).toHaveTextContent("SARL Lagon Bleu");
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("heading", { name: /Lagon Bleu/ })).toBeInTheDocument();
    expect(calls.some((c) => c.path === "/recherche?q=lagon")).toBe(true);
  });

  it("tableau de bord : alertes, fins d'évaluation, renouvellements, puis points à surveiller", async () => {
    mockServer({ "GET /auth/state": connected, "GET /parc": () => ({ body: parc }) });
    renderApp("/");
    const list = await screen.findByRole("heading", { name: "À faire aujourd’hui" });
    const items = within(list.closest("section")!).getAllByRole("listitem");
    expect(items.map((i) => i.querySelector("b")!.textContent)).toEqual(["Lagon Bleu", "Kids Club Bourail", "Lagon Bleu", "Crèche municipale de Païta"]);
    expect(items[1]).toHaveTextContent("évaluation finie dans 5 jours");
    expect(screen.getByText("Supervision lue à", { exact: false })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("1 / 4")).toBeInTheDocument());
  });
});

describe("libellés du parc", () => {
  const now = new Date("2026-11-09T21:00:00Z");

  it("temps écoulé, abonnement, sauvegarde, identifiant", () => {
    expect(ago("2026-11-09T17:50:00Z", now)).toBe("il y a 3 h 10");
    expect(ago("2026-11-09T20:55:00Z", now)).toBe("il y a 5 min");
    expect(ago("2026-11-06T21:00:00Z", now)).toBe("il y a 3 j");
    expect(ago(null, now)).toBe("jamais");
    expect(subscriptionPill({ lifecycle: "CLIENT", licenseState: "EXPIREE_GRACE", validUntil: "2026-10-31", deadline: "2026-11-30" }))
      .toEqual({ tone: "warn", label: "Grâce", sub: "échu le 31/10/2026" });
    expect(subscriptionPill({ lifecycle: "PROSPECT", licenseState: null, validUntil: null, deadline: null }).label).toBe("Prospect");
    expect(backupPill({ backupAt: "2026-11-07T17:00:00Z", backupStale: true }, now)).toEqual({ tone: "bad", label: "Retard 52 h" });
    expect(slugify("Micro-crèche Lagon Bleu")).toBe("lagon-bleu");
    expect(slugify("Ô Ti’Loup")).toBe("o-ti-loup");
  });

  it("à faire : rien quand tout va bien", () => {
    expect(buildTodos({ ...parc, creches: [tiare] }, now)).toEqual([]);
  });
});

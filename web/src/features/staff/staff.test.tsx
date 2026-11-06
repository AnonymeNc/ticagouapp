import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { director, healthUp, installed, mockApi, renderAt, session, structure } from "@/test/render";
import type { Me } from "@/features/auth/types";
import { todayIso } from "@/lib/age";
import { addDays, isoWeekday } from "@/lib/format";
import { billableMonths } from "@/features/billing/components";
import type { Forecast, Member, MonthSheet, Team, WeekPlanning } from "./api";

const today = todayIso();
const monday = addDays(today, 1 - isoWeekday(today));
const lastMonth = billableMonths(1)[1];

const member = (over: Partial<Member>): Member => ({
  userId: "u-1", firstName: "Céline", lastName: "Hnawia", role: "EDUCATRICE", active: true, jobTitle: "Auxiliaire de puériculture",
  diploma: "DE d’auxiliaire de puériculture", homeSectionId: "sec-b", countsInRatio: true, contractType: "CDI", weeklyMinutes: 39 * 60,
  hiredOn: "2021-02-01", endsOn: null, alerts: [], ...over,
});

const team: Team = {
  members: [
    member({ alerts: [{ userId: "u-1", name: "Céline Hnawia", kind: "QUALIFICATION", state: "A_RENOUVELER", label: "Sauveteur secouriste du travail (SST)", date: "2026-11-14" }] }),
    member({ userId: "u-2", firstName: "Pascale", lastName: "Wenehoua", role: "CUISINE", jobTitle: "Cuisinière", diploma: "CAP Cuisine",
      homeSectionId: null, countsInRatio: false,
      alerts: [{ userId: "u-2", name: "Pascale Wenehoua", kind: "QUALIFICATION", state: "EXPIREE", label: "Hygiène alimentaire (HACCP)", date: "2026-09-15" }] }),
  ],
  people: 2, inRatio: 1, expired: 1, toRenew: 1, nextDue: "2026-11-14", plannedTrainings: 1, nextTraining: "Recyclage SST",
  nextTrainingOn: "2026-11-09", alertDays: 30,
};

const shift = (day: string, startsAt: string | null, endsAt: string | null) => ({
  day, startsAt, endsAt, breakMinutes: 30, sectionId: "sec-b", overridden: false, note: null, absence: null, absenceId: null, halfDay: false,
  plannedMinutes: startsAt ? 480 : 0,
});
const week: WeekPlanning = {
  monday, openDays: [1, 2, 3, 4, 5], validatedMonths: [], shortages: { [monday]: 4 },
  rows: [{
    userId: "u-1", name: "Céline Hnawia", jobTitle: "Auxiliaire de puériculture", homeSectionId: "sec-b", countsInRatio: true,
    weeklyMinutes: 39 * 60, plannedMinutes: 39 * 60,
    shifts: Array.from({ length: 7 }, (_, i) => shift(addDays(monday, i), i < 5 ? "06:30:00" : null, i < 5 ? "15:00:00" : null)),
  }],
};

const load = (children: number, adults: number, required: number, status: "CONFORME" | "INSUFFISANT") => ({ children, adults, required, status });
const forecast: Forecast = {
  day: monday, open: true, shortages: 1, variableChildren: 0,
  sections: [{ id: "sec-b", name: "Bébés", color: "#9e2f66" }],
  slots: [
    { startsAt: "07:00:00", endsAt: "07:30:00", sections: { "sec-b": load(0, 1, 0, "CONFORME") }, global: load(0, 1, 0, "CONFORME") },
    { startsAt: "07:30:00", endsAt: "08:00:00", sections: { "sec-b": load(6, 1, 2, "INSUFFISANT") }, global: load(6, 1, 2, "INSUFFISANT") },
  ],
};

const sheet = (validated: boolean, anomalies: number): MonthSheet => ({
  month: lastMonth, validated, validatedAt: validated ? new Date().toISOString() : null, validatedBy: validated ? "Nathalie Kaloï" : null,
  people: [{
    userId: "u-1", name: "Céline Hnawia", jobTitle: "Auxiliaire de puériculture", contractType: "CDI", weeklyMinutes: 39 * 60,
    plannedMinutes: 168 * 60, workedMinutes: 160 * 60, overtimeMinutes: 120, absenceMinutes: { CONGES: 480 }, anomalies,
    days: [{ day: `${lastMonth}-03`, plannedMinutes: 480, workedMinutes: 0, absence: null, absenceMinutes: 0, punches: [{ arrivedAt: "06:30:00", departedAt: null }], anomalies: anomalies ? ["DEPART_MANQUANT"] : [] }],
    weeks: [{ monday: `${lastMonth}-02`, workedMinutes: 41 * 60, trainingMinutes: 0, contractMinutes: 39 * 60, overtimeMinutes: 120 }],
  }],
});

function server(user: Me = director, extra: Record<string, Parameters<typeof mockApi>[0][string]> = {}) {
  window.localStorage.setItem("ticagou.refreshToken", "refresh-1");
  return mockApi({
    "GET /setup/status": { body: installed },
    "GET /health": { body: healthUp },
    "POST /auth/refresh": { body: session(user) },
    "GET /structure": { body: structure },
    "GET /sections": { body: [{ id: "sec-b", name: "Bébés", color: "#9e2f66", minAgeMonths: 0, maxAgeMonths: 18, capacity: 12, displayOrder: 0 }] },
    "GET /staff": { body: team },
    "GET /staff/planning": { body: week },
    "GET /staff/planning/me": { body: week },
    "GET /staff/forecast": { body: forecast },
    [`GET /staff/timesheets/${lastMonth}`]: { body: sheet(false, 1) },
    [`GET /staff/timesheets/me/${lastMonth}`]: { body: sheet(false, 0) },
    ...extra,
  });
}

describe("équipe", () => {
  it("présente les chiffres clés et les alertes de qualification", async () => {
    server();
    renderAt("/personnel");
    expect(await screen.findByText("2 personnes, dont 1 compte dans le taux d’encadrement.")).toBeInTheDocument();
    expect(screen.getByText("À renouveler sous 30 jours")).toBeInTheDocument();
    expect(screen.getByText("SST expire le 14/11/2026")).toBeInTheDocument();
    expect(screen.getByText("HACCP expiré le 15/09/2026")).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText("Avec une alerte"));
    await user.type(screen.getByLabelText("Nom"), "pascale");
    expect(screen.getByText("1 personne")).toBeInTheDocument();
  });

  it("renvoie l’éducatrice vers son planning", async () => {
    server({ ...director, role: "EDUCATRICE" });
    renderAt("/personnel");
    expect(await screen.findByRole("link", { name: "Mon planning" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Équipe" })).not.toBeInTheDocument();
    expect(await screen.findByText(/Votre planning de la semaine/)).toBeInTheDocument();
  });
});

describe("planning de la semaine", () => {
  it("signale les créneaux en sous-effectif et change le poste d’un jour", async () => {
    const { calls } = server(director, { [`PUT /staff/u-1/shifts/${monday}`]: { body: shift(monday, "06:30:00", "17:00:00") } });
    renderAt("/personnel/planning");
    const user = userEvent.setup();

    expect(await screen.findByText("4 créneaux")).toBeInTheDocument();
    const red = await screen.findByLabelText("Bébés, 07:30–08:00 : 6 enfants, 1 adulte pour 2 requis");
    expect(red.className).toContain("bg-danger-600");
    expect(screen.getByText("1 créneau en sous-effectif")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: new RegExp(`Modifier le poste de Céline Hnawia le ${monday.split("-").reverse().join("/")}`) }));
    const dialog = screen.getByRole("dialog");
    const end = within(dialog).getByLabelText("Fin");
    await user.clear(end);
    await user.type(end, "17:00");
    await user.type(within(dialog).getByLabelText("Note"), "Réunion de parents");
    await user.click(within(dialog).getByRole("button", { name: "Enregistrer" }));
    await waitFor(() => expect(calls.find((c) => c.key === `PUT /staff/u-1/shifts/${monday}`)?.body).toEqual({
      startsAt: "06:30", endsAt: "17:00", breakMinutes: 30, sectionId: "sec-b", note: "Réunion de parents",
    }));
  });
});

describe("feuilles d’heures", () => {
  it("bloque la validation tant qu’il reste des anomalies", async () => {
    server();
    renderAt("/personnel/heures");
    const user = userEvent.setup();
    expect(await screen.findByText("1 anomalie à corriger")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Valider le mois" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Exporter en CSV" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Céline Hnawia" }));
    expect(screen.getByText("départ non pointé")).toBeInTheDocument();
    expect(screen.getByText("+ 2 h 00")).toBeInTheDocument();
  });

  it("exporte un mois validé", async () => {
    server(director, { [`GET /staff/timesheets/${lastMonth}`]: { body: sheet(true, 0) } });
    renderAt("/personnel/heures");
    expect(await screen.findByText(/Validé le .* par Nathalie Kaloï/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Exporter en CSV" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Rouvrir le mois" })).toBeInTheDocument();
  });
});

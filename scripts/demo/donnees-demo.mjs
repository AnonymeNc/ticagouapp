#!/usr/bin/env node
/**
 * Jeu de démonstration de Ti'Cagou : remplit une installation VIERGE par l'API, comme le ferait la directrice.
 * Les mêmes règles que dans l'application s'appliquent (validation, facturation, numérotation, journal) ; rien
 * n'est écrit directement en base. Les dates sont relatives à aujourd'hui : l'historique couvre les 18 derniers mois.
 *
 *   node scripts/demo/donnees-demo.mjs [--url http://localhost:8080]
 *   node scripts/demo/donnees-demo.mjs --aujourdhui     (démonstration déjà chargée : pointages et transmissions du jour)
 *   node scripts/demo/donnees-demo.mjs --portail http://localhost:8090 --code XXXX     (relie aussi le portail parents)
 *   node scripts/demo/donnees-demo.mjs ... --mailpit http://localhost:8025     (en local : les parents agissent aussi)
 *   node scripts/demo/donnees-demo.mjs --url http://creche-demo:8080 --jeton XXXX     (instance hébergée : jeton du lien de démarrage)
 *
 * Le script refuse une installation qui a déjà des enfants. Il n'est pas livré aux crèches : il sert au PC de
 * démonstration (scripts\tester-en-local.ps1 -Demo) et aux essais. Parcours de démonstration :
 * docs/commercial/demonstrations.md.
 *
 * Contenu : « Les P'tits Niaoulis » (Dumbéa), 3 sections, 27 enfants accueillis et 2 partis, leurs familles,
 * personnes autorisées, allergies, un PAI, régimes ; équipe de 7 personnes avec codes PIN, plannings, diplômes,
 * pointages, demandes de congé, cahier d'équipe ; barème au volume horaire et formules (temps plein, matins,
 * après-midi, 3 jours) ; financeurs ; 18 mois de présences, d'absences et de factures payées (impayés récents, un
 * avoir) ; menus, trimestres de repas clos, relevés de températures ; demandes d'inscription, dont des
 * pré-inscriptions en ligne ; modèles de documents. Avec le portail : toutes les familles invitées, deux semaines de
 * journées détaillées, un album de photos et de vidéos sur 8 semaines, actualités avec affiche, messages à tous et à
 * chaque famille, autorisation à signer. Avec Mailpit : réponses de parents, absence signalée, demande d'accueil,
 * retard annoncé et autorisation signée, à traiter pendant la démonstration.
 */
import { createHash, randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const args = process.argv.slice(2);
const option = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : null);
const BASE = (option("--url") ?? "http://localhost:8080").replace(/\/$/, "");
const ZONE = "Pacific/Noumea";
const PORTAL_URL = option("--portail")?.replace(/\/$/, "") ?? null;
const PORTAL_CODE = option("--code");
const MAILPIT = option("--mailpit")?.replace(/\/$/, "") ?? null;
// Instance hébergée (creche.sh demo) : l'assistant de démarrage exige le jeton du lien de démarrage.
const SETUP_TOKEN = option("--jeton");
const MEDIAS = join(dirname(fileURLToPath(import.meta.url)), "medias");

// Comptes de la démonstration (docs/commercial/demonstrations.md, docs/TESTER-EN-LOCAL.md § 8).
const DIRECTOR = { login: "nathalie.kaloi", password: "Niaouli-2026!", pin: "2580" };
const TEAM = [
  { firstName: "Sandrine", lastName: "Wahnyamalla", role: "EDUCATRICE", pin: "4831", section: "Bébés", job: "Auxiliaire de puériculture" },
  { firstName: "Maëva", lastName: "Tiaou", role: "EDUCATRICE", pin: "2468", section: "Moyens", job: "Éducatrice de jeunes enfants" },
  { firstName: "Léa", lastName: "Kaouma", role: "EDUCATRICE", pin: "1357", section: "Grands", job: "Auxiliaire de puériculture" },
  { firstName: "Mereana", lastName: "Tehei", role: "EDUCATRICE", pin: "3691", section: "Moyens", job: "Animatrice petite enfance" },
  // Deuxième adulte chez les Bébés et chez les Grands : l'encadrement est conforme à l'ouverture de la démonstration.
  { firstName: "Noëlla", lastName: "Kasarhérou", role: "EDUCATRICE", pin: "5824", section: "Bébés", job: "Auxiliaire de puériculture" },
  { firstName: "Tiare", lastName: "Hmae", role: "EDUCATRICE", pin: "9173", section: "Grands", job: "Éducatrice de jeunes enfants" },
  { firstName: "Paulo", lastName: "Taufana", role: "CUISINE", pin: "7419", section: null, job: "Cuisinier" },
];

// ---------------------------------------------------------------------------------------------- dates (Nouméa)

/** Jour courant à Nouméa, en « AAAA-MM-JJ ». */
function todayIso() {
  return new Intl.DateTimeFormat("en-CA", { timeZone: ZONE, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
}
const TODAY = todayIso();
const day = (iso) => new Date(`${iso}T00:00:00Z`);
const iso = (d) => d.toISOString().slice(0, 10);
const addDays = (isoDay, n) => iso(new Date(day(isoDay).getTime() + n * 86_400_000));
const monthStart = (isoDay, offset = 0) => {
  const d = day(isoDay);
  return iso(new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + offset, 1)));
};
const yearMonth = (isoDay) => isoDay.slice(0, 7);
/** 1 = lundi … 7 = dimanche. */
const weekday = (isoDay) => ((day(isoDay).getUTCDay() + 6) % 7) + 1;
/** « 2026-11-03 », « 07:45 » → instant UTC (Nouméa = UTC+11, sans heure d'été). */
const at = (isoDay, time) => new Date(`${isoDay}T${time}:00+11:00`).toISOString();
const nowHm = () => new Intl.DateTimeFormat("fr-FR", { timeZone: ZONE, hour: "2-digit", minute: "2-digit" }).format(new Date());
const minutes = (hm) => Number(hm.slice(0, 2)) * 60 + Number(hm.slice(3, 5));
const hm = (m) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
const monday = (isoDay) => addDays(isoDay, 1 - weekday(isoDay));
const later = (a, b) => (a > b ? a : b);
const earlier = (a, b) => (a < b ? a : b);

/** Historique : 18 mois, soit deux années civiles dans les statistiques. */
const HISTORY_MONTHS = 18;
const HISTORY_START = monthStart(TODAY, -HISTORY_MONTHS);
/** Fermetures de fin d'année (du 20 décembre au 5 janvier) : personne ne vient. */
const closed = (d) => (d.slice(5) >= "12-20" || d.slice(5) <= "01-05");

/** Tirage reproductible : la même démonstration à chaque lancement. */
let seed = 20261103;
const random = () => ((seed = (seed * 1103515245 + 12345) % 2 ** 31) / 2 ** 31);
const jitter = (m, spread) => m + Math.round((random() - 0.5) * 2 * spread / 5) * 5;

// --------------------------------------------------------------------------------------------------- API

let token = null;

async function call(method, path, body, { auth = token, form = null, expect = [200, 201, 202, 204], base = `${BASE}/api/v1`, headers: extra = {} } = {}) {
  const headers = { Accept: "application/json", "X-Ticagou-Poste": "demo-script", "X-Ticagou-Poste-Nom": "Script%20de%20d%C3%A9monstration", ...extra };
  if (auth) headers.Authorization = `Bearer ${auth}`;
  if (SETUP_TOKEN && path === "/setup") headers["X-Ticagou-Setup"] = SETUP_TOKEN;
  if (body !== undefined && !form) headers["Content-Type"] = "application/json";
  const response = await fetch(`${base}${path}`, { method, headers, body: form ?? (body === undefined ? undefined : JSON.stringify(body)) });
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  if (!expect.includes(response.status)) {
    throw new Error(`${method} ${path} : ${response.status} ${data?.detail ?? text}${data?.errors ? " " + JSON.stringify(data.errors) : ""}`);
  }
  return data;
}
const get = (path, options) => call("GET", path, undefined, options);
const post = (path, body, options) => call("POST", path, body, options);
const put = (path, body, options) => call("PUT", path, body, options);

/** Envoie les tâches par paquets parallèles : 9 000 pointages en une minute plutôt qu'en dix. */
async function pool(items, size, task) {
  let next = 0;
  await Promise.all(Array.from({ length: size }, async () => {
    while (next < items.length) {
      const item = items[next++];
      await task(item);
    }
  }));
}

function step(message) {
  console.log(`==> ${message}`);
}

const media = (name) => readFileSync(join(MEDIAS, name));

// ------------------------------------------------------------------------------------------- installation

/** Démonstration déjà chargée : on retrouve les enfants du jeu et l'équipe pour rejouer la journée. */
async function reload() {
  token = (await post("/auth/login", { login: DIRECTOR.login, password: DIRECTOR.password }, { auth: null })).accessToken;
  const children = await get("/children");
  const plan = new Map(FAMILIES.flatMap(([lastName, , , list]) => list.map(([firstName, , section, days, arrives, leaves, mode]) =>
    [`${firstName} ${lastName}`, { section, days, arrives, leaves, mode, familyLabel: `Famille ${lastName}` }])));
  const kids = children.filter((c) => plan.has(`${c.firstName} ${c.lastName}`))
    .map((c) => ({ id: c.id, firstName: c.firstName, lastName: c.lastName, ...plan.get(`${c.firstName} ${c.lastName}`) }));
  const users = await get("/users");
  const staffIds = Object.fromEntries(users.map((u) => [u.firstName, u.id]));
  return { kids, staffIds };
}

async function install() {
  const status = await get("/setup/status", { auth: null });
  if (!status.initialized) {
    step("Installation de la crèche « Les P’tits Niaoulis »");
    const session = await post("/setup", {
      structure: { name: "Les P’tits Niaoulis", city: "Dumbéa", ridet: "1234567.001", agrementNumber: "AGR-2024-017", agrementDate: "2024-01-15", capacity: 48 },
      sections: [
        { name: "Bébés", minAgeMonths: 2, maxAgeMonths: 18, capacity: 12, color: "#9e2f66", displayOrder: 0 },
        { name: "Moyens", minAgeMonths: 18, maxAgeMonths: 30, capacity: 16, color: "#924510", displayOrder: 1 },
        { name: "Grands", minAgeMonths: 30, maxAgeMonths: null, capacity: 20, color: "#3d479b", displayOrder: 2 },
      ],
      admin: { firstName: "Nathalie", lastName: "Kaloï", login: DIRECTOR.login, password: DIRECTOR.password },
    }, { auth: null });
    token = session.accessToken;
  } else {
    token = (await post("/auth/login", { login: DIRECTOR.login, password: DIRECTOR.password }, { auth: null })).accessToken;
  }
  const children = await get("/children");
  const count = Array.isArray(children) ? children.length : (children.totalElements ?? children.items?.length ?? 0);
  if (count > 0) throw new Error("Cette installation a déjà des enfants : le jeu de démonstration se charge sur une base vierge.");
}

/** Fiche de la structure complète : adresse, contact, banque (RIB sur les factures). */
async function structureSheet() {
  const current = await get("/structure");
  await put("/structure", {
    identity: { name: current.name, city: current.city, ridet: current.ridet, agrementNumber: current.agrementNumber, agrementDate: current.agrementDate, capacity: current.capacity },
    invoiceFooter: "Merci de régler votre facture avant le 10 du mois. Les P’tits Niaoulis, crèche agréée par la province Sud.",
    openingHours: (current.openingHours ?? []).map((o) => ({ dayOfWeek: o.dayOfWeek, opensAt: o.opensAt, closesAt: o.closesAt })),
    contact: { address: "12 rue des Niaoulis, Dumbéa-sur-Mer", postalCode: "98835", phone: "41 22 33", email: "contact@ptits-niaoulis.nc" },
    bank: { holder: "Les P’tits Niaoulis", bankName: "BCI", iban: "NC8420041010050500013M02606", bic: "BCINNCNE", printRib: true },
  }).catch((e) => console.log(`    Fiche de la structure laissée telle quelle (${e.message})`));
}

// -------------------------------------------------------------------------------------------------- données

const FAMILIES = [
  // nom, ville, parent, enfants [prénom, âge en mois, section, jours, arrivée, départ, mode, formule de facturation]
  ["Wahéo", "Dumbéa", ["Sarah", "Mère"], [["Noa", 26, "Moyens", [1, 2, 3, 4, 5], "07:30", "16:30", "MENSUALISE", "Temps plein"]]],
  ["Gope", "Nouméa", ["Kevin", "Père"], [["Lila", 14, "Bébés", [1, 2, 4, 5], "07:45", "16:00", "MENSUALISE"]]],
  ["Poédi", "Dumbéa", ["Anne", "Mère"], [["Tom", 34, "Grands", [1, 2, 3, 4, 5], "07:15", "17:00", "MENSUALISE"], ["Léna", 9, "Bébés", [1, 2, 3, 4, 5], "07:15", "17:00", "MENSUALISE"]]],
  ["Kaloï", "Païta", ["Marie", "Mère"], [["Kaïna", 22, "Moyens", [1, 3, 5], "08:00", "16:30", "MENSUALISE", "3 jours par semaine"]]],
  ["Taufana", "Dumbéa", ["Lisa", "Mère"], [["Léo", 31, "Grands", [1, 2, 3, 4, 5], "07:30", "15:30", "MENSUALISE"]]],
  ["Wamytan", "Nouméa", ["Jean-Marc", "Père"], [["Léa", 19, "Moyens", [2, 3, 4], "08:15", "16:45", "REEL"]]],
  ["Hnawia", "Dumbéa", ["Céline", "Mère"], [["Malo", 38, "Grands", [1, 2, 3, 4, 5], "07:30", "16:30", "MENSUALISE", "Temps plein"]]],
  ["Tehei", "Mont-Dore", ["Vaimiti", "Mère"], [["Teva", 11, "Bébés", [1, 2, 3, 4, 5], "07:00", "15:45", "MENSUALISE"]]],
  ["Dupont", "Nouméa", ["Julie", "Mère"], [["Jules", 28, "Moyens", [1, 2, 4, 5], "08:00", "17:00", "MENSUALISE"]]],
  ["Wenehoua", "Dumbéa", ["Pascal", "Père"], [["Inaya", 33, "Grands", [1, 2, 3, 4, 5], "07:45", "16:15", "MENSUALISE"]]],
  ["Ounou", "Païta", ["Sylvie", "Mère"], [["Kalani", 16, "Bébés", [1, 2, 3], "07:30", "16:00", "REEL"]]],
  ["Martin", "Dumbéa", ["Claire", "Mère"], [["Gabin", 24, "Moyens", [1, 2, 3, 4, 5], "07:30", "16:30", "MENSUALISE"]]],
  ["Tiaou", "Nouméa", ["Hélène", "Mère"], [["Manon", 40, "Grands", [2, 3, 4, 5], "08:00", "16:00", "MENSUALISE"]]],
  ["Boearua", "Dumbéa", ["Rémi", "Père"], [["Hina", 7, "Bébés", [1, 2, 3, 4, 5], "07:30", "16:30", "MENSUALISE"]]],
  ["Lepeu", "Mont-Dore", ["Nadia", "Mère"], [["Raphaël", 29, "Moyens", [1, 3, 4, 5], "07:45", "16:45", "MENSUALISE"]]],
  ["Naisseline", "Dumbéa", ["Joël", "Père"], [["Ania", 36, "Grands", [1, 2, 3, 4, 5], "07:30", "16:00", "MENSUALISE"]]],
  ["Ihage", "Nouméa", ["Laure", "Mère"], [["Elio", 20, "Moyens", [1, 2, 5], "08:30", "16:30", "MENSUALISE", "3 jours par semaine"]]],
  ["Faura", "Dumbéa", ["Tania", "Mère"], [["Moana", 13, "Bébés", [1, 2, 3, 4, 5], "07:15", "16:15", "MENSUALISE"]]],
  ["Cawidrone", "Païta", ["Patrice", "Père"], [["Nolan", 42, "Grands", [1, 2, 3, 4], "07:30", "16:30", "MENSUALISE"]]],
  ["Brun", "Dumbéa", ["Émilie", "Mère"], [["Zoé", 25, "Moyens", [1, 2, 3, 4, 5], "07:45", "16:00", "MENSUALISE"], ["Lucas", 44, "Grands", [1, 2, 3, 4, 5], "07:45", "16:00", "MENSUALISE"]]],
  ["Waheo", "Nouméa", ["Mélissa", "Mère"], [["Kenzo", 10, "Bébés", [2, 3, 4], "08:00", "15:30", "REEL"]]],
  ["Wejieme", "Nouméa", ["Aline", "Mère"], [["Théo", 27, "Moyens", [1, 2, 3, 4, 5], "07:30", "12:00", "MENSUALISE", "Matins seulement"]]],
  ["Pidjot", "Païta", ["Joanne", "Mère"], [["Ema", 32, "Grands", [1, 2, 3, 4, 5], "12:30", "17:00", "MENSUALISE", "Après-midi seulement"]]],
];
/** Enfants partis à l'école : ils nourrissent l'historique (factures, statistiques) sans être accueillis aujourd'hui. */
const FORMER = [
  // nom, ville, parent, enfant, parti il y a N mois
  ["Dubois", "Nouméa", ["Claire", "Mère"], ["Arthur", 52, "Grands", [1, 2, 3, 4, 5], "07:30", "16:30", "MENSUALISE"], 3],
  ["Kasovimoin", "Dumbéa", ["Paul", "Père"], ["Lou", 47, "Grands", [1, 2, 4, 5], "08:00", "16:00", "MENSUALISE"], 6],
];
const AUTHORIZED = [["Mamie", "Grand-mère"], ["Papi", "Grand-père"], ["Tatie", "Tante"], ["Tonton", "Oncle"], ["Voisine", "Amie de la famille"]];
const FORMULAS = [
  { label: "Temps plein", monthlyFee: 98_500 },
  { label: "Matins seulement", monthlyFee: 60_000 },
  { label: "Après-midi seulement", monthlyFee: 60_000 },
  { label: "3 jours par semaine", monthlyFee: 65_000 },
];

/** Âge en mois → date de naissance (le 10 du mois). */
function birthDate(months) {
  const d = day(monthStart(TODAY, -months));
  return iso(new Date(d.getTime() + 9 * 86_400_000));
}
/** Début d'accueil : le début de l'historique, ou quatre mois après la naissance pour les plus jeunes. */
const entryOf = (months) => later(HISTORY_START, monthStart(birthDate(months), 4));
const emailOf = (parent, lastName) => `${parent.toLowerCase().normalize("NFD").replace(/[^a-z]/g, "")}.${lastName.toLowerCase().normalize("NFD").replace(/[^a-z]/g, "")}@exemple.nc`;

async function team(sections) {
  step("Équipe : direction et 7 personnes, plannings, diplômes, formations");
  const me = await get("/auth/me");
  await put(`/users/${me.id}/pin`, { pin: DIRECTOR.pin });
  const ids = {};
  for (const member of TEAM) {
    const created = await post("/users", {
      firstName: member.firstName, lastName: member.lastName, role: member.role, login: null, password: null, pin: member.pin,
      jobTitle: member.job, homeSectionId: member.section ? sections[member.section] : null, countsInRatio: member.role === "EDUCATRICE",
    });
    ids[member.firstName] = created.id;
    await put(`/staff/${created.id}/profile`, {
      birthDate: null, phone: "77 00 00", email: null, address: "Dumbéa", cafatNumber: null, emergencyContact: null,
      diploma: member.job, contractType: member.firstName === "Sandrine" ? "REMPLACEMENT" : "CDI",
      weeklyMinutes: member.firstName === "Léa" ? 30 * 60 : 39 * 60, hiredOn: "2024-08-19",
      endsOn: member.firstName === "Sandrine" ? addDays(TODAY, 24) : null,
    });
    const start = member.role === "CUISINE" ? "06:30" : member.firstName === "Maëva" ? "07:00" : "08:00";
    const end = member.role === "CUISINE" ? "14:30" : member.firstName === "Maëva" ? "15:30" : "16:30";
    await put(`/staff/${created.id}/schedule`, {
      effectiveFrom: monthStart(TODAY, -3),
      days: [1, 2, 3, 4, 5].map((d) => ({ dayOfWeek: d, startsAt: start, endsAt: end, breakMinutes: 45, sectionId: member.section ? sections[member.section] : null })),
    });
  }
  await post(`/staff/${ids["Maëva"]}/qualifications`, { kind: "SST", label: "Sauveteur secouriste du travail", obtainedOn: "2024-11-14", validUntil: addDays(TODAY, 11), verified: true });
  await post(`/staff/${ids["Maëva"]}/qualifications`, { kind: "DIPLOME", label: "Diplôme d’État d’éducateur de jeunes enfants", obtainedOn: "2019-06-30", validUntil: null, verified: true });
  await post(`/staff/${ids["Paulo"]}/qualifications`, { kind: "HACCP", label: "Hygiène alimentaire (HACCP)", obtainedOn: "2022-09-01", validUntil: addDays(TODAY, -20), verified: true });
  await post(`/staff/${ids["Maëva"]}/trainings`, { label: "Recyclage SST", minutes: 7 * 60, startsOn: addDays(TODAY, 6), endsOn: addDays(TODAY, 6), organization: "Croix-Rouge NC", done: false });
  await post(`/staff/${ids["Léa"]}/trainings`, { label: "Accueil des émotions du jeune enfant", minutes: 14 * 60, startsOn: monthStart(TODAY, -2), endsOn: addDays(monthStart(TODAY, -2), 1), organization: "IFPSS", done: true });
  return ids;
}

async function billingSetup() {
  step("Barème au volume horaire, formules de facturation et financeurs");
  const year = Number(TODAY.slice(0, 4));
  await post("/tariff-schedules", {
    label: `Barème ${year}`, criterion: "HEURES_SEMAINE", weeksPerYear: 47, registrationFee: 10_000, validFrom: `${year - 2}-01-01`,
    bands: [{ lowerBound: 0, hourlyRate: 620 }, { lowerBound: 20 * 60, hourlyRate: 560 }, { lowerBound: 35 * 60 + 15, hourlyRate: 510 }],
    formulas: FORMULAS,
  });
  const funder = (name, category, calculation, amount, rate, cap, priority) => post("/billing/funders", {
    name, kind: "AIDE_FAMILLE", category, calculation, amount, rate, rateBase: "FRAIS_GARDE", monthlyCap: cap, priority,
    validFrom: `${year - 2}-01-01`, validTo: null, conventionRef: `CONV-${priority}`, contact: null,
  });
  return {
    province: (await funder("Province Sud — aide à la garde", "PROVINCE", "POURCENTAGE", 0, 200, 30_000, 1)).id,
    cafat: (await funder("CAFAT — prestation d’accueil", "ORGANISME", "MONTANT_FIXE", 12_000, 0, null, 2)).id,
    employer: (await funder("Lagon Services (employeur)", "EMPLOYEUR", "MONTANT_FIXE", 10_000, 0, null, 3)).id,
  };
}

async function signedContract(childId, start, end, days, arrives, leaves, mode, formula) {
  const created = await post("/contracts", {
    childId,
    contract: {
      startDate: start, endDate: end, noticeMonths: 1,
      planning: { mode: "FIXE", days: days.map((d) => ({ dayOfWeek: d, arrivesAt: arrives, leavesAt: leaves })), billingMode: mode, formula: formula ?? null },
      adaptationBilling: "AU_TEMPS", adaptation: [],
    },
  });
  const form = new FormData();
  form.set("signedOn", TODAY);
  await call("POST", `/contracts/${created.id}/versions/0/sign`, undefined, { form });
}

async function createKid(familyId, lastName, city, parent, relationship, index, [firstName, months, section, days, arrives, leaves, mode, formula], sections, exitDate = null) {
  const entry = entryOf(months);
  const child = { firstName, lastName, birthDate: birthDate(months), sectionId: sections[section], entryDate: entry, exitDate };
  const created = await post("/children", familyId ? { child, familyId } : {
    child,
    family: {
      family: { label: `Famille ${lastName}`, city },
      guardian: {
        firstName: parent, lastName, relationship, legalGuardian: true, payer: true,
        mobilePhone: `7${index % 10} ${String(10 + index).padStart(2, "0")} 3${index % 10}`, email: emailOf(parent, lastName),
      },
    },
  });
  const record = await get(`/children/${created.id}`);
  const [first, link] = AUTHORIZED[index % AUTHORIZED.length];
  await post(`/children/${created.id}/authorized-persons`, { firstName: first, lastName, relationship: link, phone: "26 45 67", idVerified: true });
  await put(`/children/${created.id}/consents`, {
    consents: [
      { type: "PHOTOS", granted: index % 6 !== 3, details: null, signedOn: entry, signedBy: `${parent} ${lastName}` },
      { type: "SORTIES", granted: true, details: null, signedOn: entry, signedBy: `${parent} ${lastName}` },
      { type: "SOINS_URGENCE", granted: true, details: null, signedOn: entry, signedBy: `${parent} ${lastName}` },
    ],
  });
  await signedContract(created.id, entry, exitDate ?? addDays(monthStart(TODAY, 13), -1), days, arrives, leaves, mode, formula);
  return {
    id: created.id, familyId: record.family?.id ?? familyId, firstName, lastName, section, days, arrives, leaves, mode,
    entry, exit: exitDate, photos: index % 6 !== 3, familyLabel: `Famille ${lastName}`,
  };
}

async function childrenAndFamilies(sections, funders) {
  step("Enfants et familles sur 18 mois : santé, autorisations, contrats (dont formules), aides");
  const kids = [];
  let index = 0;
  for (const [lastName, city, [parent, relationship], children] of FAMILIES) {
    let familyId = null;
    for (const spec of children) {
      const kid = await createKid(familyId, lastName, city, parent, relationship, index++, spec, sections);
      familyId = kid.familyId;
      kids.push(kid);
    }
  }
  const former = [];
  for (const [lastName, city, [parent, relationship], spec, monthsAgo] of FORMER) {
    former.push(await createKid(null, lastName, city, parent, relationship, index++, spec, sections, addDays(monthStart(TODAY, -monthsAgo), -1)));
  }

  // Santé : allergies, PAI, régimes.
  const byName = Object.fromEntries(kids.map((k) => [k.firstName, k]));
  const vegetarian = (await get("/diets")).find((d) => /végétarien/i.test(d.label))
    ?? await post("/diets", { label: "Végétarien", excludes: ["viande", "poisson"], displayOrder: 1 });
  const health = (kid, overrides) => ({
    diet: null, dietDetails: null, paiActive: false, paiReason: null, paiValidFrom: null, paiValidUntil: null, paiKitLocation: null,
    paiKitDetails: null, paiDocumentId: null, emergencyProtocol: null, doctorName: "Dr Ihage", doctorPhone: "26 33 44", doctorAddress: "Dumbéa-sur-Mer",
    instructions: null, vaccinationCheckedOn: kid.entry, allergies: [], vaccinations: [{ vaccine: "DTCaP", lastDoseOn: addDays(kid.entry, -60) }],
    dietId: null, mealTexture: "NORMALE", packedLunch: false, ...overrides,
  });
  await put(`/children/${byName.Noa.id}/health`, health(byName.Noa, {
    allergies: [{ allergen: "Arachide", reaction: "Œdème, gêne respiratoire", severity: "SEVERE" }, { allergen: "Kiwi", reaction: "Rougeurs", severity: "LEGERE" }],
    paiActive: true, paiReason: "Allergie à l’arachide", paiValidFrom: monthStart(TODAY, -3), paiValidUntil: addDays(monthStart(TODAY, -3), 365),
    paiKitLocation: "Armoire à pharmacie, section Moyens", paiKitDetails: "Adrénaline auto-injectable, antihistaminique",
    emergencyProtocol: "Injecter l’adrénaline, appeler le 15, prévenir les parents.",
  }));
  await put(`/children/${byName.Léo.id}/health`, health(byName.Léo, { dietId: vegetarian.id, diet: "Végétarien" }));
  await put(`/children/${byName.Kaïna.id}/health`, health(byName.Kaïna, { mealTexture: "MIXEE" }));
  await put(`/children/${byName.Malo.id}/health`, health(byName.Malo, { allergies: [{ allergen: "Lait de vache", reaction: "Troubles digestifs", severity: "MODEREE" }] }));
  await put(`/children/${byName.Inaya.id}/health`, health(byName.Inaya, { allergies: [{ allergen: "Œuf", reaction: "Urticaire", severity: "MODEREE" }] }));

  // Aides : province Sud pour quatre familles, CAFAT et employeur.
  const grant = (child, funderId, beneficiary) => post(`/children/${child.id}/fundings`, { funderId, fromDay: child.entry, beneficiary, fileNumber: `A-${child.firstName.toUpperCase()}` });
  for (const name of ["Noa", "Lila", "Kaïna", "Teva"]) await grant(byName[name], funders.province, "Famille");
  for (const name of ["Tom", "Malo"]) await grant(byName[name], funders.cafat, "Famille");
  await grant(byName.Léo, funders.employer, "Lisa Taufana");
  await grant(former[0], funders.province, "Famille");
  return { kids, former };
}

/** Jours d'accueil réels d'un enfant : planning, hors fermetures, congés (une semaine par semestre) et maladies. */
function attendedDays(kid, rank) {
  const vacations = new Set();
  const illness = new Set();
  const last = earlier(addDays(TODAY, -1), kid.exit ?? TODAY);
  for (let m = 0; m <= HISTORY_MONTHS; m += 6) {
    const from = addDays(monthStart(HISTORY_START, m + (rank % 5)), 7 + (rank % 3) * 2);
    for (let i = 0; i < 7; i++) vacations.add(addDays(from, i));
  }
  for (let m = 1; m <= HISTORY_MONTHS; m += 4) {
    if ((rank + m) % 3 !== 0) continue;
    const from = addDays(monthStart(HISTORY_START, m), 10 + (rank % 4));
    illness.add(from);
    illness.add(addDays(from, 1));
  }
  // Deux enfants malades ces derniers jours (mois en cours) : « Préparer le mois » montrera le certificat manquant.
  if (rank % 9 === 4 && !kid.exit) {
    let found = 0;
    for (let d = addDays(TODAY, -1); d >= monthStart(TODAY) && found < 2; d = addDays(d, -1)) {
      if (kid.days.includes(weekday(d))) { illness.add(d); found++; }
    }
  }
  const days = [];
  for (let d = kid.entry; d <= last; d = addDays(d, 1)) {
    if (!kid.days.includes(weekday(d)) || closed(d) || vacations.has(d) || illness.has(d)) continue;
    days.push(d);
  }
  return { days, vacations, illness };
}

async function attendanceHistory(kids) {
  step("Présences et absences des 18 derniers mois (deux à trois minutes)");
  const reasons = await get("/billing/absence-reasons");
  const illness = reasons.find((r) => /maladie/i.test(r.label)) ?? reasons[0];
  const holidays = reasons.find((r) => /cong|vacanc|famil/i.test(r.label)) ?? reasons[0];
  // Les mois facturés n'ont que des maladies sans certificat à joindre : leurs factures sont toutes émises. Le mois en
  // cours garde des maladies sans justificatif : des anomalies à montrer dans « Préparer le mois ».
  const other = reasons.find((r) => /autre/i.test(r.label) && !r.proofRequired) ?? illness;
  const thisMonth = monthStart(TODAY);
  const records = [];
  const absences = [];
  kids.forEach((kid, rank) => {
    const { days, vacations, illness: sick } = attendedDays(kid, rank);
    for (const d of days) {
      const arrives = hm(jitter(minutes(kid.arrives), 15));
      const leaves = hm(jitter(minutes(kid.leaves), 20));
      records.push({ childId: kid.id, arrivedAt: at(d, arrives), departedAt: at(d, leaves), pickedUpBy: "Parent", reason: "Reprise du cahier de présence" });
    }
    // Absences déclarées dans les dates d'accueil de l'enfant : congés (prévenus 10 jours avant) et maladies.
    const inside = (d) => d >= kid.entry && d < earlier(TODAY, kid.exit ?? TODAY) && kid.days.includes(weekday(d)) && !closed(d);
    const blocks = (set) => {
      const sorted = [...set].filter(inside).sort();
      const out = [];
      for (const d of sorted) {
        if (out.length && addDays(out.at(-1)[1], 1) === d) out.at(-1)[1] = d; else out.push([d, d]);
      }
      return out;
    };
    for (const [from, to] of blocks(vacations)) absences.push({ kid, body: { fromDay: from, toDay: to, reasonId: holidays.id, notifiedOn: addDays(from, -10), documentId: null, note: "Vacances en famille" } });
    for (const [from, to] of blocks(sick)) {
      const recent = from >= thisMonth;
      absences.push({ kid, body: { fromDay: from, toDay: to, reasonId: recent ? illness.id : other.id, notifiedOn: from, documentId: null, note: recent ? null : "Enfant malade" } });
    }
  });
  await pool(absences, 6, ({ kid, body }) => post(`/children/${kid.id}/absences`, body).catch(() => null));
  let done = 0;
  await pool(records, 10, async (record) => {
    await post("/attendance/children/records", record);
    if (++done % 2000 === 0) console.log(`    ${done} passages…`);
  });
  console.log(`    ${records.length} passages, ${absences.length} absences`);
}

async function staffHistory(staffIds, sections) {
  step("Pointages de l’équipe des trois derniers mois, feuilles d’heures validées");
  const punches = [];
  for (const member of TEAM) {
    const start = member.role === "CUISINE" ? "06:30" : member.firstName === "Maëva" ? "07:00" : "08:00";
    const end = member.role === "CUISINE" ? "14:30" : member.firstName === "Maëva" ? "15:30" : "16:30";
    for (let d = monthStart(TODAY, -3); d < TODAY; d = addDays(d, 1)) {
      if (weekday(d) > 5 || closed(d)) continue;
      punches.push({
        userId: staffIds[member.firstName], arrivedAt: at(d, hm(jitter(minutes(start), 10))), departedAt: at(d, hm(jitter(minutes(end), 15))),
        sectionId: member.section ? sections[member.section] : null, reason: "Reprise des feuilles de présence",
      });
    }
  }
  await pool(punches, 8, (p) => post("/attendance/staff/records", p));
  for (const offset of [-3, -2]) {
    await post(`/staff/timesheets/${yearMonth(monthStart(TODAY, offset))}/validate`).catch((e) => console.log(`    Feuille d’heures non validée : ${e.message}`));
  }
  console.log(`    ${punches.length} pointages`);
}

async function invoices(kids, former) {
  step("Factures des 18 derniers mois : envoi, encaissements, impayés récents, un avoir, demandes aux financeurs");
  const everyone = [...kids, ...former];
  const first = monthStart(everyone.map((k) => k.entry).sort()[0]);
  let count = 0;
  for (let month = first; month < monthStart(TODAY); month = monthStart(month, 1)) {
    const ym = yearMonth(month);
    const age = (Number(TODAY.slice(0, 4)) - Number(ym.slice(0, 4))) * 12 + Number(TODAY.slice(5, 7)) - Number(ym.slice(5, 7));
    await post(`/billing/months/${ym}/generate`, {});
    const list = await get(`/billing/invoices?month=${ym}`);
    const paidOn = earlier(addDays(monthStart(month, 1), 19), TODAY);
    await pool(list.map((invoice, i) => ({ invoice, i })), 6, async ({ invoice, i }) => {
      // Les factures sont émises le jour de leur préparation : l'envoi aussi. Les paiements gardent leur date réelle.
      await post(`/billing/invoices/${invoice.id}/sent`, { sentOn: TODAY, channel: "Courriel" });
      const due = invoice.familyTotal ?? (await get(`/billing/invoices/${invoice.id}`)).familyTotal ?? 0;
      // Mois anciens : presque tout est payé ; avant-dernier mois : 80 % ; dernier mois : la moitié, dont un partiel.
      const pays = age > 2 ? i % 13 !== 7 : age === 2 ? i % 5 !== 3 : i % 2 === 0;
      if (pays && due > 0) {
        const partial = age === 1 && i === 2;
        await post("/billing/payments", {
          invoiceId: invoice.id, familyId: null, funderRequestId: null, paidOn,
          amount: partial ? Math.floor(due / 2) : due, method: i % 3 === 1 ? "CHEQUE" : i % 3 === 2 ? "CARTE" : "VIREMENT",
          reference: i % 3 === 1 ? `CHQ ${4_100_000 + i}` : `VIR-${ym}-${i}`, bank: i % 3 === 1 ? "BCI" : null,
        });
      }
    });
    if (age === 1 && list.length > 5) {
      // Facture non payée (rang impair) : l'avoir réduit son reste dû.
      await post(`/billing/invoices/${list[5].id}/credit-notes`, {
        reason: "Fermeture exceptionnelle (journée pédagogique)", full: false, lines: [{ label: "Journée pédagogique non facturée", amount: 2_500 }],
      });
    }
    if (age > 1) {
      for (const request of await get(`/billing/funder-requests?month=${ym}`).catch(() => [])) {
        if (!request.sentOn) await post(`/billing/funder-requests/${request.id}/sent`, { sentOn: TODAY }).catch(() => null);
      }
    }
    count += list.length;
  }
  console.log(`    ${count} factures`);
}

const MENUS = [
  [["Salade de carottes", "Poulet coco, riz", "Yaourt nature", "Compote de pomme"],
    ["Concombre vinaigrette", "Poisson du lagon, purée de patate douce", "Fromage blanc", "Banane"],
    ["Taboulé", "Bougna de légumes", "Petit-suisse", "Pain et chocolat"],
    ["Avocat", "Bœuf aux légumes, pâtes", "Emmental", "Papaye"],
    ["Salade de tomates", "Omelette, haricots verts", "Yaourt aux fruits", "Gâteau maison"]],
  [["Betteraves", "Porc au caramel, riz", "Fromage blanc", "Mangue"],
    ["Salade verte", "Thon, igname", "Yaourt", "Pain et confiture"],
    ["Pastèque", "Poulet rôti, haricots", "Petit-suisse", "Compote"],
    ["Carottes râpées", "Lentilles, saucisse", "Emmental", "Banane"],
    ["Concombre", "Poisson pané, taro", "Yaourt aux fruits", "Crêpe"]],
  [["Salade de pâtes", "Cerf aux légumes", "Fromage", "Ananas"],
    ["Tomates", "Poulet coco, manioc", "Yaourt", "Pain et chocolat"],
    ["Avocat", "Poisson vapeur, riz", "Fromage blanc", "Papaye"],
    ["Céleri", "Hachis parmentier", "Petit-suisse", "Compote"],
    ["Salade de fruits", "Omelette, courgettes", "Yaourt", "Gâteau au coco"]],
];

async function menus() {
  step("Menus des trois derniers mois et des deux semaines à venir");
  let i = 0;
  for (let start = addDays(monday(TODAY), -7 * 13); start <= addDays(monday(TODAY), 7); start = addDays(start, 7)) {
    const week = MENUS[i++ % MENUS.length];
    const items = week.flatMap(([starter, main, dairy, snack], d) => [
      { dayOfWeek: d + 1, course: "ENTREE", dietId: null, label: starter, tags: [] },
      { dayOfWeek: d + 1, course: "PLAT", dietId: null, label: main, tags: [] },
      { dayOfWeek: d + 1, course: "LAITAGE", dietId: null, label: dairy, tags: [] },
      { dayOfWeek: d + 1, course: "GOUTER", dietId: null, label: snack, tags: [] },
    ]);
    await put(`/menus/${start}`, { status: "PUBLIE", items });
  }
}

/** Trimestres de repas passés : clos, comme après leur envoi aux financeurs. */
async function closeMealQuarters() {
  const current = Number(TODAY.slice(0, 4)) * 4 + Math.floor((Number(TODAY.slice(5, 7)) - 1) / 3);
  const first = Number(HISTORY_START.slice(0, 4)) * 4 + Math.floor((Number(HISTORY_START.slice(5, 7)) - 1) / 3);
  for (let q = first; q < current - 1; q++) {
    await post(`/meals/quarters/${Math.floor(q / 4)}/${(q % 4) + 1}/close`).catch(() => null);
  }
}

async function haccp(device, staffIds) {
  step("Relevés de températures (cuisine)");
  const points = [
    { label: "Réfrigérateur de la cuisine", kind: "FRIGO", minTenths: 0, maxTenths: 40 },
    { label: "Congélateur", kind: "CONGELATEUR", minTenths: -250, maxTenths: -180 },
    { label: "Plat témoin du déjeuner", kind: "PLAT_TEMOIN", minTenths: 0, maxTenths: 30 },
  ];
  const created = [];
  for (const p of points) created.push(await post("/haccp/points", p));
  const cook = TEAM.find((m) => m.role === "CUISINE");
  const session = await post("/auth/pin", { userId: staffIds[cook.firstName], pin: cook.pin }, { auth: device });
  const as = { auth: session.accessToken };
  await post("/haccp/readings", { pointId: created[0].id, tenths: 32, action: null }, as);
  await post("/haccp/readings", { pointId: created[1].id, tenths: -195, action: null }, as);
  await post("/haccp/readings", { pointId: created[0].id, tenths: 58, action: "Porte mal fermée : joint remis en place, nouveau relevé à 11 h." }, as);
  await post("/haccp/readings", { pointId: created[2].id, tenths: 24, action: null }, as);
}

async function staffSpace(device, staffIds, sections) {
  step("Espace pro : demandes de congé, récupérations, heures sup, cahier d’équipe");
  const sessionOf = async (name) => {
    const member = TEAM.find((m) => m.firstName === name);
    return (await post("/auth/pin", { userId: staffIds[name], pin: member.pin }, { auth: device })).accessToken;
  };
  const maeva = await sessionOf("Maëva");
  const lea = await sessionOf("Léa");
  const mereana = await sessionOf("Mereana");
  const sandrine = await sessionOf("Sandrine");
  const nextMonth = monthStart(TODAY, 1);
  const pending = await post("/staff-requests", { kind: "CONGES", fromDay: addDays(nextMonth, 14), toDay: addDays(nextMonth, 18), minutes: null, note: "Mariage de ma sœur à Lifou" }, { auth: maeva });
  const recovery = await post("/staff-requests", { kind: "RECUPERATION", fromDay: addDays(TODAY, 9), toDay: addDays(TODAY, 9), minutes: 7 * 60, note: "Récupération de la fête de fin d’année" }, { auth: lea });
  const overtime = await post("/staff-requests", { kind: "HEURES_SUP", fromDay: addDays(TODAY, -6), toDay: addDays(TODAY, -6), minutes: 90, note: "Réunion de parents jusqu’à 18 h" }, { auth: mereana });
  const refused = await post("/staff-requests", { kind: "CONGES", fromDay: addDays(TODAY, 3), toDay: addDays(TODAY, 4), minutes: null, note: "Rendez-vous administratifs" }, { auth: sandrine });
  await post(`/staff-requests/${recovery.id}/accept`).catch(() => null);
  await post(`/staff-requests/${overtime.id}/accept`).catch(() => null);
  await post(`/staff-requests/${refused.id}/refuse`, { reason: "Deux absences la même semaine : reportez d’une semaine, s’il vous plaît." }).catch(() => null);
  void pending;
  await post("/team-notes", { sectionId: null, body: "Rappel : sortie au parc vendredi matin, prévoir chapeaux et crème solaire pour tous." });
  await post("/team-notes", { sectionId: sections["Moyens"], body: "Noa : nouveau PAI arachide, la trousse est dans l’armoire à pharmacie des Moyens." }, { auth: maeva });
  await post("/team-notes", { sectionId: sections["Bébés"], body: "Hina fait ses premiers pas ! Les parents aimeraient une vidéo si possible." }, { auth: sandrine });
}

async function enrollments(sections) {
  step("Demandes d’inscription à toutes les étapes, pré-inscription en ligne");
  const requests = [
    ["Aïden", "Wakolo", 8, "Bébés", "NOUVELLE"], ["Chloé", "Bernard", 15, "Bébés", "A_RECONTACTER"], ["Ilan", "Kasarhérou", 20, "Moyens", "VISITE_PREVUE"],
    ["Maïa", "Trolue", 5, "Bébés", "PLACE_PROPOSEE"], ["Sacha", "Leroy", 26, "Moyens", "NOUVELLE"], ["Nina", "Wright", 31, "Grands", "REFUSEE"],
  ];
  for (const [first, last, months, section, stage] of requests) {
    const created = await post("/enrollment-requests", {
      childFirstName: first, childLastName: last, birthDate: birthDate(months), expectedBirthDate: null, sectionId: sections[section],
      desiredStart: monthStart(TODAY, 2), careType: "REGULIER", arrivesAt: "07:30", leavesAt: "16:30", days: [1, 2, 3, 4, 5],
      guardianFirstName: "Parent", guardianLastName: last, relationship: "Mère", phone: "75 12 12", email: null, familyId: null, criteria: [],
    });
    if (stage !== "NOUVELLE") {
      await put(`/enrollment-requests/${created.id}/stage`, {
        stage, followUpOn: stage === "A_RECONTACTER" ? addDays(TODAY, 3) : null,
        visitAt: stage === "VISITE_PREVUE" ? `${addDays(TODAY, 2)}T10:00:00` : null,
        offerExpiresOn: stage === "PLACE_PROPOSEE" ? addDays(TODAY, 10) : null,
        reason: stage === "REFUSEE" ? "Pas de place aux horaires demandés" : null,
      });
    }
  }

  // Formulaire public : texte d'accueil, deux questions, trois demandes reçues en ligne.
  await put("/enrollment/online", { enabled: true, intro: "Bienvenue aux P’tits Niaoulis ! Remplissez ce formulaire : nous vous rappelons sous une semaine." });
  await post("/enrollment/questions", { label: "Un frère ou une sœur est-il déjà accueilli chez nous ?", kind: "OUI_NON", options: [], required: false, displayOrder: 0 });
  await post("/enrollment/questions", { label: "Comment avez-vous connu la crèche ?", kind: "CHOIX", options: ["Bouche-à-oreille", "Site internet", "Mairie", "Autre"], required: true, displayOrder: 1 });
  const form = await get("/public/preinscription", { auth: null });
  const answers = Object.fromEntries(form.questions.map((q) => [q.id, q.kind === "OUI_NON" ? "Non" : q.kind === "CHOIX" ? "Bouche-à-oreille" : "—"]));
  for (const [first, last, months, parent] of [["Tiloa", "Néaoutyine", 6, "Gaëlle"], ["Matéo", "Siapo", 17, "Lina"], ["Anaë", "Ukeiwé", 22, "Rose"]]) {
    await post("/public/preinscription", {
      request: {
        childFirstName: first, childLastName: last, birthDate: birthDate(months), expectedBirthDate: null, sectionId: null,
        desiredStart: monthStart(TODAY, 3), careType: "REGULIER", arrivesAt: "07:45", leavesAt: "16:15", days: [1, 2, 4, 5],
        guardianFirstName: parent, guardianLastName: last, relationship: "Mère", phone: "79 45 45", email: emailOf(parent, last), familyId: null, criteria: [],
      },
      answers, consent: true, website: "", startedAt: Date.now() - 90_000,
    }, { auth: null }).catch((e) => console.log(`    Pré-inscription en ligne non envoyée : ${e.message}`));
  }
}

async function documentsAndRules() {
  step("Modèles de documents, règles des demandes d’accueil");
  await post("/document-templates/starters").catch((e) => console.log(`    Modèles non créés : ${e.message}`));
  await put("/booking-rules", { minNoticeHours: 48, maxDaysAhead: 60, validFrom: TODAY }).catch((e) => console.log(`    Règles non enregistrées : ${e.message}`));
}

// ------------------------------------------------------------------------------------------------- aujourd'hui

let tabletToken = null;

async function pairTablet() {
  // Une seule tablette de démonstration : celle d'un lancement précédent est retirée (limite de la licence).
  for (const old of (await get("/devices")).filter((d) => d.name === "Accueil — démonstration")) {
    await call("DELETE", `/devices/${old.id}`);
  }
  const created = await post("/devices", { name: "Accueil — démonstration" });
  const paired = await post("/devices/pair", { code: created.code }, { auth: null });
  tabletToken = (await post("/devices/session", { deviceId: paired.deviceId, secret: paired.secret }, { auth: null })).accessToken;
  return tabletToken;
}

async function today(kids, sections, staffIds) {
  step("Aujourd’hui : tablette d’accueil, pointages, transmissions");
  const device = tabletToken ?? await pairTablet();
  if (weekday(TODAY) > 5) {
    console.log("    Week-end : pas de pointage aujourd’hui.");
    return;
  }
  const now = minutes(nowHm());
  // Le pointage du personnel bascule arrivée et départ : une relance ne doit pas faire partir l'équipe.
  const onSite = new Set((await get(`/attendance?day=${TODAY}`)).staff.filter((s) => !s.departedAt).map((s) => s.userId));
  for (const member of TEAM) {
    // La cuisine ne compte pas dans l'encadrement : elle ne pointe pas de section sur la tablette.
    if (member.role === "CUISINE" || minutes("08:00") > now || onSite.has(staffIds[member.firstName])) continue;
    await post("/kiosk/staff-punch", { clientId: randomUUID(), pin: member.pin, sectionId: member.section ? sections[member.section] : null }, { auth: device });
  }
  const present = [];
  for (const kid of kids) {
    if (!kid.days.includes(weekday(TODAY))) continue;
    const arrives = jitter(minutes(kid.arrives), 10);
    if (arrives > now) continue;
    // Relancé le même jour : un enfant déjà arrivé est refusé par le serveur, on passe au suivant.
    const arrival = await post("/attendance/children/records", { childId: kid.id, arrivedAt: at(TODAY, hm(arrives)), departedAt: null, pickedUpBy: null, reason: "Arrivée du matin" }, { expect: [201, 409, 422] });
    // Réponse : l'identifiant du passage ; rien si l'enfant était déjà arrivé.
    if (!arrival || arrival.code) continue;
    present.push(kid);
  }
  console.log(`    ${present.length} enfants arrivés`);
}

// ---------------------------------------------------------------------------------------------- portail parents

/** Journée type d'un enfant. */
function dayOf(kid, baby) {
  const lunch = ["Riz, poisson et courgettes.", "Poulet coco et patate douce.", "Bougna de légumes.", "Pâtes au bœuf et carottes."][Math.floor(random() * 4)];
  const words = [
    `${kid.firstName} a passé une très belle journée.`, `${kid.firstName} a beaucoup ri pendant les comptines.`,
    `${kid.firstName} a aidé à ranger les jeux, bravo !`, `${kid.firstName} était un peu fatigué(e) cet après-midi.`,
    `${kid.firstName} a adoré la peinture avec les mains.`,
  ];
  const entries = [
    ["09:15", "REPAS", { meal: "COLLATION", quantity: "TOUT" }, ""],
    ["10:00", "ACTIVITE", {}, ["Peinture avec les mains et chansons du matin.", "Parcours de motricité.", "Lecture d’histoires.", "Jeux d’eau dans le jardin."][Math.floor(random() * 4)]],
    ["10:40", "CHANGE", { change: random() < 0.7 ? "MOUILLEE" : "SELLES" }, ""],
    ["11:30", "REPAS", { meal: "DEJEUNER", quantity: random() < 0.75 ? "BIEN" : "TOUT" }, lunch],
    ["12:30", "SIESTE", { from: "12:30", to: hm(minutes("13:40") + Math.round(random() * 6) * 10) }, ""],
    ["15:00", "REPAS", { meal: "GOUTER", quantity: "TOUT" }, ""],
    ["15:30", "MOT", {}, words[Math.floor(random() * words.length)]],
  ];
  if (baby) {
    entries.push(["08:45", "REPAS", { meal: "BIBERON", quantity: "TOUT", volumeMl: "150" }, ""]);
    entries.push(["14:30", "REPAS", { meal: "BIBERON", quantity: "BIEN", volumeMl: "120" }, ""]);
  }
  return entries.sort(([a], [b]) => a.localeCompare(b));
}

const SCENES = [
  ["atelier-peinture.jpg", "Atelier peinture : de belles couleurs ce matin !"], ["jeux-eau.jpg", "Jeux d’eau, les pieds dans le sable."],
  ["histoire.jpg", "L’heure de l’histoire, tout le monde écoute."], ["jardin.jpg", "On a planté des tomates au jardin."],
  ["motricite.jpg", "Parcours de motricité : tunnel, coussins, ballons."], ["musique.jpg", "Éveil musical avec tambourins et xylophone."],
  ["pate-a-modeler.jpg", "Pâte à modeler : des serpents et des gâteaux."], ["gouter-fruits.jpg", "Goûter : papaye et banane du marché."],
  ["sieste.jpg", "Petite sieste bien méritée."], ["anniversaire.jpg", "On a fêté un anniversaire !"],
  ["sortie-parc.jpg", "Promenade au parc sous les niaoulis."], ["construction.jpg", "La plus haute tour de la semaine !"],
];
const VIDEOS = [["video-bulles.mp4", "Les bulles au jardin 🫧"], ["video-musique.mp4", "On chante ensemble"], ["video-tour.mp4", "Et la tour tient debout !"]];

/** Session PIN de l'éducatrice sur la tablette : auteur des transmissions. */
async function educatorSession(staffIds) {
  const educ = TEAM[1];
  const pin = await post("/auth/pin", { userId: staffIds[educ.firstName], pin: educ.pin }, { auth: tabletToken });
  return { userId: staffIds[educ.firstName], sessionId: pin.sessionId };
}

/**
 * Identifiant stable d'une transmission du jeu (enfant, jour, heure, type) : relancé le même jour (--aujourdhui), le
 * script renvoie les mêmes opérations, que la tablette reconnaît (DEJA_RECUE) au lieu de les écrire une seconde fois.
 */
function stableId(...parts) {
  const h = createHash("sha256").update(parts.join("|")).digest("hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-4${h.slice(13, 16)}-${((parseInt(h[16], 16) & 3) | 8).toString(16)}${h.slice(17, 20)}-${h.slice(20, 32)}`;
}

async function journal(kids, staffIds, onlyToday = false) {
  step(onlyToday ? "Transmissions du jour" : "Journées détaillées des deux dernières semaines");
  const author = await educatorSession(staffIds);
  const operations = [];
  const nowM = minutes(nowHm());
  // La tablette accepte des opérations de moins de 7 jours : les six derniers jours ouvrés et aujourd'hui.
  for (let back = onlyToday ? 0 : 6; back >= 0; back--) {
    const d = addDays(TODAY, -back);
    if (weekday(d) > 5 || closed(d)) continue;
    for (const kid of kids.filter((k) => k.days.includes(weekday(d)))) {
      for (const [time, kind, details, text] of dayOf(kid, kid.section === "Bébés")) {
        if (d === TODAY && (minutes(time) > nowM || minutes(time) < minutes(kid.arrives))) continue;
        operations.push({
          clientId: stableId("journal", kid.id, d, time, kind), type: "TRANSMISSION", occurredAt: at(d, time), userId: author.userId, sessionId: author.sessionId,
          entry: { kind, childIds: [kid.id], details, text, shared: true },
        });
      }
    }
  }
  const hina = kids.find((k) => k.firstName === "Hina");
  if (hina && weekday(TODAY) <= 5 && nowM > minutes("11:00")) {
    operations.push({ clientId: stableId("medicament", hina.id, TODAY), type: "TRANSMISSION", occurredAt: at(TODAY, "11:00"), userId: author.userId, sessionId: author.sessionId,
      entry: { kind: "MEDICAMENT", childIds: [hina.id], details: { medicine: "Paracétamol sirop", dose: "2,5 ml" }, text: "Donné sur ordonnance, comme convenu avec vous.", shared: true } });
  }
  const results = [];
  for (let i = 0; i < operations.length; i += 200) {
    results.push(...((await post("/kiosk/operations", { operations: operations.slice(i, i + 200) }, { auth: tabletToken }))?.results ?? []));
  }
  const known = results.filter((r) => r.outcome === "DEJA_RECUE").length;
  console.log(`    ${operations.length - known} transmissions${known ? ` (${known} déjà présentes)` : ""}`);
}

async function album(kids, staffIds) {
  step("Album : photos et vidéos des 8 dernières semaines");
  const author = await educatorSession(staffIds);
  const sections = ["Bébés", "Moyens", "Grands"];
  let photos = 0;
  let videos = 0;
  let n = 0;
  for (let back = 55; back >= 0; back--) {
    const d = addDays(TODAY, -back);
    if (weekday(d) > 5 || closed(d) || (d === TODAY && minutes(nowHm()) < minutes("10:30"))) continue;
    const section = sections[n % 3];
    const group = kids.filter((k) => k.section === section && k.photos && k.days.includes(weekday(d)) && k.entry <= d);
    if (group.length === 0) continue;
    const isVideo = weekday(d) === 5 && videos < 6;
    const [file, caption] = isVideo ? VIDEOS[videos % VIDEOS.length] : SCENES[n % SCENES.length];
    const time = ["09:45", "10:30", "15:15"][n % 3];
    n++;
    // Une opération de moins de 7 jours ; au-delà, elle est envoyée maintenant puis ramenée à son jour (correction).
    const recent = back <= 5;
    const op = {
      clientId: randomUUID(), type: "TRANSMISSION", occurredAt: recent ? at(d, time) : new Date(Date.now() - 3_600_000).toISOString(),
      userId: author.userId, sessionId: author.sessionId,
      entry: { kind: isVideo ? "VIDEO" : "PHOTO", childIds: group.slice(0, 6).map((k) => k.id), details: {}, text: caption, shared: true },
    };
    const form = new FormData();
    form.set("operation", new Blob([JSON.stringify(op)], { type: "application/json" }));
    form.set(isVideo ? "video" : "photo", new Blob([media(file)], { type: isVideo ? "video/mp4" : "image/jpeg" }), file);
    const result = await call("POST", `/kiosk/journal/${isVideo ? "video" : "photo"}`, undefined, { auth: tabletToken, form });
    if (result?.outcome && result.outcome !== "APPLIQUEE") {
      console.log(`    ${file} refusé : ${result.message ?? result.code}`);
      continue;
    }
    if (!recent) await put(`/journal/${op.clientId}`, { occurredAt: at(d, time), text: caption, details: {}, shared: true });
    if (isVideo) videos++; else photos++;
  }
  console.log(`    ${photos} photos, ${videos} vidéos`);
}

const NEWS = [
  ["Fête de fin d’année", "Spectacle des enfants le samedi 13 décembre à 10 h, suivi d’un goûter partagé. Chaque famille apporte un plat sucré ou salé !", "affiche-fete.jpg", 40],
  ["Semaine du goût", "Cette semaine, un fruit du pays à découvrir chaque jour : papaye, letchi, corossol, mangue et ananas.", "affiche-gout.jpg", 10],
  ["Journée pédagogique", "La crèche sera fermée le vendredi de la semaine prochaine : l’équipe se forme à l’accueil des émotions.", "affiche-pedagogique.jpg", 14],
];

async function portalAccess(kids, staffIds) {
  if (!PORTAL_URL) return [];
  step("Portail parents : familles invitées, actualités, messages, autorisation à signer");
  const status = await get("/portal");
  if (status.state !== "ACTIF") {
    await post("/portal/activate", { portalUrl: PORTAL_URL, activationCode: PORTAL_CODE });
  }
  await put("/portal/shares", {
    entries: true, photos: true, invoices: true, documents: true, messages: true, absences: true,
    paymentInfo: "Virement à Les P’tits Niaoulis (BCI), en précisant le numéro de la facture.", mealAbsenceCutoff: "09:00",
  });

  // Toutes les familles accueillies ont accès au portail.
  const emails = new Set();
  const families = new Set();
  for (const kid of kids) {
    if (families.has(kid.familyId)) continue;
    families.add(kid.familyId);
    const record = await get(`/children/${kid.id}`);
    for (const guardian of record.family.guardians) {
      if (!guardian.email) continue;
      await post(`/guardians/${guardian.id}/portal-access`, undefined, { expect: [200, 201, 204, 409, 422] });
      emails.add(guardian.email);
    }
  }

  for (const [title, body, file, days] of NEWS) {
    const form = new FormData();
    form.set("title", title);
    form.set("body", body);
    form.set("expiresOn", addDays(TODAY, days));
    form.set("file", new Blob([media(file)], { type: "image/jpeg" }), file);
    await call("POST", "/portal/news", undefined, { form });
  }
  await post("/messages/broadcast", { sectionId: null, body: "Bonjour à toutes les familles ! Le cahier de vie de la crèche est maintenant sur l’application : photos, repas, siestes… Bonne découverte !" });
  const sections = Object.fromEntries((await get("/sections")).map((s) => [s.name, s.id]));
  await post("/messages/broadcast", { sectionId: sections["Bébés"], body: "Section des Bébés : pensez à apporter une tenue de rechange marquée au nom de votre enfant, merci !" });

  const byName = Object.fromEntries(kids.map((k) => [k.firstName, k]));
  const messages = [
    ["Noa", "Bonjour, la sortie à la plage est prévue vendredi matin. Pensez au chapeau et à la crème solaire."],
    ["Lila", "Bonjour Kevin, Lila a un peu de fièvre depuis 15 h (38,2). Elle va bien, mais pouvez-vous venir un peu plus tôt ?"],
    ["Tom", "Bonjour Anne, Tom a fait un magnifique dessin pour sa petite sœur, il vous le montrera ce soir !"],
    ["Hina", "Bonjour Rémi, Hina a fait ses premiers pas ce matin, toute la section a applaudi !"],
    ["Gabin", "Bonjour Claire, il manque le certificat de vaccination de Gabin : pouvez-vous le déposer cette semaine ?"],
  ];
  for (const [name, body] of messages) {
    if (byName[name]) await post(`/families/${byName[name].familyId}/messages`, { body });
  }
  const noa = byName.Noa;
  if ((await get(`/portal/documents?familyId=${noa.familyId}`)).length === 0) {
    await post("/portal/documents/consent", { childId: noa.id, type: "SORTIES" });
  }
  void staffIds;
  return [...emails];
}

async function syncPortal(label) {
  // Une synchronisation planifiée peut déjà tourner : on laisse au portail jusqu'à quatre minutes.
  for (let i = 0; i < 160; i++) {
    const result = await post("/portal/sync");
    if ((await get("/portal")).pending === 0) return;
    if (!result?.sent) await new Promise((r) => setTimeout(r, 1500));
  }
  console.log(`    Synchronisation (${label}) incomplète : elle reprendra toute seule chaque minute.`);
}

// ------------------------------------------------------------------------------------- côté parents (Mailpit)

/** Connexion d'un parent comme dans l'application : code reçu par e-mail, lu dans Mailpit. */
async function parentSession(email) {
  const portal = `${PORTAL_URL}/api/v1`;
  const headers = { "X-Ticagou-Portail": "1" };
  await call("POST", "/auth/request", { email }, { auth: null, base: portal, headers });
  let code = null;
  for (let i = 0; i < 20 && !code; i++) {
    await new Promise((r) => setTimeout(r, 500));
    const found = await (await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}&limit=1`)).json();
    const id = found.messages?.[0]?.ID;
    if (!id) continue;
    const message = await (await fetch(`${MAILPIT}/api/v1/message/${id}`)).json();
    const match = /(\d{3}) (\d{3})/.exec(message.Text ?? "");
    if (match) code = match[1] + match[2];
  }
  if (!code) throw new Error(`code de connexion introuvable dans Mailpit pour ${email}`);
  const response = await fetch(`${portal}/auth/verify`, {
    method: "POST", headers: { "Content-Type": "application/json", ...headers }, body: JSON.stringify({ email, code }),
  });
  if (!response.ok) throw new Error(`connexion de ${email} refusée (${response.status})`);
  const cookie = response.headers.getSetCookie().map((c) => c.split(";")[0]).join("; ");
  const as = (method, path, body) => call(method, path, body, { auth: null, base: `${portal}/me`, headers: { ...headers, Cookie: cookie } });
  const me = await as("GET", "");
  // Une même adresse peut appartenir à plusieurs crèches : on prend la famille de l'enfant voulu.
  const familyOf = (childId) => me.families.find((f) => f.children.some((c) => c.id === childId)) ?? me.families[0];
  return { me, as, familyOf };
}

/** Ce que font les parents depuis leur téléphone : à traiter par la directrice pendant la démonstration. */
async function parentsAct(kids) {
  if (!PORTAL_URL || !MAILPIT) return;
  step("Côté parents : réponses, absence signalée, demande d’accueil, retard, autorisation signée");
  const byName = Object.fromEntries(kids.map((k) => [k.firstName, k]));
  const nextWeekday = (from, wanted) => { let d = addDays(from, 1); while (weekday(d) !== wanted) d = addDays(d, 1); return d; };
  try {
    const sarah = await parentSession(emailOf("Sarah", "Wahéo"));
    const fam = sarah.familyOf(byName.Noa.id);
    await sarah.as("POST", "/messages", { structureId: fam.structureId, familyId: fam.familyId, body: "Merci ! Noa a déjà son chapeau dans son sac 😊" });
    for (const doc of await sarah.as("GET", "/documents")) {
      if (doc.payload?.status === "A_SIGNER" || doc.status === "A_SIGNER") {
        await sarah.as("POST", `/documents/${doc.structureId ?? fam.structureId}/${doc.id}/sign`, { decision: "ACCEPTE", certified: true });
      }
    }
    if (weekday(TODAY) <= 5 && minutes(nowHm()) < minutes("16:00")) {
      await sarah.as("POST", "/late", { structureId: fam.structureId, childId: byName.Noa.id, at: "17:15", note: "Bouchons sur la Savexpress" }).catch(() => null);
    }
  } catch (e) {
    console.log(`    Sarah Wahéo : ${e.message}`);
  }
  try {
    const kevin = await parentSession(emailOf("Kevin", "Gope"));
    const fam = kevin.familyOf(byName.Lila.id);
    await kevin.as("POST", "/messages", { structureId: fam.structureId, familyId: fam.familyId, body: "Merci de m’avoir prévenu, j’arrive vers 16 h 15." });
    const from = nextWeekday(TODAY, 1);
    await kevin.as("POST", "/absences", { structureId: fam.structureId, childId: byName.Lila.id, fromDay: from, toDay: addDays(from, 1), reason: "Rendez-vous chez le pédiatre" });
    // Lila ne vient pas le mercredi : une demande d'accueil occasionnel.
    await kevin.as("POST", "/bookings", { structureId: fam.structureId, childId: byName.Lila.id, day: nextWeekday(addDays(TODAY, 2), 3), arrivesAt: "08:00", leavesAt: "16:00", note: "Ma nounou est en congé ce jour-là." });
  } catch (e) {
    console.log(`    Kevin Gope : ${e.message}`);
  }
  try {
    const anne = await parentSession(emailOf("Anne", "Poédi"));
    const fam = anne.familyOf(byName.Tom.id);
    await anne.as("POST", "/messages", { structureId: fam.structureId, familyId: fam.familyId, body: "Il est très fier de son dessin ! Merci à toute l’équipe." });
  } catch (e) {
    console.log(`    Anne Poédi : ${e.message}`);
  }
}

// ---------------------------------------------------------------------------------------------------- main

try {
  if (args.includes("--aujourdhui")) {
    const { kids, staffIds } = await reload();
    const sections = Object.fromEntries((await get("/sections")).map((s) => [s.name, s.id]));
    await pairTablet();
    await today(kids, sections, staffIds);
    if (PORTAL_URL) {
      await journal(kids, staffIds, true).catch((e) => console.log(`    ${e.message}`));
      await syncPortal("journée");
    }
    step("Journée de démonstration prête.");
    process.exit(0);
  }
  const started = Date.now();
  await install();
  await structureSheet();
  const sections = Object.fromEntries((await get("/sections")).map((s) => [s.name, s.id]));
  const staffIds = await team(sections);
  const funders = await billingSetup();
  const { kids, former } = await childrenAndFamilies(sections, funders);
  await attendanceHistory([...kids, ...former]);
  await staffHistory(staffIds, sections);
  await invoices(kids, former);
  await menus();
  await closeMealQuarters();
  await enrollments(sections);
  await documentsAndRules();
  const device = await pairTablet();
  await haccp(device, staffIds);
  await staffSpace(device, staffIds, sections);
  await today(kids, sections, staffIds);
  const emails = await portalAccess(kids, staffIds);
  if (PORTAL_URL) {
    await journal(kids, staffIds);
    await album(kids, staffIds);
    await syncPortal("contenu de la crèche");
    await parentsAct(kids);
    await syncPortal("réponses des parents");
  }
  const elapsed = Math.round((Date.now() - started) / 60_000);
  step(`Démonstration prête en ${elapsed} min. Direction : ${DIRECTOR.login} / ${DIRECTOR.password} (docs/commercial/demonstrations.md).`);
  if (emails.length) console.log(`    Parents (connexion par e-mail, code dans Mailpit) : ${emails.slice(0, 6).join(", ")}…`);
} catch (error) {
  console.error(`ÉCHEC : ${error.message}`);
  process.exit(1);
}

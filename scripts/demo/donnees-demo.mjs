#!/usr/bin/env node
/**
 * Jeu de démonstration de Ti'Cagou : remplit une installation VIERGE par l'API, comme le ferait la directrice.
 * Les mêmes règles que dans l'application s'appliquent (validation, facturation, numérotation, journal) ; rien
 * n'est écrit directement en base. Les dates sont relatives à aujourd'hui : une démonstration lancée en mars montre
 * les factures de janvier et février.
 *
 *   node scripts/demo/donnees-demo.mjs [--url http://localhost:8080]
 *   node scripts/demo/donnees-demo.mjs --aujourdhui     (démonstration déjà chargée : pointages et transmissions du jour)
 *   node scripts/demo/donnees-demo.mjs --portail http://localhost:8090 --code XXXX     (relie aussi le portail parents)
 *   node scripts/demo/donnees-demo.mjs --url http://creche-demo:8080 --jeton XXXX     (instance hébergée : jeton du lien de démarrage)
 *
 * Le script refuse une installation qui a déjà des enfants. Il n'est pas livré aux crèches : il sert au PC de
 * démonstration (scripts\tester-en-local.ps1 -Demo) et aux essais.
 *
 * Contenu : « Les P'tits Niaoulis » (Dumbéa), 3 sections, 24 enfants et leurs familles, personnes autorisées,
 * allergies, un PAI, régimes ; équipe de 5 personnes avec codes PIN ; barème au volume horaire, contrats mensualisés
 * et au réel ; financeurs (province Sud, CAFAT, employeur) ; 3 mois de présences ; factures des 2 mois passés,
 * envoyées, en partie payées, avec un avoir ; menus ; demandes d'inscription ; pointages et transmissions du jour.
 * Avec le portail : cinq familles invitées, leur journée (repas, biberons, sieste, change, médicament), un message et
 * une autorisation à signer.
 */
import { randomUUID } from "node:crypto";

const args = process.argv.slice(2);
const BASE = (args[args.indexOf("--url") + 1] && args.includes("--url") ? args[args.indexOf("--url") + 1] : "http://localhost:8080")
  .replace(/\/$/, "");
const ZONE = "Pacific/Noumea";
const option = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : null);
const PORTAL_URL = option("--portail");
const PORTAL_CODE = option("--code");
// Instance hébergée (creche.sh demo) : l'assistant de démarrage exige le jeton du lien de démarrage.
const SETUP_TOKEN = option("--jeton");

// Comptes de la démonstration (README, docs/TESTER-EN-LOCAL.md § 8).
const DIRECTOR = { login: "nathalie.kaloi", password: "Niaouli-2026!", pin: "2580" };
const TEAM = [
  { firstName: "Sandrine", lastName: "Wahnyamalla", role: "EDUCATRICE", pin: "4831", section: "Bébés", job: "Auxiliaire de puériculture" },
  { firstName: "Maëva", lastName: "Tiaou", role: "EDUCATRICE", pin: "2468", section: "Moyens", job: "Éducatrice de jeunes enfants" },
  { firstName: "Léa", lastName: "Kaouma", role: "EDUCATRICE", pin: "1357", section: "Grands", job: "Auxiliaire de puériculture" },
  { firstName: "Mereana", lastName: "Tehei", role: "EDUCATRICE", pin: "3691", section: "Moyens", job: "Animatrice petite enfance" },
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

/** Tirage reproductible : la même démonstration à chaque lancement. */
let seed = 20261103;
const random = () => ((seed = (seed * 1103515245 + 12345) % 2 ** 31) / 2 ** 31);
const jitter = (m, spread) => m + Math.round((random() - 0.5) * 2 * spread / 5) * 5;

// --------------------------------------------------------------------------------------------------- API

let token = null;

async function call(method, path, body, { auth = token, form = null, expect = [200, 201, 204] } = {}) {
  const headers = { Accept: "application/json", "X-Ticagou-Poste": "demo-script", "X-Ticagou-Poste-Nom": "Script%20de%20d%C3%A9monstration" };
  if (auth) headers.Authorization = `Bearer ${auth}`;
  if (SETUP_TOKEN && path === "/setup") headers["X-Ticagou-Setup"] = SETUP_TOKEN;
  if (body !== undefined && !form) headers["Content-Type"] = "application/json";
  const response = await fetch(`${BASE}/api/v1${path}`, { method, headers, body: form ?? (body === undefined ? undefined : JSON.stringify(body)) });
  const text = await response.text();
  const data = text ? JSON.parse(text) : null;
  if (!expect.includes(response.status)) {
    throw new Error(`${method} ${path} : ${response.status} ${data?.detail ?? text}${data?.errors ? " " + JSON.stringify(data.errors) : ""}`);
  }
  return data;
}
const get = (path, options) => call("GET", path, undefined, options);
const post = (path, body, options) => call("POST", path, body, options);
const put = (path, body, options) => call("PUT", path, body, options);

function step(message) {
  console.log(`==> ${message}`);
}

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

// -------------------------------------------------------------------------------------------------- données

const FAMILIES = [
  // nom, ville, parent, enfants [prénom, âge en mois, section, jours, arrivée, départ, mode]
  ["Wahéo", "Dumbéa", ["Sarah", "Mère"], [["Noa", 26, "Moyens", [1, 2, 3, 4, 5], "07:30", "16:30", "MENSUALISE"]]],
  ["Gope", "Nouméa", ["Kevin", "Père"], [["Lila", 14, "Bébés", [1, 2, 4, 5], "07:45", "16:00", "MENSUALISE"]]],
  ["Poédi", "Dumbéa", ["Anne", "Mère"], [["Tom", 34, "Grands", [1, 2, 3, 4, 5], "07:15", "17:00", "MENSUALISE"], ["Léna", 9, "Bébés", [1, 2, 3, 4, 5], "07:15", "17:00", "MENSUALISE"]]],
  ["Kaloï", "Païta", ["Marie", "Mère"], [["Kaïna", 22, "Moyens", [1, 3, 5], "08:00", "16:30", "REEL"]]],
  ["Taufana", "Dumbéa", ["Lisa", "Mère"], [["Léo", 31, "Grands", [1, 2, 3, 4, 5], "07:30", "15:30", "MENSUALISE"]]],
  ["Wamytan", "Nouméa", ["Jean-Marc", "Père"], [["Léa", 19, "Moyens", [2, 3, 4], "08:15", "16:45", "REEL"]]],
  ["Hnawia", "Dumbéa", ["Céline", "Mère"], [["Malo", 38, "Grands", [1, 2, 3, 4, 5], "07:30", "16:30", "MENSUALISE"]]],
  ["Tehei", "Mont-Dore", ["Vaimiti", "Mère"], [["Teva", 11, "Bébés", [1, 2, 3, 4, 5], "07:00", "15:45", "MENSUALISE"]]],
  ["Dupont", "Nouméa", ["Julie", "Mère"], [["Jules", 28, "Moyens", [1, 2, 4, 5], "08:00", "17:00", "MENSUALISE"]]],
  ["Wenehoua", "Dumbéa", ["Pascal", "Père"], [["Inaya", 33, "Grands", [1, 2, 3, 4, 5], "07:45", "16:15", "MENSUALISE"]]],
  ["Ounou", "Païta", ["Sylvie", "Mère"], [["Kalani", 16, "Bébés", [1, 2, 3], "07:30", "16:00", "REEL"]]],
  ["Martin", "Dumbéa", ["Claire", "Mère"], [["Gabin", 24, "Moyens", [1, 2, 3, 4, 5], "07:30", "16:30", "MENSUALISE"]]],
  ["Tiaou", "Nouméa", ["Hélène", "Mère"], [["Manon", 40, "Grands", [2, 3, 4, 5], "08:00", "16:00", "MENSUALISE"]]],
  ["Boearua", "Dumbéa", ["Rémi", "Père"], [["Hina", 7, "Bébés", [1, 2, 3, 4, 5], "07:30", "16:30", "MENSUALISE"]]],
  ["Lepeu", "Mont-Dore", ["Nadia", "Mère"], [["Raphaël", 29, "Moyens", [1, 3, 4, 5], "07:45", "16:45", "MENSUALISE"]]],
  ["Naisseline", "Dumbéa", ["Joël", "Père"], [["Ania", 36, "Grands", [1, 2, 3, 4, 5], "07:30", "16:00", "MENSUALISE"]]],
  ["Ihage", "Nouméa", ["Laure", "Mère"], [["Elio", 20, "Moyens", [1, 2, 5], "08:30", "16:30", "REEL"]]],
  ["Faura", "Dumbéa", ["Tania", "Mère"], [["Moana", 13, "Bébés", [1, 2, 3, 4, 5], "07:15", "16:15", "MENSUALISE"]]],
  ["Cawidrone", "Païta", ["Patrice", "Père"], [["Nolan", 42, "Grands", [1, 2, 3, 4], "07:30", "16:30", "MENSUALISE"]]],
  ["Brun", "Dumbéa", ["Émilie", "Mère"], [["Zoé", 25, "Moyens", [1, 2, 3, 4, 5], "07:45", "16:00", "MENSUALISE"], ["Lucas", 44, "Grands", [1, 2, 3, 4, 5], "07:45", "16:00", "MENSUALISE"]]],
  ["Waheo", "Nouméa", ["Mélissa", "Mère"], [["Kenzo", 10, "Bébés", [2, 3, 4], "08:00", "15:30", "REEL"]]],
];
const AUTHORIZED = [["Mamie", "Grand-mère"], ["Papi", "Grand-père"], ["Tatie", "Tante"], ["Tonton", "Oncle"], ["Voisine", "Amie de la famille"]];

/** Âge en mois → date de naissance (le 10 du mois). */
function birthDate(months) {
  const d = day(monthStart(TODAY, -months));
  return iso(new Date(d.getTime() + 9 * 86_400_000));
}

async function team(sections) {
  step("Équipe : direction et 5 personnes avec leur code PIN");
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
  return ids;
}

async function billingSetup() {
  step("Barème au volume horaire et financeurs");
  await post("/tariff-schedules", {
    label: `Barème ${TODAY.slice(0, 4)}`, criterion: "HEURES_SEMAINE", weeksPerYear: 47, registrationFee: 10_000, validFrom: `${Number(TODAY.slice(0, 4)) - 1}-01-01`,
    bands: [{ lowerBound: 0, hourlyRate: 620 }, { lowerBound: 20 * 60, hourlyRate: 560 }, { lowerBound: 35 * 60 + 15, hourlyRate: 510 }],
  });
  const funder = (name, category, calculation, amount, rate, cap, priority) => post("/billing/funders", {
    name, kind: "AIDE_FAMILLE", category, calculation, amount, rate, rateBase: "FRAIS_GARDE", monthlyCap: cap, priority,
    validFrom: "2024-01-01", validTo: null, conventionRef: `CONV-${priority}`, contact: null,
  });
  return {
    province: (await funder("Province Sud — aide à la garde", "PROVINCE", "POURCENTAGE", 0, 200, 30_000, 1)).id,
    cafat: (await funder("CAFAT — prestation d’accueil", "ORGANISME", "MONTANT_FIXE", 12_000, 0, null, 2)).id,
    employer: (await funder("Lagon Services (employeur)", "EMPLOYEUR", "MONTANT_FIXE", 10_000, 0, null, 3)).id,
  };
}

async function signedContract(childId, start, days, arrives, leaves, mode) {
  const created = await post("/contracts", {
    childId,
    contract: {
      startDate: start, endDate: addDays(monthStart(start, 12), -1), noticeMonths: 1,
      planning: { mode: "FIXE", days: days.map((d) => ({ dayOfWeek: d, arrivesAt: arrives, leavesAt: leaves })), billingMode: mode },
      adaptationBilling: "AU_TEMPS", adaptation: [],
    },
  });
  const form = new FormData();
  form.set("signedOn", TODAY);
  await call("POST", `/contracts/${created.id}/versions/0/sign`, undefined, { form });
}

async function childrenAndFamilies(sections, funders) {
  step("Enfants, familles, santé, autorisations, contrats");
  const contractStart = monthStart(TODAY, -3);
  const kids = [];
  let index = 0;
  for (const [lastName, city, [parent, relationship], children] of FAMILIES) {
    let familyId = null;
    for (const [firstName, months, section, days, arrives, leaves, mode] of children) {
      const child = { firstName, lastName, birthDate: birthDate(months), sectionId: sections[section], entryDate: addDays(contractStart, -60) };
      const created = await post("/children", familyId ? { child, familyId } : {
        child,
        family: {
          family: { label: `Famille ${lastName}`, city },
          guardian: {
            firstName: parent, lastName, relationship, legalGuardian: true, payer: true, mobilePhone: `7${index % 10} ${String(10 + index).padStart(2, "0")} 3${index % 10}`,
            email: `${parent.toLowerCase().replace(/[^a-z]/g, "")}.${lastName.toLowerCase().replace(/[^a-z]/g, "")}@exemple.nc`,
          },
        },
      });
      const record = await get(`/children/${created.id}`);
      familyId = record.family?.id ?? familyId;
      kids.push({ id: created.id, firstName, lastName, section, days, arrives, leaves, mode, familyLabel: `Famille ${lastName}` });

      const [first, link] = AUTHORIZED[index % AUTHORIZED.length];
      await post(`/children/${created.id}/authorized-persons`, { firstName: first, lastName, relationship: link, phone: "26 45 67", idVerified: true });
      await put(`/children/${created.id}/consents`, {
        consents: [
          { type: "PHOTOS", granted: index % 6 !== 3, details: null, signedOn: contractStart, signedBy: `${parent} ${lastName}` },
          { type: "SORTIES", granted: true, details: null, signedOn: contractStart, signedBy: `${parent} ${lastName}` },
          { type: "SOINS_URGENCE", granted: true, details: null, signedOn: contractStart, signedBy: `${parent} ${lastName}` },
        ],
      });
      await signedContract(created.id, contractStart, days, arrives, leaves, mode);
      index++;
    }
  }

  // Santé : allergies, PAI, régimes.
  const byName = Object.fromEntries(kids.map((k) => [k.firstName, k]));
  const vegetarian = (await get("/diets")).find((d) => /végétarien/i.test(d.label))
    ?? await post("/diets", { label: "Végétarien", excludes: ["viande", "poisson"], displayOrder: 1 });
  const health = (overrides) => ({
    diet: null, dietDetails: null, paiActive: false, paiReason: null, paiValidFrom: null, paiValidUntil: null, paiKitLocation: null,
    paiKitDetails: null, paiDocumentId: null, emergencyProtocol: null, doctorName: "Dr Ihage", doctorPhone: "26 33 44", doctorAddress: "Dumbéa-sur-Mer",
    instructions: null, vaccinationCheckedOn: contractStart, allergies: [], vaccinations: [{ vaccine: "DTCaP", lastDoseOn: addDays(contractStart, -200) }],
    dietId: null, mealTexture: "NORMALE", packedLunch: false, ...overrides,
  });
  await put(`/children/${byName.Noa.id}/health`, health({
    allergies: [{ allergen: "Arachide", reaction: "Œdème, gêne respiratoire", severity: "SEVERE" }, { allergen: "Kiwi", reaction: "Rougeurs", severity: "LEGERE" }],
    paiActive: true, paiReason: "Allergie à l’arachide", paiValidFrom: contractStart, paiValidUntil: addDays(contractStart, 365),
    paiKitLocation: "Armoire à pharmacie, section Moyens", paiKitDetails: "Adrénaline auto-injectable, antihistaminique",
    emergencyProtocol: "Injecter l’adrénaline, appeler le 15, prévenir les parents.",
  }));
  await put(`/children/${byName.Léo.id}/health`, health({ dietId: vegetarian.id, diet: "Végétarien" }));
  await put(`/children/${byName.Kaïna.id}/health`, health({ mealTexture: "MIXEE" }));
  await put(`/children/${byName.Malo.id}/health`, health({ allergies: [{ allergen: "Lait de vache", reaction: "Troubles digestifs", severity: "MODEREE" }] }));
  await put(`/children/${byName.Inaya.id}/health`, health({ allergies: [{ allergen: "Œuf", reaction: "Urticaire", severity: "MODEREE" }] }));

  // Aides : province Sud pour quatre familles, CAFAT et employeur.
  const grant = (child, funderId, beneficiary) => post(`/children/${child.id}/fundings`, { funderId, fromDay: contractStart, beneficiary, fileNumber: `A-${child.firstName.toUpperCase()}` });
  for (const name of ["Noa", "Lila", "Kaïna", "Teva"]) await grant(byName[name], funders.province, "Famille");
  for (const name of ["Tom", "Malo"]) await grant(byName[name], funders.cafat, "Famille");
  await grant(byName.Léo, funders.employer, "Lisa Taufana");
  return kids;
}

async function attendanceHistory(kids, sections) {
  step("Présences des trois derniers mois (un peu de temps de chargement)");
  const reasons = await get("/billing/absence-reasons");
  const illness = reasons.find((r) => /maladie/i.test(r.label)) ?? reasons[0];
  const holidays = reasons.find((r) => /cong|vacanc|famil/i.test(r.label)) ?? reasons[0];
  const start = monthStart(TODAY, -3);
  let count = 0;
  for (const kid of kids) {
    // Une semaine d'absence (congés) pour un enfant sur quatre, deux jours de maladie pour un sur cinq.
    const absent = new Set();
    if (kids.indexOf(kid) % 4 === 0) {
      const from = addDays(monthStart(TODAY, -2), 7);
      for (let i = 0; i < 5; i++) absent.add(addDays(from, i));
      await post(`/children/${kid.id}/absences`, { fromDay: from, toDay: addDays(from, 4), reasonId: holidays.id, notifiedOn: addDays(from, -10), documentId: null, note: "Vacances en famille" });
    }
    if (kids.indexOf(kid) % 5 === 2) {
      const from = addDays(monthStart(TODAY, -1), 3 + (kids.indexOf(kid) % 3));
      absent.add(from);
      absent.add(addDays(from, 1));
      await post(`/children/${kid.id}/absences`, { fromDay: from, toDay: addDays(from, 1), reasonId: illness.id, notifiedOn: from, documentId: null, note: null });
    }
    for (let d = start; d < TODAY; d = addDays(d, 1)) {
      if (!kid.days.includes(weekday(d)) || absent.has(d)) continue;
      const arrives = hm(jitter(minutes(kid.arrives), 15));
      const leaves = hm(jitter(minutes(kid.leaves), 20));
      await post("/attendance/children/records", {
        childId: kid.id, arrivedAt: at(d, arrives), departedAt: at(d, leaves), pickedUpBy: "Parent", reason: "Reprise du cahier de présence",
      });
      count++;
    }
  }
  console.log(`    ${count} passages enregistrés`);
}

async function invoices(kids) {
  step("Factures des deux mois passés, envoi, encaissements, un avoir");
  for (const offset of [-2, -1]) {
    const month = yearMonth(monthStart(TODAY, offset));
    await post(`/billing/months/${month}/generate`, {});
    const list = await get(`/billing/invoices?month=${month}`);
    let i = 0;
    for (const invoice of list) {
      // Les factures sont émises aujourd'hui (la génération date du jour) : envoi et encaissements aussi.
      await post(`/billing/invoices/${invoice.id}/sent`, { sentOn: TODAY, channel: "Courriel" });
      const detail = await get(`/billing/invoices/${invoice.id}`);
      const due = detail.familyTotal ?? invoice.familyTotal ?? 0;
      const paidOn = TODAY;
      // Mois le plus ancien : presque tout est payé ; mois dernier : la moitié, dont un paiement partiel.
      const pays = offset === -2 ? i % 7 !== 3 : i % 2 === 0;
      if (pays && due > 0) {
        const partial = offset === -1 && i === 2;
        await post("/billing/payments", {
          invoiceId: invoice.id, familyId: null, funderRequestId: null, paidOn,
          amount: partial ? Math.floor(due / 2) : due, method: i % 3 === 1 ? "CHEQUE" : "VIREMENT",
          reference: i % 3 === 1 ? `CHQ ${4_100_000 + i}` : `VIR-${month}-${i}`, bank: i % 3 === 1 ? "BCI" : null,
        });
      }
      i++;
    }
    if (offset === -1 && list.length > 5) {
      // Facture non payée (rang impair) : l'avoir réduit son reste dû.
      await post(`/billing/invoices/${list[5].id}/credit-notes`, {
        reason: "Fermeture exceptionnelle (journée pédagogique)", full: false, lines: [{ label: "Journée pédagogique non facturée", amount: 2_500 }],
      });
    }
  }
}

async function menus() {
  step("Menus de la semaine et de la suivante");
  const week = [
    ["Salade de carottes", "Poulet coco, riz", "Yaourt nature", "Compote de pomme"],
    ["Concombre vinaigrette", "Poisson du lagon, purée de patate douce", "Fromage blanc", "Banane"],
    ["Taboulé", "Bougna de légumes", "Petit-suisse", "Pain et chocolat"],
    ["Avocat", "Bœuf aux légumes, pâtes", "Emmental", "Papaye"],
    ["Salade de tomates", "Omelette, haricots verts", "Yaourt aux fruits", "Gâteau maison"],
  ];
  for (const start of [monday(TODAY), addDays(monday(TODAY), 7)]) {
    const items = week.flatMap(([starter, main, dairy, snack], i) => [
      { dayOfWeek: i + 1, course: "ENTREE", dietId: null, label: starter, tags: [] },
      { dayOfWeek: i + 1, course: "PLAT", dietId: null, label: main, tags: [] },
      { dayOfWeek: i + 1, course: "LAITAGE", dietId: null, label: dairy, tags: [] },
      { dayOfWeek: i + 1, course: "GOUTER", dietId: null, label: snack, tags: [] },
    ]);
    await put(`/menus/${start}`, { status: "PUBLIE", items });
  }
}

async function enrollments(sections) {
  step("Demandes d’inscription à toutes les étapes");
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
}

let tabletToken = null;

async function today(kids, sections, staffIds) {
  step("Aujourd’hui : tablette d’accueil, pointages, transmissions");
  // Une seule tablette de démonstration : celle d'un lancement précédent est retirée (limite de la licence).
  for (const old of (await get("/devices")).filter((d) => d.name === "Accueil — démonstration")) {
    await call("DELETE", `/devices/${old.id}`);
  }
  const created = await post("/devices", { name: "Accueil — démonstration" });
  const paired = await post("/devices/pair", { code: created.code }, { auth: null });
  const device = (await post("/devices/session", { deviceId: paired.deviceId, secret: paired.secret }, { auth: null })).accessToken;
  tabletToken = device;
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
    if (!arrival?.id) continue;
    present.push(kid);
  }
  if (present.length === 0) return;
  const educ = TEAM[1];
  const pin = await post("/auth/pin", { userId: staffIds[educ.firstName], pin: educ.pin }, { auth: device });
  const op = (kind, children, details, text) => ({
    clientId: randomUUID(), type: "TRANSMISSION", occurredAt: new Date().toISOString(), userId: staffIds[educ.firstName], sessionId: pin.sessionId,
    entry: { kind, childIds: children.map((k) => k.id), details, text, shared: true },
  });
  const ops = [
    op("ACTIVITE", present.slice(0, 6), {}, "Peinture avec les mains et chansons du matin."),
    op("REPAS", present.slice(0, 3), { meal: "COLLATION", quantity: "TOUT" }, ""),
    op("MOT", present.slice(0, 1), {}, "Très joyeux ce matin, a beaucoup joué avec ses copains."),
  ];
  await post("/kiosk/operations", { operations: ops }, { auth: device });
  console.log(`    ${present.length} enfants arrivés, ${ops.length} transmissions`);
}

// ---------------------------------------------------------------------------------------------- portail parents

/** Familles qui ont accès au portail ; les autres restent à inviter, pour le montrer pendant une démonstration. */
const PORTAL_FAMILIES = ["Wahéo", "Gope", "Poédi", "Martin", "Boearua"];

/** Journée type d'un enfant, à des heures passées : le dernier jour ouvré si la démonstration tourne un week-end. */
function dayOf(kid, baby) {
  const entries = [
    ["09:15", "REPAS", { meal: "COLLATION", quantity: "TOUT" }, ""],
    ["10:00", "ACTIVITE", {}, "Peinture avec les mains et chansons du matin."],
    ["10:40", "CHANGE", { change: "MOUILLEE" }, ""],
    ["11:30", "REPAS", { meal: "DEJEUNER", quantity: "BIEN" }, "Riz, poisson et courgettes."],
    ["12:30", "SIESTE", { from: "12:30", to: "14:10" }, ""],
    ["15:00", "REPAS", { meal: "GOUTER", quantity: "TOUT" }, ""],
    ["15:30", "MOT", {}, `${kid.firstName} a passé une très belle journée.`],
  ];
  if (baby) {
    entries.push(["08:45", "REPAS", { meal: "BIBERON", quantity: "TOUT", volumeMl: "150" }, ""]);
    entries.push(["14:30", "REPAS", { meal: "BIBERON", quantity: "BIEN", volumeMl: "120" }, ""]);
  }
  return entries.sort(([a], [b]) => a.localeCompare(b));
}

async function portalAccess(kids, staffIds) {
  if (!PORTAL_URL) return;
  step("Portail parents : familles invitées, journée, message, autorisation à signer");
  const status = await get("/portal");
  if (status.state !== "ACTIF") {
    await post("/portal/activate", { portalUrl: PORTAL_URL, activationCode: PORTAL_CODE });
  }
  await put("/portal/shares", {
    entries: true, photos: true, invoices: true, documents: true, messages: true, absences: true,
    paymentInfo: "Virement à Les P’tits Niaoulis, en précisant le numéro de la facture.", mealAbsenceCutoff: "09:00",
  });

  const invited = kids.filter((k) => PORTAL_FAMILIES.includes(k.lastName));
  const emails = new Set();
  for (const kid of invited) {
    const record = await get(`/children/${kid.id}`);
    kid.familyId = record.family.id;
    for (const guardian of record.family.guardians) {
      await post(`/guardians/${guardian.id}/portal-access`);
      emails.add(guardian.email);
    }
  }

  // Dernier jour ouvré où l'enfant vient : la journée se montre aussi le week-end.
  const workday = weekday(TODAY) > 5 ? addDays(TODAY, 5 - weekday(TODAY)) : TODAY;
  const already = await get(`/journal?day=${workday}`);
  if (tabletToken && !already.some((e) => e.kind === "SIESTE")) {
    const educ = TEAM[1];
    const pin = await post("/auth/pin", { userId: staffIds[educ.firstName], pin: educ.pin }, { auth: tabletToken });
    const now = workday === TODAY ? minutes(nowHm()) : 24 * 60;
    const operations = [];
    for (const kid of invited) {
      const entries = dayOf(kid, kid.section === "Bébés");
      if (kid.firstName === "Hina") entries.push(["11:00", "MEDICAMENT", { medicine: "Paracétamol sirop", dose: "2,5 ml" }, "Donné sur ordonnance, comme convenu avec vous."]);
      for (const [time, kind, details, text] of entries) {
        if (minutes(time) > now) continue;
        operations.push({
          clientId: randomUUID(), type: "TRANSMISSION", occurredAt: at(workday, time), userId: staffIds[educ.firstName], sessionId: pin.sessionId,
          entry: { kind, childIds: [kid.id], details, text, shared: true },
        });
      }
    }
    await post("/kiosk/operations", { operations }, { auth: tabletToken });
    console.log(`    ${operations.length} transmissions partagées`);
  }

  const noa = invited.find((k) => k.firstName === "Noa");
  await post(`/families/${noa.familyId}/messages`, { body: "Bonjour, la sortie à la plage est prévue vendredi matin. Pensez au chapeau et à la crème solaire." });
  if ((await get(`/portal/documents?familyId=${noa.familyId}`)).length === 0) {
    await post("/portal/documents/consent", { childId: noa.id, type: "SORTIES" });
  }

  for (let i = 0; i < 12; i++) {
    await post("/portal/sync");
    if ((await get("/portal")).pending === 0) break;
  }
  console.log(`    Parents : ${[...emails].join(", ")} (connexion par e-mail, lien dans Mailpit)`);
}

// ---------------------------------------------------------------------------------------------------- main

try {
  if (args.includes("--aujourdhui")) {
    const { kids, staffIds } = await reload();
    const sections = Object.fromEntries((await get("/sections")).map((s) => [s.name, s.id]));
    await today(kids, sections, staffIds);
    await portalAccess(kids, staffIds);
    step("Journée de démonstration prête.");
    process.exit(0);
  }
  await install();
  const sections = Object.fromEntries((await get("/sections")).map((s) => [s.name, s.id]));
  const staffIds = await team(sections);
  const funders = await billingSetup();
  const kids = await childrenAndFamilies(sections, funders);
  await attendanceHistory(kids, sections);
  await invoices(kids);
  await menus();
  await enrollments(sections);
  await today(kids, sections, staffIds);
  await portalAccess(kids, staffIds);
  step("Démonstration prête : connectez-vous avec le compte de direction (README, docs/TESTER-EN-LOCAL.md § 8).");
} catch (error) {
  console.error(`ÉCHEC : ${error.message}`);
  process.exit(1);
}

#!/usr/bin/env node
/**
 * Prépare la démonstration pour les captures : un enfant allergique pointé arrivé, et un départ bloqué en attente de la
 * direction (maquette B5), demandé par une tablette « Accueil ». Données de démonstration seulement.
 *
 *   node scripts/captures/scenario-depart.mjs [--url http://localhost:8093]
 */
const args = process.argv.slice(2);
const BASE = (args.includes("--url") ? args[args.indexOf("--url") + 1] : "http://localhost:8093").replace(/\/$/, "") + "/api/v1";
const POSTE = { "X-Ticagou-Poste": "captures-ticagou-demo", "X-Ticagou-Poste-Nom": "Captures" };

async function call(path, { method = "GET", body, token } = {}) {
  const response = await fetch(BASE + path, {
    method,
    headers: { Accept: "application/json", "Content-Type": "application/json", ...POSTE, ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`${method} ${path} : ${response.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

const director = (await call("/auth/login", { method: "POST", body: { login: "nathalie.kaloi", password: "Niaouli-2026!" } })).accessToken;
const children = await call("/children", { token: director });
const list = Array.isArray(children) ? children : children.content ?? children.children ?? [];
const child = list.find((c) => c.health?.allergies?.length > 0) ?? list[0];

const created = await call("/devices", { method: "POST", token: director, body: { name: "Accueil" } });
const paired = await call("/devices/pair", { method: "POST", body: { code: created.code } });
const device = (await call("/devices/session", { method: "POST", body: { deviceId: paired.deviceId, secret: paired.secret } })).accessToken;

await call(`/attendance/children/${child.id}/arrival`, { method: "POST", token: director, body: { droppedOffBy: "Mère" } }).catch(() => {});
const day = await call("/attendance", { token: director });
const record = day.children.find((r) => r.childId === child.id && !r.departedAt);
const request = await call("/kiosk/departure-requests", { method: "POST", token: device, body: {
  attendanceId: record.id, personName: "Joseph " + child.lastName, relationship: "Oncle", idChecked: true, requestedBy: "Sandrine W.",
} });
console.log(`Départ à valider : ${child.firstName} ${child.lastName} (demande ${request.id})`);

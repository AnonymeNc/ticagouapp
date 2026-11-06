/**
 * Captures d'écran réelles de la démonstration, pour les maquettes et les prospectus.
 * Prérequis : scripts\tester-en-local.ps1 -Demo lancé (Chrome installé sur ce poste).
 *
 *   pnpm captures
 *
 * La formule Complet est capturée d'abord, puis Essentiel, en changeant l'abonnement de la base de démonstration. La
 * formule Complet est remise à la fin ; si le portail a été effacé entre-temps, relancez la démonstration.
 */
import { execFileSync } from "node:child_process";
import { mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer-core";

const here = dirname(fileURLToPath(import.meta.url));
const OUT = join(here, "..", "..", "docs", "commercial", "captures");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";
const BUREAU = "http://localhost:1420";
const PARENTS = "http://localhost:5180";
const MAILPIT = "http://localhost:8025";
const API = "http://localhost:8080/api/v1";
const PARENT = "anne.podi@exemple.nc";
const BEBE = "rmi.boearua@exemple.nc";
const MODULES = { Essentiel: "", Complet: "INSCRIPTIONS,FACTURATION,COMPTABILITE,PERSONNEL,REPAS,PORTAIL_PARENTS" };

mkdirSync(OUT, { recursive: true });
const pause = (ms) => new Promise((r) => setTimeout(r, ms));

/** Horloge de la page à 15:40 à Nouméa : une capture faite le soir montrerait une crèche à minuit. */
async function afternoon(page) {
  const day = new Intl.DateTimeFormat("en-CA", { timeZone: "Pacific/Noumea" }).format(new Date());
  const delta = Date.parse(`${day}T15:40:00+11:00`) - Date.now();
  await page.evaluateOnNewDocument((shift) => {
    const Real = Date;
    globalThis.Date = class extends Real {
      constructor(...args) {
        if (args.length) super(...args);
        else super(Real.now() + shift);
      }
      static now() {
        return Real.now() + shift;
      }
    };
  }, delta);
}

function formule(name) {
  execFileSync("docker", ["exec", "ticagou-web-postgres", "psql", "-U", "ticagou", "-d", "ticagou_demo", "-c",
    `UPDATE subscription SET modules = '${MODULES[name]}', plan = '${name} (demonstration)'`], { stdio: "ignore" });
}

async function shot(page, name, wait = 2500) {
  await pause(wait);
  await page.screenshot({ path: join(OUT, `${name}.jpg`), type: "jpeg", quality: 88 });
  console.log(`  ${name}.jpg`);
}

async function login(page) {
  await page.goto(`${BUREAU}/login`, { waitUntil: "domcontentloaded" });
  await page.waitForSelector('input[autocomplete="username"]');
  await page.type('input[autocomplete="username"]', "nathalie.kaloi");
  await page.type('input[autocomplete="current-password"]', "Niaouli-2026!");
  await page.keyboard.press("Enter");
  await page.waitForFunction(() => /Bonjour/.test(document.body.innerText), { timeout: 20000 });
}

/** Code de connexion envoyé au parent, lu dans Mailpit. */
async function parentLink(page, email) {
  await fetch(`${MAILPIT}/api/v1/messages`, { method: "DELETE" });
  await page.goto(PARENTS, { waitUntil: "domcontentloaded" });
  await page.waitForSelector('input[type="email"]');
  await page.type('input[type="email"]', email);
  await page.keyboard.press("Enter");
  for (let i = 0; i < 20; i++) {
    await pause(500);
    const { messages } = await (await fetch(`${MAILPIT}/api/v1/messages`)).json();
    if (messages?.length) {
      const message = await (await fetch(`${MAILPIT}/api/v1/message/${messages[0].ID}`)).json();
      const link = /https?:\/\/\S+/.exec(message.Text)?.[0];
      if (link) return link;
    }
  }
  throw new Error(`Aucun e-mail pour ${email}`);
}

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ["--force-prefers-reduced-motion"] });
try {
  // Un onglet par écran : le flux temps réel d'une page garde sa connexion, et Chrome en limite le nombre par site.
  const screen = async (path, name) => {
    const tab = await browser.newPage();
    await afternoon(tab);
    await tab.setViewport({ width: 1440, height: 960, deviceScaleFactor: 1.5 });
    await tab.goto(`${BUREAU}${path}`, { waitUntil: "domcontentloaded" });
    await shot(tab, name);
    await tab.close();
  };
  // Chaque lancement est un nouveau navigateur et une nouvelle tablette, comptés par l'abonnement.
  const sql = "DELETE FROM desktop_client; UPDATE device SET revoked_at = now() WHERE name = 'Captures' AND revoked_at IS NULL";
  execFileSync("docker", ["exec", "ticagou-web-postgres", "psql", "-U", "ticagou", "-d", "ticagou_demo", "-c", sql], { stdio: "ignore" });
  formule("Complet");
  const desk = await browser.newPage();
  await login(desk);
  await desk.close();
  for (const [path, name] of [["/", "tableau-de-bord"], ["/presences", "presences"], ["/facturation", "facturation"], ["/statistiques", "statistiques"], ["/parametres", "parametres-complet"]]) {
    await screen(path, name);
  }

  // Tablette : un code d'appairage pris par l'API, comme le QR code.
  const session = await (await fetch(`${API}/auth/login`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ login: "nathalie.kaloi", password: "Niaouli-2026!" }) })).json();
  const device = await (await fetch(`${API}/devices`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${session.accessToken}` }, body: JSON.stringify({ name: "Captures" }) })).json();
  const tablet = await (await browser.createBrowserContext()).newPage();
  await afternoon(tablet);
  await tablet.setViewport({ width: 1280, height: 860, deviceScaleFactor: 1.5 });
  await tablet.goto(`${BUREAU}/kiosk?pair=${device.code}`, { waitUntil: "domcontentloaded" });
  await tablet.waitForFunction(() => /Maëva/.test(document.body.innerText), { timeout: 20000 });
  await tablet.evaluate(() => [...document.querySelectorAll("button")].find((b) => /Maëva/.test(b.textContent))?.click());
  await pause(500);
  for (const digit of "2468") {
    await tablet.evaluate((d) => [...document.querySelectorAll("button")].find((b) => b.textContent.trim() === d)?.click(), digit);
    await pause(120);
  }
  await pause(1500);
  await shot(tablet, "tablette");

  // Application des parents : deux familles, une journée de grand et une journée de bébé.
  for (const [email, name] of [[PARENT, "app-parents"], [BEBE, "app-parents-bebe"]]) {
    const phone = await (await browser.createBrowserContext()).newPage();
    await afternoon(phone);
    await phone.setViewport({ width: 390, height: 844, deviceScaleFactor: 2.5 });
    // Le jeton est après le # : sans passer par une page vide, le navigateur ne recharge pas l'application.
    const link = await parentLink(phone, email);
    await phone.goto("about:blank");
    await phone.goto(link, { waitUntil: "domcontentloaded" });
    await pause(3000);
    // La journée du bébé : le fil, là où se lisent le biberon et le médicament.
    if (email === BEBE) {
      await phone.evaluate(() => [...document.querySelectorAll("h3")].find((h) => /Médicament/.test(h.textContent))?.scrollIntoView({ block: "center" }));
    }
    await shot(phone, name, 800);
  }

  // En dernier : sans le module, le portail de la démonstration est effacé à la synchronisation suivante.
  formule("Essentiel");
  await screen("/", "essentiel-tableau-de-bord");
  await screen("/parametres", "parametres-essentiel");
} finally {
  formule("Complet");
  await browser.close();
}

#!/usr/bin/env node
/**
 * Captures d'écran réelles de Ti'Cagou, à partir des données de démonstration (scripts/demo/donnees-demo.mjs), aux
 * tailles téléphone, tablette et bureau. Sans dépendance : Chrome ou Edge sans fenêtre, piloté par le protocole
 * DevTools (WebSocket de Node). Sert aux captures avant / après des phases et aux supports (docs/commercial/captures).
 *
 *   node scripts/captures/captures.mjs --url http://localhost:1430 --sortie docs/commercial/captures/web \
 *        [--pages dashboard,presences] [--tailles telephone,tablette,bureau] [--prefixe avant-]
 *
 * Connexion avec le compte de démonstration de la directrice. Les pages sont définies dans PAGES ci-dessous.
 */
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const args = process.argv.slice(2);
const option = (name, fallback = null) => (args.includes(name) ? args[args.indexOf(name) + 1] : fallback);
const BASE = option("--url", "http://localhost:1430").replace(/\/$/, "");
const OUT = resolve(option("--sortie", "docs/commercial/captures/web"));
const PREFIX = option("--prefixe", "");
const LOGIN = { login: "nathalie.kaloi", password: "Niaouli-2026!" };

export const SIZES = {
  telephone: { width: 390, height: 844, scale: 3, mobile: true },
  tablette: { width: 1180, height: 820, scale: 2, mobile: true },
  "tablette-portrait": { width: 820, height: 1180, scale: 2, mobile: true },
  bureau: { width: 1440, height: 900, scale: 1, mobile: false },
  // Formats des stores (docs/commercial/stores/fiches-stores.md).
  "store-iphone": { width: 440, height: 956, scale: 3, mobile: true },          // 1320 × 2868 (iPhone 6,9 po)
  "store-android": { width: 360, height: 780, scale: 3, mobile: true },         // 1080 × 2340
  "store-ipad": { width: 1032, height: 1376, scale: 2, mobile: true },          // 2064 × 2752 (iPad 13 po)
  "store-tablette-android": { width: 1280, height: 800, scale: 2, mobile: true }, // 2560 × 1600 (10 po)
};

/** Pages capturées : chemin, et éventuellement un sélecteur à attendre avant la capture. */
export const PAGES = {
  "tableau-de-bord": { path: "/", wait: "main h1" },
  presences: { path: "/presences", wait: "main h1" },
  enfants: { path: "/enfants", wait: "main h1" },
  "fiche-enfant": { path: "/enfants", wait: "main h1", click: "main tbody a[href^='/enfants/']" },
  facturation: { path: "/facturation", wait: "main h1" },
  personnel: { path: "/personnel", wait: "main h1" },
  repas: { path: "/repas", wait: "main h1" },
  messages: { path: "/messages", wait: "main h1" },
  parametres: { path: "/parametres", wait: "main h1" },
  plus: { path: "/plus", wait: "main h1", only: ["telephone", "store-iphone", "store-android"] },
  // Fiche d’un enfant allergique : le bandeau santé en tête.
  "fiche-sante": { path: "/enfants", wait: "main h1",
    run: "[...document.querySelectorAll(\"main tbody tr\")].find((tr) => /Allerg|PAI/.test(tr.textContent))?.querySelector(\"a\")?.click()" },
  // Départ bloqué à valider (préparé par scenario-depart.mjs).
  "depart-a-valider": { path: "/presences", wait: "main h1",
    run: "[...document.querySelectorAll(\"main button\")].find((b) => b.textContent.includes(\"Départ à valider\"))?.click()" },
  notifications: { path: "/notifications", wait: "main h1" },
  // Application Direction simulée (config « web-app ») : écrans d’avant la connexion.
  "app-bienvenue": { path: "/creche", wait: "main h1" },
  "app-code": { path: "/creche", wait: "main h1",
    run: "[...document.querySelectorAll(\"button\")].find((b) => b.textContent.includes(\"Taper le code\"))?.click()" },
};

const BROWSERS = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "/usr/bin/google-chrome",
  "/usr/bin/chromium",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function launch() {
  const executable = BROWSERS.find((b) => existsSync(b));
  if (!executable) throw new Error("Chrome ou Edge introuvable.");
  const profile = mkdtempSync(join(tmpdir(), "ticagou-captures-"));
  const port = 9300 + Math.floor(Math.random() * 500);
  const child = spawn(executable, [`--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, "--headless=new",
    "--no-first-run", "--no-default-browser-check", "--hide-scrollbars", "--lang=fr-FR", "about:blank"], { stdio: "ignore" });
  for (let i = 0; i < 50; i++) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
      const page = targets.find((t) => t.type === "page");
      if (page) return { child, profile, ws: page.webSocketDebuggerUrl };
    } catch {
      // Navigateur pas encore prêt.
    }
    await sleep(200);
  }
  throw new Error("Le navigateur ne répond pas.");
}

function connect(url) {
  const socket = new WebSocket(url);
  let id = 0;
  const pending = new Map();
  const listeners = new Set();
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.id && pending.has(message.id)) {
      const { resolve: ok, reject } = pending.get(message.id);
      pending.delete(message.id);
      if (message.error) reject(new Error(message.error.message));
      else ok(message.result);
    } else if (message.method) {
      listeners.forEach((l) => l(message));
    }
  });
  const ready = new Promise((ok) => socket.addEventListener("open", ok));
  return {
    ready,
    send: (method, params = {}) => new Promise((ok, reject) => {
      const n = ++id;
      pending.set(n, { resolve: ok, reject });
      socket.send(JSON.stringify({ id: n, method, params }));
    }),
    on: (listener) => listeners.add(listener),
    close: () => socket.close(),
  };
}

async function evaluate(cdp, expression) {
  const result = await cdp.send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
  return result.result.value;
}

async function waitFor(cdp, selector, timeout = 15000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    if (await evaluate(cdp, `!!document.querySelector(${JSON.stringify(selector)})`)) return true;
    await sleep(200);
  }
  return false;
}

async function navigate(cdp, url) {
  await cdp.send("Page.navigate", { url });
  await sleep(400);
  await waitFor(cdp, "body");
}

async function login(cdp) {
  await navigate(cdp, `${BASE}/login`);
  await waitFor(cdp, "input[type=password]", 5000);
  // Session déjà ouverte (taille précédente) : la page de connexion renvoie au tableau de bord.
  if ((await evaluate(cdp, "location.pathname")) !== "/login") return;
  // Saisie par les événements natifs : React Hook Form lit la valeur dans l'événement input.
  await evaluate(cdp, `(() => {
    const set = (el, v) => { const d = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value"); d.set.call(el, v);
      el.dispatchEvent(new Event("input", { bubbles: true })); };
    // Toujours le même poste (ADR 0011) : les captures n’occupent qu’une place de PC.
    localStorage.setItem("ticagou.poste", "captures-ticagou-demo");
    const inputs = document.querySelectorAll("input");
    set(inputs[0], ${JSON.stringify(LOGIN.login)});
    set(document.querySelector("input[type=password]"), ${JSON.stringify(LOGIN.password)});
    document.querySelector("form").requestSubmit();
  })()`);
  // La page de connexion a aussi un titre : on attend de l’avoir quittée.
  const start = Date.now();
  while (Date.now() - start < 15000) {
    if ((await evaluate(cdp, "location.pathname")) !== "/login") return;
    await sleep(200);
  }
  throw new Error("Connexion impossible avec le compte de démonstration : " + (await evaluate(cdp, "document.body.innerText.slice(0, 300)")));
}

async function capture(cdp, file, size) {
  await sleep(900);
  const shot = await cdp.send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
  writeFileSync(file, Buffer.from(shot.data, "base64"));
}

async function main() {
  // --chemins /a,/b : pages supplémentaires sans réglage (contrôle des débordements).
  const pageName = (path) => path.split("?")[0].split("/").filter(Boolean).join("-") || "accueil";
  const extra = (option("--chemins") ?? "").split(",").filter(Boolean);
  for (const path of extra) PAGES[pageName(path)] = { path, wait: "main h1" };
  const pages = (option("--pages") ?? Object.keys(PAGES).join(",")).split(",")
    .concat(option("--pages") ? extra.map(pageName) : []);
  const sizes = (option("--tailles") ?? "telephone,tablette,bureau").split(",");
  mkdirSync(OUT, { recursive: true });
  const browser = await launch();
  const cdp = connect(browser.ws);
  await cdp.ready;
  await cdp.send("Page.enable");
  await cdp.send("Runtime.enable");
  try {
    for (const sizeName of sizes) {
      const size = SIZES[sizeName];
      if (!size) throw new Error(`Taille inconnue : ${sizeName}`);
      await cdp.send("Emulation.setDeviceMetricsOverride", { width: size.width, height: size.height,
        deviceScaleFactor: size.scale, mobile: size.mobile });
      await cdp.send("Emulation.setTouchEmulationEnabled", { enabled: size.mobile });
      // --sans-connexion : écrans d’avant la connexion (choix de la crèche de l’application, connexion).
      if (!args.includes("--sans-connexion")) await login(cdp);
      for (const name of pages) {
        const page = PAGES[name];
        if (!page) throw new Error(`Page inconnue : ${name}`);
        if (page.only && !page.only.includes(sizeName)) continue;
        await navigate(cdp, `${BASE}${page.path}`);
        await waitFor(cdp, page.wait);
        if (page.run) {
          await sleep(1200);
          await evaluate(cdp, page.run);
          await sleep(900);
        }
        if (page.click) {
          await evaluate(cdp, `document.querySelector(${JSON.stringify(page.click)})?.click()`);
          await sleep(600);
          await waitFor(cdp, page.wait);
        }
        if (args.includes("--debordements")) {
          // Éléments qui dépassent la largeur de l’écran : les plus hauts dans l’arbre seulement.
          const report = await evaluate(cdp, `(() => {
            const w = innerWidth; const out = [];
            for (const el of document.querySelectorAll("main *")) {
              const r = el.getBoundingClientRect();
              if (r.width === 0 || r.right <= w + 1) continue;
              if (out.some((o) => o.el.contains(el))) continue;
              // Contenu d’un bloc qui défile lui-même (onglets, tableau) : ce bloc ne déborde pas.
              let scroller = el.parentElement, inside = false;
              while (scroller && scroller.tagName !== "MAIN") {
                const ox = getComputedStyle(scroller).overflowX;
                if ((ox === "auto" || ox === "scroll") && scroller.getBoundingClientRect().right <= w + 1) { inside = true; break; }
                scroller = scroller.parentElement;
              }
              if (inside) continue;
              out.push({ el, text: el.tagName.toLowerCase() + "." + String(el.className).split(" ").slice(0, 4).join(".") + " → " + Math.round(r.right) + "px : " + (el.innerText || "").slice(0, 60).replace(/\\s+/g, " ") });
            }
            return out.slice(0, 6).map((o) => o.text);
          })()`);
          console.log(`[${name} / ${sizeName}] ${report.length ? "DÉBORDE" : "ok"}`);
          report.forEach((line) => console.log("     " + line));
        }
        const file = join(OUT, `${PREFIX}${name}-${sizeName}.png`);
        await capture(cdp, file, size);
        console.log(`  ${file}`);
      }
    }
  } finally {
    cdp.close();
    browser.child.kill();
    await sleep(500);
    rmSync(browser.profile, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});

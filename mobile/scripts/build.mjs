#!/usr/bin/env node
/**
 * Construit le front embarqué des applications (ADR 0023, 0027) puis synchronise les projets natifs.
 *
 *   node scripts/build.mjs                    les trois applications
 *   node scripts/build.mjs direction pointage  certaines seulement
 *   node scripts/build.mjs direction --sans-sync
 *
 * Version des applications : celle du serveur (server/pom.xml, sans « -SNAPSHOT »), reportée dans le front
 * (VITE_TICAGOU_APP_VERSION, en-tête X-Ticagou-App), dans Android (versionName) et dans iOS (MARKETING_VERSION).
 * Direction et Pointage embarquent web/ (modes de build « direction » et « pointage ») ; Parents embarque parents-portal/web.
 */
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const MOBILE = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ROOT = resolve(MOBILE, "..");
const APPS = ["direction", "pointage", "parents"];

const args = process.argv.slice(2);
const sync = !args.includes("--sans-sync");
const chosen = args.filter((a) => !a.startsWith("--"));
for (const app of chosen) {
  if (!APPS.includes(app)) throw new Error(`Application inconnue : ${app} (attendu : ${APPS.join(", ")})`);
}

export function serverVersion() {
  const pom = readFileSync(join(ROOT, "server", "pom.xml"), "utf8");
  // Première <version> hors du bloc <parent> : celle du projet.
  const project = pom.replace(/<parent>[\s\S]*?<\/parent>/, "");
  const match = /<version>([^<]+)<\/version>/.exec(project);
  if (!match) throw new Error("Version introuvable dans server/pom.xml");
  return match[1].replace(/-SNAPSHOT$/, "");
}

const run = (command, commandArgs, options = {}) =>
  execFileSync(command, commandArgs, { stdio: "inherit", shell: process.platform === "win32", ...options });

// Chemins relatifs et répertoire de travail : sous Windows, le shell couperait un chemin contenant une espace.
function buildFront(app, version) {
  const env = { ...process.env, VITE_TICAGOU_APP_VERSION: version };
  if (app === "parents") {
    run("pnpm", ["exec", "vite", "build", "--outDir", "../../mobile/parents/www", "--emptyOutDir"],
      { env, cwd: join(ROOT, "parents-portal", "web") });
  } else {
    run("pnpm", ["exec", "vite", "build", "--mode", app, "--outDir", `../mobile/${app}/www`, "--emptyOutDir"],
      { env, cwd: join(ROOT, "web") });
  }
}

/** Versions des projets natifs : versionName (Android) et MARKETING_VERSION (iOS). */
export function stampVersion(app, version) {
  const gradle = join(MOBILE, app, "android", "app", "build.gradle");
  if (existsSync(gradle)) {
    const text = readFileSync(gradle, "utf8");
    writeFileSync(gradle, text.replace(/versionName "[^"]*"/, `versionName "${version}"`));
  }
  const pbx = join(MOBILE, app, "ios", "App", "App.xcodeproj", "project.pbxproj");
  if (existsSync(pbx)) {
    const text = readFileSync(pbx, "utf8");
    writeFileSync(pbx, text.replace(/MARKETING_VERSION = [^;]+;/g, `MARKETING_VERSION = ${version};`));
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const version = serverVersion();
  for (const app of chosen.length ? chosen : APPS) {
    console.log(`\n== Ti'Cagou ${app} ${version} ==`);
    buildFront(app, version);
    stampVersion(app, version);
    const native = ["android", "ios"].filter((p) => existsSync(join(MOBILE, app, p)));
    if (sync && native.length) {
      run("pnpm", ["exec", "cap", "sync", ...(native.length === 1 ? native : [])], { cwd: join(MOBILE, app) });
    }
  }
}

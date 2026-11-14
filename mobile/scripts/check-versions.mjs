#!/usr/bin/env node
/**
 * Contrôle de cohérence (ADR 0023) : chaque plugin Capacitor utilisé par un front doit être déclaré, à la même version,
 * dans la coque qui l'embarque ; sinon son code natif manquerait dans l'application.
 */
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const MOBILE = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ROOT = resolve(MOBILE, "..");
const read = (path) => JSON.parse(readFileSync(path, "utf8"));
const native = (deps) => Object.entries(deps ?? {}).filter(([name]) => /^@capacitor\/|^@aparajita\/capacitor-|^@capacitor-/.test(name)
  && !["@capacitor/cli", "@capacitor/android", "@capacitor/ios"].includes(name));

const fronts = { direction: join(ROOT, "web"), pointage: join(ROOT, "web"), parents: join(ROOT, "parents-portal", "web") };
const errors = [];
for (const [app, front] of Object.entries(fronts)) {
  const shell = read(join(MOBILE, app, "package.json")).dependencies ?? {};
  for (const [name, version] of native(read(join(front, "package.json")).dependencies)) {
    if (!shell[name]) errors.push(`${app} : ${name} est utilisé par le front mais absent de mobile/${app}/package.json`);
    else if (shell[name] !== version) errors.push(`${app} : ${name} ${shell[name]} dans la coque, ${version} dans le front`);
  }
  for (const [name, version] of Object.entries(shell)) {
    if (/[\^~]/.test(version)) errors.push(`${app} : ${name} doit être épinglé (sans ^ ni ~)`);
  }
}
if (errors.length) {
  console.error(errors.join("\n"));
  process.exit(1);
}
console.log("Plugins des coques et des fronts cohérents.");

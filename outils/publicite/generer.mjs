/**
 * Prospectus 4:3 (Instagram, Facebook) et maquette de la page des offres, à partir des captures réelles.
 *
 *   pnpm captures    (démonstration lancée)
 *   pnpm generer
 *
 * Résultat : docs/commercial/prospectus/*.png, docs/design/Ti'Cagou — Offres.html et son aperçu docs/commercial/offres.png.
 * Renseignez CONTACT avant de publier. Les prix viennent de docs/commercial/bareme-tarifaire.md.
 */
import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import puppeteer from "puppeteer-core";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const CHROME = "C:/Program Files/Google/Chrome/Application/chrome.exe";

/** Site ou téléphone, imprimé en bas de chaque prospectus. Vide : seul l'appel à la démonstration paraît. */
const CONTACT = "";

const OFFRES = [
  { nom: "Essentiel", phrase: "Savoir qui est là, à chaque instant", prix: [6500, 9500, 14000],
    liste: ["Dossiers enfants et familles", "Santé, PAI, personnes autorisées", "Présences enfants et personnel", "Encadrement en temps réel", "Sauvegardes et mises à jour"] },
  { nom: "Gestion", phrase: "Inscrire, facturer, encaisser", prix: [11500, 16500, 23500], accent: true,
    liste: ["Tout Essentiel", "Inscriptions et liste d’attente", "Contrats", "Facturation avec aides déduites", "Encaissements et statistiques"] },
  { nom: "Complet", phrase: "Toute la crèche, parents compris", prix: [18500, 26000, 36500],
    liste: ["Tout Gestion", "Portail parents", "Personnel : planning et heures", "Repas : menus, fiche cuisine", "Comptabilité : export CSV et FEC"] },
];
const TAILLES = ["Micro · jusqu’à 15 places", "Structure · 16 à 40 places", "Grande · 41 à 80 places"];
const xpf = (n) => `${n.toLocaleString("fr-FR").replace(/\s/g, "\u202f")}\u00a0F`;

const font = (file) => pathToFileURL(join(root, "web/node_modules/@fontsource-variable", file)).href;
const capture = (name) => pathToFileURL(join(root, "docs/commercial/captures", `${name}.jpg`)).href;
/** Captures réelles de l’application Direction (scripts/captures/captures.mjs). */
const mobile = (name) => pathToFileURL(join(root, "docs/commercial/captures/mobile", `${name}.png`)).href;
const appli = (name, style) => `<div class="cadre" style="border-radius:46px;border:12px solid #122;${style}"><img src="${mobile(name)}" alt=""></div>`;

const base = `
@font-face { font-family: Fraunces; src: url("${font("fraunces/files/fraunces-latin-full-normal.woff2")}") format("woff2"); font-weight: 100 900; }
@font-face { font-family: "Source Sans 3"; src: url("${font("source-sans-3/files/source-sans-3-latin-wght-normal.woff2")}") format("woff2"); font-weight: 200 900; }
:root { --lagon: #0b6e72; --nuit: #08484b; --pale: #dcefee; --sable: #fbf8f3; --terre: #b5532a; --encre: #1d2b2c; --doux: #556566; }
* { box-sizing: border-box; margin: 0; }
html, body { width: 1440px; height: 1080px; overflow: hidden; }
body { position: relative; font-family: "Source Sans 3", "Segoe UI", sans-serif; color: var(--encre); -webkit-font-smoothing: antialiased; }
h1 { font-family: Fraunces, Georgia, serif; font-weight: 600; letter-spacing: -0.02em; line-height: 1.02; font-size: 96px; font-variation-settings: "SOFT" 40; }
.sous { font-size: 34px; line-height: 1.3; margin-top: 26px; max-width: 1040px; }
.cadre { position: absolute; border-radius: 22px; overflow: hidden; box-shadow: 0 30px 70px rgb(8 40 42 / 0.32); background: #fff; }
.cadre img { display: block; width: 100%; }
.barre { height: 36px; background: #e9eded; display: flex; gap: 9px; align-items: center; padding-left: 16px; }
.barre i { width: 12px; height: 12px; border-radius: 50%; background: #c6cfcf; }
.pied { position: absolute; left: 72px; right: 72px; bottom: 46px; display: flex; align-items: center; justify-content: space-between; z-index: 3; }
.marque { font-family: Fraunces, Georgia, serif; font-weight: 600; font-size: 44px; letter-spacing: -0.01em; }
.marque small { display: block; font-family: "Source Sans 3", sans-serif; font-size: 21px; font-weight: 500; letter-spacing: 0; opacity: 0.85; margin-top: 2px; }
.appel { font-size: 27px; font-weight: 700; padding: 18px 32px; border-radius: 999px; }
.contact { font-size: 24px; font-weight: 600; margin-right: 22px; }
`;

const pied = (fond, encre) => `<div class="pied"><div class="marque" style="color:${encre}">Ti’Cagou<small>Gestion de crèches et garderies · Nouvelle-Calédonie</small></div>
<div>${CONTACT ? `<span class="contact" style="color:${encre}">${CONTACT}</span>` : ""}<span class="appel" style="background:${fond};color:${fond === "#fff" ? "var(--nuit)" : "#fff"}">Demandez une démonstration</span></div></div>`;

const ecran = (name, style) => `<div class="cadre" style="${style}"><div class="barre"><i></i><i></i><i></i></div><img src="${capture(name)}" alt=""></div>`;
const telephone = (name, style) => `<div class="cadre" style="border-radius:50px;border:12px solid #122;${style}"><img src="${capture(name)}" alt=""></div>`;

const puces = (items, couleur) => `<ul style="list-style:none;padding:0;margin-top:34px;display:flex;flex-direction:column;gap:16px;font-size:31px;font-weight:600">
${items.map((t) => `<li style="display:flex;gap:16px;align-items:baseline"><span style="color:${couleur};font-size:26px">●</span>${t}</li>`).join("")}</ul>`;

const PROSPECTUS = {
  "01-toute-la-creche": `<body style="background:var(--nuit);color:#fff">
    <div style="padding:70px 72px 0"><h1>Fini le cahier et les tableurs.</h1>
    <p class="sous" style="color:#cfe6e4">Présences, encadrement, factures, parents : le quotidien de votre crèche, en ligne.</p></div>
    ${ecran("tableau-de-bord", "left:72px;right:72px;top:338px;height:600px")}
    <div style="position:absolute;left:0;right:0;bottom:0;height:150px;background:var(--nuit)"></div>
    ${pied("#fff", "#fff")}</body>`,
  "02-presences": `<body style="background:var(--sable)">
    <div style="position:absolute;left:72px;top:76px;width:600px"><h1 style="font-size:84px;color:var(--nuit)">Qui est là&nbsp;? Vous le savez, à l’instant.</h1>
    ${puces(["Arrivée et départ en deux touches", "Départ bloqué si la personne n’est pas autorisée", "Encadrement calculé en direct", "Une coupure d’internet en journée ne bloque pas le pointage"], "var(--terre)")}</div>
    ${ecran("tablette", "left:716px;top:150px;width:900px;height:640px;border-radius:30px")}
    ${pied("var(--lagon)", "var(--nuit)")}</body>`,
  "03-parents": `<body style="background:var(--pale)">
    <div style="position:absolute;left:72px;top:96px;width:720px"><h1 style="color:var(--nuit)">Biberon, sieste, médicament&nbsp;: les parents suivent la journée.</h1>
    <p class="sous" style="color:#27484a">L’équipe note sur la tablette. La famille le voit sur son téléphone, sans application à installer.</p></div>
    ${telephone("app-parents", "left:830px;top:176px;width:290px;height:640px;transform:rotate(-4deg)")}
    ${telephone("app-parents-bebe", "left:1070px;top:80px;width:310px;height:680px;transform:rotate(3deg)")}
    ${pied("var(--lagon)", "var(--nuit)")}</body>`,
  "04-facturation": `<body style="background:#fff7f1">
    <div style="padding:70px 72px 0"><h1 style="color:#7c3517">Vos factures du mois, avec les aides déduites.</h1>
    <p class="sous" style="color:#5b3b2a">Pensé pour la Nouvelle-Calédonie : francs CFP, aides, TGC, reste à charge des familles.</p></div>
    ${ecran("facturation", "left:72px;right:72px;top:396px;height:540px")}
    <div style="position:absolute;left:0;right:0;bottom:0;height:150px;background:#fff7f1"></div>
    ${pied("var(--terre)", "#7c3517")}</body>`,
  // Une seule formule, tout compris (ADR 0021, barème : docs/commercial/bareme-tarifaire.md).
  "05-offres": `<body style="background:var(--sable)">
    <div style="padding:64px 72px 0"><h1 style="font-size:80px;color:var(--nuit)">Une seule formule. Tout est compris.</h1></div>
    <div style="position:absolute;left:72px;right:72px;top:300px;display:grid;grid-template-columns:1fr 1.2fr;gap:40px;align-items:start">
      <section style="border-radius:24px;padding:40px;background:var(--nuit);color:#fff">
        <p style="font-size:28px;opacity:.85">Pour toute la crèche</p>
        <p style="font-family:Fraunces,serif;font-weight:600;font-size:96px;line-height:1.05">${xpf(15000)}</p>
        <p style="font-size:26px;opacity:.85">par mois, hors TGC · support compris</p>
        <p style="font-size:24px;margin-top:26px;opacity:.85">Applications Direction, Pointage et Parents comprises</p></section>
      <ul style="list-style:none;padding:0;display:flex;flex-direction:column;gap:14px;font-size:30px;font-weight:600;color:var(--encre)">
        ${["Enfants, familles, santé, PAI", "Présences et encadrement en direct", "Inscriptions, contrats, facturation avec aides", "Portail et application des parents", "Personnel, repas, comptabilité", "Sauvegardes chiffrées, mises à jour"].map((l) => `<li>✓ ${l}</li>`).join("")}</ul>
    </div>
    ${pied("var(--lagon)", "var(--nuit)")}</body>`,
  "06-dans-la-poche": `<body style="background:var(--pale)">
    <div style="position:absolute;left:72px;top:90px;width:700px"><h1 style="color:var(--nuit)">Toute la crèche, dans la poche.</h1>
    ${puces(["Encadrement et présences en direct", "Départ bloqué : vous autorisez à distance", "Alertes, jamais de donnée de santé dedans", "Face ID, téléphone perdu retiré en 2 clics"], "var(--lagon)")}</div>
    ${appli("tableau-de-bord-telephone", "left:820px;top:150px;width:300px;height:650px;transform:rotate(-4deg)")}
    ${appli("depart-a-valider-telephone", "left:1080px;top:70px;width:310px;height:672px;transform:rotate(3deg)")}
    ${pied("var(--lagon)", "var(--nuit)")}</body>`,
};

/** Maquette de la page publique des offres : fichier autonome, prix selon la taille de la crèche. */
const maquette = (rendu) => {
  const image = (n) => (rendu ? capture(n) : `../commercial/captures/${n}.jpg`);
  const lignes = [
    ["Dossiers enfants et familles, santé, PAI", 1, 1, 1], ["Présences des enfants sur tablette", 1, 1, 1], ["Présences du personnel", 1, 1, 1],
    ["Encadrement en temps réel", 1, 1, 1], ["Hébergement, sauvegardes, mises à jour, support", 1, 1, 1], ["Inscriptions, liste d’attente, contrats", 0, 1, 1],
    ["Facturation avec aides, encaissements, statistiques", 0, 1, 1], ["Comptabilité : export CSV et FEC", 0, 0, 1], ["Personnel : fiches, planning, heures", 0, 0, 1],
    ["Repas : menus, fiche cuisine, récapitulatif", 0, 0, 1], ["Portail parents : journée, messages, factures, signatures", 0, 0, 1],
  ];
  return `<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Ti’Cagou — Offres</title><style>
${rendu ? base.replace("html, body { width: 1440px; height: 1080px; overflow: hidden; }", "") : base.replace(/@font-face[^\n]*\n/g, "").replace("html, body { width: 1440px; height: 1080px; overflow: hidden; }", "")}
body { background: var(--sable); }
main { max-width: 1180px; margin: 0 auto; padding: 56px 24px 72px; }
h1 { font-size: clamp(40px, 6vw, 68px); color: var(--nuit); }
.intro { font-size: 22px; color: var(--doux); margin-top: 14px; max-width: 760px; }
.tailles { display: flex; flex-wrap: wrap; gap: 8px; margin: 32px 0 20px; }
.tailles button { font: inherit; font-size: 17px; font-weight: 600; padding: 10px 18px; border-radius: 999px; border: 1px solid #c9d6d5; background: #fff; color: var(--encre); cursor: pointer; }
.tailles button[aria-pressed="true"] { background: var(--nuit); border-color: var(--nuit); color: #fff; }
.cartes { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 18px; }
.carte { background: #fff; border-radius: 18px; padding: 26px; box-shadow: 0 8px 24px rgb(8 40 42 / 0.08); }
.carte.accent { background: var(--nuit); color: #fff; }
.carte h2 { font-family: Fraunces, Georgia, serif; font-size: 32px; font-weight: 600; }
.prix { font-family: Fraunces, Georgia, serif; font-size: 42px; font-weight: 600; margin-top: 14px; }
.carte ul { padding-left: 20px; margin-top: 14px; line-height: 1.6; font-size: 17px; }
h3 { font-family: Fraunces, Georgia, serif; font-size: 30px; font-weight: 600; color: var(--nuit); margin: 52px 0 16px; }
table { width: 100%; border-collapse: collapse; background: #fff; border-radius: 14px; overflow: hidden; font-size: 17px; }
th, td { padding: 12px 16px; text-align: center; border-bottom: 1px solid #e8eeed; }
th:first-child, td:first-child { text-align: left; }
.oui { color: var(--lagon); font-weight: 700; } .non { color: #b9c5c4; }
.duo { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 18px; }
figure { margin: 0; } figure img { width: 100%; border-radius: 12px; box-shadow: 0 8px 24px rgb(8 40 42 / 0.12); display: block; }
figcaption { margin-top: 8px; color: var(--doux); font-size: 16px; }
.note { color: var(--doux); font-size: 15px; margin-top: 18px; }
</style></head><body><main>
<p class="marque" style="color:var(--nuit)">Ti’Cagou</p>
<h1 style="margin-top:24px">Ne payez que ce dont votre crèche a besoin.</h1>
<p class="intro">Commencez par les présences. Ajoutez la facturation, le portail parents ou les repas quand vous en avez besoin, sans rien réinstaller.</p>
<div class="tailles" role="group" aria-label="Taille de la structure">${TAILLES.map((t, i) => `<button type="button" data-i="${i}" aria-pressed="${i === 0}">${t}</button>`).join("")}</div>
<div class="cartes">${OFFRES.map((o) => `<section class="carte${o.accent ? " accent" : ""}"><h2>${o.nom}</h2><p>${o.phrase}</p>
<p class="prix" data-prix="${o.prix.map(xpf).join("|")}">${xpf(o.prix[0])}</p><p>par mois, hors TGC</p><ul>${o.liste.map((l) => `<li>${l}</li>`).join("")}</ul></section>`).join("")}</div>
<h3>Le détail</h3>
<table><thead><tr><th>Fonction</th>${OFFRES.map((o) => `<th>${o.nom}</th>`).join("")}</tr></thead><tbody>
${lignes.map(([n, ...c]) => `<tr><td>${n}</td>${c.map((v) => (v ? '<td class="oui">Oui</td>' : '<td class="non">—</td>')).join("")}</tr>`).join("")}</tbody></table>
<h3>Le logiciel suit votre formule</h3>
<div class="duo"><figure><img src="${image("parametres-essentiel")}" alt="Paramètres en formule Essentiel"><figcaption>Essentiel : six rubriques de paramètres.</figcaption></figure>
<figure><img src="${image("parametres-complet")}" alt="Paramètres en formule Complet"><figcaption>Complet : toutes les rubriques.</figcaption></figure></div>
<p class="note">Chaque formule comprend 1 PC et 2 tablettes. Modules à la carte, mise en route et formation : voir le barème. Prix proposés, à valider.</p>
</main><script>
document.querySelectorAll(".tailles button").forEach((b) => b.addEventListener("click", () => {
  document.querySelectorAll(".tailles button").forEach((o) => o.setAttribute("aria-pressed", String(o === b)));
  document.querySelectorAll("[data-prix]").forEach((p) => { p.textContent = p.dataset.prix.split("|")[b.dataset.i]; });
}));
</script></body></html>`;
};

const out = join(root, "docs/commercial/prospectus");
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
const work = join(here, ".travail");
mkdirSync(work, { recursive: true });

const browser = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ["--allow-file-access-from-files"] });
try {
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 1080, deviceScaleFactor: 1 });
  for (const [name, body] of Object.entries(PROSPECTUS)) {
    const file = join(work, `${name}.html`);
    writeFileSync(file, `<!doctype html><html lang="fr"><head><meta charset="utf-8"><style>${base}</style></head>${body}</html>`);
    await page.goto(pathToFileURL(file).href, { waitUntil: "networkidle0" });
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: join(out, `${name}.png`) });
    console.log(`  ${name}.png`);
  }
  writeFileSync(join(root, "docs/design/Ti'Cagou — Offres.html"), maquette(false));
  const file = join(work, "offres.html");
  writeFileSync(file, maquette(true));
  await page.setViewport({ width: 1280, height: 900, deviceScaleFactor: 1 });
  await page.goto(pathToFileURL(file).href, { waitUntil: "networkidle0" });
  await page.screenshot({ path: join(root, "docs/commercial/offres.png"), fullPage: true });
  console.log("  offres.png");
} finally {
  await browser.close();
  rmSync(work, { recursive: true, force: true });
}

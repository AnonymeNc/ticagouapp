#!/usr/bin/env node
/**
 * Fabrique les photos et vidéos du jeu de démonstration : des illustrations originales (aucune photo d'enfant réel),
 * dessinées ici en SVG, rendues en PNG par Chrome (ou Edge) sans fenêtre, puis converties par ffmpeg en JPEG (photos) et
 * en MP4 H.264 de quelques secondes (vidéos, effet de zoom lent). Les fichiers produits sont versionnés à côté de ce
 * script : la démonstration n'a besoin ni de Chrome ni de ffmpeg.
 *
 *   node scripts/demo/medias/generer-medias.mjs
 *
 * À relancer seulement pour changer les images. Prérequis : ffmpeg (avec libx264) et Chrome ou Edge.
 */
import { execFileSync } from "node:child_process";
import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const OUT = dirname(fileURLToPath(import.meta.url));
const W = 1024;
const H = 768;
const BROWSERS = [
  // Chrome d'abord : Edge sans fenêtre rend la main avant d'avoir écrit la capture.
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "/usr/bin/chromium", "/usr/bin/google-chrome",
];
const browser = BROWSERS.find((b) => existsSync(b));
if (!browser) throw new Error("Chrome ou Edge introuvable.");

// ------------------------------------------------------------------------------------------- éléments communs

const sky = (top, bottom) => `<defs><linearGradient id="ciel" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${top}"/><stop offset="1" stop-color="${bottom}"/></linearGradient></defs>
  <rect width="${W}" height="${H}" fill="url(#ciel)"/>`;
const sun = (x, y, r = 60) => `<circle cx="${x}" cy="${y}" r="${r + 22}" fill="#ffe08a" opacity=".35"/><circle cx="${x}" cy="${y}" r="${r}" fill="#ffc53d"/>`;
const cloud = (x, y, s = 1) => `<g transform="translate(${x} ${y}) scale(${s})" fill="#fff" opacity=".92"><ellipse cx="0" cy="0" rx="70" ry="34"/><ellipse cx="55" cy="-18" rx="52" ry="38"/><ellipse cx="105" cy="4" rx="58" ry="30"/></g>`;
const floor = (color, y = 560) => `<rect y="${y}" width="${W}" height="${H - y}" fill="${color}"/>`;
const wall = (color) => `<rect width="${W}" height="${H}" fill="${color}"/>`;
/** Petit personnage stylisé, vu de dos ou de trois-quarts, sans visage détaillé. */
const kid = (x, y, shirt, hair = "#3b2a20", s = 1) => `<g transform="translate(${x} ${y}) scale(${s})">
  <ellipse cx="0" cy="118" rx="44" ry="10" fill="#000" opacity=".12"/>
  <rect x="-30" y="40" width="60" height="70" rx="26" fill="${shirt}"/>
  <rect x="-24" y="96" width="18" height="22" rx="8" fill="#2f3e57"/><rect x="6" y="96" width="18" height="22" rx="8" fill="#2f3e57"/>
  <circle cx="0" cy="12" r="32" fill="#c98b5e"/><path d="M-32 8 Q-30 -26 0 -24 Q30 -26 32 8 Q20 -6 0 -4 Q-20 -6 -32 8Z" fill="${hair}"/>
  <circle cx="-11" cy="14" r="3.5" fill="#2b1d14"/><circle cx="11" cy="14" r="3.5" fill="#2b1d14"/><path d="M-9 26 Q0 33 9 26" stroke="#7a3b22" stroke-width="3" fill="none" stroke-linecap="round"/>
</g>`;
const label = (text, color = "#ffffff") => `<g><rect x="32" y="${H - 86}" width="${text.length * 19 + 48}" height="58" rx="29" fill="#0b4f6c" opacity=".78"/>
  <text x="56" y="${H - 47}" font-family="Segoe UI, Arial, sans-serif" font-size="30" font-weight="600" fill="${color}">${text}</text></g>`;
const niaouli = (x, y, s = 1) => `<g transform="translate(${x} ${y}) scale(${s})"><path d="M-10 0 Q-6 -120 -14 -230 L14 -230 Q6 -120 10 0Z" fill="#cfc4b4"/>
  <ellipse cx="-40" cy="-250" rx="70" ry="44" fill="#5f8f4e"/><ellipse cx="40" cy="-270" rx="76" ry="48" fill="#6fa35a"/><ellipse cx="0" cy="-310" rx="64" ry="42" fill="#7fb466"/></g>`;
const palm = (x, y, s = 1) => `<g transform="translate(${x} ${y}) scale(${s})"><path d="M0 0 Q20 -140 -10 -300" stroke="#9c7b52" stroke-width="22" fill="none" stroke-linecap="round"/>
  ${[-160, -110, -60, -10, 40].map((a) => `<path d="M-10 -300 q${Math.cos(a * Math.PI / 180) * 120} ${Math.sin(a * Math.PI / 180) * 60} ${Math.cos(a * Math.PI / 180) * 190} ${40 + Math.sin(a * Math.PI / 180) * 40}" stroke="#3f8f4f" stroke-width="18" fill="none" stroke-linecap="round"/>`).join("")}</g>`;
const svg = (body) => `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${body}</svg>`;

// ------------------------------------------------------------------------------------------------- scènes

const SCENES = {
  "atelier-peinture": svg(`${wall("#fff4e3")}${floor("#e9d3b0")}
    <rect x="120" y="140" width="380" height="300" rx="10" fill="#fff" stroke="#c9a77c" stroke-width="10"/>
    <circle cx="220" cy="240" r="58" fill="#ff7a59" opacity=".85"/><circle cx="330" cy="300" r="70" fill="#3fa7d6" opacity=".8"/>
    <circle cx="410" cy="220" r="40" fill="#ffc53d" opacity=".9"/><path d="M160 380 Q260 320 460 390" stroke="#59b36b" stroke-width="22" fill="none" stroke-linecap="round"/>
    ${[[640, 210, "#ff7a59"], [720, 260, "#3fa7d6"], [800, 200, "#ffc53d"], [880, 270, "#9b6cd3"]].map(([x, y, c]) => `<g transform="translate(${x} ${y}) rotate(-12)"><path d="M0 0 c-30 -60 30 -60 0 0 c20 30 -20 30 0 0" fill="${c}"/><rect x="-10" y="20" width="20" height="40" rx="8" fill="${c}"/></g>`).join("")}
    ${kid(300, 440, "#ff9f68")}${kid(700, 430, "#7fc8a9", "#1d1712")}
    <rect x="560" y="520" width="260" height="34" rx="8" fill="#b88b5c"/>${[600, 650, 700, 750].map((x, i) => `<circle cx="${x}" cy="536" r="13" fill="${["#ff7a59", "#3fa7d6", "#ffc53d", "#59b36b"][i]}"/>`).join("")}
    ${label("Atelier peinture")}`),
  "jeux-eau": svg(`${sky("#8fd3f4", "#d9f3ff")}${sun(860, 120)}${cloud(120, 110)}
    <path d="M0 470 Q256 440 512 470 T1024 470 V768 H0Z" fill="#2fa4c8"/><path d="M0 520 Q256 500 512 520 T1024 520 V768 H0Z" fill="#7fd1e0" opacity=".7"/>
    <path d="M0 600 Q300 570 640 610 T1024 600 V768 H0Z" fill="#f3dfb1"/>
    ${palm(930, 610, .9)}${kid(380, 500, "#ffc53d")}${kid(560, 520, "#ff7a59", "#2a1a10", .9)}
    <path d="M250 640 l40 -60 l40 60Z" fill="#ff7a59"/><rect x="236" y="636" width="108" height="20" rx="6" fill="#e2603f"/>
    ${[[470, 440], [520, 410], [610, 430], [660, 400]].map(([x, y]) => `<circle cx="${x}" cy="${y}" r="10" fill="#fff" opacity=".8"/>`).join("")}
    ${label("Jeux d’eau au lagon")}`),
  "histoire": svg(`${wall("#f3ecff")}${floor("#d8c9f0", 540)}
    <rect x="90" y="120" width="300" height="280" rx="12" fill="#b6875a"/>${[0, 1, 2].map((r) => [0, 1, 2, 3, 4].map((c) => `<rect x="${110 + c * 54}" y="${140 + r * 86}" width="40" height="70" rx="4" fill="${["#ff7a59", "#3fa7d6", "#ffc53d", "#59b36b", "#9b6cd3"][(r + c) % 5]}"/>`).join("")).join("")}
    <ellipse cx="620" cy="620" rx="300" ry="60" fill="#9b6cd3" opacity=".35"/>
    ${kid(500, 480, "#3fa7d6")}${kid(640, 470, "#ffc53d", "#1d1712")}${kid(780, 490, "#ff7a59")}
    <g transform="translate(560 330) rotate(-6)"><rect width="200" height="130" rx="8" fill="#fff" stroke="#9b6cd3" stroke-width="8"/><line x1="100" y1="0" x2="100" y2="130" stroke="#9b6cd3" stroke-width="6"/><circle cx="50" cy="60" r="26" fill="#ffc53d"/><path d="M125 90 l30 -40 l30 40Z" fill="#59b36b"/></g>
    ${label("L’heure de l’histoire")}`),
  "jardin": svg(`${sky("#bfe6ff", "#f4fbff")}${sun(150, 120, 50)}${cloud(620, 90, .9)}${floor("#7ab55c", 520)}
    ${[180, 360, 540, 720, 900].map((x, i) => `<g transform="translate(${x} 560)"><path d="M-50 0 h100 l-14 90 h-72Z" fill="#c8693c"/><path d="M0 0 q-10 -60 -40 -80 M0 0 q10 -70 40 -95 M0 0 v-110" stroke="#3f8f4f" stroke-width="10" fill="none"/><circle cx="0" cy="-118" r="${16 + i * 2}" fill="${["#ff7a59", "#ffc53d", "#ff9fcf", "#ff7a59", "#ffc53d"][i]}"/></g>`).join("")}
    ${kid(450, 400, "#ff9fcf")}${kid(630, 410, "#3fa7d6", "#2a1a10", .95)}
    <path d="M600 520 q30 -40 60 0" stroke="#3fa7d6" stroke-width="10" fill="none"/>
    ${label("On plante des tomates")}`),
  "motricite": svg(`${wall("#e6f7f1")}${floor("#ffd27a", 560)}
    <path d="M120 560 a140 140 0 0 1 280 0" fill="#ff7a59"/><path d="M170 560 a90 90 0 0 1 180 0" fill="#e6f7f1"/>
    <rect x="460" y="500" width="300" height="60" rx="16" fill="#3fa7d6"/><rect x="500" y="440" width="220" height="60" rx="16" fill="#59b36b"/>
    ${[[820, 520, "#ffc53d"], [880, 540, "#ff7a59"], [860, 480, "#9b6cd3"]].map(([x, y, c]) => `<circle cx="${x}" cy="${y}" r="34" fill="${c}"/>`).join("")}
    ${kid(610, 330, "#ffc53d")}${kid(260, 430, "#7fc8a9", "#1d1712", .9)}
    ${label("Parcours de motricité")}`),
  "musique": svg(`${wall("#fff0f3")}${floor("#f2c6cf", 560)}
    <g transform="translate(250 470)"><ellipse rx="90" ry="26" fill="#ff7a59"/><rect x="-90" y="0" width="180" height="90" fill="#ff7a59"/><ellipse cy="0" rx="90" ry="26" fill="#ffe0cc"/></g>
    <g transform="translate(780 480)"><rect x="-120" y="-20" width="240" height="40" rx="8" fill="#3fa7d6"/>${[-90, -45, 0, 45, 90].map((x, i) => `<rect x="${x - 18}" y="-16" width="36" height="32" rx="4" fill="${["#ffc53d", "#59b36b", "#ff7a59", "#9b6cd3", "#ff9fcf"][i]}"/>`).join("")}</g>
    ${[[420, 200], [520, 150], [620, 210], [720, 160]].map(([x, y]) => `<g transform="translate(${x} ${y})" fill="#9b6cd3"><ellipse rx="22" ry="16" transform="rotate(-20)"/><rect x="16" y="-80" width="8" height="80"/></g>`).join("")}
    ${kid(470, 440, "#9b6cd3")}${kid(620, 450, "#ffc53d", "#1d1712")}
    ${label("Éveil musical")}`),
  "pate-a-modeler": svg(`${wall("#f1f7e8")}<rect x="80" y="420" width="864" height="60" rx="14" fill="#c99a6b"/><rect x="120" y="480" width="40" height="200" fill="#a77a4f"/><rect x="864" y="480" width="40" height="200" fill="#a77a4f"/>
    ${[[260, 390, "#ff7a59", "M-40 0 q40 -70 80 0Z"], [420, 395, "#3fa7d6", "M-30 0 a30 30 0 1 1 60 0Z"], [600, 392, "#ffc53d", "M-50 0 q50 -40 100 0Z"], [760, 396, "#9b6cd3", "M-25 0 l25 -50 l25 50Z"]].map(([x, y, c, d]) => `<path transform="translate(${x} ${y})" d="${d}" fill="${c}"/>`).join("")}
    ${kid(340, 230, "#59b36b")}${kid(680, 240, "#ff9fcf", "#2a1a10")}
    ${label("Pâte à modeler")}`),
  "gouter-fruits": svg(`${wall("#fff9e8")}<rect x="60" y="430" width="904" height="50" rx="12" fill="#c99a6b"/>
    <ellipse cx="512" cy="420" rx="230" ry="40" fill="#fff" stroke="#e8d9b8" stroke-width="6"/>
    <g transform="translate(400 380)"><ellipse rx="70" ry="45" fill="#ff9a3c"/><ellipse rx="40" ry="22" fill="#ffd3a1"/>${[-20, 0, 20].map((x) => `<circle cx="${x}" cy="0" r="5" fill="#2b1d14"/>`).join("")}</g>
    <path d="M520 390 q60 -90 140 -20 q-60 -20 -130 30Z" fill="#ffe14d"/>
    ${[[600, 395], [630, 400], [580, 405]].map(([x, y]) => `<circle cx="${x}" cy="${y}" r="16" fill="#e2304a"/>`).join("")}
    ${kid(250, 270, "#3fa7d6")}${kid(780, 270, "#ff7a59", "#1d1712")}
    ${label("Goûter : papaye et banane")}`),
  "sieste": svg(`${sky("#2d3561", "#5c6aa8")}<circle cx="820" cy="140" r="60" fill="#fff6cf"/><circle cx="845" cy="125" r="56" fill="#3b4478"/>
    ${[[120, 100], [300, 180], [520, 90], [650, 220], [930, 260], [400, 300]].map(([x, y]) => `<circle cx="${x}" cy="${y}" r="4" fill="#fff"/>`).join("")}
    ${floor("#3b4478", 520)}
    ${[180, 512, 844].map((x) => `<g transform="translate(${x} 520)"><rect x="-130" y="-40" width="260" height="70" rx="20" fill="#7d8ad1"/><rect x="-120" y="-60" width="80" height="40" rx="16" fill="#fff"/><path d="M-60 -36 q40 -30 90 0 q40 20 70 0 v40 h-160Z" fill="#ffc53d" opacity=".9"/></g>`).join("")}
    <text x="560" y="300" font-family="Segoe UI, Arial" font-size="56" fill="#fff" opacity=".8">z z z</text>
    ${label("C’est l’heure de la sieste")}`),
  "anniversaire": svg(`${wall("#fff1f8")}${[[160, 160, "#ff7a59"], [260, 120, "#3fa7d6"], [780, 130, "#ffc53d"], [880, 170, "#9b6cd3"]].map(([x, y, c]) => `<g><ellipse cx="${x}" cy="${y}" rx="44" ry="54" fill="${c}"/><path d="M${x} ${y + 54} q-12 60 6 120" stroke="#888" stroke-width="3" fill="none"/></g>`).join("")}
    <rect x="80" y="480" width="864" height="40" rx="10" fill="#c99a6b"/>
    <g transform="translate(512 470)"><rect x="-130" y="-110" width="260" height="110" rx="14" fill="#ff9fcf"/><rect x="-130" y="-110" width="260" height="30" rx="14" fill="#fff"/>${[-60, 0, 60].map((x) => `<rect x="${x - 6}" y="-170" width="12" height="60" fill="#3fa7d6"/><ellipse cx="${x}" cy="-182" rx="10" ry="16" fill="#ffc53d"/>`).join("")}</g>
    ${kid(260, 330, "#ffc53d")}${kid(770, 330, "#59b36b", "#1d1712")}
    ${label("Joyeux anniversaire !")}`),
  "sortie-parc": svg(`${sky("#a7dcff", "#eaf7ff")}${sun(880, 110, 48)}${cloud(150, 120, 1.1)}${floor("#8cc66a", 500)}
    ${niaouli(160, 520, 1.1)}${niaouli(880, 540, .95)}
    <path d="M300 768 Q480 560 620 500 Q760 460 1024 470" stroke="#e8d6a8" stroke-width="80" fill="none"/>
    ${kid(450, 450, "#ff7a59")}${kid(560, 430, "#3fa7d6", "#1d1712", .9)}${kid(660, 420, "#ffc53d", "#3b2a20", .85)}
    ${label("Promenade au parc")}`),
  "construction": svg(`${wall("#eef4ff")}${floor("#c3d3f0", 560)}
    ${[[300, 520, "#ff7a59"], [380, 520, "#3fa7d6"], [340, 460, "#ffc53d"], [700, 520, "#59b36b"], [700, 460, "#9b6cd3"], [700, 400, "#ff7a59"], [700, 340, "#3fa7d6"]].map(([x, y, c]) => `<rect x="${x - 38}" y="${y - 30}" width="76" height="60" rx="8" fill="${c}"/>`).join("")}
    <path d="M610 280 l90 -60 l90 60Z" fill="#ffc53d"/>
    ${kid(500, 420, "#59b36b")}${kid(860, 430, "#ff9fcf", "#1d1712", .95)}
    ${label("La plus haute tour !")}`),
  // Affiches des actualités.
  "affiche-fete": svg(`${sky("#ffcf6e", "#ff8a65")}${[[120, 140], [900, 120], [200, 620], [860, 640]].map(([x, y], i) => `<circle cx="${x}" cy="${y}" r="${40 + i * 6}" fill="#fff" opacity=".25"/>`).join("")}
    <text x="512" y="300" text-anchor="middle" font-family="Georgia, serif" font-size="84" font-weight="700" fill="#fff">Fête de fin d’année</text>
    <text x="512" y="390" text-anchor="middle" font-family="Segoe UI, Arial" font-size="40" fill="#fff">Spectacle des enfants · goûter partagé</text>
    ${palm(120, 760, .8)}${palm(920, 760, .8)}
    <text x="512" y="520" text-anchor="middle" font-family="Segoe UI, Arial" font-size="34" font-weight="600" fill="#0b4f6c">Les P’tits Niaoulis · Dumbéa</text>`),
  "affiche-gout": svg(`${wall("#f3fbe9")}<text x="512" y="200" text-anchor="middle" font-family="Georgia, serif" font-size="78" font-weight="700" fill="#2e7d32">Semaine du goût</text>
    <text x="512" y="270" text-anchor="middle" font-family="Segoe UI, Arial" font-size="36" fill="#4a6b3a">Chaque jour, un fruit du pays à découvrir</text>
    <g transform="translate(300 480)"><ellipse rx="110" ry="70" fill="#ff9a3c"/><ellipse rx="64" ry="34" fill="#ffd3a1"/></g>
    <path d="M520 470 q90 -140 210 -30 q-90 -30 -200 50Z" fill="#ffe14d"/><circle cx="800" cy="520" r="60" fill="#7fb466"/><circle cx="800" cy="520" r="38" fill="#c8e6a5"/>`),
  "affiche-pedagogique": svg(`${sky("#cfe8ff", "#f7fbff")}<text x="512" y="250" text-anchor="middle" font-family="Georgia, serif" font-size="72" font-weight="700" fill="#0b4f6c">Journée pédagogique</text>
    <text x="512" y="330" text-anchor="middle" font-family="Segoe UI, Arial" font-size="38" fill="#0b4f6c">La crèche est fermée : l’équipe se forme</text>
    ${[300, 420, 540, 660].map((x, i) => kid(x + 40, 420, ["#ff7a59", "#3fa7d6", "#ffc53d", "#59b36b"][i], "#3b2a20", .9)).join("")}`),
};

/** Vidéos : une scène, un zoom lent et un panoramique, 8 secondes. */
const VIDEOS = { "video-bulles": "jeux-eau", "video-musique": "musique", "video-tour": "construction" };

// ------------------------------------------------------------------------------------------------- rendu

const work = mkdtempSync(join(tmpdir(), "ticagou-medias-"));
try {
  for (const [name, content] of Object.entries(SCENES)) {
    const source = join(work, `${name}.svg`);
    const png = join(work, `${name}.png`);
    writeFileSync(source, content);
    execFileSync(browser, ["--headless=new", "--disable-gpu", "--hide-scrollbars", `--user-data-dir=${join(work, "profil")}`, `--window-size=${W},${H}`,
      `--screenshot=${png}`, pathToFileURL(source).href], { stdio: "ignore" });
    execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-i", png, "-q:v", "4", join(OUT, `${name}.jpg`)]);
    console.log(`photo  ${name}.jpg`);
  }
  for (const [name, scene] of Object.entries(VIDEOS)) {
    // 8 s à 25 images/s, 640×480 : zoom de 1 à 1,25 centré, H.264 sans son, ~1 Mbit/s (ADR 0019 : 5 Mo au plus).
    execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-loop", "1", "-i", join(work, `${scene}.png`),
      "-vf", "scale=2048:1536,zoompan=z='min(zoom+0.0012,1.25)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=200:s=640x480:fps=25",
      "-t", "8", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-b:v", "900k", "-movflags", "+faststart", join(OUT, `${name}.mp4`)]);
    console.log(`vidéo  ${name}.mp4`);
  }
} finally {
  rmSync(work, { recursive: true, force: true });
}

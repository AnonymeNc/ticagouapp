// Briques communes des présentations Ti'Cagou : couleurs de la charte (web/src/index.css), modèles de diapositive,
// cartes, étapes numérotées et captures d'écran encadrées. Polices : Cambria (titres) et Calibri (texte), présentes
// avec Office partout ; Fraunces et Source Sans 3 de l'application ne le sont pas.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import pptxgen from "pptxgenjs";

export const RACINE = join(dirname(fileURLToPath(import.meta.url)), "..", "..");

export const COULEURS = {
  lagon700: "08585C",
  lagon600: "0B6E72",
  lagon100: "DCEFEE",
  lagon50: "EEF7F6",
  niaouli700: "3F5A26",
  niaouli100: "E7EEDB",
  terre600: "B5532A",
  encre: "2B2620",
  encreDouce: "665B4E",
  blanc: "FFFFFF",
  danger: "B3261E",
  danger100: "FBE4E1",
  alerte100: "FCEFD6",
  succes100: "E1F1E7",
};
const C = COULEURS;
export const TITRE = "Cambria";
export const TEXTE = "Calibri";
const PIED = "Ti'Cagou · Gestion de crèches et garderies · Nouvelle-Calédonie";

/** Image du dépôt : chemin, largeur et hauteur lues dans l'en-tête PNG. */
export function capture(chemin) {
  const fichier = join(RACINE, chemin);
  const tete = readFileSync(fichier).subarray(16, 24);
  return { path: fichier, l: tete.readUInt32BE(0), h: tete.readUInt32BE(4) };
}

/** Nouvelle présentation avec ses modèles. format : « 16:9 » ou « A4 » (portrait). */
export function nouvelle({ titre, sujet, format = "16:9" }) {
  const pres = new pptxgen();
  if (format === "A4") {
    pres.defineLayout({ name: "A4", width: 8.27, height: 11.69 });
    pres.layout = "A4";
  } else {
    pres.layout = "LAYOUT_WIDE";
  }
  pres.author = "Ti'Cagou";
  pres.company = "Ti'Cagou";
  pres.title = titre;
  pres.subject = sujet;
  pres.theme = { headFontFace: TITRE, bodyFontFace: TEXTE };
  if (format === "A4") return pres;

  pres.defineSlideMaster({
    title: "Ouverture",
    background: { color: C.lagon700 },
    objects: [
      { text: { text: PIED, options: { x: 0.6, y: 6.85, w: 9, h: 0.35, fontFace: TEXTE, fontSize: 11, color: C.lagon100, margin: 0 } } },
    ],
  });
  pres.defineSlideMaster({
    title: "Intercalaire",
    background: { color: C.lagon600 },
    objects: [
      { text: { text: PIED, options: { x: 0.6, y: 6.85, w: 9, h: 0.35, fontFace: TEXTE, fontSize: 11, color: C.lagon100, margin: 0 } } },
    ],
  });
  pres.defineSlideMaster({
    title: "Contenu",
    background: { color: C.blanc },
    objects: [
      { text: { text: PIED, options: { x: 0.6, y: 6.95, w: 9, h: 0.3, fontFace: TEXTE, fontSize: 10, color: C.encreDouce, margin: 0 } } },
    ],
    slideNumber: { x: 12.2, y: 6.95, w: 0.6, h: 0.3, fontFace: TEXTE, fontSize: 10, color: C.encreDouce, align: "right" },
  });
  return pres;
}

/** Diapositive de contenu : titre en haut à gauche, sous-titre facultatif. */
export function contenu(pres, titre, sousTitre) {
  const s = pres.addSlide({ masterName: "Contenu" });
  s.addText(titre, {
    x: 0.6, y: 0.35, w: 12.1, h: 0.8, fontFace: TITRE, fontSize: 34, bold: true, color: C.lagon700, margin: 0,
    valign: "middle", isTextBox: true, objectName: "Titre",
  });
  if (sousTitre) {
    s.addText(sousTitre, {
      x: 0.6, y: 1.15, w: 12.1, h: 0.45, fontFace: TEXTE, fontSize: 18, color: C.encreDouce, margin: 0, isTextBox: true,
      objectName: "Sous-titre",
    });
  }
  return s;
}

/** Hauteur approchée d'un texte (pouces) : pour empiler des blocs sans chevauchement. */
export function hauteurTexte(texte, largeur, taille) {
  const parLigne = Math.max(8, Math.floor(largeur / (taille * 0.0063)));
  const lignes = String(texte).split("\n").reduce((n, l) => n + Math.max(1, Math.ceil(l.length / parLigne)), 0);
  return lignes * taille * 0.0175 + 0.05;
}

/** Pastille ronde avec un numéro ou une lettre. */
export function pastille(s, x, y, d, texte, { fond = C.terre600, couleur = C.blanc, taille } = {}) {
  s.addShape("ellipse", { x, y, w: d, h: d, fill: { color: fond }, line: { color: fond }, objectName: `Pastille ${texte}` });
  s.addText(String(texte), {
    x, y, w: d, h: d, align: "center", valign: "middle", fontFace: TITRE, bold: true, fontSize: taille ?? Math.round(d * 30),
    color: couleur, margin: 0, isTextBox: true,
  });
}

/** Carte : fond teinté, titre en gras, texte ; numéro facultatif dans une pastille. */
export function carte(s, { x, y, w, h, titre, texte, numero, fond = C.lagon50, tailleTexte = 14 }) {
  s.addShape("roundRect", { x, y, w, h, rectRadius: 0.12, fill: { color: fond }, line: { color: fond }, objectName: `Carte ${titre}` });
  let gauche = x + 0.25;
  if (numero !== undefined) {
    pastille(s, x + 0.22, y + 0.22, 0.5, numero);
    gauche = x + 0.9;
  }
  const largeur = x + w - 0.25 - gauche;
  s.addText(titre, {
    x: gauche, y: y + 0.2, w: largeur, h: 0.55, fontFace: TEXTE, fontSize: 17, bold: true, color: C.lagon700, margin: 0,
    valign: "middle", isTextBox: true,
  });
  if (texte) {
    s.addText(texte, {
      x: gauche, y: y + 0.78, w: largeur, h: Math.max(0.4, h - 0.95), fontFace: TEXTE, fontSize: tailleTexte, color: C.encre,
      margin: 0, valign: "top", isTextBox: true,
    });
  }
}

/** Grille de cartes (colonnes × lignes) dans un cadre. */
export function grille(s, cartes, { x = 0.6, y = 1.75, w = 12.1, h = 4.95, colonnes = 2, ecart = 0.3, numeros = false, tailleTexte } = {}) {
  const lignes = Math.ceil(cartes.length / colonnes);
  const lc = (w - ecart * (colonnes - 1)) / colonnes;
  const hc = (h - ecart * (lignes - 1)) / lignes;
  cartes.forEach(([titre, texte], i) => {
    const col = i % colonnes;
    const lig = Math.floor(i / colonnes);
    carte(s, { x: x + col * (lc + ecart), y: y + lig * (hc + ecart), w: lc, h: hc, titre, texte, numero: numeros ? i + 1 : undefined, tailleTexte });
  });
}

/** Lignes « titre gras + texte » empilées, avec une pastille numérotée ou une puce ronde. */
export function lignes(s, items, { x, y, w, numeros = false, tailleTexte = 15, ecart = 0.28 }) {
  let haut = y;
  items.forEach(([titre, texte], i) => {
    if (numeros) pastille(s, x, haut, 0.46, i + 1);
    else s.addShape("ellipse", { x: x + 0.13, y: haut + 0.13, w: 0.2, h: 0.2, fill: { color: C.terre600 }, line: { color: C.terre600 } });
    const gauche = x + 0.65;
    const largeur = w - 0.65;
    s.addText(titre, {
      x: gauche, y: haut, w: largeur, h: 0.46, fontFace: TEXTE, fontSize: 17, bold: true, color: C.lagon700, margin: 0,
      valign: "middle", isTextBox: true,
    });
    haut += 0.46;
    if (texte) {
      const ht = hauteurTexte(texte, largeur, tailleTexte);
      s.addText(texte, {
        x: gauche, y: haut, w: largeur, h: ht, fontFace: TEXTE, fontSize: tailleTexte, color: C.encre, margin: 0,
        valign: "top", isTextBox: true,
      });
      haut += ht;
    }
    haut += ecart;
  });
  return haut;
}

/** Capture d'écran dans un cadre blanc ombré, ajustée à la boîte (x, y, w, h) sans déformation. */
export function ecran(s, chemin, { x, y, w, h, legende, recadrage, ombre = true }) {
  const img = capture(chemin);
  const src = recadrage ?? { x: 0, y: 0, w: img.l, h: img.h };
  const ratio = src.w / src.h;
  const hLeg = legende ? 0.4 : 0;
  let lw = w;
  let lh = lw / ratio;
  if (lh > h - hLeg) {
    lh = h - hLeg;
    lw = lh * ratio;
  }
  const ix = x + (w - lw) / 2;
  const iy = y;
  const m = 0.06;
  s.addShape("roundRect", {
    x: ix - m, y: iy - m, w: lw + 2 * m, h: lh + 2 * m, rectRadius: 0.08, fill: { color: C.blanc },
    line: { color: "D9D2C7", width: 0.75 },
    shadow: ombre ? { type: "outer", color: "000000", opacity: 0.18, blur: 8, offset: 3, angle: 90 } : undefined,
    objectName: `Cadre ${legende ?? chemin}`,
  });
  const options = { path: img.path, x: ix, y: iy, w: lw, h: lh, altText: legende ?? "Capture d'écran de Ti'Cagou" };
  if (recadrage) {
    // pptxgenjs recadre en pouces dans l'image redimensionnée à w × h de l'image entière.
    const echelle = lw / src.w;
    options.w = img.l * echelle;
    options.h = img.h * echelle;
    options.sizing = { type: "crop", x: src.x * echelle, y: src.y * echelle, w: lw, h: lh };
  }
  s.addImage(options);
  if (legende) {
    s.addText(legende, {
      x, y: iy + lh + 0.12, w, h: 0.3, align: "center", fontFace: TEXTE, fontSize: 13, color: C.encreDouce, margin: 0,
      isTextBox: true,
    });
  }
  return { x: ix, y: iy, w: lw, h: lh };
}

/** Encadré « Exercice » : fond niaouli clair, case à cocher. */
export function exercice(s, texte, { x, y, w, h }) {
  s.addShape("roundRect", { x, y, w, h, rectRadius: 0.12, fill: { color: C.niaouli100 }, line: { color: C.niaouli100 }, objectName: "Exercice" });
  s.addText("☐  Exercice", {
    x: x + 0.25, y: y + 0.18, w: w - 0.5, h: 0.4, fontFace: TEXTE, fontSize: 17, bold: true, color: C.niaouli700, margin: 0,
    isTextBox: true,
  });
  s.addText(texte, {
    x: x + 0.25, y: y + 0.62, w: w - 0.5, h: h - 0.8, fontFace: TEXTE, fontSize: 15, color: C.encre, margin: 0, valign: "top",
    isTextBox: true,
  });
}

/** Pastille de durée (« 30 min ») à droite du titre. */
export function duree(s, texte, { x = 11.3, y = 0.5 } = {}) {
  s.addShape("roundRect", { x, y, w: 1.4, h: 0.5, rectRadius: 0.25, fill: { color: C.lagon100 }, line: { color: C.lagon100 } });
  s.addText(texte, {
    x, y, w: 1.4, h: 0.5, align: "center", valign: "middle", fontFace: TEXTE, fontSize: 15, bold: true, color: C.lagon700,
    margin: 0, isTextBox: true,
  });
}

/** Diapositive d'ouverture sombre : grand titre, sous-titre, étiquettes, image facultative à droite. */
export function ouverture(pres, { titre, sousTitre, etiquettes = [], image, recadrage, notes }) {
  const s = pres.addSlide({ masterName: "Ouverture" });
  const largeur = image ? 7.2 : 12;
  s.addText(titre, {
    x: 0.6, y: 1.2, w: largeur, h: 1.6, fontFace: TITRE, fontSize: titre.length > 22 ? 44 : 60, bold: true, color: C.blanc,
    margin: 0, valign: "bottom", isTextBox: true, objectName: "Titre",
  });
  s.addText(sousTitre, {
    x: 0.6, y: 3.0, w: largeur, h: 1.2, fontFace: TEXTE, fontSize: 22, color: C.lagon100, margin: 0, valign: "top",
    isTextBox: true,
  });
  let ex = 0.6;
  let ey = 4.55;
  etiquettes.forEach((e) => {
    const l = 0.4 + e.length * 0.105;
    if (ex + l > 0.6 + largeur) {
      ex = 0.6;
      ey += 0.7;
    }
    s.addShape("roundRect", { x: ex, y: ey, w: l, h: 0.5, rectRadius: 0.25, fill: { color: C.lagon600 }, line: { color: C.lagon100, width: 0.75 } });
    s.addText(e, { x: ex, y: ey, w: l, h: 0.5, align: "center", valign: "middle", fontFace: TEXTE, fontSize: 15, color: C.blanc, margin: 0, isTextBox: true });
    ex += l + 0.25;
  });
  if (image) ecran(s, image, { x: 8.4, y: 0.6, w: 4.3, h: 6.0, recadrage });
  if (notes) s.addNotes(notes);
  return s;
}

/** Intercalaire de partie (fond lagon) : grand numéro, titre, ligne d'explication. */
export function intercalaire(pres, { numero, titre, texte }) {
  const s = pres.addSlide({ masterName: "Intercalaire" });
  if (numero !== undefined) pastille(s, 0.6, 2.0, 1.1, numero, { fond: C.blanc, couleur: C.lagon600, taille: 36 });
  s.addText(titre, {
    x: 0.6, y: 3.3, w: 12, h: 1.0, fontFace: TITRE, fontSize: 44, bold: true, color: C.blanc, margin: 0, isTextBox: true,
    objectName: "Titre",
  });
  if (texte) {
    s.addText(texte, { x: 0.6, y: 4.35, w: 12, h: 0.6, fontFace: TEXTE, fontSize: 20, color: C.lagon100, margin: 0, isTextBox: true });
  }
  return s;
}

/**
 * Module de formation : « N · titre », durée, points à gauche. Avec une capture : capture à droite et exercice sous
 * elle ; sans capture : points sur toute la largeur et exercice en bas. Le texte se resserre s'il déborde.
 */
export function module(pres, { numero, titre, minutes, points, exercice: texteExercice, image, legende, notes }) {
  const s = contenu(pres, `${numero} · ${titre}`);
  duree(s, `${minutes} min`);
  const avecImage = Boolean(image);
  const largeur = avecImage ? 6.9 : 12.1;
  const limite = avecImage || !texteExercice ? 6.7 : 5.35;
  const hauteur = (taille, ecart) => points.reduce((h, [, t]) => h + 0.46 + (t ? hauteurTexte(t, largeur - 0.65, taille) : 0) + ecart, 0);
  let taille = 15;
  let ecart = 0.22;
  if (1.55 + hauteur(taille, ecart) > limite) { taille = 14; ecart = 0.14; }
  lignes(s, points, { x: 0.6, y: 1.55, w: largeur, tailleTexte: taille, ecart });
  if (avecImage) {
    const hImage = texteExercice ? 3.45 : 5.1;
    ecran(s, image, { x: 7.9, y: 1.55, w: 4.8, h: hImage, legende });
    if (texteExercice) exercice(s, texteExercice, { x: 7.6, y: 5.25, w: 5.1, h: 1.45 });
  } else if (texteExercice) {
    exercice(s, texteExercice, { x: 0.6, y: 5.5, w: largeur, h: 1.2 });
  }
  if (notes) s.addNotes(notes);
  return s;
}

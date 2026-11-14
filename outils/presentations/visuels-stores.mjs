#!/usr/bin/env node
/**
 * Images de présentation Google Play (1024 × 500) des trois applications : un .pptx d'une diapositive par
 * application, exporté en PNG par PowerPoint (Windows) avec exporter-png.ps1.
 *
 *   node visuels-stores.mjs
 *   powershell -ExecutionPolicy Bypass -File exporter-png.ps1
 */
import { join } from "node:path";
import pptxgen from "pptxgenjs";
import { COULEURS as C, RACINE, TEXTE, TITRE, ecran } from "./lib.mjs";

const CAP = "docs/commercial/captures/mobile/";
const APPS = [
  { app: "direction", titre: "Ti’Cagou Direction", texte: "Votre crèche dans la poche : encadrement en direct, départs à valider.", image: CAP + "tableau-de-bord-telephone.png" },
  { app: "pointage", titre: "Ti’Cagou Pointage", texte: "La tablette d’accueil : arrivées, départs, même sans internet.", image: "docs/commercial/captures/stores/pointage/kiosk-store-tablette-android.png" },
  { app: "parents", titre: "Ti’Cagou Parents", texte: "La journée de votre enfant à la crèche, sur votre téléphone.", image: "docs/commercial/prospectus/03-parents.png", recadrage: { x: 800, y: 60, w: 610, h: 790 } },
];

const pres = new pptxgen();
pres.defineLayout({ name: "PLAY", width: 10.24, height: 5.0 });
pres.layout = "PLAY";
pres.title = "Ti'Cagou — images des stores";
for (const a of APPS) {
  const s = pres.addSlide();
  s.background = { color: C.lagon700 };
  s.addText(a.titre, { x: 0.5, y: 1.2, w: 5.4, h: 1.0, fontFace: TITRE, fontSize: 38, bold: true, color: C.blanc, margin: 0, isTextBox: true });
  s.addText(a.texte, { x: 0.5, y: 2.3, w: 5.0, h: 1.2, fontFace: TEXTE, fontSize: 20, color: C.lagon100, margin: 0, valign: "top", isTextBox: true });
  const large = a.app === "pointage";
  ecran(s, a.image, large ? { x: 5.9, y: 0.75, w: 4.0, h: 3.5 } : { x: 6.6, y: 0.35, w: 3.0, h: 4.3, recadrage: a.recadrage, ombre: !a.recadrage });
  s.addNotes(a.app);
}
await pres.writeFile({ fileName: join(RACINE, "outils", "presentations", "visuels-stores.pptx") });
console.log("outils/presentations/visuels-stores.pptx");

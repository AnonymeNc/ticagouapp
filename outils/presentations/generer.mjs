#!/usr/bin/env node
/**
 * Génère les présentations PowerPoint de Ti'Cagou dans docs/commercial/presentations/ :
 * présentation commerciale, plaquette A4, formation direction, formation équipes, formation applications mobiles.
 * Contenu tiré du wiki (docs/wiki/12, 13, 19) et du barème ; captures de docs/commercial/captures.
 *
 *   cd outils/presentations && pnpm install && pnpm generer
 */
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import {
  COULEURS as C, RACINE, TEXTE, TITRE, carte, contenu, duree, ecran, exercice, grille, intercalaire, lignes, module,
  nouvelle, ouverture, pastille,
} from "./lib.mjs";

const SORTIE = join(RACINE, "docs", "commercial", "presentations");
mkdirSync(SORTIE, { recursive: true });

const CAP = "docs/commercial/captures/mobile/";
const KIOSQUE = "docs/commercial/captures/stores/pointage/kiosk-store-tablette-android.png";
// Les deux téléphones de l'application des parents, découpés dans le prospectus « parents ».
const PARENTS = { chemin: "docs/commercial/prospectus/03-parents.png", recadrage: { x: 800, y: 60, w: 610, h: 790 } };

async function ecrire(pres, nom) {
  await pres.writeFile({ fileName: join(SORTIE, nom) });
  console.log(`docs/commercial/presentations/${nom}`);
}

// ---------------------------------------------------------------------------------------------------------------
// 1. Présentation commerciale
// ---------------------------------------------------------------------------------------------------------------
async function presentation() {
  const pres = nouvelle({ titre: "Ti'Cagou — Présentation", sujet: "Présentation commerciale de Ti'Cagou" });

  ouverture(pres, {
    titre: "Ti’Cagou",
    sousTitre: "Le logiciel de gestion des crèches et garderies de Nouvelle-Calédonie",
    etiquettes: ["Rien à installer", "Ordinateur, tablette, téléphone", "Facturation avec les aides"],
    image: CAP + "tableau-de-bord-telephone.png",
    notes: "Présentez-vous, puis demandez à la directrice comment elle gère aujourd'hui présences, contrats et factures.",
  });

  let s = contenu(pres, "Votre quotidien aujourd’hui");
  grille(s, [
    ["Cahiers et tableurs", "Présences sur papier, contrats dans un tableur, factures refaites chaque mois à la main."],
    ["Aides compliquées", "Province, CAFAT, employeurs : chaque aide se calcule et se réclame à part, avec ses justificatifs."],
    ["Départs à sécuriser", "Qui a le droit de venir chercher l’enfant ? L’équipe doit le savoir tout de suite, même une remplaçante."],
    ["Encadrement à surveiller", "Le taux d’encadrement change à chaque arrivée et à chaque départ, de l’équipe comme des enfants."],
  ], { numeros: true, y: 1.6, h: 5.05, tailleTexte: 17 });
  s.addNotes("Faites réagir : laquelle de ces situations vous prend le plus de temps ?");

  s = contenu(pres, "Ce que fait Ti’Cagou", "Une seule formule : tout est compris.");
  grille(s, [
    ["Enfants et familles", "Fiches, personnes autorisées avec photo, santé et PAI toujours visibles."],
    ["Pointage sur tablette", "Arrivées, départs, journée des enfants ; continue sans internet."],
    ["Encadrement en direct", "Alerte quand une section manque d’adultes, selon vos règles."],
    ["Facturation", "Mensualisé ou au réel, aides déduites, avoirs, relances, export comptable."],
    ["Repas", "Fiche cuisine avec allergies et régimes, menus, récapitulatif trimestriel."],
    ["Personnel", "Diplômes et échéances, planning, heures du mois pour la paie."],
    ["Parents", "Application : journée, photos, absences, messages, factures, signatures."],
    ["Direction", "Application : la crèche sur votre téléphone, départs à valider à distance."],
  ], { colonnes: 4, y: 1.8, h: 4.9, tailleTexte: 14 });

  s = contenu(pres, "Le tableau de bord", "Tout ce qui demande votre attention, dès le matin.");
  ecran(s, CAP + "tableau-de-bord-bureau.png", { x: 0.6, y: 1.85, w: 7.4, h: 4.85 });
  lignes(s, [
    ["Encadrement insuffisant", "Section par section, en temps réel."],
    ["Allergies des enfants présents", "Et les PAI, avec la conduite à tenir."],
    ["Impayés, diplômes, contrats", "Les échéances arrivent avant qu’il ne soit trop tard."],
  ], { x: 8.5, y: 2.0, w: 4.2 });

  s = contenu(pres, "La tablette d’accueil", "Pointer en un geste, remettre l’enfant à la bonne personne.");
  ecran(s, KIOSQUE, { x: 0.6, y: 1.85, w: 7.4, h: 4.85 });
  lignes(s, [
    ["Départ avec photo", "Seules les personnes autorisées sont proposées."],
    ["Personne inconnue : écran rouge", "L’enfant ne part pas sans l’accord de la direction."],
    ["Sans internet, on continue", "Les pointages partent seuls au retour du réseau."],
    ["Mode kiosque", "L’application Ti’Cagou Pointage garde la tablette sur l’accueil."],
  ], { x: 8.5, y: 1.95, w: 4.2, ecart: 0.2 });

  s = contenu(pres, "Un départ bloqué ? Vous décidez à distance", "La tablette demande, la directrice répond depuis son téléphone.");
  ecran(s, CAP + "depart-a-valider-tablette.png", { x: 0.6, y: 1.85, w: 5.6, h: 4.0, legende: "Tablette de l’accueil" });
  ecran(s, CAP + "depart-a-valider-telephone.png", { x: 6.5, y: 1.85, w: 2.3, h: 4.8, legende: "Téléphone de la direction" });
  lignes(s, [
    ["L’équipe demande", "Nom, lien, pièce d’identité vue."],
    ["La direction est prévenue", "Notification sur son téléphone."],
    ["Autoriser ou refuser", "Avec un motif, tracé au journal."],
  ], { x: 9.2, y: 1.95, w: 3.5, numeros: true });

  s = contenu(pres, "La facturation avec les aides", "Le mois se facture en quelques minutes, sans oubli.");
  ecran(s, CAP + "facturation-tablette.png", { x: 0.6, y: 1.85, w: 7.4, h: 4.85 });
  lignes(s, [
    ["Anomalies avant de facturer", "Pointage manquant, justificatif absent : vous corrigez d’abord."],
    ["Aides déduites et réclamées", "Province, CAFAT, employeur, avec les justificatifs."],
    ["Export comptable", "Fichier FEC ou CSV pour votre cabinet."],
  ], { x: 8.5, y: 2.0, w: 4.2 });

  s = contenu(pres, "La fiche de l’enfant", "La santé de l’enfant, visible partout où il apparaît.");
  ecran(s, CAP + "fiche-sante-tablette.png", { x: 0.6, y: 1.85, w: 7.4, h: 4.85 });
  lignes(s, [
    ["Allergies, PAI, conduite à tenir", "En tête de fiche, sur la tablette, en cuisine et sur le téléphone."],
    ["Accès selon le rôle", "Chaque consultation de dossier est enregistrée."],
    ["Documents chiffrés", "PAI, contrats, pièces : rangés et protégés."],
  ], { x: 8.5, y: 2.0, w: 4.2 });

  s = contenu(pres, "Toute la crèche, sur chaque écran", "Le même logiciel au bureau, sur la tablette et dans la poche de la direction.");
  ecran(s, CAP + "presences-bureau.png", { x: 0.6, y: 1.9, w: 5.6, h: 4.3, legende: "Ordinateur du bureau" });
  ecran(s, CAP + "presences-tablette.png", { x: 6.55, y: 1.9, w: 3.9, h: 4.3, legende: "Tablette" });
  ecran(s, CAP + "presences-telephone.png", { x: 10.8, y: 1.9, w: 1.9, h: 4.8, legende: "Téléphone" });

  s = contenu(pres, "L’application des parents", "Gratuite pour les familles, sur iPhone et Android.");
  ecran(s, PARENTS.chemin, { x: 0.6, y: 1.8, w: 5.6, h: 4.9, recadrage: PARENTS.recadrage, ombre: false });
  lignes(s, [
    ["La journée de l’enfant", "Repas, sieste, activités, photos et vidéos autorisées, partagés par l’équipe."],
    ["Absences, retards et messages", "Les parents préviennent, la crèche confirme."],
    ["Factures et documents à signer", "Consultés et signés depuis le téléphone."],
    ["Aucune donnée de santé", "Ni dans l’application, ni dans les notifications, sauf ce que la direction partage."],
  ], { x: 6.9, y: 1.9, w: 5.8, ecart: 0.2 });

  s = contenu(pres, "Vos données en sécurité", "Hébergées, chiffrées, sauvegardées : vous n’avez rien à gérer.");
  [
    ["Chaque nuit", "Sauvegarde chiffrée, copiée chez un second hébergeur"],
    ["2 facteurs", "Mot de passe et code du téléphone pour la direction et la comptabilité"],
    ["Par rôle", "Chacun ne voit que ce qui le concerne ; consultations tracées"],
    ["Zéro", "information de santé dans les notifications"],
  ].forEach(([grand, petit], i) => {
    const x = 0.6 + i * 3.1;
    s.addShape("roundRect", { x, y: 2.0, w: 2.8, h: 3.6, rectRadius: 0.12, fill: { color: C.lagon50 }, line: { color: C.lagon50 } });
    s.addText(grand, { x: x + 0.2, y: 2.3, w: 2.4, h: 1.1, fontFace: TITRE, fontSize: 30, bold: true, color: C.terre600, margin: 0, valign: "middle", isTextBox: true });
    s.addText(petit, { x: x + 0.2, y: 3.5, w: 2.4, h: 1.9, fontFace: TEXTE, fontSize: 16, color: C.encre, margin: 0, valign: "top", isTextBox: true });
  });
  s.addText("Un téléphone perdu se retire depuis l’ordinateur de la crèche. Les mises à jour sont faites par Ti’Cagou.", {
    x: 0.6, y: 5.95, w: 12.1, h: 0.5, fontFace: TEXTE, fontSize: 16, color: C.encreDouce, margin: 0, isTextBox: true,
  });

  s = contenu(pres, "L’offre", "Une seule formule, tout compris, quelle que soit la taille de la crèche.");
  s.addShape("roundRect", { x: 0.6, y: 1.85, w: 5.4, h: 4.85, rectRadius: 0.15, fill: { color: C.lagon700 }, line: { color: C.lagon700 } });
  s.addText("15 000 F", { x: 0.9, y: 2.15, w: 4.8, h: 1.1, fontFace: TITRE, fontSize: 60, bold: true, color: C.blanc, margin: 0, isTextBox: true });
  s.addText("HT par mois", { x: 0.9, y: 3.25, w: 4.8, h: 0.5, fontFace: TEXTE, fontSize: 20, color: C.lagon100, margin: 0, isTextBox: true });
  s.addText([
    { text: "Toutes les fonctions et les trois applications", options: { bullet: true, breakLine: true } },
    { text: "1 PC, 2 tablettes, jusqu’à 10 téléphones", options: { bullet: true, breakLine: true } },
    { text: "Support rapide, mises à jour, sauvegardes", options: { bullet: true, breakLine: true } },
    { text: "180 000 F HT par an payé d’avance", options: { bullet: true } },
  ], { x: 0.9, y: 3.95, w: 4.9, h: 2.5, fontFace: TEXTE, fontSize: 16, color: C.blanc, margin: 0, paraSpaceAfter: 6, valign: "top", isTextBox: true });
  carte(s, { x: 6.4, y: 1.85, w: 6.3, h: 2.25, titre: "Options", texte: "PC supplémentaire : 1 500 F par mois\nTablette supplémentaire : 1 000 F par mois\nEngagement 3 ans payé à l’année : − 10 %" });
  carte(s, { x: 6.4, y: 4.4, w: 6.3, h: 2.3, titre: "Mise en service", texte: "Mise en route : 30 000 F · reprise des données : offerte\nFormation direction (3 h) : 30 000 F\nFormation équipe (1 h, 8 personnes) : 15 000 F" });
  s.addNotes("Prix HT, TGC en sus selon le régime applicable. Offre de lancement possible : mise en route offerte pour 3 ans.");

  s = contenu(pres, "La mise en service", "Rien à installer : votre crèche est en ligne en une semaine.");
  [
    ["Semaine 1", "Rendez-vous de préparation", "Vos tarifs, aides, sections, horaires et le matériel."],
    ["Semaine 1", "Votre crèche en ligne", "Reprise de vos fichiers Excel, relue par la direction."],
    ["Jour J", "Formation", "3 h avec la direction, 1 h par équipe, applications installées."],
    ["Mois 1", "Première facturation", "Accompagnée, puis bilan ensemble."],
  ].forEach(([quand, titre, texte], i) => {
    const x = 0.6 + i * 3.1;
    pastille(s, x, 2.1, 0.8, i + 1);
    if (i < 3) s.addShape("line", { x: x + 0.95, y: 2.5, w: 2.0, h: 0, line: { color: C.lagon100, width: 2 } });
    s.addText(quand, { x, y: 3.1, w: 2.8, h: 0.4, fontFace: TEXTE, fontSize: 15, bold: true, color: C.terre600, margin: 0, isTextBox: true });
    s.addText(titre, { x, y: 3.5, w: 2.8, h: 0.8, fontFace: TEXTE, fontSize: 19, bold: true, color: C.lagon700, margin: 0, valign: "top", isTextBox: true });
    s.addText(texte, { x, y: 4.35, w: 2.8, h: 1.5, fontFace: TEXTE, fontSize: 15, color: C.encre, margin: 0, valign: "top", isTextBox: true });
  });

  ouverture(pres, {
    titre: "Merci !",
    sousTitre: "Demandez une démonstration sur votre téléphone.\n[Votre nom] · [téléphone] · [e-mail] · [site]",
    notes: "Remplacez la ligne de contact avant d'envoyer la présentation.",
  });

  await ecrire(pres, "Ti'Cagou - Présentation.pptx");
}

// ---------------------------------------------------------------------------------------------------------------
// 2. Plaquette A4
// ---------------------------------------------------------------------------------------------------------------
async function plaquette() {
  const pres = nouvelle({ titre: "Ti'Cagou — Plaquette", sujet: "Plaquette commerciale A4", format: "A4" });
  const s = pres.addSlide();
  s.background = { color: C.blanc };
  s.addShape("rect", { x: 0, y: 0, w: 8.27, h: 3.55, fill: { color: C.lagon700 }, line: { color: C.lagon700 } });
  s.addText("Ti’Cagou", { x: 0.5, y: 0.45, w: 5, h: 0.9, fontFace: TITRE, fontSize: 44, bold: true, color: C.blanc, margin: 0, isTextBox: true });
  s.addText("Le logiciel de gestion des crèches et garderies de Nouvelle-Calédonie", {
    x: 0.5, y: 1.4, w: 4.6, h: 1.0, fontFace: TEXTE, fontSize: 17, color: C.lagon100, margin: 0, valign: "top", isTextBox: true,
  });
  s.addText("Rien à installer · sur ordinateur, tablette et téléphone · données hébergées et sauvegardées", {
    x: 0.5, y: 2.5, w: 4.6, h: 0.75, fontFace: TEXTE, fontSize: 13, color: C.blanc, margin: 0, valign: "top", isTextBox: true,
  });
  ecran(s, CAP + "tableau-de-bord-telephone.png", { x: 5.55, y: 0.35, w: 2.3, h: 4.6 });

  const blocs = [
    ["Départs sécurisés", "Personnes autorisées avec photo ; inconnu = écran rouge, validé à distance par la direction."],
    ["Encadrement en direct", "Alerte sur le téléphone dès qu’une section manque d’adultes."],
    ["Facturation avec aides", "Province, CAFAT, employeurs ; avoirs, relances, export comptable."],
    ["Santé toujours visible", "Allergies et PAI sur la fiche, la tablette, en cuisine et sur le téléphone."],
    ["Sans internet", "Le pointage continue sur la tablette, tout part au retour du réseau."],
    ["Les parents dans la boucle", "Application gratuite : journée, photos, absences, messages, factures."],
  ];
  blocs.forEach(([titre, texte], i) => {
    const x = 0.5 + (i % 2) * 3.7;
    const y = 5.2 + Math.floor(i / 2) * 1.55;
    pastille(s, x, y, 0.45, i + 1, { taille: 14 });
    s.addText(titre, { x: x + 0.6, y, w: 2.9, h: 0.45, fontFace: TEXTE, fontSize: 14, bold: true, color: C.lagon700, margin: 0, valign: "middle", isTextBox: true });
    s.addText(texte, { x: x + 0.6, y: y + 0.48, w: 2.9, h: 0.9, fontFace: TEXTE, fontSize: 11, color: C.encre, margin: 0, valign: "top", isTextBox: true });
  });
  s.addShape("roundRect", { x: 0.5, y: 9.95, w: 7.27, h: 1.05, rectRadius: 0.12, fill: { color: C.lagon50 }, line: { color: C.lagon50 } });
  s.addText([
    { text: "15 000 F HT par mois, tout compris", options: { bold: true, color: C.lagon700, fontSize: 16, breakLine: true } },
    { text: "Toutes les fonctions, les trois applications, le support et les mises à jour. Mise en service et formation sur place.", options: { fontSize: 11, color: C.encre } },
  ], { x: 0.75, y: 10.0, w: 6.8, h: 0.95, fontFace: TEXTE, margin: 0, valign: "middle", isTextBox: true });
  s.addText("[Votre nom] · [téléphone] · [e-mail] · [site]", {
    x: 0.5, y: 11.15, w: 7.27, h: 0.35, fontFace: TEXTE, fontSize: 11, color: C.encreDouce, margin: 0, align: "center", isTextBox: true,
  });
  await ecrire(pres, "Ti'Cagou - Plaquette.pptx");
}

// ---------------------------------------------------------------------------------------------------------------
// 3. Formation direction (docs/wiki/12-formation-direction.md)
// ---------------------------------------------------------------------------------------------------------------
async function formationDirection() {
  const pres = nouvelle({ titre: "Ti'Cagou — Formation de la direction", sujet: "Support de formation de la direction" });
  ouverture(pres, {
    titre: "Formation de la direction",
    sousTitre: "Faire seule ce qui revient chaque jour, chaque mois et chaque année",
    etiquettes: ["3 h 30", "PC du bureau et téléphone", "Directrice, adjointe, gestionnaire"],
    notes: "Faites faire chaque geste par la directrice, souris en main. Cochez la case en fin de module.",
  });

  const deroule = [
    ["Se repérer", 15], ["Enfants et familles", 30], ["Inscriptions et contrats", 30], ["Pointage et encadrement", 20],
    ["Facturation du mois", 40], ["Repas et personnel", 20], ["Paramètres", 20], ["Sécurité et abonnement", 15],
    ["Portail parents", 20], ["Ti’Cagou sur le téléphone", 30],
  ];
  let s = contenu(pres, "Déroulé de la formation", "Dix modules, sur les vraies données de la crèche.");
  deroule.forEach(([titre, min], i) => {
    const x = 0.6 + (i % 2) * 6.2;
    const y = 1.85 + Math.floor(i / 2) * 0.97;
    s.addShape("roundRect", { x, y, w: 5.9, h: 0.8, rectRadius: 0.1, fill: { color: C.lagon50 }, line: { color: C.lagon50 } });
    pastille(s, x + 0.15, y + 0.12, 0.56, i + 1, { taille: 16 });
    s.addText(titre, { x: x + 0.9, y, w: 3.8, h: 0.8, fontFace: TEXTE, fontSize: 17, bold: true, color: C.lagon700, margin: 0, valign: "middle", isTextBox: true });
    s.addText(`${min} min`, { x: x + 4.6, y, w: 1.1, h: 0.8, fontFace: TEXTE, fontSize: 15, color: C.encreDouce, margin: 0, valign: "middle", align: "right", isTextBox: true });
  });

  module(pres, {
    numero: 1, titre: "Se repérer", minutes: 15,
    points: [
      ["Se connecter avec son propre compte", "Adresse de la crèche dans le navigateur, identifiant, mot de passe et code du téléphone. Après plusieurs erreurs, le compte se bloque un moment : c’est voulu."],
      ["Le menu de gauche", "Tableau de bord, Présences, Enfants, Inscriptions, Facturation, Statistiques, Repas, Personnel, Messages, Paramètres."],
      ["Le tableau de bord", "Présents sur attendus, encadrement en direct, alertes. Se déconnecter en quittant le poste."],
    ],
    exercice: "La directrice se connecte et lit seule son tableau de bord.",
    image: CAP + "tableau-de-bord-bureau.png",
  });
  module(pres, {
    numero: 2, titre: "Les enfants et les familles", minutes: 30,
    points: [
      ["La santé toujours en haut", "Allergies et PAI en tête de fiche. Onglets : identité, famille, autorisés, santé, autorisations, contrat, présences, journée, facturation, documents."],
      ["Personnes autorisées", "Nom, lien, téléphone, photo, pièce vérifiée : les seules proposées au départ de l’enfant."],
      ["Consultations tracées", "Chaque ouverture de fiche est journalisée : n’ouvrez que les fiches utiles."],
      ["Données d’une famille", "Export complet, ou suppression d’une famille partie sans factures."],
    ],
    exercice: "Ajouter une personne autorisée avec sa photo ; ajouter une allergie et la voir en haut de la fiche.",
    image: CAP + "fiche-sante-tablette.png",
  });
  module(pres, {
    numero: 3, titre: "Inscriptions et contrats", minutes: 30,
    points: [
      ["Les demandes avancent par étapes", "Reçue, à l’étude, place proposée, acceptée, contrat signé. Pré-inscriptions en ligne comprises."],
      ["Capacité", "Places libres par section, par jour et par mois ; « Tester une demande »."],
      ["Contrat", "Planning type, barème, mensualisé ou au réel, adaptation, puis PDF à faire signer ; avenant en cours d’année."],
    ],
    exercice: "Faire un devis de contrat pour 2 jours par semaine et comparer mensualisé et au réel.",
  });
  module(pres, {
    numero: 4, titre: "Le pointage et l’encadrement", minutes: 20,
    points: [
      ["Le PIN de la direction", "Il sert à pointer et à valider un départ avec une personne non autorisée (écran rouge). Ne le donnez à personne."],
      ["Présences du jour", "« Corriger », « Ajouter un passage », pointages hors connexion à vérifier, absences avec leur motif."],
      ["Encadrement en direct", "Calculé à partir des enfants présents et du personnel pointé."],
    ],
    exercice: "Corriger une heure d’arrivée oubliée.",
    image: CAP + "presences-bureau.png",
  });
  module(pres, {
    numero: 5, titre: "La facturation du mois", minutes: 40,
    points: [
      ["Corriger les anomalies d’abord", "Pointage manquant, contrat expiré, payeur ou justificatif manquant. Sinon : « Prête à facturer »."],
      ["Générer les factures", "Les numéros se suivent sans trou. Une facture ne se modifie plus : on fait un avoir."],
      ["Encaissements et financeurs", "Paiement partiel, reçu, relance ; montants à réclamer à chaque organisme."],
      ["Export comptable et statistiques", "CSV ou FEC pour le cabinet ; bilan d’activité en PDF et Excel."],
    ],
    exercice: "Générer les factures d’un mois de test, enregistrer un paiement partiel, faire un avoir.",
    image: CAP + "facturation-tablette.png",
  });
  module(pres, {
    numero: 6, titre: "Repas et personnel", minutes: 20,
    points: [
      ["Repas du jour", "Allergies et régimes en évidence, « Mode cuisine », « Imprimer la fiche »."],
      ["Menus et récapitulatif", "Menus de la semaine, récapitulatif trimestriel pour la demande d’aide."],
      ["Personnel", "Diplômes et échéances, planning (sous-effectif en rouge), heures : « Valider le mois », puis export pour la paie."],
    ],
    exercice: "Valider les heures d’un mois de test.",
  });
  module(pres, {
    numero: 7, titre: "Les paramètres", minutes: 20,
    points: [
      ["Structure", "Identité, agrément, IBAN imprimé sur les factures, horaires. Seule la direction y accède."],
      ["Règles de fonctionnement", "Sections, encadrement, critères d’inscription, repas, barèmes datés, financeurs, comptabilité."],
      ["Utilisateurs et rôles", "Un compte par personne, jamais partagé, avec son PIN. Désactiver le compte d’une personne qui part."],
      ["Tablettes et abonnement", "Appairer ou retirer une tablette, voir l’abonnement et les téléphones de la direction."],
    ],
    exercice: "Créer le compte d’une nouvelle éducatrice avec son PIN, puis le désactiver.",
  });
  module(pres, {
    numero: 8, titre: "Sécurité, sauvegardes, abonnement", minutes: 15,
    points: [
      ["Double authentification", "Mot de passe et code du téléphone. Téléphone perdu : la directrice réinitialise pour son équipe ; pour elle-même, elle appelle Ti’Cagou."],
      ["Sauvegardes : rien à faire", "Chaque nuit, chiffrées et copiées hors site. Une sauvegarde manuelle avant une opération importante."],
      ["Abonnement et postes", "Places, PC, tablettes et échéance ; renouvellement par Ti’Cagou, sans rien saisir. Un PC remplacé se retire."],
      ["Sans internet", "Le bureau est inaccessible, les tablettes continuent. Routeur 4G de secours s’il y en a un."],
    ],
    exercice: "Se connecter avec son code, puis lancer une sauvegarde manuelle.",
  });
  module(pres, {
    numero: 9, titre: "Le portail parents", minutes: 20,
    points: [
      ["Paramètres › Portail parents", "Ce qui est partagé, coordonnées de paiement, heure limite des repas."],
      ["Santé partagée", "Seul le texte écrit dans « santé partagée » est visible des parents. Rien d’autre ne sort."],
      ["Absences, messages, signatures", "Absence à confirmer avec un motif ; un fil de messages par famille ; signatures à enregistrer."],
    ],
    exercice: "Inviter un parent de test, lui écrire un message, confirmer une absence signalée.",
  });
  module(pres, {
    numero: 10, titre: "Ti’Cagou sur le téléphone", minutes: 30,
    points: [
      ["Installer et ouvrir sa crèche", "Ti’Cagou Direction, QR « Ouvrir sur mon téléphone » du PC, connexion, Face ID."],
      ["Barre du bas", "Accueil, Présences, Enfants, Messages, Plus ; bandeau santé sur chaque fiche."],
      ["Départ bloqué à valider", "Notification, motif, Autoriser ou Refuser."],
      ["Téléphone perdu", "Paramètres › Téléphones de la direction › Retirer, depuis le PC."],
    ],
    exercice: "Valider à distance un départ demandé par la tablette, motif compris.",
    image: CAP + "depart-a-valider-telephone.png",
  });

  s = contenu(pres, "Aide-mémoire de la direction");
  [
    ["Chaque jour", ["Tableau de bord : alertes, encadrement", "Absences signalées, messages", "Corriger les pointages oubliés", "Départs à valider sur le téléphone"]],
    ["Chaque mois", ["Corriger les anomalies, générer les factures", "Encaissements, relances", "Valider les heures, export paie", "Demandes aux financeurs, export comptable"]],
    ["Chaque année", ["Barème daté de la nouvelle année", "Attestations de frais de garde", "Diplômes et formations qui expirent", "Bilan d’activité (Statistiques)"]],
  ].forEach(([titre, items], i) => {
    const x = 0.6 + i * 4.1;
    s.addShape("roundRect", { x, y: 1.6, w: 3.8, h: 4.6, rectRadius: 0.12, fill: { color: C.lagon50 }, line: { color: C.lagon50 } });
    s.addText(titre, { x: x + 0.3, y: 1.8, w: 3.2, h: 0.6, fontFace: TITRE, fontSize: 24, bold: true, color: C.terre600, margin: 0, isTextBox: true });
    s.addText(items.map((t, j) => ({ text: t, options: { bullet: true, breakLine: j < items.length - 1 } })), {
      x: x + 0.3, y: 2.55, w: 3.25, h: 3.5, fontFace: TEXTE, fontSize: 18, color: C.encre, margin: 0, paraSpaceAfter: 10, valign: "top", isTextBox: true,
    });
  });

  ouverture(pres, { titre: "En cas de problème", sousTitre: "Notez le message exact et l’heure, puis appelez le support Ti’Cagou." });
  await ecrire(pres, "Ti'Cagou - Formation Direction.pptx");
}

// ---------------------------------------------------------------------------------------------------------------
// 4. Formation des équipes (docs/wiki/13-formation-equipe.md)
// ---------------------------------------------------------------------------------------------------------------
async function formationEquipes() {
  const pres = nouvelle({ titre: "Ti'Cagou — Formation des équipes", sujet: "Support de formation des équipes" });
  ouverture(pres, {
    titre: "Formation des équipes",
    sousTitre: "Pointer, accueillir, remettre l’enfant à la bonne personne, raconter sa journée",
    etiquettes: ["Sur la tablette d’accueil", "Éducatrices, cuisine, comptabilité"],
    image: KIOSQUE,
  });

  let s = contenu(pres, "Trois publics, trois séances", "Chaque séance se fait sur le matériel réel, avec les vrais comptes de la crèche.");
  grille(s, [
    ["Éducatrices, auxiliaires · 1 h", "1 · Mon code PIN\n2 · Arrivée d’un enfant\n3 · Départ d’un enfant\n4 · La journée des enfants\n5 · Mon pointage\n6 · Quand internet est coupé"],
    ["Cuisine · 30 min", "1 · Mon code PIN\n2 · La grille, en consultation\n7 · Cuisine"],
    ["Comptabilité · 1 h", "8 · Comptabilité, sur le PC du bureau"],
  ], { colonnes: 3, y: 1.85, h: 4.8, tailleTexte: 16 });

  intercalaire(pres, { titre: "Éducatrices et auxiliaires", texte: "Modules 1 à 6 · 1 heure · sur la tablette" });
  module(pres, {
    numero: 1, titre: "Mon code PIN", minutes: 5,
    points: [
      ["Un code à 4 chiffres, personnel", "Donné par la direction. Il prouve qui pointe : ne le prêtez pas, ne le notez pas sur la tablette."],
      ["« Qui êtes-vous ? »", "Touchez votre nom, puis tapez votre code : validation au 4ᵉ chiffre."],
      ["En partant de la tablette", "Bouton à votre nom, en haut à droite › Changer d’utilisateur."],
    ],
    exercice: "Chacun ouvre et ferme sa session.",
  });
  module(pres, {
    numero: 2, titre: "Arrivée d’un enfant", minutes: 10,
    points: [
      ["La grille", "Enfants attendus, présents et partis, par section (onglets en haut)."],
      ["Les pastilles santé, à chaque arrivée", "Allergie, PAI, régime : elles sont sur chaque tuile."],
      ["Toucher un enfant attendu", "Arrivée enregistrée ; noter qui l’a déposé et les consignes du parent, médicament compris."],
      ["Rappel médicament", "Un quart d’heure avant l’heure ; « Médicament donné » une fois fait."],
    ],
    exercice: "Une arrivée avec une consigne médicament.",
    image: KIOSQUE,
  });
  s = contenu(pres, "3 · Départ d’un enfant");
  duree(s, "15 min");
  s.addText("Personne autorisée", { x: 0.6, y: 1.5, w: 5.8, h: 0.5, fontFace: TEXTE, fontSize: 20, bold: true, color: C.lagon700, margin: 0, isTextBox: true });
  lignes(s, [
    ["Toucher l’enfant présent", "Écran « Départ de … »."],
    ["Choisir qui vient le chercher", "Dans la liste, avec la photo."],
    ["Comparer la photo et la personne", "Pièce d’identité si vous ne la connaissez pas."],
  ], { x: 0.6, y: 2.15, w: 5.8, numeros: true, ecart: 0.18 });
  s.addShape("roundRect", { x: 6.9, y: 1.5, w: 5.8, h: 5.2, rectRadius: 0.12, fill: { color: C.danger100 }, line: { color: C.danger100 } });
  s.addText("Personne NON autorisée", { x: 7.15, y: 1.65, w: 5.3, h: 0.5, fontFace: TEXTE, fontSize: 20, bold: true, color: C.danger, margin: 0, isTextBox: true });
  s.addText([
    { text: "« Une autre personne » › écran rouge.", options: { bullet: true, breakLine: true } },
    { text: "Remplir prénom, nom, lien, pièce d’identité vue.", options: { bullet: true, breakLine: true } },
    { text: "La directrice tape son code sur la tablette.", options: { bullet: true, breakLine: true } },
    { text: "Directrice absente : « Demander son accord à distance ». Elle répond depuis son téléphone, motif obligatoire.", options: { bullet: true, breakLine: true } },
    { text: "Sans accord : l’enfant reste, on appelle les parents.", options: { bullet: true, bold: true } },
  ], { x: 7.15, y: 2.25, w: 5.3, h: 4.3, fontFace: TEXTE, fontSize: 16, color: C.encre, margin: 0, paraSpaceAfter: 8, valign: "top", isTextBox: true });
  exercice(s, "Un départ normal, puis un départ refusé.", { x: 0.6, y: 5.35, w: 5.8, h: 1.35 });

  module(pres, {
    numero: 4, titre: "La journée des enfants", minutes: 15,
    points: [
      ["1. Pour qui ?", "Bouton « Journée » : touchez un ou plusieurs enfants présents (toute une table pour un repas)."],
      ["2. Quoi ?", "Repas, sieste, change, médicament (un enfant à la fois), activité, photo, mot pour les parents."],
      ["3. Partager avec les familles", "Une photo ne part que si chaque enfant a l’autorisation. Pas d’information médicale dans les textes."],
      ["Sans réseau", "Tout est gardé et part au retour d’internet, sauf les photos."],
    ],
    exercice: "Un repas pour deux enfants, une sieste, une photo de groupe.",
  });
  module(pres, {
    numero: 5, titre: "Mon pointage", minutes: 5,
    points: [
      ["« Pointage personnel » + mon code", "La tablette sait si c’est votre arrivée ou votre départ ; à l’arrivée, elle demande votre section."],
      ["Pas besoin d’ouvrir sa session", "N’importe quelle session ouverte suffit : c’est votre code qui compte."],
      ["L’encadrement en dépend", "Vert, orange, rouge en haut de la tablette. Oubli : prévenez la direction."],
    ],
    exercice: "Chacun pointe son arrivée et son départ.",
  });
  module(pres, {
    numero: 6, titre: "Quand internet est coupé", minutes: 5,
    points: [
      ["Bandeau « Hors connexion »", "On continue à pointer normalement : « N pointages en attente d’envoi »."],
      ["Connexion hors ligne", "Votre nom puis votre code : il sera vérifié au retour d’internet."],
      ["Si cela dure", "Prévenez la direction ; au-delà de 30 minutes, elle est prévenue sur son téléphone."],
      ["Pointage refusé", "Il s’affiche en rouge avec la raison : prévenez la direction."],
    ],
  });

  intercalaire(pres, { titre: "Cuisine", texte: "Modules 1, 2 et 7 · 30 minutes" });
  module(pres, {
    numero: 7, titre: "Cuisine", minutes: 20,
    points: [
      ["La tablette en consultation", "La cuisine voit la grille mais ne pointe pas les enfants."],
      ["Repas › vue du jour (PC)", "Repas et goûters par section, menu du jour, allergies et régimes en évidence."],
      ["Mode cuisine et fiche imprimée", "Lisible de loin ; nombres à vérifier juste avant le service."],
    ],
    exercice: "Imprimer la fiche du jour et repérer les enfants allergiques.",
  });

  intercalaire(pres, { titre: "Comptabilité", texte: "Module 8 · 1 heure · sur le PC du bureau" });
  module(pres, {
    numero: 8, titre: "Comptabilité", minutes: 60,
    points: [
      ["Identifiant, mot de passe, code du téléphone", "Menus : Tableau de bord, Présences, Inscriptions, Facturation ; Paramètres limités au barème et aux financeurs."],
      ["Facturer le mois", "Faire corriger les anomalies par la direction, générer, PDF, noter comme envoyée."],
      ["Une facture ne se modifie jamais", "On fait un avoir."],
      ["Encaissements et financeurs", "Paiement partiel, reçu, relance ; montants à réclamer, justificatifs, versements."],
    ],
    exercice: "Enregistrer un virement partiel et imprimer le reçu.",
    image: CAP + "facturation-tablette.png",
  });

  s = contenu(pres, "À afficher près de la tablette");
  grille(s, [
    ["Arrivée", "Toucher l’enfant › consignes du parent."],
    ["Départ", "Toucher l’enfant › choisir la personne avec la photo."],
    ["Personne inconnue", "Écran rouge : la direction valide, sur place ou à distance. Sinon l’enfant reste."],
    ["Journée", "Enfants › repas, sieste, change, activité, photo, mot › Enregistrer."],
    ["Mon pointage", "Pointage personnel › mon code."],
    ["Bandeau orange", "On continue : tout part seul au retour d’internet."],
  ], { colonnes: 3, numeros: true, y: 1.5, h: 5.2, tailleTexte: 16 });

  ouverture(pres, { titre: "Un doute ?", sousTitre: "Prévenez la direction. Un enfant ne part jamais avec une personne non autorisée." });
  await ecrire(pres, "Ti'Cagou - Formation Equipes.pptx");
}

// ---------------------------------------------------------------------------------------------------------------
// 5. Formation aux applications mobiles (docs/wiki/19-session-formation-applications.md)
// ---------------------------------------------------------------------------------------------------------------
async function formationApplications() {
  const pres = nouvelle({ titre: "Ti'Cagou — Les applications mobiles", sujet: "Session de formation aux applications mobiles" });
  ouverture(pres, {
    titre: "Les applications mobiles",
    sousTitre: "Ti’Cagou Direction, Ti’Cagou Pointage et Ti’Cagou Parents, sur vos propres appareils",
    etiquettes: ["1 h 30", "Téléphones et tablette d’accueil", "Direction et équipe"],
    image: CAP + "presences-telephone.png",
    notes: "Prérequis : applications installées, ordinateur du bureau ouvert sur Paramètres. Chacun fait chaque geste lui-même.",
  });

  let s = contenu(pres, "Déroulé de la session", "Chacun fait chaque geste lui-même ; on coche quand c’est fait sans aide.");
  grille(s, [
    ["Installer et ouvrir sa crèche", "15 min · direction"],
    ["Se repérer sur le téléphone", "15 min · direction"],
    ["La tablette en mode kiosque", "15 min · équipe et direction"],
    ["Départ validé à distance", "20 min · ensemble"],
    ["Notifications, téléphone perdu", "10 min · direction"],
    ["Les familles", "15 min · direction"],
  ], { colonnes: 3, numeros: true, y: 1.75, h: 4.9, tailleTexte: 16 });

  module(pres, {
    numero: 1, titre: "Installer et ouvrir sa crèche", minutes: 15,
    points: [
      ["Sur l’ordinateur", "Paramètres › Tablettes et abonnement › « Ouvrir sur mon téléphone »."],
      ["Sur le téléphone", "Ti’Cagou Direction › « Scanner le QR de la crèche » › vérifier le nom › Continuer."],
      ["Connexion", "Identifiant, mot de passe, code de l’application d’authentification ; puis Face ID ou empreinte."],
    ],
    exercice: "Fermer l’application, la rouvrir : elle demande Face ID, pas le mot de passe.",
    image: CAP + "app-bienvenue-telephone.png",
  });
  module(pres, {
    numero: 2, titre: "Se repérer sur le téléphone", minutes: 15,
    points: [
      ["La barre du bas", "Accueil, Présences, Enfants, Messages, Plus."],
      ["Accueil", "Encadrement de chaque section, en direct."],
      ["Fiche enfant", "Le bandeau rouge des allergies reste en tête."],
    ],
    exercice: "Trouver en moins d’une minute un enfant allergique présent et la personne à appeler pour lui.",
    image: CAP + "fiche-sante-telephone.png",
  });
  module(pres, {
    numero: 3, titre: "La tablette en mode kiosque", minutes: 15,
    points: [
      ["Appairer", "Ti’Cagou Pointage › « Scanner le QR » affiché par Paramètres › Appairer une tablette."],
      ["Activer le mode kiosque", "Le bouton Accueil ne sort plus de Ti’Cagou ; il revient seul après un redémarrage."],
      ["Sortir du mode kiosque", "Appui long de 5 secondes sur « Mode kiosque », puis le code de la directrice."],
    ],
    exercice: "L’éducatrice pointe une arrivée et un départ ; la directrice fait sortir puis rentrer la tablette en mode kiosque.",
    image: KIOSQUE,
  });
  module(pres, {
    numero: 4, titre: "Départ validé à distance", minutes: 20,
    points: [
      ["Tablette", "Départ › « Une autre personne » › nom, lien, pièce vue › « Directrice absente ? Demander son accord à distance »."],
      ["Téléphone", "Notification « Départ à valider » › motif › Refuser (premier essai), puis Autoriser."],
      ["Vérifier", "La tablette affiche la réponse ; Présences montre le départ et le nom de la directrice."],
    ],
    exercice: "Sans regarder le guide, l’équipe demande et la directrice décide, motif compris.",
    image: CAP + "depart-a-valider-telephone.png",
  });
  module(pres, {
    numero: 5, titre: "Notifications et téléphone perdu", minutes: 10,
    points: [
      ["Choisir ses alertes", "Plus › Notifications : départ à valider, encadrement, sauvegarde, tablette muette."],
      ["Aucune information de santé", "Les notifications ne disent jamais le contenu d’une fiche."],
      ["Téléphone perdu", "Sur l’ordinateur : Paramètres › Téléphones de la direction › Retirer."],
    ],
    exercice: "Retirer le téléphone d’une collègue (avec son accord), constater qu’il est déconnecté, puis le reconnecter.",
    image: CAP + "plus-telephone.png",
  });
  module(pres, {
    numero: 6, titre: "Les familles", minutes: 15,
    points: [
      ["Le guide parents", "Une page à imprimer avec le nom et le téléphone de la crèche."],
      ["Connexion des parents", "Ti’Cagou Parents › e-mail donné à la crèche › code à 6 chiffres, sans mot de passe."],
      ["Suppression de compte", "La demande arrive dans Messages ; le dossier de l’enfant reste à la crèche."],
    ],
    exercice: "Envoyer une actualité depuis le téléphone ; le parent de test reçoit la notification.",
  });

  s = contenu(pres, "Fin de session", "Les six cases cochées, chacun repart avec ses supports.");
  lignes(s, [
    ["Les six exercices sont faits sans aide", "Sinon, refaire le geste avant de partir."],
    ["Laisser à la crèche", "Ce support, le guide parents imprimé et les pages 15, 16 et 17 du wiki."],
    ["Le premier départ réel validé à distance", "Faites-le ensemble dans la semaine, puis appelez le support si besoin."],
  ], { x: 0.6, y: 1.85, w: 7.0, numeros: true });
  ecran(s, CAP + "tableau-de-bord-telephone.png", { x: 8.6, y: 1.6, w: 4.1, h: 5.1 });

  await ecrire(pres, "Ti'Cagou - Formation Applications mobiles.pptx");
}

await presentation();
await plaquette();
await formationDirection();
await formationEquipes();
await formationApplications();

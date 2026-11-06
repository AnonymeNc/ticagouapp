# Checklist de commercialisation

[← Wiki](../wiki/README.md)

Tout ce qu'il faut avoir fait pour vendre Ti'Cagou sereinement, dans l'ordre conseillé. Les points juridiques et
fiscaux sont des pistes à faire valider par un comptable et un juriste au fait du droit calédonien.

## 1. Le produit est prêt

- [ ] **Avis juridique** sur l'hébergement des données de santé, puis hébergeur choisi et contrat signé.
- [ ] **Test d'intrusion** externe, failles corrigées.
- [ ] Serveur de production préparé, copie hors site chez un second hébergeur
      ([EXPLOITATION.md](../EXPLOITATION.md)).
- [ ] **Perte totale du serveur** répétée à blanc, avec les `creche.env` du coffre.
- [ ] **Dépôt GitHub privé** avec les tests automatiques verts (`.github/workflows/ci.yml`).
- [ ] Tablettes essayées : un Android, un iPad, coupure d'internet comprise.
- [ ] Internet mesuré dans 3 à 5 crèches ; routeur 4G de secours choisi.
- [ ] Une **crèche pilote** pendant au moins un mois de facturation réelle, avec ses retours corrigés.
- [ ] Mentions légales des composants tiers (PostgreSQL, Caddy, BouncyCastle, Spring, React, polices…) dans l'application.

## 2. L'entreprise

- [ ] Forme juridique choisie (entreprise individuelle, SARL, SAS…) avec un comptable : responsabilité, charges,
      fiscalité.
- [ ] Immatriculation : **RIDET** (registre ISEE), registre du commerce, patente si elle s'applique.
- [ ] **TGC** : immatriculation, taux applicable à vos prestations et licences, déclarations périodiques.
- [ ] Compte bancaire professionnel ; moyen d'encaisser les abonnements (virement, prélèvement).
- [ ] **Assurances** : responsabilité civile professionnelle (erreur de facturation chez un client, perte de
      données…) et, si possible, cyber-risques.
- [ ] Logiciel de facturation pour vos propres factures (numérotation continue, TGC, mentions obligatoires).

## 3. La marque et la propriété

- [ ] Disponibilité du nom « Ti'Cagou » vérifiée, marque déposée (vérifier l'effet du dépôt en Nouvelle-Calédonie).
- [ ] Noms de domaine réservés (`ticagou.nc` et une variante `.com`/`.fr`).
- [ ] Logo définitif (le monogramme actuel est provisoire : [installer/README.md › Logo](../../installer/README.md#logo)).
- [ ] Propriété du code clarifiée si d'autres personnes y ont contribué ; code sauvegardé hors de votre PC (dépôt
      Git privé distant, sauvegarde chiffrée).

## 4. Les contrats et documents commerciaux

- [ ] **Conditions générales de vente** et **contrat d'abonnement** : objet, durée et renouvellement, prix et révision,
      paiement, mises à jour, support, limites de responsabilité, suspension pour impayé, fin de contrat et
      restitution des données.
- [ ] **Accord de niveau de service** (support Standard et Premium du [barème](bareme-tarifaire.md#5-niveaux-de-support)).
- [ ] **Contrat de sous-traitance des données personnelles** avec chaque crèche : vous hébergez leurs données d'enfants.
- [ ] Clauses de **réversibilité** : export des données et restitution à la fin du contrat.
- [ ] Modèles : devis, bon de commande, procès-verbal de mise en service (signé après la recette), facture.
- [ ] Tableau des clients : numéro, crèche, RIDET, modules, dates, contacts, dates de renouvellement.

## 5. Les données personnelles

Ti'Cagou traite des **données de santé d'enfants** : c'est le point le plus sensible.
- [ ] Cadre légal applicable en Nouvelle-Calédonie vérifié avec un juriste (loi Informatique et Libertés, obligations
      pour les données de santé, hébergement).
- [ ] Votre **registre des traitements** (en tant que sous-traitant) et un modèle de registre pour les crèches.
- [ ] **Analyse d'impact** sur la protection des données.
- [ ] Procédure en cas de **violation de données** (serveur compromis, accès non autorisé) : qui prévenir, sous quel délai.
- [ ] Note d'information des familles pour le portail ([modèle](../wiki/08-portail-nouvelle-creche.md#4-informer-les-familles-avant-toute-invitation)).
- [ ] Accès du support aux données de la crèche encadré : uniquement à la demande de la directrice, tracé.

## 6. L'organisation

- [ ] **Support** : adresse e-mail dédiée, numéro de téléphone (Premium), horaires, outil de suivi des demandes
      (même un tableau au début), outil de prise en main à distance.
- [ ] Processus de livraison des versions écrit et suivi ([EXPLOITATION.md § 4](../EXPLOITATION.md#4-mettre-à-jour)).
- [ ] Astreinte pendant les heures d'ouverture des crèches, de 6 h à 19 h.
- [ ] Rappel automatique des **renouvellements d'abonnement** (calendrier : 45 jours avant la fin).
- [ ] Surveillance du service en place ([11-supervision.md](../wiki/11-supervision.md)).
- [ ] Plan de continuité : qui reprend le service et les clés si vous êtes indisponible. **Point critique** : en hébergé,
      les crèches dépendent de vous.

## 7. Le prix et l'offre

- [ ] Barème validé auprès de directrices ([bareme-tarifaire.md § 8](bareme-tarifaire.md#8-comment-valider-ces-prix)).
- [ ] Coût de revient par client calculé ([§ 7](bareme-tarifaire.md#7-ce-que-coûte-un-client-pour-vérifier-la-marge)).
- [ ] Offre de lancement définie (crèches pilotes, engagement 3 ans).

## 8. Faire connaître

- [ ] Liste des crèches et garderies du territoire (provinces Sud, Nord, Îles : services de la petite enfance,
      annuaires), avec gestionnaires (associations, communes, entreprises).
- [ ] Crèche de démonstration en ligne, avec des données réalistes (`scripts/demo`), et une tablette.
- [ ] Plaquette et présentation à refaire : celles de la V1, dans le dossier d'archive, promettent « données gardées à
      la crèche » et « fonctionne sans internet ». Nouveaux arguments à valider une fois l'hébergeur choisi.
- [ ] Logo définitif reporté dans les captures et les supports.
- [ ] Site web simple : présentation, captures, formulaire de contact, mentions légales.
- [ ] Témoignage de la crèche pilote.
- [ ] Contacts : fédérations et réseaux de crèches, services petite enfance des provinces et communes, revendeurs
      informatiques (partenariat pour le matériel et l'installation en Brousse et dans les îles).

## 9. Avant chaque vente

- [ ] Devis signé, bon de commande, acompte éventuel.
- [ ] Contrat d'abonnement et contrat de sous-traitance signés.
- [ ] Internet de la crèche vérifié.
- [ ] Crèche créée, abonnement enregistré, `creche.env` au coffre, recette, PV de mise en service signé, facture émise.

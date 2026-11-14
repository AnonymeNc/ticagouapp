# ADR 0006 — Export Excel des récapitulatifs

- **Statut** : acceptée
- **Date** : 01/11/2026

## Contexte

Le récapitulatif trimestriel des repas délivrés accompagne la demande d'aide : la direction l'envoie en PDF signé
(ADR 0004), mais les financeurs et la comptabilité demandent aussi un tableur, avec le détail par enfant et par jour.
Les états de facturation auront le même besoin. Tout est produit par l'instance de la crèche, sans service extérieur.

## Décision

- Les fichiers `.xlsx` sont écrits avec **fastexcel** (`org.dhatim:fastexcel`, licence Apache 2), version fixée dans
  `pom.xml` (`fastexcel.version`, 0.20.2). Environ 130 Ko, une seule dépendance d'exécution (`opczip`), sans Apache POI.
- Le classeur est produit à la demande, en mémoire, à partir des mêmes données que l'écran ; rien n'est stocké.
- Plusieurs feuilles par classeur (récapitulatif, détail), en-têtes en gras, largeurs de colonnes, dates au format
  JJ/MM/AAAA, volet figé sur la ligne d'en-tête. Les nombres restent des nombres (sommes possibles dans le tableur).
- Les exports sont tracés dans le journal d'audit (`MEAL_EXPORTED`).

## Alternatives écartées

- **Apache POI** : la référence, lecture comprise, mais plusieurs Mo de dépendances pour n'écrire que des tableaux simples.
- **Fichier écrit à la main** (zip + XML SpreadsheetML) : aucune dépendance, mais du code de format à maintenir
  (styles, partage des chaînes, échappements) pour un gain faible.
- **CSV** : ne porte ni plusieurs feuilles, ni formats ; les accents et séparateurs posent problème selon le tableur.

## Conséquences

- Les entrées du classeur sont écrites en flux (descripteurs de données) : un `ZipInputStream` Java ne sait pas les
  lire, `ZipFile` et les tableurs si. Les tests lisent donc l'archive par son répertoire central.

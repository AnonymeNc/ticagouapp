# ADR 0004 — Génération des PDF (contrats, puis factures)

- **Statut** : acceptée
- **Date** : 31/10/2026

## Contexte

Le contrat d'accueil se signe sur papier : Ti'Cagou doit produire un PDF fidèle à la maquette
(en-tête de la structure, articles, tableau du planning, montants, signatures). Les factures et les avoirs
auront le même besoin. Tout est produit par l'instance de la crèche, sans service extérieur.

## Décision

- Les documents sont écrits en **HTML/CSS** et remplis avec **Thymeleaf** (`spring-boot-starter-thymeleaf`, géré
  par Spring Boot). Gabarits dans `server/src/main/resources/templates/`. Thymeleaf échappe les valeurs saisies.
- La conversion en PDF est faite par **openhtmltopdf** (`io.github.openhtmltopdf:openhtmltopdf-pdfbox`, fork maintenu,
  licence LGPL, sur Apache PDFBox 3). Version fixée dans `pom.xml` (`openhtmltopdf.version`).
- Polices : les polices PDF standard (Helvetica), qui couvrent les accents du français. Les montants utilisent
  l'espace insécable classique (U+00A0), présente dans ces polices.
- Un PDF est produit à la demande, à partir des données figées de la version du contrat : il n'est pas stocké.
  L'exemplaire signé est numérisé et joint au dossier de l'enfant (document chiffré, catégorie CONTRAT).

## Alternatives écartées

- **Génération directe avec PDFBox ou OpenPDF** : plus léger, mais la mise en page (tableaux, sauts de page,
  paraphes) devient du code de positionnement difficile à faire évoluer.
- **Navigateur sans interface (Chromium)** : rendu parfait, mais une dépendance de plus de 100 Mo à installer
  sur le PC de la crèche.
- **JasperReports** : puissant pour les états, mais lourd et éloigné de la maquette HTML.

## Conséquences

- Environ 6 Mo de dépendances en plus dans le serveur (PDFBox, openhtmltopdf, Thymeleaf).
- Le gabarit doit rester du XHTML bien formé (balises fermées), exigence d'openhtmltopdf.
- Les factures réutiliseront le même service de rendu.

# ADR 0008 — QR code d'appairage des tablettes

- **Statut** : acceptée
- **Date** : 01/11/2026

## Contexte

Appairer une tablette demandait de taper sur son pavé un code à 6 caractères affiché sur le PC du bureau, après
avoir ouvert la bonne adresse du serveur local. La maquette « Tablettes, licence et mises à jour » prévoit un QR code.

## Décision

- Le PC du bureau dessine un QR code avec **qrcode-generator** (licence MIT, version fixée à 1.4.4, ~56 Ko, aucune
  dépendance), en SVG, sans appel réseau.
- Le QR contient l'adresse du kiosque sur le réseau de la structure et le code :
  `http://<adresse IPv4 du serveur>:<port>/kiosk?pair=CODE`. Le serveur donne ses adresses
  (`GET /api/v1/system/addresses`) ; la direction choisit la bonne s'il y en a plusieurs.
- La tablette scanne avec l'appareil photo, ouvre le kiosque, qui lit `pair` et s'appaire seule. La saisie manuelle
  du code reste possible ; le code expire toujours après 10 minutes.

## Alternatives écartées

- **Génération côté serveur (ZXing)** : plus lourd (~550 Ko) pour une image affichée uniquement sur le PC.
- **Encodeur QR écrit à la main** : correction d'erreurs Reed-Solomon et masques à maintenir, sans gain.

## Conséquences

- Le code d'appairage apparaît dans l'URL : il ne vaut que 10 minutes et une seule fois, comme avant.

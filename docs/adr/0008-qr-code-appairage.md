# ADR 0008 — QR codes d'appairage et d'ouverture sur téléphone

- **Statut** : acceptée
- **Date** : 01/11/2026, mise à jour le 15/11/2026

## Contexte

Appairer une tablette demande de taper un code à 6 chiffres affiché sur le PC du bureau, après avoir ouvert la bonne
adresse de la crèche. Les maquettes prévoient un QR code, scanné par l'appareil photo de la tablette ou par
l'application Pointage ; l'application Direction s'ouvre aussi par un QR ([ADR 0025](0025-choix-de-la-creche.md)).

## Décision

- Le PC du bureau dessine les QR codes avec **qrcode-generator** (licence MIT, version fixée à 1.4.4, ~56 Ko, aucune
  dépendance), en SVG, sans appel réseau.
- QR d'appairage d'une tablette : `https://<crèche>/kiosk?pair=CODE` (Paramètres › Tablettes et abonnement ›
  Appairer une tablette). Le navigateur ouvre le kiosque, qui lit `pair` et s'appaire seul ; l'application Pointage
  lit l'adresse et le code d'un coup (`parsePairingQr`). La saisie manuelle du code reste possible ; il expire après
  10 minutes et ne sert qu'une fois.
- QR « Ouvrir sur mon téléphone » : l'adresse `/app` de la crèche, lue par l'application Direction pour choisir la
  crèche ([ADR 0025](0025-choix-de-la-creche.md)). Il ne contient aucun secret.

## Alternatives écartées

- **Génération côté serveur (ZXing)** : plus lourd (~550 Ko) pour une image affichée uniquement sur le PC.
- **Encodeur QR écrit à la main** : correction d'erreurs Reed-Solomon et masques à maintenir, sans gain.

## Conséquences

- Le code d'appairage apparaît dans l'URL : il ne vaut que 10 minutes et une seule fois.
- Chaque application reconnaît le mauvais QR et le dit (« ce n'est pas un QR d'appairage Ti'Cagou »).

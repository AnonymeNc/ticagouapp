# ADR 0027 — Distribution et mises à jour des applications

- **Statut** : acceptée
- **Date** : 14/11/2026
- **S'appuie sur** : [ADR 0023](0023-applications-mobiles-capacitor.md)

## Contexte

Jusqu'ici, une mise à jour de Ti'Cagou était simple : `creche.sh mettre-a-jour` remplace l'image, le navigateur
recharge le nouveau front. Avec des applications installées, une crèche peut avoir un serveur récent et un téléphone
qui n'a pas été mis à jour depuis des mois, ou l'inverse le temps qu'une version passe la relecture d'Apple.

## Décision

**Contenu embarqué, pas de mise à jour à chaud**
- Chaque application embarque son front (`www/`), construit depuis le même commit que le serveur publié.
- Pas de service de mise à jour à chaud (Capgo, Appflow) : un tiers de plus dans la chaîne de signature du code, et
  le contenu d'une application relue par Apple doit rester celui qui a été relu. Une correction urgente du front
  sort comme une version de l'application (relecture accélérée possible chez Apple).
- Le navigateur, lui, reçoit toujours le front de son instance.

**Versions**
- Les trois applications portent le numéro de version du serveur dont elles sont construites (`1.4.0`), avec un
  numéro de build croissant pour les stores.
- Chaque requête porte `X-Ticagou-App: <application>/<version> (<plateforme>)` (ADR 0024).

**Compatibilité de l'API**
- `/api/v1` n'évolue que par **ajouts** : nouveau point d'accès, nouveau champ facultatif. Un champ n'est retiré qu'une
  fois qu'aucune version encore acceptée ne le lit.
- Un changement incompatible ouvre `/api/v2`, servi **à côté** de `/api/v1` au moins 6 mois.
- Un test compare l'OpenAPI produit avec l'instantané `docs/api/openapi-v1.json` et échoue sur une suppression ou un
  changement de type ; l'instantané se met à jour volontairement.

**Version minimale exigée par l'instance**
- `GET /api/v1/app/compat` (public) : `{"api": 1, "minimum": {"direction": "1.2.0", "pointage": "1.2.0"}}` ; le portail
  publie l'équivalent pour `parents`. Valeurs par défaut dans le code, réglables par crèche
  (`TICAGOU_APP_MIN_DIRECTION`…) pour un correctif de sécurité.
- **Direction et Parents** : en dessous du minimum, écran bloquant « Mettez à jour l'application » avec le lien du
  store ; le navigateur reste possible.
- **Pointage** : **jamais bloqué** — on ne coupe pas le pointage des enfants. Bandeau rouge à la direction et sur la
  tablette ; l'appairage d'une nouvelle tablette trop ancienne est refusé.

**Canal de distribution**
- **Parents** : App Store et Google Play publics (les familles doivent la trouver).
- **Direction et Pointage** : App Store et Google Play publics aussi (décidé le 14/11/2026) ; sans crèche abonnée,
  elles ne servent à rien. Pointage n'existe que sur Android.
- Comptes développeur Apple et Google : créés et payés par l'éditeur, au nom de la société éditrice (numéro D-U-N-S).
- Pas d'achat intégré : l'abonnement est vendu hors de l'application, les factures des crèches paient un service
  physique (accueil d'enfants), hors du champ des achats intégrés.

**Publication d'une version** (détail dans `docs/EXPLOITATION.md`)
1. Serveur publié et déployé sur une crèche de démonstration.
2. Builds Android (CI) et iOS (machines macOS de GitHub Actions) depuis le même commit ; tests internes (TestFlight, test
   interne Google) avec le compte de démonstration.
3. Publication dans les stores ; relèvement de la version minimale seulement quand la nouvelle version est
   disponible partout.

## Alternatives écartées

- **Vue web qui charge l'instance (`server.url`)** : toujours à jour, mais plus de pointage hors connexion, contenu
  hors de la version relue, risque de refus Apple (règle 4.2), et l'application ne marche plus quand l'instance est
  injoignable.
- **Mise à jour à chaud par un service tiers** : voir ci-dessus.
- **Versions d'API par en-tête** : plus difficile à lire dans les journaux et à router dans Caddy qu'un préfixe.

## Conséquences

- Toute modification d'API passe par la question « une application installée la comprend-elle ? ».
- `creche.sh mettre-a-jour` ne suffit plus pour livrer une nouveauté visible sur téléphone : il faut aussi une version
  des applications. La console pourra afficher la répartition des versions (`X-Ticagou-App`) par crèche.

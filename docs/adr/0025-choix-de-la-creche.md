# ADR 0025 — Choix de la crèche dans les applications

- **Statut** : acceptée
- **Date** : 14/11/2026
- **Complète** : [ADR 0013](0013-web-heberge-instance-par-creche.md) (une instance par crèche), [ADR 0017](0017-console-editeur.md) (console de l'éditeur)

## Contexte

Chaque crèche a son instance et son adresse (`https://<slug>.<DOMAINE_BASE>` ou un domaine propre, ADR 0013). Le
navigateur y va par son adresse ; une application publiée dans un store est la même pour toutes les crèches et doit
apprendre où se connecter.

- La tablette de pointage l'apprend déjà par le QR d'appairage (ADR 0008).
- Le portail parents est unique : l'application Parents connaît son adresse.
- L'application Direction doit trouver l'instance de sa crèche, sans que la directrice tape une adresse.

Une même personne peut travailler pour deux crèches (gestionnaire de deux structures, comptable partagée).

## Décision

**Trois façons de choisir sa crèche, dans l'ordre de l'écran**

1. **Scanner le QR** affiché par l'instance dans Paramètres › Postes › « Ouvrir sur mon téléphone » (et imprimé dans la
   fiche de mise en service). Il contient l'adresse publique de l'instance sous la forme `https://<instance>/app`. Scanné
   par l'appareil photo d'un téléphone sans l'application, il ouvre une page qui renvoie vers les stores.
2. **Taper le code de la crèche** : 6 caractères (alphabet sans 0, O, 1, I, L), tiré au hasard à la création, sans
   lien avec le nom. Le code est sur la fiche de mise en service et dans Paramètres.
3. **Taper l'adresse** (repli, « Autre adresse ») : `https://` obligatoire, sauf dans les builds de développement.

Dans les trois cas, l'application appelle `GET /api/v1/health` et `GET /api/v1/app/compat` sur l'adresse trouvée,
affiche le nom de la crèche et demande confirmation avant la connexion.

**Annuaire : codes → adresses, tenu par l'éditeur**
- La console de l'éditeur est la source : le code est généré à la création de la crèche (ordre `CREER`) et reste
  stable ; un changement de domaine met l'annuaire à jour.
- L'hôte publie l'annuaire avec `creche.sh annuaire` : un **fichier statique par code**,
  `/srv/annuaire/v1/<CODE>.json` (`{"nom": …, "commune": …, "adresse": "https://…"}`), servi par Caddy sur
  `https://annuaire.<DOMAINE_BASE>/v1/<CODE>.json`. Pas de liste : impossible d'énumérer les crèches sans connaître
  leurs codes (32⁶ ≈ un milliard de possibilités), et Caddy limite le débit.
- L'annuaire ne dépend pas de l'accès à la console (`CONSOLE_ACCES=tunnel` reste possible) ni de son état : si la
  console est arrêtée, les fichiers restent servis.
- Une crèche suspendue garde son fichier (l'application affichera « abonnement suspendu » depuis l'instance) ; une
  crèche supprimée perd le sien.

**Plusieurs crèches sur un téléphone**
- La liste des crèches connues (nom, commune, adresse, dernière utilisation) est dans `Preferences` : rien de sensible.
- Tout le reste est rangé **par crèche** : jeton de rafraîchissement, identifiant de poste, préférences de
  notification, caches de requêtes. Changer de crèche ferme la session courante (jeton d'accès en mémoire) et ouvre
  celle de l'autre crèche, verrou biométrique compris.
- « Oublier cette crèche » révoque la session côté serveur si possible, efface ses jetons de notification, puis
  toutes ses données locales.

**Pointage** : le QR d'appairage (`/kiosk?pair=CODE`, ADR 0008) porte déjà l'adresse ; l'application Pointage le scanne
nativement. Une tablette n'appartient qu'à une crèche.

**Parents** : une seule adresse (le portail), pas de choix. Un parent dont les enfants sont dans deux crèches les voit
déjà dans le même portail.

## Alternatives écartées

- **Le code de la crèche est son sous-domaine** : expose le nom technique, casse au premier domaine propre, et ne permet
  pas de déplacer une crèche.
- **Annuaire servi par l'API de la console** : ouvrirait la console à internet pour tous les téléphones, alors qu'elle
  peut rester fermée (`CONSOLE_ACCES`).
- **Annuaire dans le portail parents** : mélange deux produits ; une crèche sans portail en dépendrait.
- **Une application par crèche (marque blanche)** : une fiche de store par crèche, impossible à tenir pour cinquante
  structures.

## Conséquences

- Nouvelle colonne `code` dans la console (crèches) et nouvelle commande `creche.sh annuaire` ; le socle Caddy sert
  `annuaire.<DOMAINE_BASE>` (certificat compris).
- L'instance connaît son code et son adresse publique (`creche.env` : `CODE_CRECHE`, `ADRESSE_PUBLIQUE`) pour le QR et
  Paramètres.
- La fiche de mise en service remise à la directrice gagne le code et le QR.

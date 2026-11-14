# Déclarations de confidentialité des stores

[← Fiches des stores](fiches-stores.md) · À reporter dans App Store Connect (« Confidentialité de l'app ») et dans la
Google Play Console (« Sécurité des données »). Établies d'après le code au 14/11/2026 ; à revoir à chaque version qui
touche aux données. Aucun outil d'analyse, aucune publicité, aucun traceur tiers dans les applications.

## Apple — App Privacy (« Étiquettes nutritionnelles »)

**Suivi (tracking)** : non, pour les trois applications.

### Ti'Cagou Direction

| Type de donnée Apple | Collectée | Liée à l'identité | Finalité |
|---|---|---|---|
| Coordonnées › Adresse e-mail, nom | oui (compte de l'utilisatrice) | oui | Fonctionnalité de l'app |
| Santé et forme › Santé | oui (allergies, PAI, régimes des enfants) | oui | Fonctionnalité de l'app |
| Contenu utilisateur › Photos ou vidéos, autre contenu (messages, notes) | oui | oui | Fonctionnalité de l'app |
| Identifiants › Identifiant de l'appareil | oui (identifiant de poste tiré par l'app, jeton de notification) | oui | Fonctionnalité, sécurité (postes de l'abonnement) |
| Données de diagnostic | non | — | — |

Les données sont stockées sur l'instance de la crèche (hébergement de l'éditeur), pas chez Apple.

### Ti'Cagou Parents

| Type de donnée Apple | Collectée | Liée à l'identité | Finalité |
|---|---|---|---|
| Coordonnées › Adresse e-mail, nom | oui | oui | Fonctionnalité de l'app |
| Contenu utilisateur › Photos ou vidéos, messages | oui (reçus de la crèche, messages envoyés) | oui | Fonctionnalité de l'app |
| Santé et forme | oui, seulement ce que la crèche choisit de partager avec la famille (médicament donné, ADR 0014) | oui | Fonctionnalité de l'app |
| Achats › Historique d'achats | non (les factures de la crèche ne sont pas des achats dans l'app) | — | — |
| Identifiants › Identifiant de l'appareil | oui (jeton de notification) | oui | Fonctionnalité de l'app |

## Google Play — Sécurité des données

Questions générales (les trois applications) :

- Les données sont-elles **chiffrées en transit** ? **Oui** (HTTPS).
- Les utilisateurs peuvent-ils **demander la suppression** ? **Oui** — Parents : dans l'application (Mon compte ›
  Supprimer mon compte) ; Direction et Pointage : comptes professionnels gérés par la crèche (la direction désactive un
  compte ; l'éditeur supprime l'instance à la fin du contrat).
- Données **partagées** avec des tiers ? **Non** (les services de notification d'Apple et de Google acheminent un titre et
  une phrase, sans donnée de santé : c'est un transfert à un prestataire, pas un partage au sens de Google).
- Respect des règles **Familles** : sans objet, les applications ne s'adressent pas aux enfants.

| Catégorie Google | Direction | Pointage | Parents | Facultatif ? |
|---|---|---|---|---|
| Infos personnelles › Nom, adresse e-mail | collectées | collectées (personnel) | collectées | non |
| Infos sur la santé | collectées | collectées | collectées (partage limité) | non |
| Photos et vidéos | collectées | collectées | collectées (reçues) | oui |
| Messages | collectés | — | collectés | oui |
| Identifiants de l'appareil | collectés | collectés | collectés | non |
| Position, contacts, agenda, historique web | non | non | non | — |

Finalités déclarées : **Fonctionnalité de l'application**, **Gestion du compte**, **Sécurité, conformité et prévention
de la fraude** (postes, appareils appairés). Jamais : publicité, analyse, personnalisation.

## Autorisations demandées

| Application | Autorisation | Pourquoi (texte affiché) |
|---|---|---|
| Direction | Appareil photo | Lire le QR code de la crèche, photos de la journée |
| Direction | Face ID | Protéger l'accès aux informations des enfants |
| Direction, Parents | Notifications | Alertes de la crèche (choisies par la personne) |
| Pointage | Appareil photo, micro | QR d'appairage, photos et vidéos de la journée |
| Parents | Face ID (si verrouillage activé) | Verrouiller l'application |

# La console de l'éditeur : se connecter et gérer ses crèches

La console est **votre interface d'administration** : une seule pour toutes vos crèches ([ADR 0017](adr/0017-console-editeur.md)).
Vous y suivez le parc, saisissez les contrats, facturez, et donnez des **ordres** aux crèches : le serveur les exécute
dans la minute (`creche.sh appliquer`, lancé chaque minute par le cron). Vous n'avez pas besoin d'ouvrir un terminal sur
le serveur pour la gestion courante.

Ce guide dit où cliquer. L'installation du serveur et de la console est dans [EXPLOITATION.md](EXPLOITATION.md) (§ 2 et
§ 3 bis) ; l'essai sur votre PC est dans [TESTER-EN-LOCAL.md](TESTER-EN-LOCAL.md).

## 1. Se connecter

| | Adresse | Premier compte |
|---|---|---|
| **Serveur en production** | `https://console.ticagou.nc` (la valeur de `CONSOLE_DOMAIN` dans `socle.env`), ouverte seulement depuis les adresses IP de `CONSOLE_ADRESSES_AUTORISEES` | `https://console.ticagou.nc/premier-compte?jeton=<CONSOLE_SETUP_TOKEN>` |
| Sans adresse IP fixe | tunnel SSH : `ssh -L 8095:127.0.0.1:8095 <serveur>`, puis `http://localhost:8095` | `http://localhost:8095/premier-compte?jeton=<CONSOLE_SETUP_TOKEN>` |
| Sur votre PC (essai) | `scripts\tester-en-local.ps1 -Demo -Console`, puis `http://localhost:1421` | `http://localhost:1421/premier-compte?jeton=dev` |

1. La première fois, ouvrez l'adresse « premier compte » : choisissez votre identifiant et votre mot de passe.
2. Connectez-vous : la console affiche un QR code. Scannez-le avec une application d'authentification (Google
   Authenticator, Microsoft Authenticator…). Ensuite, chaque connexion demande identifiant, mot de passe et le code à 6
   chiffres.
3. Les actions sensibles (ordres, validation de facture) redemandent ce code.

Vous avez perdu votre téléphone : sur le serveur, `./creche.sh console-telephone-perdu <identifiant>` ; le QR code se
réaffiche à la connexion suivante.

## 2. Gérer une crèche : où cliquer

Tout part de **Parc des crèches**. Cliquez sur une crèche pour ouvrir sa fiche (onglets Santé, Abonnement, Usage,
Factures, Contacts et notes, Journal). Un ordre donné s'affiche dans **Ordres** et en bas de l'onglet Abonnement : « En
attente de l'hôte », puis « Fait » (ou « Échec », avec la sortie) en 1 à 2 minutes.

| Besoin | Où |
|---|---|
| Nouvelle crèche (prospect) | Parc des crèches › Nouvelle crèche, puis sur sa fiche : **Créer l'instance**. Le lien de démarrage à remettre à la directrice s'affiche dans l'ordre, avec un bouton Copier |
| Démonstration pour un salon, un rendez-vous | Ordres › Démonstration… ; supprimez-la depuis sa fiche quand elle a servi |
| Saisir ou modifier le contrat | Fiche › Abonnement › Saisir le contrat (ou Avenant ou correction…) |
| Reporter le contrat dans la crèche (places, PC, tablettes, échéance) | Fiche › Abonnement › Reporter le contrat dans l'instance. Proposé tout seul après le paiement d'une facture, à confirmer |
| Facturer, relancer | Factures, puis Boîte d'envoi : rien ne part sans votre clic |
| **Masquer des rubriques** que la crèche n'utilise pas (comptabilité, congés, repas…) | Fiche › Abonnement › **Rubriques affichées** : décochez, puis Appliquer (code). Rien n'est effacé, une rubrique se réaffiche sans perte ([ADR 0022](adr/0022-rubriques-masquees.md)) |
| **Mot de passe oublié** par la directrice | Fiche › Abonnement › **Accès de la crèche** : son identifiant, puis « Mot de passe oublié : créer un lien ». Le lien (24 h, usage unique) s'affiche dans l'ordre, avec Copier : envoyez-le-lui par SMS ou e-mail. Elle choisit elle-même son nouveau mot de passe ; vous ne le connaissez jamais |
| Téléphone perdu (double authentification) | Fiche › Abonnement › Accès de la crèche : son identifiant, puis « Téléphone perdu ». Elle rescanne un QR code à la connexion suivante |
| Code d'activation du portail parents | Fiche › Abonnement › Accès de la crèche › Code du portail parents. Le code s'affiche dans l'ordre ; la direction le saisit dans Paramètres › Portail parents |
| Impayé : suspendre, puis reprendre | Fiche › Suspendre… (avec le motif), puis Reprendre |
| Mettre à jour Ti'Cagou | Ordres › Mise à jour… : une crèche témoin d'abord, puis les autres |
| Exporter les données d'une crèche | Fiche › Abonnement › Données de la crèche › Exporter les données |
| Fin de contrat : supprimer la crèche | Fiche › Abonnement › Données de la crèche › Supprimer la crèche… (exporte et télécharge d'abord) |
| Prix, mentions légales, TGC, relances | Paramètres |

Avant de créer un lien de mot de passe ou de refaire la double authentification, **vérifiez l'identité de la personne
qui appelle** (rappel au numéro de la fiche Contacts, par exemple) : ce lien ouvre le dossier de la crèche.

## 3. Ce qui demande encore le serveur

Rien de la gestion courante. Restent en ligne de commande, sur le serveur : l'installation (§ 2 d'EXPLOITATION.md),
la construction d'une nouvelle image avant une mise à jour (`./creche.sh construire <version>`), la restauration d'une
sauvegarde (`./creche.sh restaurer`), et le téléphone perdu de votre propre compte de console. Chaque action de la
console a aussi sa commande (`./creche.sh` sans argument les liste), utile si la console est indisponible.

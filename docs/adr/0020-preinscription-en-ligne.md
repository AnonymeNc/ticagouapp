# ADR 0020 — Pré-inscription en ligne

- **Statut** : acceptée
- **Date** : 10/11/2026

## Contexte

Les familles déposent leur demande d'inscription en ligne, depuis le
site de la crèche, sans compte. Le formulaire est public, donc exposé aux robots ; il recueille des données
personnelles d'enfants à naître ou en bas âge.

## Décision

- **Page publique** `/preinscription` servie par l'instance de la crèche (ADR 0013), API publique
  `/api/v1/public/preinscription`. Désactivée tant que la direction ne l'a pas ouverte (Paramètres › Inscriptions).
- **Pas d'e-mail de confirmation** : l'instance n'envoie pas d'e-mails et on n'ajoute pas de serveur de messagerie par
  crèche. Une demande en ligne entre dans le tableau des inscriptions à l'étape « Nouvelle », marquée « En ligne » ;
  la direction rappelle la famille comme pour une demande reçue au bureau.
- **Anti-abus sans CAPTCHA** : champ piège invisible, temps de saisie minimal (5 s), 5 demandes par heure et par
  adresse IP, 30 demandes en ligne par jour et par crèche. Une demande refusée par ces règles reçoit la même réponse
  qu'une demande acceptée (rien à apprendre pour un robot).
- **Questions personnalisées**, datées comme les critères : texte, oui/non ou choix ; la réponse garde le libellé de
  la question au moment de la demande.
- **Consentement** : case obligatoire (traitement de la demande, durée de conservation), horodatée.
- **Conservation** : une demande refusée, ou restée sans suite à l'étape « Nouvelle » ou « À recontacter », est
  effacée 12 mois après son dernier changement d'étape (tâche de nuit), sauf si elle a abouti à un enfant inscrit.
- **Intégration** au site de la crèche : lien direct, bouton, ou `<iframe>`. Le Caddyfile autorise l'affichage de
  `/preinscription` dans un cadre sur tout site HTTPS (`frame-ancestors https:`) ; le reste de l'application reste
  interdit de cadre. Le formulaire ne déclenche aucune action authentifiée : le risque de détournement de clic est nul.

## Conséquences

- Le dépôt de pièces (vaccins, justificatifs) par la famille n'est pas inclus : il passera par le portail parents une
  fois la place acceptée.
- Une crèche qui veut un contrôle d'adresse e-mail devra attendre un envoi d'e-mails côté instance (ADR à venir).

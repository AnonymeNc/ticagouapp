# Relier une crèche au portail parents

[← Sommaire](README.md)

Prérequis : le portail tourne ([EXPLOITATION.md](../EXPLOITATION.md)) et l'abonnement de la crèche comprend le module
`PORTAIL_PARENTS`.

## 1. Créer le code d'activation

Le code permet à **une** crèche de s'enregistrer sur le portail. Il ne sert qu'une fois et expire au bout de 30 jours.

Sur le serveur du portail (`ssh root@<IP du VPS>`) :

```bash
cd /opt/ticagou/parents-portal/deploy
chmod +x code-activation.sh
./creche.sh code-portail "Les P'tits Niaoulis — Dumbéa"
```

Réponse : `{"activationCode":"K7QM-2WXP-9HTD"}`. Notez le code dans le dossier du client. L'intitulé entre
guillemets ne sert qu'à vous (liste des codes).

## 2. Activer le portail dans la crèche (avec la directrice)

1. PC du bureau, en direction : **Paramètres** › **Portail parents**.
2. **Adresse du portail** : `https://parents.ticagou.nc` (votre domaine) ; **Code d'activation** : le code du § 1 ›
   **Activer le portail**.
3. L'état passe à « Activé ». En cas d'erreur :
   - « Activation refusée. Portail : Code d'activation inconnu, expiré ou déjà utilisé » : créez un nouveau code ;
   - « Portail injoignable » : adresse mal saisie, ou portail arrêté ;
   - « Le portail doit être joint en HTTPS » : l'adresse doit commencer par `https://`.

## 3. Régler ce qui est partagé

Toujours dans Paramètres › Portail parents, section « Ce qui est partagé avec les familles » :
- cochez les catégories souhaitées (transmissions, photos, factures, documents, messages, absences) ;
- **Coordonnées de paiement affichées** : laissez vide pour reprendre l'IBAN de la fiche Structure ;
- **Repas non facturé si l'absence est signalée avant** : une heure (par exemple `09:00`) seulement si la crèche le
  pratique réellement ; vide sinon (les parents n'en sont pas informés) ;
- **Enregistrer**.

## 4. Informer les familles (avant toute invitation)

Obligatoire : les familles doivent savoir quelles données sont mises en ligne. Modèle de note à remettre (à faire
valider par la crèche) :

> **Application Ti'Cagou pour les familles**
> La crèche vous propose de suivre la journée de votre enfant sur votre téléphone : arrivée et départ, repas, sieste,
> change, activités, photos (seulement si vous avez autorisé les photos, et seulement celles où tous les enfants
> présents sont autorisés), messages, factures et documents à signer. Y figurent aussi les médicaments donnés à votre
> enfant (nom, dose, heure), visibles seulement par sa famille. Les autres informations de santé n'y figurent pas, sauf
> celles que la direction choisit de vous montrer. Les transmissions et photos sont effacées au bout de 60 jours, les
> factures et documents au bout de 24 mois. Le service est hébergé par Ti'Cagou pour le compte de la crèche. Vous
> pouvez demander à ne pas y avoir accès, ou à ce que votre accès soit retiré, à tout moment auprès de la direction.
> Dans l’application Ti’Cagou Parents, vous pouvez aussi supprimer votre compte vous-même. Les notifications passent
> par les services d’Apple (iPhone) ou de Google (Android) : elles ne contiennent jamais d’information de santé ni le
> texte des messages.

Pensez aussi à recueillir l'**autorisation de photographier** (fiche enfant › Autorisations, ou proposée à la
signature sur le portail).

## 5. Inviter les parents

Pour chaque enfant : fiche › **Journée et portail** › section « Accès de la famille » › **Inviter au portail** sur la
ligne de chaque responsable. Il faut une adresse e-mail dans la fiche (onglet Famille). Le parent n'a rien reçu à ce
stade : c'est lui qui se connecte (§ 6). Remettez-lui l'adresse du portail (QR code imprimé, affiche à l'accueil).

## 6. Première connexion d'un parent (à montrer)

**Avec l’application** (conseillé, surtout sur iPhone) : Ti’Cagou Parents › adresse e-mail › code à 6 chiffres reçu.
Voir [17-application-parents.md](17-application-parents.md) et la fiche à imprimer [18-guide-parents.md](18-guide-parents.md).

**Avec le navigateur** :

1. Sur son téléphone : `https://parents.ticagou.nc` › son adresse e-mail › **Recevoir mon lien de connexion**.
2. Il ouvre l'e-mail « Votre connexion à Ti'Cagou » : il touche le lien, **ou** recopie le code à 6 chiffres.
3. Il ajoute l'application à l'écran d'accueil (menu du navigateur › « Ajouter à l'écran d'accueil »).
4. Il reste connecté tant que l'abonnement de la crèche court (grâce de 30 jours comprise) : pas de nouvel e-mail
   à chaque visite. Un renouvellement (`creche.sh abonnement`) prolonge sa connexion.

## 7. Vérifier

- Paramètres › Portail parents : « Dernier envoi réussi » récent, « En attente d'envoi » qui retombe à 0
  (synchronisation chaque minute ; **Synchroniser maintenant** pour forcer).
- Un parent de test voit la journée, une facture, un message ; une absence signalée apparaît au tableau de bord de la
  crèche.

## 8. Arrêter le portail pour une crèche

Paramètres › Portail parents › saisir `DÉSACTIVER` › **Désactiver et effacer** : plus rien ne part, et le portail
efface tout ce qu'il détient pour cette crèche. Si internet est coupé, l'effacement est renvoyé jusqu'à ce qu'il
réussisse. Retirer l'accès d'un seul parent : fiche enfant › Journée et portail › **Retirer l'accès**.

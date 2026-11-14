# L'application Ti'Cagou Pointage (tablette d'accueil)

[← Sommaire](README.md) · Voir aussi [Installer une tablette](04-tablettes.md) (version navigateur)

Ti'Cagou Pointage est l'application de la **tablette Android** de l'accueil. C'est le même écran de pointage que dans le
navigateur (`/kiosk`), avec en plus : le **mode kiosque** (la tablette reste sur Ti'Cagou), le scan du QR d'appairage
par l'appareil photo, et le secret de la tablette gardé dans la puce de sécurité d'Android. iPad : pas d'application
(décision du 14/11/2026) ; un iPad reste utilisable dans le navigateur.

Tablette conseillée : Android 10 ou plus (Android 8 au minimum), 10 pouces, avec appareil photo.

## 1. Appairer la tablette

1. Installez **Ti'Cagou Pointage** depuis Google Play.
2. Sur l'ordinateur : **Paramètres › Tablettes et abonnement › Appairer une tablette**. Un QR code et un code à
   6 chiffres s'affichent (valables quelques minutes).
3. Sur la tablette : **Scanner le QR** et visez l'écran de l'ordinateur. L'adresse de la crèche et le code sont lus d'un
   coup.
   Sans appareil photo : tapez l'**adresse de la crèche** (`https://…`), puis le code à 6 chiffres.
4. La tablette affiche le nom de la crèche : c'est fait.

## 2. Le mode kiosque

Touchez **Activer le mode kiosque** dans la barre du haut. La tablette reste sur Ti'Cagou : pas de barre de navigation,
pas d'autre application, écran allumé, et Ti'Cagou revient tout seul après un redémarrage.

- Tablette ordinaire : Android demande une confirmation la première fois (« épingler l'application »).
- Tablette gérée par Ti'Cagou (Managed Google Play, propriétaire de l'appareil) : verrouillage complet, sans
  confirmation.
- Pour démarrer directement sur le pointage : à la première pression sur le bouton Accueil, choisissez **Ti'Cagou
  Pointage** comme écran d'accueil, **Toujours**.

**Sortir du mode kiosque** : appui long de 5 secondes sur **Mode kiosque**, puis le code personnel d'une personne de la
direction (vérifié par le serveur, tracé au journal). Hors connexion, la sortie attend le retour du réseau.

## 3. Au quotidien

Rien ne change par rapport à la version navigateur : PIN de la personne, arrivées, départs, journée, photos et vidéos.
Nouveau : en cas de **personne non autorisée** sans directrice sur place, **« Directrice absente ? Demander son accord à
distance »** prévient la direction sur son téléphone ; gardez l'enfant avec vous jusqu'à la réponse.

## 4. Hors connexion

Le bandeau rouge « Hors connexion depuis … » indique combien de pointages attendent. Ils sont gardés **chiffrés** sur la
tablette et partent au retour du réseau. Le taux d'encadrement affiché ne compte alors que cette tablette. Si la coupure
dure plus de 30 minutes pendant l'ouverture, la direction en est prévenue (si elle l'a choisi).

## 5. Dépannage

| Ce que vous voyez | Que faire |
|---|---|
| « Ce QR code n'est pas un QR d'appairage Ti'Cagou » | Vous avez scanné le QR « Ouvrir sur mon téléphone » : prenez celui d'« Appairer une tablette ». |
| Code refusé | Il a expiré : affichez-en un nouveau sur l'ordinateur. |
| Bandeau « Mettez à jour Ti'Cagou Pointage » | Le pointage continue. Mettez à jour depuis Google Play dès que possible. |
| La tablette demande à nouveau l'appairage | Elle a été retirée dans Paramètres : appairez-la de nouveau. |
| Impossible de sortir du mode kiosque | Réseau nécessaire pour vérifier le code. En dernier recours (tablette non gérée) : boutons Retour et Aperçu maintenus ensemble. |

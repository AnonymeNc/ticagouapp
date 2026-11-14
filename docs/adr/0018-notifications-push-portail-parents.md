# ADR 0018 — Notifications sur le téléphone des parents (Web Push)

- **Statut** : acceptée, complétée par l'[ADR 0026](0026-notifications-natives.md) (notifications natives APNs et FCM des applications)
- **Date** : 10/11/2026
- Complète l'[ADR 0010](0010-portail-parents-flux-de-donnees.md) (portail parents).

## Contexte

L'application parents demande que les parents soient prévenus
quand la crèche partage quelque chose : une photo, un message, une actualité, un document à signer, une facture.
L'application parents est une PWA servie par le portail ; il n'y a pas d'application des magasins Apple ou Google.

Contraintes :
- pas de dépendance lourde sans ADR (CLAUDE.md) ; les bibliothèques Java de Web Push tirent Bouncy Castle et un client
  HTTP supplémentaire ;
- les données de santé ne sortent pas de la crèche ; une notification transite par le service de notification du
  navigateur (Google, Apple, Mozilla), hors de notre contrôle.

## Décision

**Standard Web Push**, sans service tiers ni compte : VAPID ([RFC 8292](https://www.rfc-editor.org/rfc/rfc8292)) pour
identifier le portail, contenu chiffré de bout en bout ([RFC 8291](https://www.rfc-editor.org/rfc/rfc8291),
`aes128gcm`, [RFC 8188](https://www.rfc-editor.org/rfc/rfc8188)).

**Aucune dépendance nouvelle** : le JDK 21 fournit ECDH et ECDSA P-256, HMAC-SHA256 (HKDF), AES-128-GCM et le client
HTTP. Le chiffrement est vérifié par le vecteur de test de l'annexe A du RFC 8291.

**Clés VAPID** : une paire P-256 par portail. Fournie par `PORTAL_VAPID_PRIVATE_KEY` (PKCS#8 en Base64) ; sinon
générée au premier démarrage et gardée dans la base (`portal_secret`). Une fuite de la base permettrait d'envoyer des
notifications aux abonnés, sans rien lire : risque accepté, la clé se change en vidant la ligne (les parents se
réabonnent).

**Abonnements** (`push_subscription`) : par appareil, rattachés à l'adresse e-mail de la session, avec les catégories
choisies par le parent (photos, messages, actualités, documents, factures ; factures décochées par défaut). Un
abonnement refusé par le service (404, 410) est effacé ; il l'est aussi quand l'adresse n'a plus d'accès (même règle
que les sessions).

**Contenu** : un titre et une phrase courte, sans donnée de santé ni contenu de message : « Nouvelle photo de Maëlys »,
« Nouveau message de la crèche », « Actualité : Fête du lagon samedi », « Document à signer », « Nouvelle facture ».
Le texte d'une actualité n'est pas repris, seulement son titre. Le service de notification ne voit que du chiffré ;
le téléphone affiche le texte, écran verrouillé compris.

**Déclencheurs** : à l'arrivée d'un élément *nouveau* dans un lot de la crèche (un élément déjà reçu et renvoyé ne
notifie pas) : photo partagée (`ENTRY` de type PHOTO), message de l'équipe, actualité, document à signer, facture ou
avoir. Une notification par famille et par élément. L'envoi part après la validation de la transaction, en tâche de
fond, avec un délai de vie (TTL) de 24 h.

**Le portail ne calcule toujours rien** : il notifie ce qu'il reçoit, selon le choix du parent.

## Conséquences

- Android, Windows, macOS : notifications dès que le parent les autorise.
- iPhone (iOS 16.4 et plus) : seulement si l'application est ajoutée à l'écran d'accueil ; l'écran Notifications
  l'explique.
- Les crèches n'ont rien à régler ; un parent choisit ses catégories, par appareil.
- Le service de notification du navigateur est un sous-traitant de fait (il voit l'adresse d'abonnement et l'heure) :
  à mentionner dans l'information des familles.

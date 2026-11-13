# ADR 0024 — Authentification dans les applications mobiles

- **Statut** : acceptée
- **Date** : 14/11/2026
- **Modifie** : [ADR 0002](0002-authentification.md) (stockage du jeton de rafraîchissement), [ADR 0013](0013-web-heberge-instance-par-creche.md) (même origine, cookie de session)
- **S'appuie sur** : [ADR 0023](0023-applications-mobiles-capacitor.md)

## Contexte

Le navigateur parle à son instance sur la même origine. Son jeton de rafraîchissement est dans un cookie `HttpOnly`,
`Secure`, `SameSite=Strict` (ADR 0013), demandé par l'en-tête `X-Ticagou-Session` ; sans cet en-tête, le serveur
renvoie déjà le jeton dans la réponse (c'était le cas du client Tauri, ADR 0002).

Une application Capacitor tourne sur `capacitor://localhost` (iOS) ou `https://localhost` (Android) : l'API de
l'instance est une **autre origine**. Un cookie `SameSite=Strict` n'y est pas envoyé, et les cookies tiers sont bloqués
par les vues web. Le portail parents a le même problème avec son cookie de session.

Un téléphone se perd, se prête, s'oublie sur une table : les données de santé d'enfants ne doivent pas s'y lire
librement.

## Décision

**Navigateur : rien ne change.** Cookie de rafraîchissement, jeton d'accès en mémoire, même origine.

**Applications Direction et Parents : jeton porteur**
- Jeton d'accès en mémoire seulement, envoyé dans `Authorization: Bearer`.
- Jeton de rafraîchissement renvoyé dans le corps (pas d'en-tête `X-Ticagou-Session`), gardé dans le **Keychain**
  (iOS, `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`) ou le **Keystore** (Android) par le plugin de stockage sécurisé.
  Jamais dans `localStorage`, `Preferences` ou un fichier.
- Rotation à chaque usage et révocation de la famille en cas de réutilisation : inchangées (ADR 0002).
- Chaque application s'annonce : `X-Ticagou-App: direction/1.4.0 (ios)`. Le serveur s'en sert pour la version
  minimale (ADR 0027), le nom du poste et le journal. Ce n'est pas une preuve d'identité.
- Portail parents : la connexion par code reçu par e-mail renvoie le jeton de session dans le corps quand
  `X-Ticagou-App` est présent ; `ParentSessionInterceptor` accepte le cookie **ou** `Authorization: Bearer`. Le lien
  magique reste pour le navigateur.

**CORS** (`ticagou.allowed-origins`, portail : idem)
- Par défaut : `capacitor://localhost` et `https://localhost`, en plus des origines de développement.
- `allowCredentials=false` pour ces origines : aucun cookie ne traverse. La sécurité repose sur le jeton porteur, pas
  sur l'origine (une page web qui imiterait l'origine n'a pas le jeton).
- Le flux SSE de l'encadrement passe déjà par `fetch` avec l'en-tête `Authorization` : il fonctionne tel quel.

**Verrouillage local**
- Application Direction : **verrouillage obligatoire** au retour après 2 minutes en arrière-plan et à chaque
  lancement : Face ID / empreinte, ou code de l'appareil en repli. Sans verrou configuré sur le téléphone,
  l'application refuse de garder la session (elle redemande le mot de passe à chaque ouverture).
- Application Parents : verrouillage **proposé** (désactivé par défaut) ; les parents ne voient pas les données de santé
  détaillées, mais la vie de leur enfant.
- Le verrou est local : il ne remplace pas la double authentification et le serveur ne le voit pas. Il conditionne la
  lecture du jeton de rafraîchissement dans le trousseau.
- Aperçu masqué dans le sélecteur d'applications et captures d'écran bloquées sur Android (`privacy-screen`), pour
  Direction et Pointage.

**Double authentification : inchangée.** TOTP obligatoire pour DIRECTION et COMPTABILITE (ADR 0013), demandé à la
connexion par mot de passe, pas au déverrouillage biométrique.

**Durée des sessions**
- Navigateur : 7 jours (`refresh-token-ttl`), inchangé.
- Application Direction : 30 jours glissants (`app-refresh-token-ttl`), parce qu'un verrou biométrique protège l'accès.
  Retaper mot de passe et code TOTP chaque semaine sur un téléphone pousserait à désactiver le verrou de l'appareil.
- Une session inutilisée 30 jours expire ; la direction peut révoquer un appareil à tout moment (ci-dessous).

**Appareils et révocation**
- L'identifiant de poste (ADR 0011) est tiré à la première ouverture et gardé dans le stockage sécurisé, avec un nom
  lisible (« iPhone de Sandrine », modèle de l'appareil). Paramètres › Postes liste aussi les téléphones.
- Retirer un poste révoque ses jetons de rafraîchissement et ses jetons de notification (ADR 0026) : la session tombe
  au prochain renouvellement (15 minutes au plus).
- « Se déconnecter » et « Oublier cette crèche » (ADR 0025) effacent du téléphone les jetons, l'identifiant de poste
  et les caches de cette crèche.

**Tablette de pointage** : inchangée dans son principe (appareil appairé, PIN, session en mémoire, ADR 0005 et 0008).
Le secret de l'appareil et la clé de la file chiffrée passent dans le Keystore sur l'application native.

## Alternatives écartées

- **Garder le cookie avec `SameSite=None`** : refusé par les vues web iOS (ITP) et ouvre la porte au CSRF.
- **Charger l'instance dans la vue web (`server.url`) pour rester sur la même origine** : plus d'application hors
  connexion pour le pointage, contenu non maîtrisé par la version publiée, risque de refus Apple (« site web
  emballé »). Voir ADR 0027.
- **Jeton de rafraîchissement dans `Preferences`** : lisible sur un appareil rooté ou dans une sauvegarde.
- **Biométrie comme second facteur côté serveur (WebAuthn / passkeys)** : intéressant à terme, mais demande un
  serveur de clés et un parcours de secours ; la TOTP reste le second facteur.

## Conséquences

- `SessionCookies` reste pour le navigateur ; le mode « corps de réponse » devient le mode des applications et
  reçoit sa propre durée.
- Le front gagne un adaptateur d'authentification (`lib/native/session.ts`) : cookie dans le navigateur, trousseau dans
  les applications. Le reste du code ne voit qu'un jeton d'accès.
- La perte d'un téléphone se traite comme celle d'un PC : retrait du poste par la direction ; double authentification
  réinitialisée si le téléphone portait l'application TOTP (procédure de l'ADR 0013).

# Captures d'écran

Captures **réelles** de Ti'Cagou, faites sur la démonstration (« Les P'tits Niaoulis », données fictives de
`scripts/demo/donnees-demo.mjs`). Aucune donnée réelle.

| Dossier | Contenu |
|---|---|
| `*.jpg` (racine) | captures historiques du bureau et de la PWA parents (prospectus) |
| `mobile/` | application Direction : téléphone (390 px), tablette (1 180 px), bureau (1 440 px) ; choix de la crèche (`app-*`) |
| `stores/direction/` | formats des stores : iPhone 6,9 po (1 320 × 2 868), Android (1 080 × 2 340), iPad 13 po (2 064 × 2 752) |
| `stores/pointage/` | tablette Android 10 po (2 560 × 1 600) |

## Refaire les captures

1. Démonstration chargée (`scripts/tester-en-local.ps1 -Demo`, ou `node scripts/demo/donnees-demo.mjs --url …`), **un jour
   de semaine, le matin** : `node scripts/demo/donnees-demo.mjs --aujourdhui` pointe les arrivées du jour. Le week-end ou
   le soir, les écrans sont vides et l'encadrement paraît insuffisant (c'est le cas des captures du 14/11/2026, un samedi
   soir : **à refaire avant la publication dans les stores**).
2. Départ bloqué à valider : `node scripts/captures/scenario-depart.mjs --url <serveur>`.
3. Captures :

   ```bash
   node scripts/captures/captures.mjs --url http://localhost:1430 --sortie docs/commercial/captures/stores/direction --pages tableau-de-bord,depart-a-valider,fiche-sante,presences,facturation --tailles store-iphone,store-android,store-ipad
   ```

   Écrans d'avant la connexion de l'application (config « web-app », `--sans-connexion`) ; tablette appairée :
   `--chemins "/kiosk?pair=<code>"` (code d'appairage créé dans Paramètres). Sous Git Bash, préfixer par
   `MSYS_NO_PATHCONV=1`.
4. `--debordements` signale tout élément qui dépasse la largeur de l'écran (contrôle de la phase 3).

Les captures de l'application Parents pour les stores restent à faire avec le portail de démonstration
(`donnees-demo.mjs --portail`), aux mêmes formats.

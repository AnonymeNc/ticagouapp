# Présentations PowerPoint

[← Wiki](../../wiki/README.md)

| Fichier | Pour quoi | Diapositives |
|---|---|---|
| `Ti'Cagou - Présentation.pptx` | Rendez-vous commercial, salon : le quotidien, ce que fait Ti'Cagou, les applications, la sécurité, l'offre, la mise en service | 14 |
| `Ti'Cagou - Plaquette.pptx` | Plaquette A4 à imprimer ou à envoyer en PDF | 1 |
| `Ti'Cagou - Formation Direction.pptx` | Formation de la direction, 3 h 30, d'après [12-formation-direction.md](../../wiki/12-formation-direction.md) | 14 |
| `Ti'Cagou - Formation Equipes.pptx` | Formation des éducatrices, de la cuisine et de la comptabilité, d'après [13-formation-equipe.md](../../wiki/13-formation-equipe.md) | 15 |
| `Ti'Cagou - Formation Applications mobiles.pptx` | Session sur les applications Direction, Pointage et Parents, d'après [19-session-formation-applications.md](../../wiki/19-session-formation-applications.md) | 9 |

Avant d'envoyer la présentation ou la plaquette : remplacez la ligne « [Votre nom] · [téléphone] · [e-mail] · [site] ».
Les prix sont ceux du [barème](../bareme-tarifaire.md).

## Les régénérer

Elles sont produites par `outils/presentations` (charte, captures de `docs/commercial/captures`, textes tirés du wiki).
Après un changement du wiki, du barème ou des captures :

```bash
cd outils/presentations
```

```bash
pnpm install
```

```bash
pnpm generer
```

Une modification faite à la main dans PowerPoint est perdue à la génération suivante : changez plutôt
`outils/presentations/generer.mjs`.

## Images de présentation Google Play (1024 × 500)

`docs/commercial/captures/stores/<app>/presentation-1024x500.png`, produites par le même outil (PowerPoint requis pour
l'export en PNG) :

```bash
node visuels-stores.mjs
```

```bash
powershell -ExecutionPolicy Bypass -File exporter-png.ps1
```

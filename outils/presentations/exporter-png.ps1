# Exporte visuels-stores.pptx en images Google Play 1024 x 500 :
# docs/commercial/captures/stores/<app>/presentation-1024x500.png (le nom de l'application est dans les notes).
$ErrorActionPreference = 'Stop'
$racine = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$app = New-Object -ComObject PowerPoint.Application
try {
    $p = $app.Presentations.Open((Join-Path $PSScriptRoot 'visuels-stores.pptx'), $true, $false, $false)
    foreach ($s in $p.Slides) {
        $nom = $s.NotesPage.Shapes.Placeholders(2).TextFrame.TextRange.Text.Trim()
        $dossier = Join-Path $racine "docs\commercial\captures\stores\$nom"
        New-Item -ItemType Directory -Force $dossier | Out-Null
        $fichier = Join-Path $dossier 'presentation-1024x500.png'
        $s.Export($fichier, 'PNG', 1024, 500)
        "docs/commercial/captures/stores/$nom/presentation-1024x500.png"
    }
    $p.Close()
} finally {
    $app.Quit()
}

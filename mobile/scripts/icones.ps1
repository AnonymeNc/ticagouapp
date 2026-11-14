<#
  Icônes et écrans de démarrage des applications Ti'Cagou (ADR 0023), dessinés aux couleurs de la charte
  (web/src/index.css) sans outil ni dépendance : System.Drawing de Windows.

    powershell -ExecutionPolicy Bypass -File mobile/scripts/icones.ps1

  Chaque application a sa couleur : Direction lagon (#0b6e72), Pointage niaouli (#3f5a26), Parents terre (#b5532a).
  Icône : « T’ » blanc sur la couleur de l'application. Démarrage : « Ti’Cagou » sur fond crème (#fbf8f3).
  Les PNG sont régénérés à la taille des fichiers déjà présents dans les projets natifs (créés par « cap add »).
#>
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$mobile = Split-Path -Parent $PSScriptRoot
$apps = @{
  direction = @{ Color = "#0b6e72"; Label = "Direction" }
  pointage  = @{ Color = "#3f5a26"; Label = "Pointage" }
  parents   = @{ Color = "#b5532a"; Label = "Parents" }
}
$cream = [System.Drawing.ColorTranslator]::FromHtml("#fbf8f3")
$ink = [System.Drawing.ColorTranslator]::FromHtml("#08585c")
$muted = [System.Drawing.ColorTranslator]::FromHtml("#665b4e")

function New-Canvas([int]$w, [int]$h) {
  $bmp = New-Object System.Drawing.Bitmap $w, $h
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = "AntiAlias"; $g.TextRenderingHint = "AntiAliasGridFit"; $g.InterpolationMode = "HighQualityBicubic"
  return @($bmp, $g)
}

function Draw-Centered($g, [string]$text, [string]$fontName, [float]$size, $color, [float]$cx, [float]$cy, [string]$style = "Bold") {
  $font = New-Object System.Drawing.Font $fontName, $size, ([System.Drawing.FontStyle]::$style), ([System.Drawing.GraphicsUnit]::Pixel)
  $brush = New-Object System.Drawing.SolidBrush $color
  $format = New-Object System.Drawing.StringFormat
  $format.Alignment = "Center"; $format.LineAlignment = "Center"
  $g.DrawString($text, $font, $brush, $cx, $cy, $format)
  $font.Dispose(); $brush.Dispose()
}

# Icône : fond plein (le système applique son masque), « T’ » dans la zone sûre. $round : pastille ronde (ic_launcher_round).
function Save-Icon([string]$path, [int]$w, [int]$h, $color, [bool]$transparent = $false, [bool]$round = $false, [float]$scale = 1.0) {
  $canvas = New-Canvas $w $h; $bmp = $canvas[0]; $g = $canvas[1]
  if ($transparent) { $g.Clear([System.Drawing.Color]::Transparent) }
  elseif ($round) {
    $g.Clear([System.Drawing.Color]::Transparent)
    $brush = New-Object System.Drawing.SolidBrush $color
    $g.FillEllipse($brush, 0, 0, $w - 1, $h - 1); $brush.Dispose()
  }
  else { $g.Clear($color) }
  Draw-Centered $g "T’" "Georgia" ([float]($h * 0.52 * $scale)) ([System.Drawing.Color]::White) ($w / 2 + $w * 0.02) ($h / 2)
  $g.Dispose(); $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
}

function Save-Splash([string]$path, [int]$w, [int]$h, $color, [string]$label) {
  $canvas = New-Canvas $w $h; $bmp = $canvas[0]; $g = $canvas[1]
  $g.Clear($cream)
  $unit = [Math]::Min($w, $h)
  Draw-Centered $g "Ti’Cagou" "Georgia" ([float]($unit * 0.11)) $ink ($w / 2) ($h / 2 - $unit * 0.02)
  Draw-Centered $g $label "Segoe UI" ([float]($unit * 0.04)) $color ($w / 2) ($h / 2 + $unit * 0.08) "Regular"
  $g.Dispose(); $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
}

function Size-Of([string]$path) {
  $img = [System.Drawing.Image]::FromFile($path)
  $size = @($img.Width, $img.Height); $img.Dispose(); return $size
}

foreach ($name in $apps.Keys) {
  $app = $apps[$name]
  $color = [System.Drawing.ColorTranslator]::FromHtml($app.Color)
  $res = Join-Path $mobile "$name/android/app/src/main/res"
  if (Test-Path $res) {
    # Dans un switch, $_ désigne la valeur testée : le fichier passe par $file.
    foreach ($file in Get-ChildItem $res -Recurse -Filter "*.png") {
      $path = $file.FullName
      $s = Size-Of $path
      switch ($file.Name) {
        "ic_launcher.png" { Save-Icon $path $s[0] $s[1] $color }
        "ic_launcher_round.png" { Save-Icon $path $s[0] $s[1] $color $false $true 0.9 }
        # Premier plan de l'icône adaptative : 108 dp dont 66 dp visibles au centre.
        "ic_launcher_foreground.png" { Save-Icon $path $s[0] $s[1] $color $true $false 0.62 }
        "splash.png" { Save-Splash $path $s[0] $s[1] $color $app.Label }
      }
    }
    Set-Content -Encoding utf8 (Join-Path $res "values/ic_launcher_background.xml") @"
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">$($app.Color)</color>
</resources>
"@
    # Dessins par défaut de Capacitor, inutilisés par l'icône adaptative (fond = couleur, premier plan = PNG).
    Remove-Item -ErrorAction SilentlyContinue (Join-Path $res "drawable/ic_launcher_background.xml"), (Join-Path $res "drawable-v24/ic_launcher_foreground.xml")
  }
  $assets = Join-Path $mobile "$name/ios/App/App/Assets.xcassets"
  if (Test-Path $assets) {
    Save-Icon (Join-Path $assets "AppIcon.appiconset/AppIcon-512@2x.png") 1024 1024 $color
    foreach ($file in Get-ChildItem (Join-Path $assets "Splash.imageset") -Filter "*.png") {
      $s = Size-Of $file.FullName
      Save-Splash $file.FullName $s[0] $s[1] $color $app.Label
    }
  }
  Write-Host "Ti'Cagou $($app.Label) : icônes et démarrage générés."
}

<#
  生成 Android 图标资源。
  用法: powershell -ExecutionPolicy Bypass -File .\tools\make-android-icons.ps1 -Source <方形的PNG，>=256x256>
#>
param(
  [Parameter(Mandatory=$true)][string]$Source
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$res  = Join-Path $root 'app\res'
if (-not (Test-Path $Source)) { throw ('源文件不存在: ' + $Source) }

Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Image]::FromFile($Source)
if ($src.Width -lt 256) { throw '源图建议至少 256x256' }

function Save-Resized([System.Drawing.Image]$img, [int]$size, [string]$outPath, [double]$scale) {
  $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $g.Clear([System.Drawing.Color]::Transparent)
  $inner = [int]($size * $scale)
  $off = [int](($size - $inner) / 2)
  $g.DrawImage($img, $off, $off, $inner, $inner)
  $g.Dispose()
  $dir = Split-Path -Parent $outPath
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
}

# 传统 mipmap：满幅
$dens = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
foreach ($k in $dens.Keys) {
  $s = $dens[$k]
  Save-Resized $src $s (Join-Path $res ('mipmap-' + $k + '\ic_launcher.png')) 1.0
  Save-Resized $src $s (Join-Path $res ('mipmap-' + $k + '\ic_launcher_round.png')) 1.0
}

# 自适应图标前景：安全区约 66%，这里取 0.62 让鲸鱼留出边距
foreach ($k in $dens.Keys) {
  $s = [int]($dens[$k] * 108 / 48)
  Save-Resized $src $s (Join-Path $res ('drawable-' + $k + '\ic_launcher_foreground.png')) 0.62
}

$src.Dispose()
Write-Host '图标已生成:'
Get-ChildItem $res -Recurse -File -Filter *.png | ForEach-Object { '  ' + $_.FullName.Replace($res + '\', '') + '  ' + $_.Length + 'B' }

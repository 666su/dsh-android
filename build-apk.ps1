<#
  DSH 移动端 - 构建 APK（不用 Gradle，直接调 aapt2 / javac / d8 / apksigner）

  用法:
    powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
    powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Url "http://192.168.1.10:3080/"

  前置：先跑过 toolchain 安装（见 README），或本机已装 Android SDK + JDK 17。
#>
param(
  [string]$Url = ''
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$tc   = Join-Path $root 'toolchain'
$sdk  = Join-Path $tc 'android-sdk'
$java = Join-Path $root 'src'
$res  = Join-Path $root 'res'
$mf   = Join-Path $root 'AndroidManifest.xml'
$out  = Join-Path $root 'out'

if (-not (Test-Path $sdk)) { throw '找不到 Android SDK，请先完成 toolchain 安装' }
$jdk = (Get-ChildItem $tc -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -First 1)
if (-not $jdk) { throw '找不到 JDK 17' }
$jdkHome = $jdk.FullName

$env:JAVA_HOME = $jdkHome
$env:ANDROID_HOME = $sdk

$bt = Join-Path $sdk 'build-tools\35.0.0'
$androidJar = Join-Path $sdk 'platforms\android-35\android.jar'
foreach ($f in @($androidJar, (Join-Path $bt 'aapt2.exe'), (Join-Path $bt 'd8.bat'), (Join-Path $bt 'zipalign.exe'), (Join-Path $bt 'apksigner.bat'))) {
  if (-not (Test-Path $f)) { throw ('缺少: ' + $f) }
}

$stage = Join-Path $root 'build'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage, (Join-Path $stage 'gen'), (Join-Path $stage 'classes'), (Join-Path $stage 'dex'), $out | Out-Null

function Run-Native([string]$exe, [string[]]$cargs) {
  $eap = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  & $exe @cargs 2>&1 | ForEach-Object { Write-Host ('    ' + $_.ToString()) }
  $code = $LASTEXITCODE
  $ErrorActionPreference = $eap
  if ($code -ne 0) { throw ($exe + ' 失败，exit=' + $code) }
}

# 可选：覆盖内置默认地址
if ($Url -ne '') {
  Write-Host ('[0/6] 覆盖默认地址为 ' + $Url)
  $srcMain = Join-Path $java 'com\dshmobile\app\MainActivity.java'
  $txt = [System.IO.File]::ReadAllText($srcMain, [System.Text.Encoding]::UTF8)
  $txt = [regex]::Replace($txt, 'DEFAULT_URL = "[^"]*"', 'DEFAULT_URL = "' + $Url + '"')
  [System.IO.File]::WriteAllText($srcMain, $txt, (New-Object System.Text.UTF8Encoding($false)))
}

Write-Host '[1/6] aapt2 compile'
Run-Native (Join-Path $bt 'aapt2.exe') @('compile', '--dir', $res, '-o', (Join-Path $stage 'res.zip'))

Write-Host '[2/6] aapt2 link'
Run-Native (Join-Path $bt 'aapt2.exe') @(
  'link', '-o', (Join-Path $stage 'base.apk'),
  '-I', $androidJar,
  '--manifest', $mf,
  '-R', (Join-Path $stage 'res.zip'),
  '--java', (Join-Path $stage 'gen'),
  '--min-sdk-version', '24',
  '--target-sdk-version', '35',
  '--auto-add-overlay'
)

Write-Host '[3/6] javac'
$srcs = @()
$srcs += (Get-ChildItem (Join-Path $stage 'gen') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem $java -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Run-Native (Join-Path $jdkHome 'bin\javac.exe') (@('-source', '8', '-target', '8', '-encoding', 'UTF-8',
  '-bootclasspath', $androidJar, '-classpath', $androidJar,
  '-d', (Join-Path $stage 'classes')) + $srcs)

Write-Host '[4/6] d8 -> classes.dex'
Run-Native (Join-Path $jdkHome 'bin\jar.exe') @('cf', (Join-Path $stage 'classes.jar'), '-C', (Join-Path $stage 'classes'), '.')
Run-Native (Join-Path $bt 'd8.bat') @('--lib', $androidJar, '--min-api', '24',
  '--output', (Join-Path $stage 'dex'), (Join-Path $stage 'classes.jar'))

# 把 classes.dex 塞进 APK
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = Join-Path $stage 'unsigned.apk'
Copy-Item (Join-Path $stage 'base.apk') $apk -Force
$zip = [System.IO.Compression.ZipFile]::Open($apk, 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $stage 'dex\classes.dex'), 'classes.dex') | Out-Null
$zip.Dispose()

Write-Host '[5/6] zipalign'
$aligned = Join-Path $stage 'aligned.apk'
Run-Native (Join-Path $bt 'zipalign.exe') @('-f', '4', $apk, $aligned)

Write-Host '[6/6] 签名'
$ks = Join-Path $root 'dsh-release.keystore'
if (-not (Test-Path $ks)) {
  Write-Host '    生成签名密钥（首次）'
  Run-Native (Join-Path $jdkHome 'bin\keytool.exe') @(
    '-genkeypair', '-keystore', $ks, '-alias', 'dsh',
    '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
    '-storepass', 'dshmobile', '-keypass', 'dshmobile',
    '-dname', 'CN=DSH Mobile, OU=Personal, O=Personal, C=CN'
  )
}
$final = Join-Path $out 'DSH-mobile.apk'
Run-Native (Join-Path $bt 'apksigner.bat') @('sign',
  '--ks', $ks, '--ks-pass', 'pass:dshmobile', '--key-pass', 'pass:dshmobile',
  '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
  '--out', $final, $aligned)
Run-Native (Join-Path $bt 'apksigner.bat') @('verify', '--print-certs', $final)

Write-Host ''
Write-Host ('APK: ' + $final + '  (' + [Math]::Round((Get-Item $final).Length/1KB,1) + ' KB)')

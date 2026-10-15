# Builds build\PicoSchoolAgent.apk without Gradle, using only the Android SDK build-tools and a JDK 17.
# Both are taken from ANDROID_HOME / JAVA_HOME, or from the newest Unity editor with Android support.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location $root

function Find-UnityAndroid {
    $editors = Get-ChildItem 'C:\Program Files\Unity\Hub\Editor' -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending
    foreach ($e in $editors) {
        $p = Join-Path $e.FullName 'Editor\Data\PlaybackEngines\AndroidPlayer'
        if (Test-Path "$p\SDK\build-tools") { return $p }
    }
    return $null
}

$unity = Find-UnityAndroid
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($unity) { "$unity\SDK" } else { throw 'Android SDK not found: set ANDROID_HOME' }
$jdk = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { $env:JAVA_HOME } elseif ($unity) { "$unity\OpenJDK" } else { throw 'JDK not found: set JAVA_HOME' }
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$buildTools = (Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '-.*', '') } -Descending)[0].FullName
$platform = (Get-ChildItem "$sdk\platforms" -Directory | Where-Object { $_.Name -match '^android-\d+$' } |
    Sort-Object { [int]($_.Name -replace 'android-', '') } -Descending)[0].FullName
$androidJar = "$platform\android.jar"
Write-Host "SDK:         $sdk"
Write-Host "Build tools: $buildTools"
Write-Host "Platform:    $platform"
Write-Host "JDK:         $jdk"

function Run($exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($exe)) failed with exit code $LASTEXITCODE" }
}

$out = "$root\build"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory "$out\classes", "$out\dex" | Out-Null

# 1. Manifest -> base APK (no resources: labels and icons come from the framework).
Run "$buildTools\aapt2.exe" @('link', '--manifest', "$root\AndroidManifest.xml", '-I', $androidJar,
    '--min-sdk-version', '29', '--target-sdk-version', '32',
    '--version-code', '5', '--version-name', '1.4', '-o', "$out\unsigned.apk")

# 2. Java -> classes -> dex.
$sources = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object FullName
Run "$jdk\bin\javac.exe" (@('-source', '1.8', '-target', '1.8', '-Xlint:-options', '-encoding', 'UTF-8',
    '-bootclasspath', "$androidJar;$buildTools\core-lambda-stubs.jar", '-d', "$out\classes") + $sources)
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
Run "$buildTools\d8.bat" (@('--release', '--min-api', '29', '--lib', $androidJar, '--output', "$out\dex") + $classes)

# 3. Add classes.dex, align, sign.
Push-Location "$out\dex"
try { Run "$buildTools\aapt.exe" @('add', "$out\unsigned.apk", 'classes.dex') | Out-Null } finally { Pop-Location }
Run "$buildTools\zipalign.exe" @('-f', '-p', '4', "$out\unsigned.apk", "$out\aligned.apk")

# The same key must sign every build, otherwise headsets refuse the update. Back up agent.keystore.
$keystore = "$root\agent.keystore"
if (-not (Test-Path $keystore)) {
    Write-Host 'Creating signing key agent.keystore (keep it, it is needed for updates)'
    Run "$jdk\bin\keytool.exe" @('-genkeypair', '-keystore', $keystore, '-storepass', 'picoagent', '-keypass', 'picoagent',
        '-alias', 'agent', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=PicoSchoolAgent')
}
Run "$buildTools\apksigner.bat" @('sign', '--ks', $keystore, '--ks-pass', 'pass:picoagent', '--ks-key-alias', 'agent',
    '--out', "$out\PicoSchoolAgent.apk", "$out\aligned.apk")

Write-Host ''
Write-Host "Built $out\PicoSchoolAgent.apk" -ForegroundColor Green

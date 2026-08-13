$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$signingProperties = Join-Path $root "signing-private\signing.properties"
$distDir = Join-Path $root "dist"
$sourceApk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
$outputApk = Join-Path $distDir "QQRecallGuard-v1.3.0-QQ-9.2.60-arm64.apk"

if (-not (Test-Path -LiteralPath $signingProperties)) {
    throw "Missing signing-private\signing.properties. Use a private release key before publishing."
}

New-Item -ItemType Directory -Force $distDir | Out-Null
Push-Location $root
try {
    .\gradlew.bat :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle release build failed: $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $sourceApk)) { throw "Signed release APK was not generated." }
    Copy-Item -Force -LiteralPath $sourceApk -Destination $outputApk

    $apksigner = Join-Path $env:ANDROID_HOME "build-tools\37.0.0\apksigner.bat"
    if (Test-Path -LiteralPath $apksigner) {
        & $apksigner verify --verbose --print-certs $outputApk
        if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $LASTEXITCODE" }
    }
    Get-FileHash -Algorithm SHA256 -LiteralPath $outputApk
} finally {
    Pop-Location
}

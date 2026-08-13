$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$privateDir = Join-Path $root "signing-private"
$distDir = Join-Path $root "dist"
$keyStore = Join-Path $privateDir "qq-recall-guard-local.jks"
$apkName = "QQRecallGuard-v1.3.0-QQ-9.2.60-arm64-modern-performance-local-signed.apk"
$outputApk = Join-Path $distDir $apkName
if ([string]::IsNullOrWhiteSpace($env:QQ_RECALL_GUARD_KEYSTORE_PASSWORD)) {
    throw "Set QQ_RECALL_GUARD_KEYSTORE_PASSWORD before building a locally signed APK."
}

New-Item -ItemType Directory -Force $privateDir, $distDir | Out-Null
Push-Location $root
try {
    .\gradlew.bat :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle release build failed: $LASTEXITCODE" }
    if (-not (Test-Path -LiteralPath $keyStore)) {
        keytool -genkeypair -noprompt -keystore $keyStore `
            -storepass:env QQ_RECALL_GUARD_KEYSTORE_PASSWORD `
            -keypass:env QQ_RECALL_GUARD_KEYSTORE_PASSWORD `
            -alias qqrecallguard -keyalg RSA -keysize 3072 -validity 10000 `
            -dname "CN=QQ Recall Guard Local, OU=Local Build, O=RecallGuard, C=CN"
        if ($LASTEXITCODE -ne 0) { throw "keytool failed: $LASTEXITCODE" }
    }
    Copy-Item -Force "app\build\outputs\apk\release\app-release-unsigned.apk" $outputApk
    & "$env:ANDROID_HOME\build-tools\37.0.0\apksigner.bat" sign `
        --ks $keyStore --ks-key-alias qqrecallguard `
        --ks-pass "env:QQ_RECALL_GUARD_KEYSTORE_PASSWORD" `
        --key-pass "env:QQ_RECALL_GUARD_KEYSTORE_PASSWORD" $outputApk
    if ($LASTEXITCODE -ne 0) { throw "APK signing failed: $LASTEXITCODE" }
    & "$env:ANDROID_HOME\build-tools\37.0.0\apksigner.bat" verify --verbose --print-certs $outputApk
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $LASTEXITCODE" }
    Get-FileHash -Algorithm SHA256 $outputApk
} finally {
    Pop-Location
}

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "build\parser-self-test"
New-Item -ItemType Directory -Force $out | Out-Null
$src = Join-Path $root "app\src\main\java\dev\recallguard\qq"
javac -encoding UTF-8 -d $out `
    (Join-Path $src "ProtoReader.java") `
    (Join-Path $src "RecallEvent.java") `
    (Join-Path $src "RecallParser.java") `
    (Join-Path $PSScriptRoot "ParserSelfTest.java")
java -cp $out dev.recallguard.qq.ParserSelfTest

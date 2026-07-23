param(
    [string]$AudioFile
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$localRoot = Join-Path $repoRoot "local-testing"
$audioRoot = Join-Path $localRoot "audio"

if (-not (Test-Path -LiteralPath (Join-Path $localRoot "secrets.properties"))) {
    throw "Missing local-testing\secrets.properties. Add DEEPGRAM_API_KEY=your_key."
}

if ($AudioFile) {
    $source = (Resolve-Path -LiteralPath $AudioFile).Path
    $destination = Join-Path $audioRoot ([System.IO.Path]::GetFileName($source))
    New-Item -ItemType Directory -Path $audioRoot -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Force
}

$supported = @(
    Get-ChildItem -LiteralPath $localRoot -File -ErrorAction SilentlyContinue
    Get-ChildItem -LiteralPath $audioRoot -File -ErrorAction SilentlyContinue
) |
    Where-Object { $_.Extension.ToLowerInvariant() -in @(".mp3", ".m4a", ".wav", ".aac", ".flac", ".ogg") }
if (-not $supported) {
    throw "Put a sample audio file in local-testing or local-testing\audio first."
}

$env:DEBRIEF_RUN_LOCAL_AUDIO_TEST = "1"
Push-Location $repoRoot
try {
    & .\gradlew.bat testDebugUnitTest --tests "com.andyluu.debrief.transcription.LocalDeepgramAudioTest"
    if ($LASTEXITCODE -ne 0) { throw "Local Deepgram audio test failed." }
}
finally {
    Pop-Location
    Remove-Item Env:\DEBRIEF_RUN_LOCAL_AUDIO_TEST -ErrorAction SilentlyContinue
}

Write-Host "Transcript written under local-testing\results."

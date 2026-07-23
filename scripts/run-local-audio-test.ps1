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
    $selectedAudio = Get-Item -LiteralPath $destination
}

$supported = @(
    Get-ChildItem -LiteralPath $localRoot -File -ErrorAction SilentlyContinue
    Get-ChildItem -LiteralPath $audioRoot -File -ErrorAction SilentlyContinue
) |
    Where-Object { $_.Extension.ToLowerInvariant() -in @(".mp3", ".m4a", ".wav", ".aac", ".flac", ".ogg") }
if (-not $supported) {
    throw "Put a sample audio file in local-testing or local-testing\audio first."
}
if (-not $selectedAudio) {
    $selectedAudio = $supported | Sort-Object FullName | Select-Object -First 1
}

$ffprobe = Get-Command ffprobe -ErrorAction SilentlyContinue
if ($ffprobe) {
    & $ffprobe.Source -v error -show_entries "format=duration,size,bit_rate:stream=codec_name,sample_rate,channels" `
        -of "default=noprint_wrappers=1" $selectedAudio.FullName
    if ($LASTEXITCODE -ne 0) { throw "The selected local audio fixture could not be decoded." }
}

$env:DEBRIEF_RUN_LOCAL_AUDIO_TEST = "1"
$env:DEBRIEF_LOCAL_AUDIO_FILE = $selectedAudio.FullName
Push-Location $repoRoot
try {
    & .\gradlew.bat testDebugUnitTest --tests "com.andyluu.debrief.transcription.LocalDeepgramAudioTest"
    if ($LASTEXITCODE -ne 0) { throw "Local Deepgram audio test failed." }
}
finally {
    Pop-Location
    Remove-Item Env:\DEBRIEF_RUN_LOCAL_AUDIO_TEST -ErrorAction SilentlyContinue
    Remove-Item Env:\DEBRIEF_LOCAL_AUDIO_FILE -ErrorAction SilentlyContinue
}

Write-Host "Transcript written under local-testing\results."

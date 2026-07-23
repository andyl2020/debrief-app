# Private local audio testing

Real recordings and API credentials belong only in the repository's ignored
`local-testing` directory. They must never be committed, attached to a GitHub
release, copied into Android resources, or printed in test logs.

## Local layout

```text
local-testing/
  audio/
    sample.m4a
  results/
  secrets.properties
```

Put private fixture audio directly in `local-testing` or in `local-testing/audio`.
Supported fixture formats are MP3, M4A, WAV, AAC, FLAC, and OGG. Put the local Deepgram credential in
`local-testing/secrets.properties`:

```properties
DEEPGRAM_API_KEY=paste_key_here
```

Run the opt-in real-provider test from PowerShell:

```powershell
.\scripts\run-local-audio-test.ps1
```

Or copy a fixture into the private directory and run it in one command:

```powershell
.\scripts\run-local-audio-test.ps1 -AudioFile "C:\path\to\sample.m4a"
```

The test exercises Debrief's real Deepgram request/parser, asserts that transcript
segments and chronological word timestamps are returned, and writes a readable
transcript to `local-testing/results`. Normal unit tests and GitHub Actions skip
this provider-billed test unless `DEBRIEF_RUN_LOCAL_AUDIO_TEST=1` is explicitly
set.

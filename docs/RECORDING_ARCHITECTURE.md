# Offline recording architecture

Status: implemented for Debrief v1.9.2
Updated: July 23, 2026

## Product behavior

The Library and Recorder are first-class bottom-navigation destinations. The
Recorder shows the linked folder, editable filename, live timer, microphone
level, large record control, pause/resume, trash/discard, and stop/save.
Recording does not call any network API. Filename edits change only the eventual
destination name; local durability parts keep stable session identifiers
throughout capture.

If a folder is already linked, Record starts after Android grants microphone
permission. If no folder is linked, the same durable Storage Access Framework
folder picker used by Library opens first. A completed recording is copied into
that folder and immediately discovered by the normal Library scan.

The foreground notification opens the Recorder and offers pause/resume. Stop
stays inside the app to reduce accidental termination of a long field session.
On Android 13+, a user swipe is delivered to an exported-false receiver and
checkpointed for the session. Notification updates stop, but capture and the
foreground service continue. A new recording resets notification visibility.

While recording or paused, tapping trash opens a destructive confirmation.
Pressing and holding the same control changes its icon, gives haptic feedback,
and immediately discards. Discard stops and releases `MediaRecorder`, removes
monitor callbacks and the wake lock, deletes every app-private part for that
session, clears the recovery checkpoint, and stops the foreground service. It
does not call the folder-export pipeline. Once Stop has advanced to finalization
or recovery, discard is rejected so a late interaction cannot race a destination
write.

## Capture engine

Debrief uses Android's native `MediaRecorder`, not a small third-party wrapper.
The platform implementation receives OEM codec, microphone routing, Bluetooth,
call-policy, and Android compatibility fixes. Capture runs in an exported-false
foreground service declared with the `microphone` service type.

Default format:

- MPEG-4 container (`.m4a`)
- AAC audio
- mono
- 48 kHz
- 128 kbps

This is approximately 58 MB per hour before container overhead. It preserves
far more speech detail than a low-bitrate voice memo while remaining practical
for multi-hour sessions.

## Long-session durability

Capture first targets app-specific external storage rather than writing for
hours through an arbitrary document provider. At about 90 percent of an 8 MiB
part limit, Android emits `MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING`.
Debrief then queues the next local file with `setNextOutputFile`. Android changes
files inside the active recorder and reports
`MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED`; Debrief does not stop/restart at
normal boundaries.

When Stop is tapped, readable parts are joined without re-encoding:

1. `MediaExtractor` reads AAC samples and timestamps.
2. `MediaMuxer` writes one continuous MPEG-4 audio track.
3. The result is copied and flushed to a new document in the linked folder.
4. The folder is rescanned.
5. Local parts are deleted only after the destination write succeeds.

If the encoder itself reports a fatal error, Debrief attempts a new local part
twice and keeps the elapsed session alive. Repeated failure finalizes and saves
everything captured so far.

## Interruptions

Manual pause/resume uses the platform recorder APIs, so paused wall-clock time
is not added to the audio timeline.

Debrief polls Android audio mode while active. `MODE_IN_CALL`,
`MODE_IN_COMMUNICATION`, and supported call-screening mode trigger an automatic
pause. Capture resumes after the mode returns to normal unless the user changed
the pause to a manual pause.

Android 10+ applies a system priority policy when two apps want the microphone.
A normal app cannot override a call or a higher-priority/privacy-sensitive
capture. Debrief registers an audio-recording callback and displays a warning
when Android reports that its client is being supplied silence. The recorder
stays alive and Android restores microphone data when the competing capture
ends.

## Power and storage

The foreground service and ongoing notification make the recording visible to
Android. A non-reference-counted partial wake lock keeps capture and monitoring
alive with the screen off, capped at 12 hours as a leak safeguard.

Recording will not begin with less than 256 MiB free in app-local storage. It
automatically pauses below 32 MiB and resumes at 64 MiB, preventing an encoder
failure from consuming the last bytes on the device.

## Recovery contract

Session identity, destination, state, name, timing, pause reason, and notification
dismissal are synchronously checkpointed outside the audio files. If Android recreates the
service or Debrief opens with an unfinished session, the app discovers finalized
parts, losslessly joins them, and retries the folder save.

A destination failure changes the Recorder to **Save needs attention**. The
user can retry the current linked folder or choose another folder. The local
copy remains until a verified save succeeds.

No Android app can promise survival through force-stop, uninstall, storage
hardware failure, permission revocation, or power loss. An abruptly terminated
MPEG-4 part may lack its final container metadata. Previously finalized parts
remain recoverable; the currently open part (up to roughly nine minutes) may
not be. Uninstalling Debrief removes app-specific recovery files.

## Tests

- Pure unit tests cover session timing, pause behavior, filename normalization,
  extension preservation, and stable part naming.
- Compose device tests cover idle, active, call-paused, save-failure, trash
  visibility, tap confirmation, and direct press-and-hold discard UX.
- An Android device test runs the real foreground microphone service, records,
  pauses, resumes, stops, forces an invalid folder export, verifies a playable
  recovery part, duplicates it, joins both parts losslessly, and verifies the
  joined timeline grows.
- A separate real-service path starts microphone capture, verifies a private
  session part exists, discards, and verifies the session returns to idle with
  every local part removed.
- Android 15 UI automation physically swipes away the active recording
  notification, then verifies capture and pause/resume continue without the
  notification returning.
- Release tests retain the full app unit/lint/instrumentation/signature/16 KB
  alignment checks.
- Private provider/audio fixtures live only under ignored `local-testing`; see
  `LOCAL_AUDIO_TESTING.md`.

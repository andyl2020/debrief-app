# Future iOS and shared-contract strategy

Last updated: 2026-07-10

## Decision

Debrief stays Android-first. Primary product development continues in the native Android app.

If an iOS release becomes important later, the preferred path is not to rewrite the current Android app into a universal cross-platform app. The preferred path is:

1. Keep the Android app native and current.
2. Maintain a shared product/technical contract that describes behavior, schemas, prompts, fixtures, and acceptance criteria.
3. When iOS work starts, update the shared contract from the latest Android behavior.
4. Build a separate native SwiftUI iOS app against that contract.
5. Share pure business logic only where it clearly pays off, most likely through a Kotlin Multiplatform shared-core module.

The strategic split is: native platform apps for platform-specific UX, audio, files, background work, and distribution; shared contracts and possibly shared Kotlin logic for transcript and AI behavior.

## Why this decision

Debrief is not a simple CRUD app. The hard parts are platform-sensitive:

- local audio file access
- long-running transcription uploads
- background work and resumability
- local encrypted storage
- synced playback
- clip extraction
- provider API handling
- transcript search
- Android/iOS permissions
- sideload/TestFlight/App Store distribution

A full cross-platform rewrite would not eliminate those platform differences. It would mostly move them behind bridges while slowing current Android development.

The current Android app already works and ships. The better use of effort is to keep improving Android while documenting the contract needed to produce an iOS version later.

## Architecture target

Future repo layout can evolve toward:

```text
debrief-app/
  app/                 current native Android app
  shared-contract/     specs, schemas, fixtures, prompts, acceptance tests
  shared-core/         optional Kotlin Multiplatform logic
  ios/                 future native SwiftUI app
  docs/future/         planning notes like this one
  release/             signed Android release outputs
```

The repo does not need to be restructured immediately. This layout is the target if/when iOS work becomes active.

## What should be shared

High-value shared contract or shared-core candidates:

- transcript data model
- recording metadata model
- provider request/response DTOs
- Deepgram/AssemblyAI normalization rules
- word confidence and suspect-span detection
- AI Enhance repair-run and repair diff schemas
- repair validation rules
- Gemini prompt schemas and JSON response schemas
- cleaned-view derivation rules
- comments/chapters export schema
- Markdown export format
- search behavior expectations
- test fixtures using real or anonymized provider responses
- acceptance criteria for Android/iOS parity

These are the parts where duplication is risky and feature behavior should remain consistent across platforms.

## What should remain platform-specific

Do not force-share these unless there is a strong reason:

- Android UI and iOS UI
- Android Storage Access Framework vs iOS Document Picker/security-scoped files
- WorkManager vs iOS background URLSession/BGTaskScheduler
- Media3/ExoPlayer vs AVFoundation/AVPlayer
- Android notifications vs iOS notifications
- Room/SQLCipher Android implementation vs iOS storage implementation
- app signing, release, TestFlight, App Store, and sideloading flows
- platform-specific audio clipping implementation

These areas are where native implementation is usually cheaper and more reliable than trying to abstract too early.

## Options considered

### Option 1: Native Android now, native iOS later, shared contract only

Summary: keep Android as-is. Add durable specs, schemas, fixtures, and acceptance tests. Build iOS later from that contract.

Pros:

- fastest path for Android development
- lowest short-term risk
- no rewrite
- easiest to keep shipping Android releases
- iOS can be built cleanly later from documented behavior

Cons:

- some logic will be duplicated in Swift later
- Android bug fixes may need manual iOS parity updates
- parity depends on maintaining the contract

Best when: iOS is a future option, not an immediate release target.

### Option 2: Kotlin Multiplatform shared core, native UI per platform

Summary: extract pure Kotlin logic from Android into a `shared-core` module. Android uses it directly. Future iOS imports it as a framework while still using native SwiftUI.

Pros:

- reuses Kotlin investment from the current Android app
- shares high-risk logic without forcing a shared UI
- reduces duplicated bugs in transcript, provider, and AI Enhance behavior
- lets Android keep moving while gradually improving portability
- good fit for Debrief because much of the value is transcript/AI logic, not just UI

Cons:

- adds build complexity
- Swift/Kotlin interop can be awkward
- Android-only libraries do not automatically become portable
- iOS still needs native file, audio, background, storage, and distribution work
- requires deliberate architecture boundaries

Best when: iOS becomes likely, but Android should remain the primary development surface.

### Option 3: Full cross-platform rewrite

Summary: rebuild Debrief in Flutter, React Native, or Compose Multiplatform with one shared UI/codebase.

Pros:

- maximum shared code
- one UI implementation
- easier to make Android and iOS visually similar
- good if starting from zero

Cons:

- high rewrite cost
- pauses or slows Android momentum
- audio, files, background work, and app distribution still need platform-specific native code
- increases risk of regressions in features that already work
- debugging becomes harder when issues cross framework/native boundaries

Best when: Android and iOS must launch together and a rewrite budget is acceptable.

This is not the recommended path for Debrief right now.

## Recommended future workflow

When Andy asks to start iOS work, do this first:

1. Review this note.
2. Review the latest `README.md`, `RELEASE_NOTES.md`, `IMPLEMENTATION_STATUS.md`, and current Android behavior.
3. Create or update `shared-contract/`.
4. Capture the latest Android behavior as versioned specs:
   - recording import and library states
   - transcription provider behavior
   - transcript schema
   - word timestamps/confidence behavior
   - comments and chapters
   - search rules
   - AI Enhance rules
   - repair validation
   - export format
   - sidecar/backup behavior
5. Add fixtures:
   - sample Deepgram response
   - sample AssemblyAI response if still supported
   - sample transcript with low-confidence words
   - sample repair run
   - sample comments/chapters
   - expected Markdown export
6. Decide whether to add Kotlin Multiplatform `shared-core`.
7. Only then scaffold a native SwiftUI iOS app.

The first iOS milestone should be a small TestFlight MVP:

1. API key storage in Keychain.
2. Import/select an audio file.
3. Upload to Deepgram using the same provider contract as Android.
4. Parse and store transcript.
5. Synced playback with transcript tap-to-seek.
6. Transcript-only in-player search.
7. Timestamped comments.
8. Markdown export.

After that, add:

1. Chapters.
2. AI Enhance text repair.
3. Optional short-clip audio re-listen.
4. Sidecar/backup parity.

## Rule for future Android development

Android remains the source of product truth until iOS exists.

When Android features are added, update the shared contract only when one of these is true:

- the feature changes transcript/provider/AI/export behavior
- the feature should exist on future iOS
- the feature changes data schemas
- the feature changes acceptance criteria
- the feature changes release notes in a way future iOS users would need to understand

Do not block Android feature work on iOS architecture unless the change would make future iOS materially harder.

## Future trigger phrase

If Andy says something like:

> "Pull up the iOS/shared-contract plan and start preparing Debrief for iOS."

Then the implementation agent should:

1. open this file first
2. create/update `shared-contract/`
3. inventory current Android behavior
4. propose whether KMP shared-core is worth adding at that time
5. avoid starting with a full cross-platform rewrite unless explicitly approved

## Current conclusion

The durable strategy is:

> Separate native Android and iOS apps, with Android as the primary development surface, backed by a shared contract and optional Kotlin Multiplatform core for logic that should not be duplicated.

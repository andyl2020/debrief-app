# Release process

Debrief releases are automated through GitHub Actions.

## Repo secrets

The repository has these Actions secrets configured:

- `DEBRIEF_KEYSTORE_BASE64`
- `DEBRIEF_KEYSTORE_PASSWORD`
- `DEBRIEF_KEY_ALIAS`

Do not print or commit the secret values. The workflow restores the keystore from the base64 secret at build time and signs the release APK with the same certificate used for previous public releases.

## Normal release flow

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Update `RELEASE_NOTES.md`.
3. Run local verification as appropriate:
   - `testDebugUnitTest`
   - `lintDebug assembleDebug`
   - `connectedDebugAndroidTest` when an emulator/device is available
4. Commit and push `main`.
5. Create and push a version tag:

   ```powershell
   git tag -a v1.7.2 -m "Debrief v1.7.2"
   git push origin v1.7.2
   ```

6. GitHub Actions runs `.github/workflows/release.yml`, which:
   - checks out the tag,
   - restores the signing keystore from repo secrets,
   - runs unit tests, release lint, and release assembly,
   - verifies 16 KB native alignment,
   - creates or updates the GitHub Release,
   - uploads `Debrief-vX.Y.Z.apk`,
   - includes APK size and SHA-256 in the release notes.

## Manual rerun

If a tag already exists and the release needs to be rebuilt/reuploaded, run the **Release APK** workflow manually from GitHub Actions and provide the existing tag, for example `v1.7.2`.

## Why this exists

This avoids future releases depending on a local GitHub token, Chrome session, or phone device-code approval. Codex only needs normal Git push access to push the release tag; GitHub Actions handles signing and release asset upload with repository-scoped secrets.

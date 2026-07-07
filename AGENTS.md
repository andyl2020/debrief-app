# Repository instructions

## Release documentation is mandatory

For every user-requested build or GitHub release:

1. Update `RELEASE_NOTES.md` before tagging the release.
2. The GitHub release description must contain:
   - what changed in this release;
   - concise instructions for using each new or changed feature;
   - a current feature inventory so downloaders know what the APK includes;
   - limitations, gotchas, destructive/replacement behavior, and relevant edge cases;
   - install/upgrade and device compatibility notes;
   - tests performed, APK size, and SHA-256.
3. Keep historical release entries in `RELEASE_NOTES.md`; do not replace the file with only the latest release.
4. Record incomplete verification honestly. Do not describe a build as released until its public APK has been downloaded without authentication and matched to the verified local artifact.
5. Update `IMPLEMENTATION_STATUS.md`, commit and push source checkpoints, use an annotated version tag, and publish the signed APK from that tag.

This policy is part of the product requirements and must not be omitted from future releases.

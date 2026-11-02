# Releasing the lab

Release tags belong to this companion lab. They do not publish the experimental
library to Maven Central or imply upstream acceptance of gradual recovery.

## First release

The first planned tag is `v0.1.0`. Its [release notes](releases/v0.1.0.md) are prepared
for review; this document does not mean that the release has been published.

1. Merge the reviewed pull request and wait for `Lab validation` to pass on `main`.
2. Check out the intended `main` commit in a clean working tree.
3. Review the release notes, pinned library revision and runtime versions.
4. Create and push the version tag:

   ```sh
   git tag -a v0.1.0 -m "Resilience4j lab v0.1.0"
   git push origin v0.1.0
   ```

The tag starts the same offline, application and live-stack checks as a pull request.
Only after they pass does the release job check that the tagged commit belongs to
`main` and create the GitHub release. The downloadable dashboard is generated from
that tag's checked-in sources. Build output, local caches, traffic data and credentials
are not release assets.

Release notes record the full source commit. A repeated run accepts an existing
release only when that commit and the downloadable dashboard match; it never
silently replaces published assets.

If validation fails, fix the issue through a new pull request. Do not move a published
release tag to different code; use a new version instead.

Future releases use the same `vMAJOR.MINOR.PATCH` format. Review meaningful behavior,
dependency and dashboard changes in their notes.

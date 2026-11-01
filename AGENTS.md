# Contributing to this lab

- Keep code, documentation, UI labels and examples in English.
- Keep the application, launcher and dashboards reproducible from public sources.
  Use synthetic data and generic provider examples.
- Do not add private URLs, local machine paths, organization-specific identifiers,
  internal domains, credentials or real transaction data to source, fixtures,
  generated files, Git metadata or pull requests.
- Use the repository's personal Git identity. Review generated artifacts and commit
  metadata before publication, and keep local backups outside this repository.
- Preserve upstream authorship, license headers and dashboard attribution in NOTICE.
- Keep standard Resilience4j behavior unchanged; enable gradual recovery explicitly.
- Treat this repository as a development lab. Keep its traffic generator and service
  controls behind the lab profile, and bind the local stack to loopback.
- Run focused application tests for behavior changes and offline Python checks for
  launcher/dashboard/verifier changes. Do not commit caches or generated output.

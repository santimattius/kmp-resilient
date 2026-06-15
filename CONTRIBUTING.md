# Contributing to Resilient

Thank you for your interest in contributing to **Resilient**, a Kotlin Multiplatform library for resilience patterns. This document explains how to get started and what we expect from contributions.

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold these standards.

## How to Contribute

1. **Check existing issues** — Search [open issues](https://github.com/santimattius/kmp-resilient/issues) before opening a new one.
2. **Open an issue** — Use the bug report or feature request templates to describe the problem or proposal.
3. **Fork and branch** — Create a feature branch from `main` (e.g. `feat/retry-jitter`, `fix/circuit-breaker-race`).
4. **Make your changes** — Keep the scope focused; follow existing code style and conventions.
5. **Add or update tests** — Changes to `shared` should include tests in `shared/src/commonTest`. Use `kotlinx-coroutines-test` with `runTest` and virtual time.
6. **Run checks locally** — Ensure the build passes before opening a PR.
7. **Open a pull request** — Fill in the PR template and link the related issue.

## Development Setup

### Requirements

- JDK 21+
- Android SDK (for the sample `androidApp` module)
- Xcode (optional, for iOS targets)

### Build and Test

```bash
# Run all shared module checks (same as CI)
./gradlew :shared:check

# Run tests only
./gradlew :shared:allTests

# Build the sample Android app
./gradlew :androidApp:assembleDebug
```

CI runs `./gradlew :shared:check` on every push and pull request to `main`.

## Project Structure

| Module | Purpose |
|--------|---------|
| `shared` | Core library — resilience policies, composition DSL, telemetry |
| `resilient-test` | Test utilities (`FaultInjector`, `PolicyBuilders`, `TestResilientScope`) |
| `androidApp` | Compose sample app demonstrating usage |
| `iosApp` | SwiftUI sample app (optional for library changes) |
| `docs/patterns/` | In-depth pattern documentation |

## Coding Guidelines

- **Kotlin style**: Official Kotlin style (`kotlin.code.style=official`).
- **Coroutines**: Follow structured concurrency — no `GlobalScope`, handle `CancellationException` correctly, use appropriate dispatchers. See the [Kotlin Coroutines documentation](https://kotlinlang.org/docs/coroutines-overview.html).
- **API design**: Prefer backward-compatible changes. Breaking API changes require a major version bump and a note in [CHANGELOG.md](CHANGELOG.md).
- **Documentation**: Update README or `docs/patterns/` when adding or changing public API behavior.
- **Changelog**: Add user-facing changes under `[Unreleased]` in [CHANGELOG.md](CHANGELOG.md) following [Keep a Changelog](https://keepachangelog.com/).

## Pull Request Expectations

- One logical change per PR when possible.
- Include tests for bug fixes and new behavior.
- Update documentation when the public API or usage patterns change.
- Ensure CI is green before requesting review.
- Be responsive to review feedback.

## Reporting Security Issues

Please do **not** open a public issue for security vulnerabilities. See [SECURITY.md](SECURITY.md) for responsible disclosure instructions.

## Questions

Open a [GitHub Discussion](https://github.com/santimattius/kmp-resilient/discussions) or an issue if you are unsure whether a change fits the project scope.

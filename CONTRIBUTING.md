# Contribution Guide

Thank you for your interest in contributing to **NovaMusic**! This document
outlines the guidelines and best practices for contributing to the project.
By following these recommendations, you help us maintain high-quality code
and a collaborative, inclusive community.

> [!NOTE]
> Before you begin, make sure to read and understand our
> [Code of Conduct](CODE_OF_CONDUCT.md).

## Table of Contents

* [How to Contribute](#how-to-contribute)
  * [Reporting Bugs](#reporting-bugs)
  * [Suggesting Features](#suggesting-features)
  * [Submitting Pull Requests](#submitting-pull-requests)
* [Style Guide](#style-guide)
  * [Code Style](#code-style)
  * [Commit Messages](#commit-messages)
  * [Documentation](#documentation)
* [Development Process](#development-process)
  * [Git Workflow](#git-workflow)
  * [Pull Request Lifecycle](#pull-request-lifecycle)
* [Development Environment Setup](#development-environment-setup)
* [Translation Contributions](#translation-contributions)

---

## How to Contribute

### Reporting Bugs

We track bugs using [GitHub Issues](https://github.com/CodeWithTayyab96/NovaMusic-/issues).
Before creating a new issue, please search existing issues to see if the
problem has already been reported. If you find a similar issue, feel free to
add relevant information or insights as a comment.

When creating a new issue, include:

* **Descriptive title** that summarizes the issue
* **Steps to reproduce** the problem
* **Expected behavior** vs. **actual behavior**
* **Environment details** (e.g., app version, device model, Android version)
* **Screenshots** if applicable
* **Relevant logs** if available

> [!TIP]
> Use the provided templates to ensure you include all the necessary details.

---

### Suggesting Features

We also handle feature suggestions through [GitHub Issues](https://github.com/CodeWithTayyab96/NovaMusic-/issues).
When suggesting a new feature:

* **Describe the problem** the feature is intended to solve
* **Explain the solution** and how it would work
* **Provide examples** or use cases if possible
* **Assess the scope** of the feature: is it minor, moderate, or large in
  terms of implementation effort?

> [!IMPORTANT]
> Please discuss the feature in an issue and obtain approval from the
> maintainers before starting development.

---

### Submitting Pull Requests

Pull Requests (PRs) are the primary way to contribute code to NovaMusic. To
ensure your PR is effective and easy to review, follow these steps:

1. **Fork the repository** and create a feature branch from the default
   branch (`main`).
2. **Implement your changes** while following the [style guide](#style-guide).
3. **Write or update tests** as needed to cover your changes.
4. **Ensure all tests pass** before submitting your PR.
5. **Update the documentation** if your changes affect the public API or
   usage instructions.
6. **Submit your pull request** with a **clear, concise, and informative
   description**.

Your PR description should include:

* **Purpose**: What problem does this PR solve? What feature or fix does it
  introduce?
* **Motivation**: Why is this change necessary or useful? How does it improve
  the project?
* **Implementation details**: Briefly explain how the changes were
  implemented.
* **Related issues**: Link to any related issues using `Closes #123`,
  `Fixes #456`, etc.

Example:

```
### Summary
This PR adds offline playback support for downloaded audio files.

### Motivation
This feature improves the user experience for those who want to listen to
music without an internet connection.

### Changes
- Added local media cache management
- Updated playback logic to prefer local files when available
- Modified UI to show offline availability

### Related Issues
Closes #45
```

> [!TIP]
> Well-written PRs that clearly explain their purpose and impact are more
> likely to be reviewed and merged quickly.

---

## Style Guide

### Code Style

* **Kotlin**: Follow the [official Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
* **XML**: Use 4 spaces for indentation
* **Variable and function names**: Use `camelCase` (e.g., `playbackController`)
* **Class names**: Use `PascalCase` (e.g., `AudioPlayer`)
* **Constants**: Use `UPPER_SNAKE_CASE` (e.g., `MAX_VOLUME_LEVEL`)

---

### Commit Messages

We use [Conventional Commits](https://www.conventionalcommits.org/) to keep
commit history clean and meaningful:

```
<type>(<optional scope>): <short description>

[optional body]

[optional footer]
```

**Common types**:

* `feat`: A new feature
* `fix`: A bug fix
* `docs`: Documentation-only changes
* `style`: Changes that do not affect functionality (formatting, whitespace, etc.)
* `refactor`: Code changes that neither fix a bug nor add a feature
* `perf`: Code changes that improve performance
* `test`: Adding or fixing tests
* `chore`: Changes to build scripts or auxiliary tools

**Examples**:

```
feat(player): add support for offline playback
fix(ui): resolve layout bug in song list
docs(readme): update installation instructions
```

---

### Documentation

* Write documentation in **Markdown**
* Document all public classes and functions
* Include **usage examples** whenever possible
* Keep the documentation **up to date** with code changes

---

## Development Process

### Git Workflow

We use a structured branching model:

* `main`: The default and production-ready branch (CI workflows listen to it)
* `feature/xyz`: New feature branches
* `fix/xyz`: Bug fix branches
* `release/xyz`: Release preparation branches

### Pull Request Lifecycle

1. **Creation**: A contributor opens a PR from a feature/fix branch
2. **Review**: Maintainers review the code and provide feedback
3. **CI Validation**: Automated tests are run
4. **Discussion**: Issues are resolved and necessary changes are made
5. **Approval**: Once approved, the PR is ready for merging
6. **Merge**: The PR is merged into `main`

---

## Development Environment Setup

To set up your local environment for contributing:

1. **Install JDK 21** — the project requires it (`kotlin.jvmToolchain(21)` +
   `compileOptions` `JavaVersion.VERSION_21`). The foojay toolchain
   auto-provisioning plugin is disabled in `settings.gradle.kts`, so your
   installed JDK must be 21.
2. **Install Android Studio** (current stable release, 2024.2+ recommended)
3. **Configure Android SDK** (platform **API 36**: compileSdk 36, targetSdk
   36, minSdk 26)
4. **Clone the repository**:

   ```bash
   git clone https://github.com/CodeWithTayyab96/NovaMusic-.git
   cd NovaMusic-
   ```
5. **Build the project using the Gradle wrapper** (no Gradle installation
   needed — the wrapper pins Gradle 9.4.1):

   ```bash
   ./gradlew build
   ```

   The app defines ABI product flavors (`universal`, `arm64`, `armeabi`,
   `x86`, `x86_64`), so a plain `assembleDebug` task does not exist. For a
   quick debug APK use `./gradlew assembleUniversalDebug` — the APK lands in
   `app/build/outputs/apk/universal/debug/`.

> **Note for low-memory machines**: a full `./gradlew build` compiles all ten
> variant combos and can exhaust the heap — use
> `./gradlew build --no-parallel --max-workers=2` (this is what CI does).

### Continuous Integration

CI runs on GitHub Actions (`.github/workflows/`):

- **`build.yml`** — on push to `main` and manual dispatch: builds the
  universal debug APK, then runs the full `build` with unit tests. The
  signing keystore is decoded from repository secrets for the full-build job.
- **`release-build.yml`** — manual release pipeline: builds a signed release
  APK and publishes it as a GitHub Release asset. Requires the
  `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`
  repository secrets.

### Releasing

Releases are cut from the **Actions** tab using `release-build.yml`. The
`version` input becomes both the git tag and release title. Before releasing:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` so they
   match the tag you're about to create (the in-app updater compares the
   release tag against the app's `versionName`).
2. Dispatch `release-build.yml` with the matching tag (e.g. `v1.0.1`).

---

## Translation Contributions

To help with translations:

1. Edit or add strings in `app/src/main/res/values-*` (or submit new locale
   directories)
2. Submit a pull request with your translations

Translations are reviewed by the maintainers before merging.

---

Thank you for contributing to **NovaMusic**! Your efforts help us build a
better experience for all users.

If you have any questions, feel free to open an issue labeled `question` on
the [issue tracker](https://github.com/CodeWithTayyab96/NovaMusic-/issues).

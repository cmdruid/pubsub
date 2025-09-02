# PubSub Android Scripts Guide

This directory contains utility scripts for building, testing, and managing the PubSub Android application. Each script is designed to be simple, focused, and easy to use for both developers and CI/CD systems.

## Quick Reference

| Script | Purpose | Usage |
|--------|---------|-------|
| `version.sh` | Manage app version | `./script/version.sh 1.0.0` |
| `build.sh` | Build debug APK | `./script/build.sh` |
| `install.sh` | Build and install on device | `./script/install.sh` |
| `test.sh` | Run all tests | `./script/test.sh` |
| `dev.sh` | Start emulator for development | `./script/dev.sh` |
| `release.sh` | Create production release | `./script/release.sh` |
| `keystore.sh` | Generate signing keystore | `./script/keystore.sh` |

---

## Scripts Overview

### 🔧 `version.sh` - Version Management
**Purpose**: Set and manage the app version independently of releases.

**Key Features**:
- Show current version from `build.gradle.kts`
- Set specific version (e.g., `1.2.0`)
- Bump version automatically (`--bump-patch`, `--bump-minor`, `--bump-major`)
- Validates semantic versioning format
- Creates backup before changes

**Common Usage**:
```bash
./script/version.sh                    # Show current version
./script/version.sh 1.2.0             # Set version to 1.2.0
./script/version.sh --bump-patch       # Increment patch (0.9.5 → 0.9.6)
```

**When to use**: Set version ahead of development, before starting work on a new feature.

---

### 🏗️ `build.sh` - Debug Build
**Purpose**: Build debug APK for development and testing.

**Key Features**:
- Builds debug version with `-debug` suffix
- Places APK in `dist/` folder for easy access
- Shows installation instructions
- Optional clean build (`--clean`)
- Displays APK size and location

**Common Usage**:
```bash
./script/build.sh                      # Standard debug build
./script/build.sh --clean              # Clean build from scratch
```

**Output**: `dist/pubsub-{version}-debug.apk`

**When to use**: When you need a debug APK for manual testing or distribution to testers.

---

### 📱 `install.sh` - Build and Install
**Purpose**: One-command build and install on connected device.

**Key Features**:
- Builds debug APK
- Automatically detects connected devices
- Installs APK with `-r` flag (replace if exists)
- Shows device connection status
- Provides launch instructions

**Common Usage**:
```bash
./script/install.sh                    # Build and install on device
```

**Prerequisites**: Device connected with USB debugging enabled, or emulator running.

**When to use**: During active development for quick install-and-test cycles.

---

### 🧪 `test.sh` - Test Runner
**Purpose**: Comprehensive test execution with reporting.

**Key Features**:
- Runs unit tests, instrumented tests, and lint checks
- Selective test execution (`--unit`, `--instrumented`, `--lint`)
- HTML report generation (`--report`)
- Device detection for instrumented tests
- Colored output and detailed summaries

**Common Usage**:
```bash
./script/test.sh                       # Run all tests
./script/test.sh --unit                # Unit tests only
./script/test.sh --clean --report      # Clean build with reports
```

**When to use**: Before commits, during CI/CD, or when validating changes.

---

### 🚀 `dev.sh` - Development Environment
**Purpose**: Start and manage Android emulator for development.

**Key Features**:
- Automatically starts first available AVD
- Port forwarding for local services (`-p 3000,8080`)
- Graceful shutdown with cleanup
- Force kill all emulators (`-k`)
- Optimized emulator settings for development

**Common Usage**:
```bash
./script/dev.sh                        # Start emulator
./script/dev.sh -p 3000                # Start with port forwarding
./script/dev.sh -k                     # Kill all emulators
```

**Interactive**: Press 'q' to quit and cleanup emulator.

**When to use**: When you need a clean emulator environment for development or testing.

---

### 🎯 `release.sh` - Production Release
**Purpose**: Create production builds and manage git tags for releases.

**Key Features**:
- Reads version from `build.gradle.kts` (no version arguments accepted)
- Builds signed AAB and APK for production
- Creates and pushes git tags
- GitHub Actions integration
- Force tag recreation for failed releases

**Common Usage**:
```bash
./script/release.sh                    # Full release process
./script/release.sh --skip-build       # Only create git tag
./script/release.sh --github-only      # Tag only, let GitHub build
```

**Prerequisites**: 
- Version set via `./script/version.sh`
- Keystore configured (run `./script/keystore.sh` first)

**When to use**: When ready to create an official release build.

---

### 🔐 `keystore.sh` - Keystore Generation
**Purpose**: Generate signing keystore for Google Play Store releases.

**Key Features**:
- Creates production keystore with proper settings
- Generates base64 encoding for GitHub Actions
- Provides GitHub Secrets setup instructions
- Security best practices guidance
- 10,000-day validity period

**Common Usage**:
```bash
./script/keystore.sh                   # Generate keystore (run once)
```

**Output**: 
- `pubsub-release.keystore` file
- GitHub Actions configuration instructions

**When to use**: One-time setup before first production release.

---

## Workflow Examples

### 🔄 Development Workflow
```bash
# 1. Set version for new feature
./script/version.sh 1.1.0

# 2. Start development environment
./script/dev.sh -p 3000

# 3. Build and test during development
./script/install.sh
./script/test.sh --unit

# 4. Final testing before release
./script/test.sh --clean --report
```

### 🚀 Release Workflow
```bash
# 1. Ensure version is set
./script/version.sh --show

# 2. Run full test suite
./script/test.sh --clean --report

# 3. Create release (builds and tags)
./script/release.sh

# 4. Verify release artifacts
ls -la app/build/outputs/
```

### 🧪 Testing Workflow
```bash
# Quick unit tests during development
./script/test.sh --unit

# Full test suite before commits
./script/test.sh --clean --report

# Instrumented tests (requires device)
./script/dev.sh &  # Start emulator in background
./script/test.sh --instrumented
```

---

## Dependencies

### Required Tools
- **Android SDK**: `adb`, `emulator` commands
- **Java/Kotlin**: For Gradle builds
- **Git**: For release tagging

### Optional Tools
- **keytool**: For keystore generation (usually included with Java)
- **base64**: For GitHub Actions setup (standard on most systems)

---

## File Outputs

### Build Artifacts
- `dist/pubsub-{version}-debug.apk` - Debug builds from `build.sh`
- `app/build/outputs/apk/release/pubsub-{version}-release.apk` - Release APK
- `app/build/outputs/bundle/release/pubsub-{version}-release.aab` - Release AAB

### Test Reports
- `app/build/reports/tests/` - Unit test HTML reports
- `app/build/reports/lint-results/` - Lint check reports
- `app/build/reports/androidTests/` - Instrumented test reports

### Configuration
- `pubsub-release.keystore` - Production signing keystore
- `.env` - Local environment variables (keystore passwords)

---

## Best Practices

### Version Management
1. **Set version before development**: Use `version.sh` to set target version
2. **Semantic versioning**: Follow `major.minor.patch` format
3. **Commit version changes**: `git add app/build.gradle.kts && git commit -m "Bump version to X.Y.Z"`

### Testing
1. **Test early and often**: Use `test.sh --unit` during development
2. **Full test suite**: Run `test.sh --clean --report` before releases
3. **Device testing**: Use `install.sh` for manual device testing

### Release Process
1. **Version first**: Set version with `version.sh`
2. **Test thoroughly**: Full test suite with reports
3. **Release once**: Use `release.sh` for official releases
4. **GitHub integration**: Tags trigger automated builds

### Security
1. **Never commit keystores**: Keep `pubsub-release.keystore` out of git
2. **Use GitHub Secrets**: Store sensitive data securely
3. **Backup keystores**: Store in secure password manager

---

## Troubleshooting

### Common Issues

**"No devices found"**
- Start emulator: `./script/dev.sh`
- Check USB debugging on physical device
- Verify with: `adb devices`

**"Keystore not found"**
- Generate keystore: `./script/keystore.sh`
- Ensure file exists: `ls -la pubsub-release.keystore`

**"Version not found"**
- Set version first: `./script/version.sh 1.0.0`
- Check build.gradle.kts has `versionName` field

**"Build failed"**
- Clean build: `./gradlew clean`
- Check Java/Android SDK versions
- Verify dependencies in `build.gradle.kts`

### Getting Help

Each script includes built-in help:
```bash
./script/{script-name}.sh --help
```

For example:
```bash
./script/version.sh --help
./script/release.sh --help
```

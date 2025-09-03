<div align="center">
  <img src="assets/pubsub-icon.png" alt="PubSub Logo" width="120" height="120">
  
  # pubsub
  
  Keep your self (and your sleepy apps) subscribed to the nostr fire-hose.
  
  [![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://android.com)
  [![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
  [![Nostr](https://img.shields.io/badge/Protocol-Nostr-purple.svg)](https://nostr.com)
</div>

## Features

- 🔗 **Persistent Multi-Relay Connections**: Stay connected to multiple Nostr relays simultaneously, surviving Android's aggressive power management with intelligent battery optimization.

- 🎯 **Advanced Event Filtering**: Subscribe to events with comprehensive filtering options:
  - **NIP-1 Filters**: Standard pubkey, hashtag, and event ID filtering
  - **Local Filters**: Client-side filtering for mentions to self and replies to events
  - **Keyword Filtering**: Content-based filtering with word boundary matching
  - **Custom Tag Filtering**: Arbitrary single-letter tag/value pairs for advanced scenarios

- 📱 **Smart Notifications**: Receive notifications with NIP-19 `nevent` links forwarded to your app of choice (via custom URI), with configurable frequency and rate limiting.

- ⚙️ **Comprehensive Settings**: Full control over app behavior:
  - **Battery Optimization Modes**: Performance, Balanced, and Battery Saver modes
  - **Debug Console**: Integrated structured logging with filtering and export
  - **Performance Metrics**: Real-time battery and network statistics (optional)

- 🌐 **Seamless Integration**: 
  - **Deep Link Registration**: `pubsub://register` scheme for web app integration
  - **Import/Export**: Backup and restore subscription configurations
  - **Multi-Subscription Management**: Individual enable/disable controls per subscription

- 🔋 **Intelligent Battery Management**: 
  - **Dynamic Ping Intervals**: Adaptive connection keep-alive based on device state
  - **Connection Pooling**: Optimized WebSocket client management
  - **Background Processing**: Message batching to prevent CPU spikes
  - **Per-Relay Health Monitoring**: Independent connection health tracking

## Screenshots

<div align="center">
  <img src="assets/screens/home.png" alt="Home Screen" width="250">
  <img src="assets/screens/subscribe.png" alt="Subscribe Screen" width="250">
</div>

*From left to right: Main dashboard with debug console and metrics, Advanced subscription editor with local filters*

> **Note**: Screenshots show the comprehensive interface including multi-subscription management, integrated debug console, performance metrics, and advanced filtering options.

## Configuration

The app supports multiple named subscription configurations, each with:

### Basic Configuration
1. **Subscription Name**: User-friendly descriptive identifier
2. **Target URI**: Callback URI for event forwarding (supports nevent links)
3. **Relay URLs**: Multiple Nostr relay endpoints with validation
4. **Enable/Disable**: Individual subscription control with real-time updates

### Advanced Filtering Options
- **Pubkey Filters**: Monitor mentions and direct messages (full NIP-19 support)
- **Hashtag Filters**: Simple word-based hashtags using #t tags
- **Custom Tag Filters**: Arbitrary single-letter tag/value pairs (excludes reserved e, p, t)
- **Content Keywords**: Word boundary matching for content-based filtering
- **Local Filters**: Client-side filtering for enhanced control:
  - **Exclude Mentions to Self**: Filter events where authors mention themselves
  - **Exclude Replies to Events**: Filter replies from filtered authors

### Settings & Optimization
- **Battery Modes**: Choose between Performance, Balanced, or Battery Saver modes
- **Notification Settings**: Configure frequency and rate limiting
- **Debug Console**: Toggle structured logging and performance metrics
- **Default Configuration**: Set default relay server and event viewer URLs

### Integration Features
- **Deep Link Support**: `pubsub://register` scheme with comprehensive parameter support
- **Import/Export**: Backup and restore configurations with direct UI integration
- **Web App Integration**: Seamless subscription registration from Progressive Web Apps

## Development

### Prerequisites

- Android Studio (Koala or later)
- Android SDK with API level 34
- Java 21+ (updated for modern development)
- Git for version control

### Development Build

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle files
4. Build and run on device or emulator

### Build Scripts

The project includes comprehensive build scripts for streamlined development:

```bash
# Quick development cycle
./script/build.sh          # Build debug APK
./script/install.sh        # Build and install on device
./script/test.sh           # Run all tests

# Development environment
./script/dev.sh            # Start Android emulator
./script/version.sh 1.0.0  # Set app version

# Production release
./script/keystore.sh       # Generate signing keystore (one-time setup)
./script/release.sh        # Create production release with git tagging
```

**Common Development Workflow:**
```bash
# 1. Start development environment
./script/dev.sh

# 2. Build and install during development
./script/install.sh

# 3. Run tests before commits
./script/test.sh --unit

# 4. Create release when ready
./script/version.sh 1.1.0
./script/test.sh --clean --report
./script/release.sh
```

> **📖 Complete Scripts Guide**: See [`docs/SCRIPT_GUIDE.md`](docs/SCRIPT_GUIDE.md) for detailed documentation of all build scripts, workflow examples, and troubleshooting.

> **📋 Release Process**: See `docs/RELEASE_CHECKLIST.md` for complete Google Play Store preparation steps.

### Dependencies

#### Core Dependencies
- **OkHttp 4.12.0**: WebSocket client for Nostr connections with connection pooling
- **Gson 2.10.1**: JSON parsing for Nostr messages and configuration
- **AndroidX Core**: Modern Android components with Material Design 3
- **Kotlin Coroutines 1.7.3**: Asynchronous operations and service management
- **Core Splash Screen**: Modern splash screen implementation

#### Testing Dependencies
- **JUnit 4.13.2**: Unit testing framework
- **MockK 1.13.8**: Kotlin-friendly mocking library
- **Robolectric 4.11.1**: Android unit testing
- **MockWebServer 4.12.0**: WebSocket testing infrastructure
- **Truth 1.1.5**: Fluent assertions for tests

#### Development Tools
- **ViewBinding**: Type-safe view references
- **ProGuard**: Code optimization for release builds
- **Gradle Kotlin DSL**: Modern build configuration

## Permissions

The app requires the following permissions for core functionality:

- **`INTERNET`**: Network access for WebSocket connections to Nostr relays
- **`FOREGROUND_SERVICE`**: Running persistent background service for continuous monitoring
- **`FOREGROUND_SERVICE_DATA_SYNC`**: Data synchronization service type (Android 14+)
- **`POST_NOTIFICATIONS`**: Displaying event notifications (Android 13+)
- **`RECEIVE_BOOT_COMPLETED`**: Auto-starting service after device reboot
- **`WAKE_LOCK`**: Maintaining connections during Android's doze mode

### Privacy & Data Handling
- **Local Storage Only**: All data stored locally using Android's SharedPreferences
- **No External Tracking**: No data collection or transmission to external services
- **User Control**: Complete control over data collection through settings
- **Transparent Logging**: Optional debug logging with user consent

All permissions are documented in the privacy policy and are essential for the app's core functionality. The app follows Android's best practices for background services and power management.

## Testing & Quality

The app includes comprehensive testing infrastructure:

- **406+ Tests**: Extensive test coverage across all critical components
- **Integration Testing**: NIP-01 compliant relay simulation for realistic testing
- **Performance Testing**: Battery optimization and connection efficiency validation
- **Protocol Compliance**: Full NIP-01 specification compliance testing
- **Real Component Testing**: Minimal mocking with authentic component interaction

## License

This project is free and open source. It is intended for educational and development purposes. 

**No warranty, no refunds.**

---

## Quick Start Guide

1. **Install** the app from the release page or build from source
2. **Configure** your first subscription with a relay URL and target URI
3. **Start** the service to begin monitoring events
4. **Customize** settings for optimal battery life and notification preferences
5. **Monitor** performance through the integrated debug console

For detailed setup instructions and troubleshooting, see the [User Guide](docs/USER_GUIDE.md).

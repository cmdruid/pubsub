# AGENTS.md - PubSub Android App

## Project Overview
**PubSub** is a native Android application that provides persistent Nostr push notifications for Progressive Web Apps (PWAs). It maintains background connections to Nostr relays and forwards events via notifications and deep links.

### Core Purpose
- Keep PWAs connected to the Nostr "fire-hose" when they're not active
- Provide real-time event notifications with callback URIs
- Enable background monitoring of Nostr events with persistent connections

## Architecture Summary

### Package Structure (`com.cmdruid.pubsub`)
```
├── ui/                     # User Interface
│   ├── MainActivity.kt     # Main config screen (launcher)
│   ├── ConfigurationEditorActivity.kt  # Subscription editor
│   └── adapters/           # RecyclerView adapters (4 files)
├── service/                # Background Services
│   ├── PubSubService.kt    # Main foreground service (WebSocket handling)
│   ├── BootReceiver.kt     # Auto-start after device reboot
│   ├── EventCache.kt       # Event caching logic
│   └── SubscriptionManager.kt  # Subscription management
├── nostr/                  # Nostr Protocol Implementation
│   ├── NostrEvent.kt       # Event data model
│   ├── NostrFilter.kt      # Filter data model
│   ├── NostrMessage.kt     # WebSocket message parsing
│   └── KeyPair.kt          # Nostr key handling
├── data/                   # Data Models & Persistence
│   ├── Configuration.kt    # Subscription configuration model
│   └── ConfigurationManager.kt  # Configuration persistence
└── utils/                  # Utilities
    ├── PreferencesManager.kt   # SharedPreferences wrapper
    ├── UriBuilder.kt           # URI construction for callbacks
    ├── NostrUtils.kt           # Nostr helper functions
    └── DeepLinkHandler.kt      # Deep link processing
```

### Key Components

#### 1. Main Activities
- **MainActivity**: Primary configuration UI, manages relay URLs, pubkeys, target URIs
- **ConfigurationEditorActivity**: Detailed subscription editing interface

#### 2. Background Service
- **PubSubService**: Foreground service maintaining WebSocket connections
  - Automatic reconnection with exponential backoff
  - Event forwarding to configured URIs
  - Persistent notification for service visibility

#### 3. Nostr Protocol Layer
- Full NIP-1 event parsing and filtering
- WebSocket message handling
- Support for multiple relay connections
- Key management (supports NIP-19 bech32 encoding)

## Configuration System

### Subscription Configuration
Each subscription includes:
- **Name**: Descriptive identifier
- **Target URI**: Callback URI for event forwarding
- **Relay URLs**: One or more Nostr relay endpoints
- **Filters**: NIP-1 compliant event filters (by pubkey, etc.)

### Deep Link Support
- Scheme: `pubsub://register`
- Enables web app registration of subscriptions
- Processed by `DeepLinkHandler.kt`

## Technical Specifications

### Build Configuration
- **Target SDK**: 34 (Android 14)
- **Min SDK**: 26 (Android 8.0)
- **Language**: Kotlin with Java 21 target
- **Package**: `com.cmdruid.pubsub`

### Key Dependencies
- **OkHttp 4.12.0**: WebSocket client for Nostr connections
- **Gson 2.10.1**: JSON parsing for Nostr messages
- **AndroidX**: Modern Android components (Core, AppCompat, Material)
- **Kotlin Coroutines 1.7.3**: Asynchronous operations
- **Core Splash Screen**: Modern splash implementation

### Permissions Required
- `INTERNET`: WebSocket connections to relays
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`: Background service
- `POST_NOTIFICATIONS`: Event notifications (Android 13+)
- `RECEIVE_BOOT_COMPLETED`: Auto-start after reboot
- `WAKE_LOCK`: Maintain connections during device sleep

## Development Status

### ✅ Completed Features
- Full Android project setup with Kotlin DSL
- WebSocket-based Nostr relay connections
- NIP-1 event parsing and filtering
- Foreground service with persistent notifications
- Configuration UI with subscription management
- Auto-start after device reboot
- Deep link registration system
- Battery optimization handling

### Build Variants
- **Debug**: `com.cmdruid.pubsub.debug` (development)
- **Release**: `com.cmdruid.pubsub` (production)

### Key Build Scripts
- `script/build_release.sh`: Production builds
- `script/build_beta.sh`: Beta testing builds
- `script/generate_keystore.sh`: Keystore creation

## File Locations

### Documentation
- `docs/DEVELOPMENT_STATUS.md`: Current project status
- `docs/PRIVACY_POLICY.md`: Privacy policy template
- `docs/RELEASE_CHECKLIST.md`: Google Play Store prep
- `docs/NIP-19.md`: Nostr bech32 encoding reference

### Configuration Files
- `app/build.gradle.kts`: Main build configuration
- `app/proguard-rules.pro`: Code optimization rules
- `app/src/main/AndroidManifest.xml`: App permissions and components

### Assets
- `assets/screens/`: App screenshots
- `assets/pubsub-icon.png`: App icon
- App icons in various resolutions under `app/src/main/res/mipmap-*/`

## Development Workflow

### Quick Start
1. Open in Android Studio
2. Sync Gradle files
3. Build and run on device/emulator
4. Configure real Nostr relay URLs for testing

### Testing Checklist
- UI accepts configuration input
- Service starts with foreground notification
- WebSocket connects to Nostr relays
- Events are received and parsed correctly
- URI forwarding works in both notification and direct modes
- Service survives device reboot
- Battery optimization handled correctly

### Release Process
1. Generate signing keystore
2. Update build.gradle.kts with keystore details
3. Build release APK/AAB
4. Test on multiple Android versions
5. Deploy to Google Play Store

## Privacy & Security Notes
- All data stored locally on device (SharedPreferences)
- No external data collection or transmission (except to configured relays/URIs)
- WebSocket connections use standard protocols
- Follows Android background service best practices

## Common Patterns

### Service Management
- Foreground service pattern for background execution
- Notification channels for Android 8.0+ compatibility
- Proper lifecycle management with StartCommand handling

### WebSocket Handling
- Connection state management with automatic reconnection
- Ping/pong keep-alive messages
- Exponential backoff for failed connections

### Event Processing
- JSON parsing with Gson for Nostr messages
- Filter matching against incoming events
- URI building with event data for callbacks

This document provides a comprehensive overview for understanding the PubSub Android app architecture and should fit efficiently into AI context windows for future assistance.

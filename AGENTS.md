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
│   └── adapters/           # RecyclerView adapters (7 files)
│       ├── ConfigurationAdapter.kt     # Main configuration list
│       ├── HashtagsAdapter.kt          # Simple hashtag management (#t tags)
│       ├── CustomTagAdapter.kt         # Custom tag/value pairs
│       ├── KeywordAdapter.kt           # Content keyword filtering
│       ├── RelayUrlAdapter.kt          # Relay URL management
│       ├── TextEntryAdapter.kt         # Generic text entry lists
│       └── DebugLogAdapter.kt          # Debug log display
├── service/                # Background Services & Optimization
│   ├── PubSubService.kt    # Main foreground service coordinator
│   ├── BootReceiver.kt     # Auto-start after device reboot
│   ├── EventCache.kt       # Event caching logic
│   ├── SubscriptionManager.kt          # Subscription management
│   ├── WebSocketConnectionManager.kt   # WebSocket connection handling
│   ├── MessageHandler.kt               # Nostr message processing
│   ├── EventNotificationManager.kt     # Notification management
│   ├── ConnectionHealthTester.kt       # Connection health monitoring
│   ├── NetworkManager.kt               # Network connectivity optimization
│   ├── BatteryPowerManager.kt          # Battery & power state management
│   ├── BatteryOptimizationLogger.kt    # Battery optimization tracking
│   ├── BatteryMetricsCollector.kt      # Battery usage metrics
│   └── NetworkOptimizationLogger.kt    # Network optimization tracking
├── nostr/                  # Nostr Protocol Implementation
│   ├── NostrEvent.kt       # Event data model
│   ├── NostrFilter.kt      # Filter data model with serialization
│   ├── NostrFilterSerializer.kt        # Custom filter JSON serialization
│   ├── NostrMessage.kt     # WebSocket message parsing
│   └── KeyPair.kt          # Nostr key handling
├── data/                   # Data Models & Persistence
│   ├── Configuration.kt    # Subscription configuration model
│   ├── ConfigurationManager.kt         # Multi-config persistence
│   ├── HashtagEntry.kt     # Custom tag entry model
│   └── KeywordFilter.kt    # Content-based keyword filtering
└── utils/                  # Utilities
    ├── PreferencesManager.kt   # SharedPreferences wrapper
    ├── UriBuilder.kt           # URI construction for callbacks
    ├── NostrUtils.kt           # Nostr helper functions
    ├── DeepLinkHandler.kt      # Deep link processing
    └── KeywordMatcher.kt       # Content matching utilities
```

### Key Components

#### 1. User Interface Layer
- **MainActivity**: Primary configuration dashboard with multi-subscription management
  - Configuration list with enable/disable toggles
  - Service start/stop controls with status monitoring
  - Debug log viewer for troubleshooting
  - Real-time service state updates
- **ConfigurationEditorActivity**: Advanced subscription editor
  - Multi-relay URL management
  - Pubkey filtering with NIP-19 support
  - Hashtag filtering (simple #t tags)
  - Custom tag/value pairs for advanced filtering
  - Content keyword filtering with word boundary matching
  - Target URI configuration with validation

#### 2. Enhanced Background Service Architecture
- **PubSubService**: Main service coordinator with modular components
  - Component-based architecture for maintainability
  - Enhanced logging and debugging capabilities
  - App state change monitoring and optimization
- **WebSocketConnectionManager**: Dedicated WebSocket handling
  - Automatic reconnection with exponential backoff
  - Connection health monitoring and testing
  - Multi-relay connection management
- **Battery & Power Optimization**:
  - **BatteryPowerManager**: App state tracking and power optimization
  - **BatteryOptimizationLogger**: Battery usage pattern analysis
  - **BatteryMetricsCollector**: Detailed battery consumption metrics
  - Dynamic ping interval adjustment based on device state
- **Network Optimization**:
  - **NetworkManager**: Network connectivity monitoring
  - **NetworkOptimizationLogger**: Network usage optimization tracking
  - Adaptive behavior based on network quality and type
- **Event Processing**:
  - **MessageHandler**: Nostr message parsing and routing
  - **EventNotificationManager**: Smart notification management
  - **EventCache**: Event deduplication and caching
  - **SubscriptionManager**: Multi-subscription coordination

#### 3. Enhanced Nostr Protocol Layer
- Full NIP-1 event parsing with custom serialization
- Advanced filtering capabilities:
  - Pubkey mentions and direct messages
  - Hashtag filtering with validation
  - Custom tag filtering for advanced use cases
  - Content-based keyword matching
- WebSocket message handling with health monitoring
- Support for multiple simultaneous relay connections
- Key management with full NIP-19 bech32 encoding support

#### 4. Data Management Layer
- **Multi-Configuration System**: Support for multiple named subscriptions
- **Advanced Filter Models**: Structured filtering with validation
- **Persistent Debug Logging**: Comprehensive troubleshooting information
- **Migration Support**: Automatic upgrade from legacy single-config format

## Configuration System

### Multi-Subscription Architecture
Supports multiple named subscriptions, each with:
- **Unique ID**: UUID-based identification
- **Name**: User-friendly descriptive identifier
- **Enable/Disable State**: Individual subscription control
- **Target URI**: Callback URI for event forwarding
- **Relay URLs**: Multiple Nostr relay endpoints with validation
- **Advanced Filtering Options**:
  - **Pubkey Filters**: Mentions and direct messages (NIP-19 compatible)
  - **Hashtag Filters**: Simple word-based hashtags using #t tags
  - **Custom Tag Filters**: Arbitrary single-letter tag/value pairs
  - **Keyword Filters**: Content-based filtering with word boundary matching

### Filter Validation & UX
- Real-time input validation with user feedback
- Automatic sanitization of filter inputs
- Prevention of reserved tag conflicts (e, p, t)
- Support for complex filter combinations

### Deep Link Support
- Scheme: `pubsub://register`
- Enables web app registration of subscriptions
- Processed by `DeepLinkHandler.kt`
- Support for complex filter parameters

### Data Persistence
- JSON-based configuration storage in SharedPreferences
- Automatic migration from legacy single-configuration format
- Comprehensive debug logging with size limits
- Configuration backup and restore capabilities

## Technical Specifications

### Build Configuration
- **Target SDK**: 34 (Android 14)
- **Min SDK**: 26 (Android 8.0)
- **Current Version**: 0.9.3 (as of latest update)
- **Language**: Kotlin with Java 21 target
- **Package**: `com.cmdruid.pubsub`
- **Build Variants**: Debug (`com.cmdruid.pubsub.debug`) and Release

### Key Dependencies
- **OkHttp 4.12.0**: WebSocket client for Nostr connections
- **Gson 2.10.1**: JSON parsing for Nostr messages and configuration
- **AndroidX**: Modern Android components (Core, AppCompat, Material)
- **Kotlin Coroutines 1.7.3**: Asynchronous operations and service management
- **Material Design 3**: Modern UI components and theming
- **ViewBinding**: Type-safe view references
- **Core Splash Screen**: Modern splash implementation

### Permissions Required
- `INTERNET`: WebSocket connections to relays
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`: Background service
- `POST_NOTIFICATIONS`: Event notifications (Android 13+)
- `RECEIVE_BOOT_COMPLETED`: Auto-start after reboot
- `WAKE_LOCK`: Maintain connections during device sleep

## Development Status

### ✅ Completed Features (v0.9.3)
- **Core Infrastructure**:
  - Full Android project setup with Kotlin DSL
  - Multi-configuration subscription management
  - Enhanced service architecture with component separation
  - Comprehensive battery and network optimization
- **Nostr Protocol Support**:
  - WebSocket-based multi-relay connections with health monitoring
  - Advanced NIP-1 event parsing and filtering
  - Custom JSON serialization for complex filters
  - Stale subscription detection and automatic resubscription
- **User Interface**:
  - Modern Material Design 3 interface
  - Specialized adapters for different filter types
  - Real-time validation with user feedback
  - Debug log viewer for troubleshooting
  - Configuration enable/disable toggles
- **Advanced Features**:
  - Content-based keyword filtering with word boundary matching
  - Custom tag/value pair filtering
  - Hashtag filtering with validation
  - Auto-start after device reboot
  - Deep link registration system
  - Comprehensive battery optimization and monitoring
  - Network-aware connection management

### 🚧 Known Issues & TODO Items
- Minor UX bugs with field warning alignment
- Target URI enhancement to forward proper `nevent` links
- Settings page development
- Import/Export functionality for filter configurations
- Enhanced test coverage

### Build Variants & Scripts
- **Debug**: `com.cmdruid.pubsub.debug` (development with enhanced logging)
- **Release**: `com.cmdruid.pubsub` (production optimized)
- **Build Scripts**:
  - `script/release.sh`: Production builds with signing
  - `script/dev.sh`: Development builds
  - `script/keystore.sh`: Keystore management
  - `script/install.sh`: Installation automation

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

## Architecture Patterns & Best Practices

### Service Management
- **Component-Based Architecture**: Modular service design with specialized managers
- **Foreground Service Pattern**: Persistent background execution with user visibility
- **Notification Channels**: Android 8.0+ compatibility with proper channel management
- **Lifecycle Management**: Proper StartCommand handling with service state persistence
- **Resource Management**: Automatic cleanup and resource optimization

### Battery & Performance Optimization
- **App State Monitoring**: Dynamic behavior based on foreground/background/doze states
- **Adaptive Ping Intervals**: Battery-aware connection keep-alive strategies
- **Network Quality Awareness**: Connection optimization based on network conditions
- **Metrics Collection**: Comprehensive battery and network usage tracking
- **Doze Mode Handling**: Proper behavior during Android's power optimization states

### WebSocket Connection Management
- **Health Monitoring**: Proactive connection health testing and recovery
- **Stale Subscription Detection**: Automatic detection and recovery from stale subscriptions
- **Multi-Relay Support**: Simultaneous connections with independent health monitoring
- **Exponential Backoff**: Intelligent reconnection strategies
- **Connection Pooling**: Efficient WebSocket client management with OkHttp

### Event Processing & Filtering
- **Multi-Stage Filtering**: Nostr filters + keyword filters + content validation
- **Event Deduplication**: Intelligent caching to prevent duplicate processing
- **Content Matching**: Advanced keyword matching with word boundary detection
- **Filter Validation**: Real-time input validation with user feedback
- **JSON Serialization**: Custom serializers for complex filter structures

### Data Management
- **Multi-Configuration Support**: JSON-based persistence with migration support
- **Debug Logging**: Comprehensive logging with size limits and rotation
- **Input Validation**: Client-side validation with server-side sanitization
- **Configuration Backup**: Structured data export/import capabilities (planned)

### UI/UX Patterns
- **Material Design 3**: Modern Android design language implementation
- **Specialized Adapters**: Type-specific RecyclerView adapters for different data types
- **Real-Time Validation**: Immediate user feedback with error states
- **State Management**: Proper handling of configuration and service states
- **Accessibility Support**: Proper content descriptions and navigation support

## Recent Changes (v0.9.3)

### Service Architecture Refactoring
The main `PubSubService.kt` has been significantly refactored into a component-based architecture:
- **Separation of Concerns**: Battery, network, and connection management extracted into dedicated classes
- **Enhanced Monitoring**: Comprehensive logging and metrics collection for troubleshooting
- **Improved Reliability**: Better connection health monitoring and automatic recovery

### UI/UX Improvements
- **Specialized Adapters**: Dedicated adapters for different filter types (hashtags, custom tags, keywords)
- **Real-Time Validation**: Immediate feedback for user input with proper error messaging
- **Enhanced Configuration Editor**: Support for complex filtering scenarios with intuitive UX

### Advanced Filtering Capabilities
- **Keyword Filtering**: Content-based filtering with word boundary matching
- **Custom Tag Support**: Arbitrary single-letter tag/value pairs for advanced filtering
- **Hashtag Validation**: Proper validation and sanitization of hashtag inputs
- **Filter Serialization**: Custom JSON serialization for complex filter structures

### Battery & Network Optimization
- **App State Awareness**: Dynamic behavior based on device power states
- **Network Quality Adaptation**: Connection optimization based on network conditions
- **Metrics Collection**: Detailed battery and network usage tracking
- **Stale Subscription Recovery**: Automatic detection and recovery from failed subscriptions

## Agent Usage Guidelines

This document is designed for AI agents working with the PubSub Android codebase:

### Quick Navigation
- **UI Issues**: Focus on `ui/` package, especially adapters for specific data types
- **Service Problems**: Check `service/` package components, particularly the new manager classes
- **Filter/Nostr Issues**: Examine `nostr/` and `data/` packages for protocol and model handling
- **Configuration**: Look at `ConfigurationManager.kt` for multi-config persistence

### Common Debugging Areas
- **Connection Issues**: `WebSocketConnectionManager.kt` and `ConnectionHealthTester.kt`
- **Battery Problems**: `BatteryPowerManager.kt` and related optimization classes
- **Filter Validation**: Individual adapter classes and data model validation methods
- **Service Lifecycle**: Main `PubSubService.kt` and component initialization

### Development Priorities
Based on TODO.md, current focus areas include:
- Settings page development
- Target URI enhancement for `nevent` links
- Import/Export functionality
- Test coverage improvements
- Minor UX refinements

This comprehensive overview provides AI agents with the context needed to effectively assist with PubSub Android app development, maintenance, and troubleshooting.

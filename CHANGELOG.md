# Changelog

## [0.9.9] - 2025-01-04

### 🚫 Subscription Cancellation System

- **NEW: Automatic Subscription Cancellation**: Automatically detect and cancel unwanted subscriptions
  - Detects events from subscriptions that don't match active configurations
  - Sends CLOSE messages to relays to stop receiving unwanted events
  - Lightweight memory cache with automatic cleanup to prevent memory bloat
  - Comprehensive logging and metrics tracking for cancellation events

- **NEW: SubscriptionCancellationTracker**: Memory-efficient cancellation tracking
  - Maximum 1000 cancelled subscription IDs in memory with LRU eviction
  - Automatic cleanup every 5 minutes to prevent memory bloat
  - Thread-safe concurrent access for high-performance scenarios
  - Detailed statistics tracking (total cancellations, recent activity, memory usage)

- **NEW: Enhanced RelayConnectionManager**: Subscription cancellation capabilities
  - `cancelSubscription()` method to send CLOSE messages to relays
  - Automatic detection of unmatched event messages
  - Integration with metrics system for cancellation tracking
  - Graceful handling of connection failures during cancellation

### 📊 Enhanced Diagnostic Metrics

- **NEW: Rate Limit Tracking**: Monitor notification rate limiting events
  - Tracks hourly notification limits (200 notifications/hour)
  - Tracks frequency-based rate limits (configurable intervals)
  - Detailed reasons for each rate limit (hourly exceeded, time limit exceeded)
  - Per-subscription and per-relay rate limit tracking

- **ENHANCED: Performance Metrics UI**: Integrated diagnostic metrics into existing Performance Metrics section
  - Added "Rate Limits Hit" metric with orange color coding
  - Merged diagnostic metrics into unified Performance Metrics card
  - Consistent styling and layout with existing metrics
  - Real-time display of rate limit events

### 🧪 Comprehensive Test Coverage

- **NEW: Subscription Cancellation Test Suite**: Complete test coverage for cancellation functionality
  - **SubscriptionCancellationTrackerTest**: 8 unit tests covering core tracking logic
  - **SubscriptionCancellationIntegrationTest**: 8 integration tests for message processing
  - **RelayConnectionManagerCancellationTest**: Component tests for relay management
  - **SubscriptionCancellationTestSuite**: Organized test suite with 17 total tests

- **TEST COVERAGE**: All cancellation functionality thoroughly tested
  - Memory management and cleanup scenarios
  - Concurrent access and thread safety
  - Edge cases (invalid messages, duplicates, disabled configs)
  - Integration with existing message processing pipeline

### 🔧 Technical Improvements

- **ENHANCED: Message Processing Pipeline**: Improved handling of unmatched events
  - Automatic detection of events from unwanted subscriptions
  - Immediate cancellation of detected unwanted subscriptions
  - Comprehensive error handling and logging
  - Integration with existing metrics and notification systems

- **OPTIMIZED: Memory Management**: Lightweight cancellation tracking
  - Efficient storage using ConcurrentHashMap for thread safety
  - Automatic cleanup with configurable intervals
  - Size limits with intelligent eviction policies
  - Minimal memory footprint with maximum 1000 tracked subscriptions

## [0.9.8] - 2025-01-04

### 🧪 Android Test Framework Integration

- **COMPLETED: Subscription Testing Framework**: Fully integrated subscription testing into Android test framework
  - All subscription tests now run with standard `./gradlew test` commands
  - TestWebSocketServer provides automated subscription testing with mock Nostr relay
  - Comprehensive test coverage with 48 tests covering all critical subscription functionality
  - 100% test success rate after fixing subscription isolation test validation
  - Enhanced diagnostic exports with system information and logging state

### 🔧 Test Framework Improvements

- **FIXED: Subscription Isolation Test**: Resolved content validation mismatch in multi-subscription testing
  - Fixed assertion logic to properly validate event content isolation
  - Ensured multiple subscriptions receive only their own events (no cross-contamination)
  - Validated TestWebSocketServer correctly handles multiple concurrent subscriptions

### 📊 Enhanced Testing Capabilities

- **NEW: Automated Test Execution**: Complete test suite available for subscription validation
  - Run all subscription tests: `./gradlew testDebugUnitTest --tests="com.cmdruid.pubsub.integration.SubscriptionIntegrationTestSuite"`
  - Run specific test categories: core reliability, automated testing, health monitoring
  - Get test relay URLs for manual testing with real device integration
  - CI/CD ready with proper test reporting and build integration

## [0.9.7] - 2025-01-04

### 🔧 Critical Bug Fixes

- **FIXED: Subscription Renewal Failures**: Resolved critical issue where subscriptions would stop receiving events after connection failures
  - Fixed health check logic inconsistency between HealthMonitor and RelayConnectionManager
  - Added reconnection attempt reset during health checks to prevent permanent failure states
  - Enhanced subscription renewal with proper timestamp-based filtering
  - Improved debug logging for reconnection triggers

- **FIXED: Cross-Subscription Notification Bug**: Prevented events from one subscription appearing in notifications for another subscription
  - Added critical validation to ensure subscription ID matches configuration
  - Enhanced notification ID generation to include subscription ID for isolation
  - Added race condition protection in subscription ID handling
  - Implemented comprehensive error logging for cross-subscription detection

- **FIXED: Debug Console Filter Bug**: Resolved issue where debug console filter would default to disabled or randomly disable all logs
  - Fixed filter corruption during dialog initialization by reordering setup sequence
  - Added validation to prevent saving corrupted filters with empty types/domains
  - Implemented automatic recovery from corrupted filter states
  - Added safety defaults to ensure ERROR and WARN logs are always visible

- **FIXED: Duplicate Detection Metrics**: Resolved issue where "Duplicates Detected" and "Network Data Saved" metrics always showed zero
  - Added tracking for timestamp-based duplicate prevention (the primary duplicate prevention mechanism)
  - Enhanced EventCache duplicate detection with proper metrics tracking
  - Implemented bandwidth savings calculation for prevented duplicate events
  - Added precise timestamp usage rate tracking

- **FIXED: Network Quality Detection**: Enhanced network quality detection that was causing overly conservative health thresholds
  - Improved null handling in network capabilities detection
  - Added detailed logging for network quality determination issues
  - Fixed cases where network quality would incorrectly report 'none' for extended periods

### 🏗️ Architecture Improvements

- **Testable Health Monitoring**: Completely refactored health monitoring system for better testability and maintainability
  - Created `HealthEvaluator` for pure health logic (no dependencies, easy to test)
  - Implemented `HealthCheckOrchestrator` for coordination logic with minimal mocking
  - Developed `HealthMonitorV2` with synchronous testing support via `runHealthCheckSync()`
  - Added comprehensive test coverage with 25+ focused tests

- **Enhanced Event Processing**: Improved event processing pipeline with better validation and metrics
  - Added comprehensive subscription ID validation to prevent cross-subscription contamination
  - Enhanced event processing metrics tracking for all processed events
  - Improved error handling and logging throughout the event pipeline

### 📊 Metrics & Logging Improvements

- **Comprehensive Metrics Tracking**: 
  - Event processing metrics now properly tracked (previously always zero)
  - Timestamp-based duplicate prevention metrics with bandwidth savings calculation
  - Precise timestamp usage rate tracking for system health monitoring
  - Enhanced connection and health check metrics

- **Improved Logging Organization**:
  - Properly categorized verbose logs as TRACE level
  - Enhanced connection and subscription logging with detailed status information
  - Added cross-subscription bug detection logging
  - Improved network quality determination logging

### 🧪 Testing Infrastructure

- **Enhanced Test Coverage**: Added 30+ new focused tests for critical bug fixes
- **Cross-Subscription Bug Tests**: Comprehensive validation of subscription isolation
- **Duplicate Detection Tests**: Validation of both EventCache and timestamp-based duplicate prevention
- **Health Monitoring Tests**: Pure unit tests for health evaluation logic
- **User Scenario Tests**: Tests that reproduce and validate fixes for reported issues

### 📈 Performance & Reliability

- **Improved Subscription Continuity**: Enhanced subscription renewal prevents service interruptions
- **Better Resource Management**: Proper metrics tracking reveals system performance and optimization opportunities
- **Enhanced Error Recovery**: Automatic recovery from corrupted states and connection failures
- **Reduced Debug Noise**: Proper log level categorization improves debugging efficiency

## [0.9.6]

- **Major Testing Infrastructure Overhaul**: Added comprehensive test coverage with 139 new tests across all critical components.

- **Complete Integration Testing Framework**: Implemented dedicated testing tools with NIP-01 compliant relay simulation.

- **Service Architecture Refactoring**: 
  - Replaced `WebSocketConnectionManager` with new `RelayConnectionManager` (504 lines of new code).
  - Refactored `MessageHandler` into `MessageProcessor` with enhanced functionality.
  - Updated `SubscriptionManager` with improved per-relay timestamp tracking.
  - Added new `HealthMonitor` and `HealthThresholds` for better service monitoring.
  - Enhanced `MetricsCollector` and added `MetricsReader` for comprehensive metrics.

- **Battery Optimization Improvements**: Streamlined `BatteryPowerManager` and removed redundant battery logging components.

- **UI Enhancements**:
  - Major updates to `MainActivity` with improved layout and functionality.
  - Enhanced `ConfigurationEditorActivity` with better user experience.
  - Added new `TagSelectorView` component for better tag management.
  - Updated string resources and color schemes for better accessibility.

- **Deep Link and URI Handling**: Enhanced `DeepLinkHandler` and `UriBuilder` with improved nevent support.

- **Build and Development Tools**: 
  - Added comprehensive build scripts (`build.sh`, `version.sh`).
  - Enhanced release script with better automation.

- **Code Quality**: Removed obsolete test files and consolidated testing approach for better maintainability.

-**Improved Performance**: Vastly improved performance of health monitor and resubscriptions.

- **Feature Additions**: Added a number of new features to Subscriptions and Settings.

## [0.9.5]

- Added export button to debug console.
- Updated icons for debug console.
- Minor changes to some text labels in the app.
- Fixed health monitoring error messages spamming the logs.

## [0.9.4]

- Added settings page with some configurations.
- Added import / export of configs.
- Changed `note` link to `nevent` link.
- Separated hash tags (#t) from custom tags.
- Minor changes to the subscription config UI
- Added test coverage for `nevent` handling and import / export.
- Various other changes and fixes.

## [0.9.3]

- Fixed issues with relay subscriptions going stale. The app should now detect if the subscription is stale, and resubscribe.
- Added better logging to the debug console.
- Refactored the main service file and split into multiple files.

## [0.9.2]

- Battery optimizations across the board.
- Fixed issue with hashtags not having a value field.
- Added keyword filtering.
- Fixed issue with notification gap between a dead socket and reconnection.

## [0.9.1]

- Refactored nostr utility methods.
- Fixed numerous issues regarding subscription handling.
- Updated subscriptions config page.

## [0.9.0]

- Initial release of application

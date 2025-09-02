# Changelog

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

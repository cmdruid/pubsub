# Privacy Policy for PubSub - Nostr Push Notifications

**Last updated: December 2024**  
**App Version: 0.9.7**

## Overview

PubSub is a native Android application that connects to Nostr relays to monitor events and deliver push notifications. This privacy policy explains how we handle your data.

## Data Collection and Usage

### Data We Collect
- **Subscription Configurations**: The relay URLs, public keys, and target URIs you configure
- **Filter Settings**: Hashtags, keywords, and custom tags you set up for event filtering
- **Local Filter Preferences**: Your choices for mentions to self and replies filtering
- **App Settings**: Battery optimization mode, notification preferences, and debug console settings
- **Performance Metrics** (Optional): Battery usage and connection statistics when metrics collection is enabled
- **Debug Logs** (Optional): Structured application logs when debug console is enabled
- **Device Information**: Basic Android device information required for background service functionality

### Data We Don't Collect
- **No Personal Information**: We do not collect names, email addresses, or personal identifiers
- **No Behavioral Tracking**: We do not track user behavior or app usage patterns
- **No External Storage**: We do not store any data on external servers or cloud services
- **No Third-Party Sharing**: We do not share data with third parties, advertisers, or analytics services
- **No Location Data**: We do not access or store location information
- **No Contact Access**: We do not access your contacts, calendar, or other personal data
- **No Network Monitoring**: We only connect to the Nostr relays you explicitly configure

## Data Storage

All data is stored locally on your device using Android's secure SharedPreferences system:

### Local Storage Details
- **Subscription Configurations**: Stored in encrypted SharedPreferences
- **App Settings**: Locally stored preferences with automatic backup exclusion
- **Event Cache**: Temporary local storage for duplicate event prevention (automatically cleaned)
- **Debug Logs**: Local storage with automatic size limits and rotation (when enabled)
- **Performance Metrics**: Local storage with automatic cleanup (when enabled)
- **Relay Timestamps**: Per-relay tracking data stored locally for duplicate prevention

### Data Transmission
Data is only transmitted in the following scenarios:
- **Direct connections** to the Nostr relays you explicitly configure
- **Event forwarding** to the target URIs you specify in your subscriptions
- **No other external communication** occurs without your explicit configuration

## Permissions

The app requires the following permissions, all essential for core functionality:

### Required Permissions
- **`INTERNET`**: Connect to Nostr relays you configure (no other network access)
- **`FOREGROUND_SERVICE`**: Maintain persistent background connections
- **`FOREGROUND_SERVICE_DATA_SYNC`**: Data synchronization service type (Android 14+)
- **`POST_NOTIFICATIONS`**: Display event notifications (Android 13+)
- **`RECEIVE_BOOT_COMPLETED`**: Auto-restart service after device reboot
- **`WAKE_LOCK`**: Maintain connections during Android's doze mode

### Permission Usage
- **Network Access**: Only used to connect to relays you explicitly configure
- **Background Service**: Required for continuous event monitoring
- **Notifications**: Only displays notifications for events matching your filters
- **Boot Receiver**: Only starts the service if it was running before reboot
- **Wake Lock**: Used sparingly with intelligent battery management

### No Additional Permissions
The app does **NOT** request access to:
- Camera, microphone, or sensors
- Contacts, calendar, or personal data
- Location services
- External storage (beyond app-specific data)
- Phone or SMS functionality
- Other apps or system information

## Data Security

### Local Data Protection
- **Encrypted Storage**: All configuration data stored using Android's secure SharedPreferences
- **App-Specific Storage**: Data isolated from other apps using Android's sandboxing
- **No External Access**: Data cannot be accessed by other apps or external services
- **Automatic Cleanup**: Temporary data (logs, metrics) automatically cleaned with size limits

### Network Security
- **Secure Connections**: All relay connections use secure WebSocket (WSS) protocols when available
- **No Man-in-the-Middle**: Direct connections to relays without proxy servers
- **Minimal Data Transmission**: Only sends subscription requests and receives events
- **No Authentication Required**: App doesn't store or transmit private keys or sensitive credentials

### Privacy by Design
- **Local-First Architecture**: All processing happens locally on your device
- **Configurable Collection**: Performance metrics and debug logging are optional and user-controlled
- **Transparent Operation**: All network connections are to relays you explicitly configure
- **No Background Data**: No hidden data collection or transmission

## User Control

### Data Management
- **Settings Control**: Full control over all data collection through app settings
- **Debug Console**: Optional structured logging that can be enabled/disabled at any time
- **Performance Metrics**: Optional metrics collection with easy on/off toggle
- **Data Export**: Ability to export and backup your subscription configurations
- **Data Deletion**: Uninstalling the app removes all stored data

### Transparency Features
- **Open Source**: Complete source code available for inspection
- **Debug Visibility**: Optional debug console shows exactly what the app is doing
- **Network Transparency**: Clear indication of all network connections in debug logs
- **Settings Transparency**: All data collection preferences clearly explained

## Changes to This Policy

We may update this privacy policy to reflect changes in the app or legal requirements:

- **Version Updates**: Policy version will be updated with app releases
- **Notification Method**: Changes will be communicated through app updates and GitHub releases
- **No Retroactive Changes**: Changes will not affect data already collected under previous versions
- **User Consent**: Significant changes will require user acknowledgment

## Contact

If you have questions about this privacy policy or data handling practices:

- **GitHub Issues**: [Create an issue](https://github.com/cmdruid/pubsub/issues) for privacy concerns
- **Documentation**: See `USER_GUIDE.md` for detailed app functionality
- **Source Code**: Full transparency available at [GitHub Repository](https://github.com/cmdruid/pubsub)

---

**Summary**: PubSub is designed with privacy as a core principle. All data stays on your device, no external tracking occurs, and you have complete control over what data is collected and how the app behaves. The app only connects to services you explicitly configure and operates with full transparency.

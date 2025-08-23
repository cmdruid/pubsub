# Privacy Policy for PubSub - Nostr Push Notifications

**Last updated: [DATE]**

## Overview

PubSub is a native Android application that connects to Nostr relays to monitor events and deliver push notifications. This privacy policy explains how we handle your data.

## Data Collection and Usage

### Data We Collect
- **Relay URLs**: The Nostr relay endpoints you configure
- **Public Keys**: Nostr public keys you choose to monitor
- **Target URIs**: URIs you configure for event forwarding
- **Device Information**: Basic Android device information for service functionality

### Data We Don't Collect
- We do not collect personal information
- We do not track user behavior
- We do not store data on external servers
- We do not share data with third parties

## Data Storage

All configuration data is stored locally on your device using Android's SharedPreferences. No data is transmitted to our servers or third-party services except:

- Direct connections to the Nostr relays you configure
- Event forwarding to the target URIs you specify

## Permissions

The app requires the following permissions:

- **INTERNET**: To connect to Nostr relays
- **FOREGROUND_SERVICE**: To maintain persistent connections
- **POST_NOTIFICATIONS**: To display event notifications
- **RECEIVE_BOOT_COMPLETED**: To restart service after device reboot
- **WAKE_LOCK**: To maintain connections during device sleep

## Data Security

- All data is stored locally on your device
- Connections to Nostr relays use WebSocket protocols
- No data is transmitted to external servers under our control

## Changes to This Policy

We may update this privacy policy from time to time. We will notify you of any changes by posting the new privacy policy in the app or on our website.

## Contact

If you have questions about this privacy policy, please contact us at [YOUR_EMAIL].

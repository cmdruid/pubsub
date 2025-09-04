# PubSub User Guide

**Complete Guide to Nostr Push Notifications for Progressive Web Apps**

**Version:** 0.9.7 (Beta)  
**Last Updated:** December 2024

---

## Table of Contents

1. [Quick Start Guide](#quick-start-guide)
2. [Subscription Management](#subscription-management)
3. [Advanced Filtering](#advanced-filtering)
4. [Settings & Optimization](#settings--optimization)
5. [Deep Link Integration](#deep-link-integration)
6. [Debug Console & Monitoring](#debug-console--monitoring)
---

## Quick Start Guide

### Installation and Setup

1. **Install the App**
   - Download from the release page or build from source
   - Grant required permissions when prompted
   - The app will create a foreground service notification when running

2. **Create Your First Subscription**
   - Tap the "+" button on the main screen
   - Enter a descriptive name (e.g., "My Nostr Feed")
   - Add at least one relay URL (e.g., `wss://relay.damus.io`)
   - Set your target URI where events should be forwarded
   - Configure basic filters (optional for initial setup)

3. **Start the Service**
   - Tap "Start Service" on the main screen
   - The app will begin monitoring your configured relays
   - You'll see a persistent notification indicating the service is running

4. **Test Your Setup**
   - Check the debug console for connection status
   - Look for "Connected to relay" messages
   - Events matching your filters will appear in the logs

### Understanding the Interface

**Main Dashboard:**
- **Subscriptions List**: All your configured subscriptions with enable/disable toggles
- **Service Controls**: Start/stop the monitoring service
- **Debug Console**: Real-time logs and connection status
- **Performance Metrics**: Battery usage and connection statistics
- **Settings Button**: Access comprehensive app settings

---

## Subscription Management

### Creating Subscriptions

#### Basic Configuration
1. **Subscription Name**: Choose a descriptive name to identify your subscription
2. **Target URI**: The URL where events will be forwarded (your web app or service)
3. **Relay URLs**: Add one or more Nostr relay endpoints
   - Use secure WebSocket URLs (wss://) when available
   - Popular relays: `wss://relay.damus.io`, `wss://nos.lol`, `wss://relay.snort.social`

#### Advanced Configuration
- **Enable/Disable**: Toggle subscriptions without deleting them
- **Individual Control**: Each subscription operates independently
- **Real-time Updates**: Changes take effect immediately when the service is running

### Managing Multiple Subscriptions

**Why Use Multiple Subscriptions:**
- Different relay sets for different purposes
- Separate filtering rules for different types of content
- Different target URIs for different web applications
- Testing configurations without affecting production setups

**Best Practices:**
- Use descriptive names that indicate the subscription's purpose
- Keep relay lists focused (3-5 relays per subscription is usually sufficient)
- Enable only the subscriptions you're actively using

### Import/Export Functionality

**Exporting Subscriptions:**
1. Tap the export icon on the subscriptions card
2. Choose a location to save your configuration file
3. The file contains all subscription settings in JSON format

**Importing Subscriptions:**
1. Tap the import icon on the subscriptions card
2. Select your previously exported configuration file
3. Choose whether to replace existing subscriptions or merge them

**Use Cases:**
- Backup configurations before making changes
- Share configurations between devices
- Migrate settings when reinstalling the app

---

## Advanced Filtering

### Filter Types Overview

PubSub supports multiple types of filters that work together in a pipeline:

1. **NIP-1 Filters** → 2. **Local Filters** → 3. **Keyword Filters** → 4. **Event Delivery**

### NIP-1 Standard Filters

#### Pubkey Filters
- **Purpose**: Monitor mentions and direct messages for specific users
- **Formats Supported**:
  - Hex format: `3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d`
  - NIP-19 npub: `npub1809rr0kt2drrgpa6j7jh4hhyl2yzr5plhejk33v5avd2nwh2gkwsrh8rvh`
- **Usage**: Enter one pubkey per field, add more fields as needed

#### Event ID Filters  
- **Purpose**: Monitor specific events or threads
- **Format**: 64-character hex strings
- **Usage**: Useful for monitoring replies to specific posts

#### Hashtag Filters
- **Purpose**: Monitor events containing specific hashtags
- **Format**: Single words without the # symbol
- **Examples**: `bitcoin`, `nostr`, `technology`
- **Validation**: Only alphanumeric characters, hyphens, and underscores allowed

#### Custom Tag Filters
- **Purpose**: Advanced filtering using arbitrary tag/value pairs
- **Format**: Single letter tag + value
- **Examples**: 
  - Tag `r` with value `bitcoin` (for topics)
  - Tag `g` with value `group123` (for groups)
- **Restrictions**: Cannot use reserved tags `e` (events), `p` (pubkeys), `t` (hashtags)

### Local Filters (Client-Side)

Local filters provide additional control over events that have already passed NIP-1 filtering:

#### Exclude Mentions to Self
- **Purpose**: Filter out events where the author mentions themselves
- **How it works**: Checks if the event author's pubkey appears in the event's 'p' tags
- **Use case**: Reduce noise from users who frequently mention themselves
- **Default**: Enabled (recommended for most users)

#### Exclude Replies to Events  
- **Purpose**: Filter out replies from filtered authors
- **How it works**: Checks if events from your filtered authors contain 'e' tags (indicating replies)
- **Use case**: Focus on original content rather than replies
- **Default**: Disabled (preserves all content by default)

### Keyword Filters

#### Content-Based Filtering
- **Purpose**: Filter events based on content text
- **Matching**: Uses word boundary matching (not simple substring matching)
- **Examples**:
  - `bitcoin` matches "Bitcoin is great" but not "bitcoins"
  - `AI` matches "AI technology" but not "AIR"
- **Performance**: Optimized with pattern caching for repeated keywords

#### Best Practices for Keywords
- Use specific terms rather than common words
- Consider variations (e.g., both "bitcoin" and "Bitcoin")
- Start with fewer keywords and add more based on results
- Monitor the debug console to see which keywords are matching

---

## Settings & Optimization

### Battery Optimization Modes

The app offers three battery optimization modes to balance performance with battery life:

#### Performance Mode
- **Best for**: Users who prioritize real-time updates
- **Ping Intervals**: 15s foreground, 60s background, 120s doze
- **Battery Impact**: Higher battery usage for maximum responsiveness
- **Use when**: You need immediate notifications and have good battery life

#### Balanced Mode (Default)
- **Best for**: Most users seeking a good balance
- **Ping Intervals**: 30s foreground, 120s background, 300s doze
- **Battery Impact**: Moderate battery usage with good responsiveness
- **Use when**: You want reliable notifications without excessive battery drain

#### Battery Saver Mode
- **Best for**: Users prioritizing maximum battery life
- **Ping Intervals**: 60s foreground, 300s background, 600s doze
- **Battery Impact**: Minimal battery usage with delayed updates
- **Use when**: Battery life is critical and delayed notifications are acceptable

### Notification Settings

#### Notification Frequency
- **Immediate**: No rate limiting (can be overwhelming with high activity)
- **Moderate**: Rate limiting to prevent notification spam
- **Conservative**: Significant rate limiting for minimal interruption

#### Notification Behavior
- Events are forwarded to your configured target URI
- Notifications include NIP-19 nevent links for proper event identification
- Notification channels can be customized in Android settings

### Default Configuration

#### Default Relay Server
- Set a default relay that's automatically added to new subscriptions
- Recommended: `wss://relay.damus.io` (reliable and well-maintained)
- Can be overridden for each individual subscription

#### Default Event Viewer
- Set a default service for viewing events (used in nevent links)
- Default: `https://njump.me` (universal Nostr event viewer)
- Examples: `https://snort.social`, `https://iris.to`, `https://damus.io`

---

## Deep Link Integration

### Overview

PubSub supports deep link integration for seamless subscription registration from Progressive Web Apps using the `pubsub://register` scheme.

### Basic Deep Link Format

```
pubsub://register?label=[NAME]&relay=[RELAY_URL]&filter=[BASE64URL_ENCODED_JSON_FILTER]&uri=[TARGET_URI]
```

### Parameters

#### Required Parameters
- **`label`**: Descriptive name for the subscription
- **`filter`**: Base64url encoded JSON NostrFilter object (NIP-01 format)
- **`uri`**: Target URI where events should be forwarded
- **`relay`**: Relay URL (can be specified multiple times)

#### Optional Parameters  
- **`keyword`**: Content keyword to monitor (can be specified multiple times)
- **`excludeMentionsToSelf`**: `true` or `false` (default: `true`)
- **`excludeRepliesToEvents`**: `true` or `false` (default: `false`)

### Handling Registration Conflicts

When a deep link specifies a subscription name that already exists:
1. The app shows a dialog asking whether to update the existing subscription
2. Users can choose to update or create a new subscription with a different name
3. All parameters from the deep link are validated before applying

---

## Debug Console & Monitoring

### Understanding the Debug Console

The debug console provides real-time visibility into app operation and is essential for troubleshooting.

### Log Categories

#### Service Logs
- Service startup and shutdown events
- Component initialization status
- Configuration changes and updates

#### Network Logs  
- Relay connection attempts and results
- WebSocket connection health
- Reconnection attempts and backoff timing

#### Event Logs
- Incoming events from relays
- Filter matching results
- Event forwarding attempts
- Local filter application results

#### Battery Logs
- Power state changes (foreground/background/doze)
- Ping interval adjustments
- Battery optimization decisions

### Log Filtering

#### By Log Level
- Filter logs by importance (Trace, Debug, Info, Warning, Error)
- Higher levels show fewer, more important messages
- Lower levels show detailed operation information

#### By Domain
- **SERVICE**: Service lifecycle and management
- **NETWORK**: Connection and relay communication
- **EVENT**: Event processing and filtering
- **BATTERY**: Power management and optimization
- **SUBSCRIPTION**: Subscription management
- **UI**: User interface operations

### Performance Metrics

#### Connection Statistics
- **Active Connections**: Number of currently connected relays
- **Connection Uptime**: How long connections have been stable
- **Reconnection Count**: Number of reconnection attempts
- **Message Rate**: Events received per minute

#### Battery Statistics  
- **Current Mode**: Active battery optimization mode
- **Ping Intervals**: Current ping timing for different states
- **Wake Lock Usage**: Time spent with wake locks active
- **Battery Level Impact**: Estimated battery usage

#### Event Processing
- **Events Received**: Total events from all relays
- **Events Filtered**: Events that passed all filters
- **Events Forwarded**: Events successfully sent to target URIs
- **Duplicate Events**: Events caught by deduplication

### Exporting Debug Information

#### Log Export
1. Tap the export button in the debug console
2. Choose export format (text or JSON)
3. Select time range for export
4. Share or save the exported logs

#### Getting Help

**Before Reporting Issues**:
1. **Check Debug Console**: Gather relevant log messages
2. **Export Logs**: Create a log export covering the problem period  
3. **Document Steps**: Note exact steps to reproduce the issue
4. **Test Configuration**: Try with minimal configuration to isolate the problem

**Where to Get Help**:
- **GitHub Issues**: https://github.com/cmdruid/pubsub/issues
- **Documentation**: Check this user guide and README.md
- **Debug Console**: Often provides immediate insights into problems

---

## Conclusion

PubSub provides a powerful and flexible way to keep your Progressive Web Apps connected to the Nostr ecosystem. By following this guide, you should be able to:

- Set up reliable event monitoring with appropriate battery optimization
- Configure advanced filtering to receive only the events you care about
- Integrate seamlessly with your web applications using deep links
- Troubleshoot common issues using the debug console
- Optimize performance for your specific use case

### Key Takeaways

1. **Start Simple**: Begin with basic subscriptions and add complexity gradually
2. **Monitor Performance**: Use the debug console and metrics to understand app behavior
3. **Optimize for Your Needs**: Choose battery modes and filters that match your priorities
4. **Stay Updated**: Keep the app updated for the latest features and optimizations
5. **Backup Configurations**: Regular exports prevent data loss

### Getting More Help

- **Documentation**: README.md for technical details, AGENTS.md for development info
- **Source Code**: Full transparency at https://github.com/cmdruid/pubsub
- **Issues**: Report bugs or request features on GitHub
- **Privacy**: See PRIVACY.md for detailed privacy information

---

**Happy Nostr monitoring!** 🚀

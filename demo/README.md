# PubSub Demo PWA

A Progressive Web App demonstrating integration with the PubSub Nostr event monitoring Android app.

## Features

### 🔗 Deep Link Registration
- **Interactive Form** - Configure app name, target URI, relays, and filters
- **Filter Examples** - Pre-built filter templates for common use cases
- **One-Click Registration** - Generate and open pubsub:// deep links
- **Real-time Validation** - Validates JSON filters and URIs

### 📨 Event Handling
- **Event Reception** - Handles incoming events via URL parameters
- **Base64URL Decoding** - Automatically decodes event data
- **Event Display** - Shows event details in a readable format
- **Notification Support** - Browser notifications for new events
- **Event Logging** - Real-time logs of all activity

### 🎯 Filter Examples

The PWA includes several pre-configured filter examples:

1. **Text Notes** - Subscribe to all text notes (kind 1)
2. **Reactions** - Subscribe to reactions (kind 7) 
3. **Mentions** - Events mentioning a specific pubkey
4. **Hashtags** - Events with specific hashtags
5. **Recent** - Recent events since current time
6. **Custom** - Create your own filter

## How to Use

### 1. Setup
1. Open the PWA in a web browser
2. Configure your app name and target URI
3. Select relay URLs to monitor
4. Choose or create a Nostr filter

### 2. Registration
1. Click "Generate Registration Link" to create the deep link
2. Click "Open in PubSub App" to register with the Android app
3. In PubSub app, approve the registration
4. Enable the configuration and start the service

### 3. Testing
1. Use "Simulate Incoming Event" to test event handling locally
2. Monitor the event logs for real-time activity
3. Check that events are properly decoded and displayed

## Deep Link Format

The PWA generates deep links in this format:

```
pubsub://register?label=MyApp&uri=https://myapp.com/handle&relay=wss://relay1.com&relay=wss://relay2.com&filter=base64url_encoded_filter
```

### Parameters:
- **`label`** - Display name for the configuration
- **`uri`** - Target URI where events should be sent
- **`relay`** - Relay URLs (can have multiple)
- **`filter`** - Base64URL encoded NostrFilter JSON

## Event Reception

When the PubSub app finds matching events, it sends them to the PWA via URL parameters:

```
https://yourapp.com/handle?id=event_id&event=base64url_encoded_event_json
```

### Parameters:
- **`id`** - The Nostr event ID
- **`event`** - Complete event JSON (base64url encoded, omitted if > 500KB)

## Development

### Local Testing
1. Serve the PWA from a local web server
2. Use the target URI pointing to your local server
3. Test deep link generation and event simulation
4. Monitor browser console for detailed logs

### Production Deployment
1. Deploy the PWA to your web server
2. Update the target URI to your production URL
3. Test the complete flow with the PubSub Android app
4. Monitor real Nostr events from the configured relays

## Integration Examples

### JavaScript Integration
```javascript
// Generate a registration deep link
const filter = { kinds: [1], authors: ["pubkey_here"], limit: 50 };
const filterBase64 = btoa(JSON.stringify(filter))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');

const deepLink = `pubsub://register?` +
  `label=${encodeURIComponent("My App")}` +
  `&uri=${encodeURIComponent("https://myapp.com/handle")}` +
  `&relay=${encodeURIComponent("wss://relay.damus.io")}` +
  `&filter=${filterBase64}`;

// Open registration
window.location.href = deepLink;
```

### Event Handling
```javascript
// Parse incoming events
const urlParams = new URLSearchParams(window.location.search);
const eventId = urlParams.get('id');
const eventData = urlParams.get('event');

if (eventData) {
    const eventJson = atob(eventData.replace(/-/g, '+').replace(/_/g, '/'));
    const event = JSON.parse(eventJson);
    console.log('Received event:', event);
}
```

## Security Notes

- **User Approval Required** - All registrations require user confirmation in PubSub app
- **URI Validation** - All URIs are validated before registration
- **Filter Validation** - Filters must be valid NIP-01 compliant JSON
- **Size Limits** - Events larger than 500KB only include the ID parameter

## Troubleshooting

### Common Issues
1. **Deep link not opening** - Ensure PubSub app is installed
2. **Registration failed** - Check that all required parameters are provided
3. **Events not received** - Verify target URI is accessible and correct
4. **Filter not working** - Validate JSON syntax and NIP-01 compliance

### Debug Information
- Check the event logs in the PWA for detailed information
- Monitor the debug logs in the PubSub Android app
- Use browser developer tools to inspect network requests
- Test with the "Simulate Incoming Event" feature first

# Google Play Store Release Checklist

## ✅ Completed Items

- [x] Updated package name from `com.example.pubsub` to `com.frostr.pubsub`
- [x] Updated app name to "PubSub"
- [x] Added signing configuration template
- [x] Enhanced ProGuard configuration
- [x] Added privacy policy
- [x] Updated manifest with security settings
- [x] Created keystore generation script

## 🔄 Next Steps Required

### 1. App Signing (CRITICAL)
- [ ] Run `./generate_keystore.sh` to create your release keystore
- [ ] Update `app/build.gradle.kts` with your keystore details
- [ ] Uncomment the signing configuration lines
- [ ] Test signing with: `./gradlew assembleRelease`

### 2. Version Management
- [ ] Increment version code for each release
- [ ] Use semantic versioning for version name (1.0.0, 1.0.1, etc.)

### 3. App Store Listing Requirements

#### App Description (Required)
Create a compelling description highlighting:
- Nostr push notification functionality
- Background service capabilities
- Privacy-focused design
- Easy configuration

#### Screenshots (Required - minimum 2, maximum 8)
- Main configuration screen
- Service running notification
- Settings/subscription management
- Notification examples

#### Feature Graphic (Required)
- 1024 x 500 pixels
- Showcase app branding and key features

#### App Icon (Already present but verify)
- 512 x 512 pixels high-res icon
- Follows Material Design guidelines

### 4. Privacy & Compliance
- [ ] Update privacy policy with your contact information
- [ ] Host privacy policy on a public URL
- [ ] Ensure GDPR compliance if targeting EU users
- [ ] Review data safety section requirements

### 5. Testing
- [ ] Test on multiple Android versions (API 26+)
- [ ] Test background service functionality
- [ ] Test notification permissions on Android 13+
- [ ] Test deep linking functionality
- [ ] Verify ProGuard doesn't break functionality

### 6. Google Play Console Setup
- [ ] Create Google Play Console account ($25 one-time fee)
- [ ] Create new app listing
- [ ] Upload signed APK/AAB
- [ ] Complete store listing information
- [ ] Set up content rating questionnaire
- [ ] Configure pricing and distribution

### 7. Pre-Launch Checklist
- [ ] Enable Google Play App Signing (recommended)
- [ ] Set up crash reporting (Firebase Crashlytics recommended)
- [ ] Configure app updates strategy
- [ ] Plan rollout strategy (staged rollout recommended)

## 📋 Store Listing Content Template

### Short Description (80 characters max)
"Native Android app for Nostr push notifications with background monitoring"

### Full Description
```
Pushover delivers real-time Nostr event notifications directly to your Android device.

KEY FEATURES:
🔔 Real-time push notifications for Nostr events
🔄 Persistent background monitoring
🎯 Customizable event filtering
🔒 Privacy-focused - all data stays on your device
⚡ Battery optimized background service
🚀 Direct integration with Progressive Web Apps

PERFECT FOR:
• Nostr users who want instant notifications
• Developers building Nostr applications
• Users who need reliable event monitoring

PRIVACY FIRST:
All configuration data is stored locally on your device. No data is collected or shared with third parties.

TECHNICAL DETAILS:
• Supports WebSocket connections to any Nostr relay
• Configurable event filtering by public key
• Auto-restart after device reboot
• Follows Android background service best practices
```

### Keywords (for ASO)
nostr, push notifications, decentralized, social network, real-time, background service

## 🚨 Important Notes

1. **Keystore Security**: Your keystore file and passwords are critical. Losing them means you cannot update your app.

2. **Package Name**: Once published, you cannot change the package name (`com.frostr.pubsub`).

3. **Permissions**: Google Play reviews apps with sensitive permissions carefully. Document why each permission is needed.

4. **Target API**: Ensure you're targeting the latest API level required by Google Play.

5. **Testing**: Use Google Play Console's internal testing track before releasing to production.

## 🔧 Build Commands

```bash
# Generate keystore (run once)
./generate_keystore.sh

# Build production release
./build_release.sh

# Build beta version for closed testing
./build_beta.sh

# Manual Gradle commands:
./gradlew assembleRelease    # Production APK
./gradlew bundleRelease      # Production AAB
./gradlew assembleBeta       # Beta APK
./gradlew bundleBeta         # Beta AAB
./gradlew installRelease     # Install production
./gradlew installBeta        # Install beta
```

## 🧪 Beta Testing Setup

### Build Variants
- **Debug**: `com.frostr.pubsub.debug` - Development builds
- **Beta**: `com.frostr.pubsub.beta` - Closed beta testing
- **Release**: `com.frostr.pubsub` - Production release

### Beta Distribution Options

#### Option 1: Google Play Console Internal Testing
1. Build beta AAB: `./build_beta.sh`
2. Upload `app-beta.aab` to Google Play Console
3. Create internal testing release
4. Add testers by email address
5. Share internal testing link with testers

#### Option 2: Direct APK Distribution
1. Build beta APK: `./build_beta.sh`
2. Share `app-beta.apk` with testers
3. Testers enable "Install from unknown sources"
4. Beta app installs alongside production app

### Beta Testing Benefits
- ✅ Separate package ID allows side-by-side installation
- ✅ Same optimization as production build
- ✅ Easy to identify with "Beta" suffix in app name
- ✅ Can test updates without affecting production app

## 📞 Support

For Google Play Store specific issues:
- [Google Play Console Help](https://support.google.com/googleplay/android-developer/)
- [Android Developer Documentation](https://developer.android.com/distribute/google-play)

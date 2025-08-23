#!/bin/bash

# Script to build beta version of PubSub app for closed beta testing
# This creates a separate app that can be installed alongside the production version

echo "=== PubSub Beta Build ==="
echo ""

# Check if keystore exists
if [ ! -f "pubsub-release.keystore" ]; then
    echo "⚠️  Keystore not found!"
    echo "Run ./generate_keystore.sh first to create your signing keystore."
    echo ""
    read -p "Do you want to generate the keystore now? (y/n): " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        ./generate_keystore.sh
    else
        echo "❌ Cannot build beta without keystore. Exiting."
        exit 1
    fi
fi

echo "🔧 Building beta version..."
echo ""
echo "ℹ️  Beta build features:"
echo "   - Package ID: com.cmdruid.pubsub.beta"
echo "   - App name: PubSub Beta"
echo "   - Can be installed alongside production version"
echo "   - Same optimization as release build"
echo ""

# Clean previous builds
echo "🧹 Cleaning previous builds..."
./gradlew clean

# Build beta AAB (for Google Play Console internal testing)
echo "📦 Building beta AAB..."
./gradlew bundleBeta

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Beta AAB built successfully!"
    echo "📍 Location: app/build/outputs/bundle/beta/app-beta.aab"
    echo ""
    
    # Also build beta APK for direct distribution
    echo "📦 Building beta APK..."
    ./gradlew assembleBeta
    
    if [ $? -eq 0 ]; then
        echo ""
        echo "✅ Beta APK built successfully!"
        echo "📍 Location: app/build/outputs/apk/beta/app-beta.apk"
        echo ""
        echo "🎉 Beta build complete!"
        echo ""
        echo "📋 Next steps for closed beta:"
        echo ""
        echo "🔹 Google Play Console Internal Testing:"
        echo "   1. Upload app-beta.aab to Google Play Console"
        echo "   2. Create internal testing release"
        echo "   3. Add beta testers by email"
        echo "   4. Share the internal testing link"
        echo ""
        echo "🔹 Direct APK Distribution:"
        echo "   1. Share app-beta.apk directly with testers"
        echo "   2. Testers need to enable 'Install from unknown sources'"
        echo "   3. Beta app installs alongside production app"
        echo ""
        echo "🔹 Testing Commands:"
        echo "   - Install beta APK: adb install app/build/outputs/apk/beta/app-beta.apk"
        echo "   - Check installed: adb shell pm list packages | grep pubsub"
        echo ""
    else
        echo "❌ Failed to build beta APK"
        exit 1
    fi
else
    echo "❌ Failed to build beta AAB"
    exit 1
fi

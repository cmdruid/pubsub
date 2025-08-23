#!/bin/bash

# Script to build release version of PubSub app
# Make sure you have configured your keystore first!

echo "=== PubSub Release Build ==="
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
        echo "❌ Cannot build release without keystore. Exiting."
        exit 1
    fi
fi

echo "🔧 Building release version..."
echo ""

# Clean previous builds
echo "🧹 Cleaning previous builds..."
./gradlew clean

# Build release AAB (recommended for Play Store)
echo "📦 Building release AAB..."
./gradlew bundleRelease

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Release AAB built successfully!"
    echo "📍 Location: app/build/outputs/bundle/release/app-release.aab"
    echo ""
    
    # Also build APK for testing
    echo "📦 Building release APK for testing..."
    ./gradlew assembleRelease
    
    if [ $? -eq 0 ]; then
        echo ""
        echo "✅ Release APK built successfully!"
        echo "📍 Location: app/build/outputs/apk/release/app-release.apk"
        echo ""
        echo "🎉 Build complete!"
        echo ""
        echo "📋 Next steps:"
        echo "1. Test the APK on your device: adb install app/build/outputs/apk/release/app-release.apk"
        echo "2. Upload the AAB file to Google Play Console"
        echo "3. Complete your store listing information"
        echo ""
        echo "💡 For beta testing, use: ./build_beta.sh"
        echo ""
    else
        echo "❌ Failed to build release APK"
        exit 1
    fi
else
    echo "❌ Failed to build release AAB"
    exit 1
fi

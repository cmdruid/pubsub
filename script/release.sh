#!/bin/bash

# PubSub Android Release Script
# Usage: ./script/release.sh [version] [--skip-build] [--skip-tag]
# If no version is provided, it will read from app/build.gradle.kts

set -e  # Exit on any error

# Default values
SKIP_BUILD=false
SKIP_TAG=false
VERSION=""

# Parse command line arguments
for arg in "$@"; do
    case $arg in
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --skip-tag)
            SKIP_TAG=true
            shift
            ;;
        --help|-h)
            echo "PubSub Android Release Script"
            echo ""
            echo "Usage: ./script/release.sh [version] [options]"
            echo ""
            echo "Arguments:"
            echo "  version                 Version number (e.g., 1.0.0). If not provided, reads from build.gradle.kts"
            echo ""
            echo "Options:"
            echo "  --skip-build            Skip the build process, only create/push git tag"
            echo "  --skip-tag              Skip git tag creation, only build"
            echo "  --help, -h              Show this help message"
            echo ""
            echo "Examples:"
            echo "  ./script/release.sh 1.2.0                    # Release build with version 1.2.0"
            echo "  ./script/release.sh --skip-build             # Only create git tag"
            echo "  ./script/release.sh 1.3.0 --skip-tag        # Build only, no git tag"
            exit 0
            ;;
        -*)
            echo "❌ Unknown option: $arg"
            echo "Use --help for usage information"
            exit 1
            ;;
        *)
            if [ -z "$VERSION" ]; then
                VERSION="$arg"
            else
                echo "❌ Multiple version arguments provided"
                exit 1
            fi
            ;;
    esac
done

echo "=== PubSub Android Release ==="
echo ""

# Check if we're in a git repository with origin remote
if [ "$SKIP_TAG" = false ]; then
    if ! git rev-parse --git-dir &> /dev/null; then
        echo "❌ Not in a git repository"
        echo "   Make sure you're in a git repository"
        exit 1
    fi

    if ! git remote get-url origin &> /dev/null; then
        echo "❌ No 'origin' remote found"
        echo "   Make sure you have an 'origin' remote configured"
        exit 1
    fi
fi

# Function to get version from build.gradle.kts
get_gradle_version() {
    grep -o 'versionName = "[^"]*"' app/build.gradle.kts | sed 's/versionName = "\(.*\)"/\1/'
}

# Function to check if tag exists remotely
check_tag_exists() {
    local tag=$1
    
    # Check if tag exists on remote
    if git ls-remote --tags origin | grep -q "refs/tags/$tag$"; then
        return 0  # Tag exists
    else
        return 1  # Tag doesn't exist
    fi
}

# Function to update version in build.gradle.kts
update_gradle_version() {
    local new_version=$1
    echo "📝 Updating version in app/build.gradle.kts to $new_version"
    sed -i "s/versionName = \".*\"/versionName = \"$new_version\"/" app/build.gradle.kts
}

# Function to check and setup keystore
check_keystore() {
    if [ ! -f "pubsub-release.keystore" ]; then
        echo "⚠️  Keystore not found!"
        echo "   Run ./script/generate_keystore.sh first to create your signing keystore."
        echo ""
        read -p "Do you want to generate the keystore now? (y/n): " -n 1 -r
        echo ""
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            ./script/generate_keystore.sh
        else
            echo "❌ Cannot build release without keystore. Exiting."
            exit 1
        fi
    fi
}

# Function to build release APK/AAB
build_release() {
    echo "🔧 Building release version..."
    echo ""
    
    check_keystore
    
    # Clean previous builds
    echo "🧹 Cleaning previous builds..."
    ./gradlew clean
    
    # Build release AAB (recommended for Play Store)
    echo "📦 Building release AAB..."
    ./gradlew bundleRelease
    
    # Build release APK
    echo "📦 Building release APK..."
    ./gradlew assembleRelease
    
    echo ""
    echo "✅ Release build completed!"
    echo "📍 AAB: app/build/outputs/bundle/release/app-release.aab"
    echo "📍 APK: app/build/outputs/apk/release/app-release.apk"
}

# Get version
if [ -z "$VERSION" ]; then
    VERSION=$(get_gradle_version)
    if [ -z "$VERSION" ]; then
        echo "❌ Failed to read version from app/build.gradle.kts"
        exit 1
    fi
    echo "📦 Using version from build.gradle.kts: $VERSION"
else
    echo "📦 Using provided version: $VERSION"
    if [ "$SKIP_BUILD" = false ]; then
        update_gradle_version "$VERSION"
    fi
fi

# Build phase
if [ "$SKIP_BUILD" = false ]; then
    build_release
    
    echo ""
    echo "📋 Next steps:"
    echo "   1. Test APK: adb install app/build/outputs/apk/release/app-release.apk"
    echo "   2. Upload AAB to Google Play Console for distribution:"
    echo "      • Internal testing for quick validation"
    echo "      • Closed testing for beta users" 
    echo "      • Open testing for wider beta"
    echo "      • Production for public release"
    echo ""
fi

# Git tag phase
if [ "$SKIP_TAG" = false ]; then
    TAG="v$VERSION"
    
    # Check if tag already exists remotely
    echo "🔍 Checking if tag $TAG already exists on remote..."
    if check_tag_exists "$TAG"; then
        echo "❌ Tag $TAG already exists on remote!"
        exit 1
    fi
    
    echo "✅ No existing tag found for $TAG"
    echo "🏷️  Creating and pushing tag: $TAG"
    
    # Create and push tag
    git tag "$TAG" && git push origin "$TAG"
    
    if [ $? -eq 0 ]; then
        echo "✅ Successfully created and pushed tag: $TAG"
        echo "🚀 GitHub Action should now be running for the release"
    else
        echo "❌ Failed to create/push tag"
        exit 1
    fi
fi

echo ""
echo "🎉 Release process completed!" 
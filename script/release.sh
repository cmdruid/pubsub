#!/bin/bash

# PubSub Android Release Script
# Usage: ./script/release.sh [version] [--skip-build] [--skip-tag]
# If no version is provided, it will read from app/build.gradle.kts

set -e  # Exit on any error

# Default values
SKIP_BUILD=false
SKIP_TAG=false
FORCE_TAG=false
GITHUB_ONLY=false
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
        --force-tag)
            FORCE_TAG=true
            shift
            ;;
        --github-only)
            GITHUB_ONLY=true
            SKIP_BUILD=true
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
            echo "  --force-tag             Force recreate tag if it already exists (non-interactive)"
            echo "  --github-only           Skip local build, only create tag for GitHub Actions"
            echo "  --help, -h              Show this help message"
            echo ""
            echo "Examples:"
            echo "  ./script/release.sh 1.2.0                    # Release build with version 1.2.0"
            echo "  ./script/release.sh --skip-build             # Only create git tag"
            echo "  ./script/release.sh 1.3.0 --skip-tag        # Build only, no git tag"
            echo "  ./script/release.sh 0.9.1 --force-tag       # Force recreate existing tag"
            echo "  ./script/release.sh 0.9.1 --github-only     # Only create tag, let GitHub build"
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
        echo "   Run ./script/keystore.sh first to create your signing keystore."
        echo ""
        read -p "Do you want to generate the keystore now? (y/n): " -n 1 -r
        echo ""
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            ./script/keystore.sh
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
    
    # Find the actual APK/AAB files with our custom naming
    AAB_FILE=$(find app/build/outputs/bundle/release -name "pubsub-v*-release.aab" | head -1)
    APK_FILE=$(find app/build/outputs/apk/release -name "pubsub-v*-release.apk" | head -1)
    
    if [ -n "$AAB_FILE" ]; then
        echo "📍 AAB: $AAB_FILE"
    else
        echo "⚠️  AAB: app/build/outputs/bundle/release/ (custom naming - check directory)"
    fi
    
    if [ -n "$APK_FILE" ]; then
        echo "📍 APK: $APK_FILE"
    else
        echo "⚠️  APK: app/build/outputs/apk/release/ (custom naming - check directory)"
    fi
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
elif [ "$GITHUB_ONLY" = true ]; then
    echo "⏭️  Skipping local build (--github-only specified)"
    echo "   GitHub Actions will build and sign the APK automatically"
    
    echo ""
    echo "📋 Next steps:"
    echo "   1. Test APK: adb install app/build/outputs/apk/release/pubsub-v$VERSION-release.apk"
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
        echo "⚠️  Tag $TAG already exists on remote!"
        
        if [ "$FORCE_TAG" = true ]; then
            echo "🔄 --force-tag specified, recreating tag..."
            echo "🗑️  Deleting existing tag locally and remotely..."
            git tag -d "$TAG" 2>/dev/null || true
            git push origin --delete "$TAG" 2>/dev/null || true
            echo "🏷️  Will recreate and push tag: $TAG"
        else
            echo ""
            echo "This could mean:"
            echo "  1. Release was already completed successfully"
            echo "  2. GitHub Actions failed after tag creation"
            echo "  3. Tag was created manually"
            echo ""
            echo "Options:"
            echo "  [r] Recreate tag (delete and push again) - triggers new GitHub Actions"
            echo "  [s] Skip tag creation (build only)"
            echo "  [a] Abort release process"
            echo ""
            read -p "What would you like to do? (r/s/a): " -n 1 -r
            echo ""
            
            case $REPLY in
                [Rr])
                    echo "🗑️  Deleting existing tag locally and remotely..."
                    git tag -d "$TAG" 2>/dev/null || true
                    git push origin --delete "$TAG" 2>/dev/null || true
                    echo "🏷️  Recreating and pushing tag: $TAG"
                    ;;
                [Ss])
                    echo "⏭️  Skipping tag creation..."
                    SKIP_TAG=true
                    ;;
                [Aa]|*)
                    echo "❌ Release aborted."
                    exit 1
                    ;;
            esac
        fi
    fi
    
    if [ "$SKIP_TAG" = false ]; then
        if check_tag_exists "$TAG"; then
            echo "🏷️  Recreating and pushing tag: $TAG"
        else
            echo "✅ No existing tag found for $TAG"
            echo "🏷️  Creating and pushing tag: $TAG"
        fi
        
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
fi

echo ""
echo "🎉 Release process completed!" 
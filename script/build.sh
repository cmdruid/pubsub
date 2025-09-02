#!/bin/bash

# build.sh - Build debug APK for PubSub Android app
# Usage: ./script/build.sh [--clean]
# Builds a debug version of the APK and places it in the dist/ folder for sideloading

set -e  # Exit on any error

# Default values
CLEAN_BUILD=false

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[0;33m'
NC='\033[0m'

info() { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }
warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }

# Show usage
show_usage() {
    echo "PubSub Android Debug Build Script"
    echo ""
    echo "Usage: ./script/build.sh [options]"
    echo ""
    echo "Options:"
    echo "  --clean         Clean build (remove previous build artifacts)"
    echo "  --help, -h      Show this help message"
    echo ""
    echo "Description:"
    echo "  Builds a debug version of the PubSub Android app and places the APK"
    echo "  in the dist/ folder for easy sideloading to devices."
    echo ""
    echo "Output:"
    echo "  dist/pubsub-<version>-debug.apk"
    echo ""
    echo "Examples:"
    echo "  ./script/build.sh           # Standard debug build"
    echo "  ./script/build.sh --clean   # Clean build from scratch"
}

# Parse command line arguments
for arg in "$@"; do
    case $arg in
        --clean)
            CLEAN_BUILD=true
            shift
            ;;
        --help|-h)
            show_usage
            exit 0
            ;;
        -*)
            error "Unknown option: $arg"
            echo "Use --help for usage information"
            exit 1
            ;;
        *)
            error "Unexpected argument: $arg"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

echo "========================================"
echo "   PubSub Android Debug Build"
echo "========================================"
echo ""

# Check if we're in the right directory
if [ ! -f "app/build.gradle.kts" ]; then
    error "app/build.gradle.kts not found"
    error "Make sure you're running this script from the project root directory"
    exit 1
fi

# Check if gradlew exists
if [ ! -f "./gradlew" ]; then
    error "gradlew not found"
    error "Make sure you're in the project root directory"
    exit 1
fi

# Make gradlew executable if it isn't already
if [ ! -x "./gradlew" ]; then
    info "Making gradlew executable..."
    chmod +x ./gradlew
fi

# Get version from build.gradle.kts
get_version() {
    grep -o 'versionName = "[^"]*"' app/build.gradle.kts | sed 's/versionName = "\(.*\)"/\1/'
}

VERSION=$(get_version)
if [ -z "$VERSION" ]; then
    error "Failed to read version from app/build.gradle.kts"
    exit 1
fi

info "Building debug APK for version: $VERSION"
echo ""

# Clean build if requested
if [ "$CLEAN_BUILD" = true ]; then
    info "Cleaning previous builds..."
    ./gradlew clean
    echo ""
fi

# Build debug APK
info "Building debug APK..."
./gradlew assembleDebug

if [ $? -ne 0 ]; then
    error "Build failed!"
    exit 1
fi

echo ""
success "Debug build completed!"

# Create dist directory if it doesn't exist
DIST_DIR="dist"
if [ ! -d "$DIST_DIR" ]; then
    info "Creating dist/ directory..."
    mkdir -p "$DIST_DIR"
fi

# Find the built APK
APK_SOURCE_DIR="app/build/outputs/apk/debug"
APK_FILE=$(find "$APK_SOURCE_DIR" -name "*.apk" | head -1)

if [ -z "$APK_FILE" ] || [ ! -f "$APK_FILE" ]; then
    error "Debug APK not found in $APK_SOURCE_DIR"
    error "Build may have failed or APK location changed"
    exit 1
fi

# Get the APK filename and copy to dist/
APK_FILENAME=$(basename "$APK_FILE")
DEST_APK="$DIST_DIR/$APK_FILENAME"

info "Copying APK to dist/ folder..."
cp "$APK_FILE" "$DEST_APK"

if [ $? -eq 0 ]; then
    success "APK copied successfully!"
    echo ""
    echo "📱 Debug APK ready for sideloading:"
    echo "   📍 Location: $DEST_APK"
    echo "   📦 Version: $VERSION-debug"
    echo ""
    echo "📋 To install on device:"
    echo "   1. Enable 'Developer options' and 'USB debugging' on your device"
    echo "   2. Connect device via USB"
    echo "   3. Run: adb install \"$DEST_APK\""
    echo "   4. Or transfer the APK file to your device and install manually"
    echo ""
    echo "💡 Pro tip: You can also drag and drop the APK file onto an emulator"
    echo ""
else
    error "Failed to copy APK to dist/ folder"
    exit 1
fi

# Show file size
if command -v du >/dev/null 2>&1; then
    APK_SIZE=$(du -h "$DEST_APK" | cut -f1)
    info "APK size: $APK_SIZE"
fi

echo "🎉 Build process completed successfully!"

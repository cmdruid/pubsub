#!/bin/bash

# install.sh - Build and install PubSub Android app
# Quick script to build debug APK and install it on connected device

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if adb is available
check_adb() {
    if ! command -v adb &> /dev/null; then
        print_error "adb not found. Please install Android SDK and add it to your PATH."
        exit 1
    fi
}

# Function to check if any device is connected
check_device() {
    print_status "Checking for connected devices..."
    
    local device_count=$(adb devices | grep -v "List of devices" | grep -v "^$" | grep "device$" | wc -l)
    
    if [ "$device_count" -eq 0 ]; then
        print_error "No devices found. Please ensure:"
        print_error "  1. An emulator is running, or"
        print_error "  2. A physical device is connected with USB debugging enabled"
        print_error ""
        print_status "Available devices:"
        adb devices
        exit 1
    fi
    
    print_success "Found $device_count connected device(s)"
    adb devices | grep "device$"
}

# Function to build debug APK
build_debug() {
    print_status "Building debug version of the app..."
    
    # Clean and build debug APK
    ./gradlew clean assembleDebug
    
    if [ $? -eq 0 ]; then
        print_success "Debug APK built successfully!"
        local apk_path="app/build/outputs/apk/debug/app-debug.apk"
        if [ -f "$apk_path" ]; then
            print_status "APK location: $apk_path"
            return 0
        else
            print_error "APK file not found at expected location: $apk_path"
            exit 1
        fi
    else
        print_error "Failed to build debug APK"
        exit 1
    fi
}

# Function to install APK on device
install_apk() {
    print_status "Installing APK on device..."
    
    local apk_path="app/build/outputs/apk/debug/app-debug.apk"
    
    if [ ! -f "$apk_path" ]; then
        print_error "APK file not found: $apk_path"
        exit 1
    fi
    
    # Install APK
    adb install -r "$apk_path"
    
    if [ $? -eq 0 ]; then
        print_success "APK installed successfully!"
        print_status "App package: com.cmdruid.pubsub.debug"
    else
        print_error "Failed to install APK"
        exit 1
    fi
}

# Function to launch the app
launch_app() {
    print_status "Launching the app..."
    adb shell am start -n com.cmdruid.pubsub.debug/.MainActivity
    
    if [ $? -eq 0 ]; then
        print_success "App launched successfully!"
    else
        print_warning "Failed to launch app automatically. You can launch it manually from the device."
    fi
}

# Main script execution
main() {
    echo "========================================="
    echo "    PubSub Android Install Helper"
    echo "========================================="
    echo ""
    
    # Check dependencies
    check_adb
    
    # Check for connected devices
    check_device
    
    echo ""
    
    # Build debug APK
    build_debug
    
    echo ""
    
    # Install APK
    install_apk
    
    echo ""
    print_success "Install complete!"
    print_status "To launch the app: adb shell am start -n com.cmdruid.pubsub.debug/.MainActivity"
}

# Run main function
main "$@"

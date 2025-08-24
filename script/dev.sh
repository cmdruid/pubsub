#!/bin/bash

# dev.sh - Development helper script for PubSub Android app
# Checks for running AVD, starts one if needed, builds debug app, and installs it

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

# Function to check if Android SDK tools are available
check_android_tools() {
    print_status "Checking Android SDK tools..."
    
    if ! command -v adb &> /dev/null; then
        print_error "adb not found. Please install Android SDK and add it to your PATH."
        exit 1
    fi
    
    if ! command -v emulator &> /dev/null; then
        print_error "emulator not found. Please install Android SDK and add it to your PATH."
        exit 1
    fi
    
    print_success "Android SDK tools found"
}

# Function to check if any AVD is currently running
check_avd_running() {
    print_status "Checking for running Android Virtual Devices..."
    
    # Check if any emulator is running by looking for adb devices
    local running_devices=$(adb devices | grep -v "List of devices" | grep -v "^$" | wc -l)
    
    if [ "$running_devices" -gt 0 ]; then
        print_success "Found $running_devices running device(s)"
        adb devices
        return 0
    else
        print_warning "No running Android devices found"
        return 1
    fi
}

# Function to list available AVDs
list_avds() {
    print_status "Listing available AVDs..."
    local avd_list=$(emulator -list-avds)
    
    if [ -z "$avd_list" ]; then
        print_error "No AVDs found. Please create an AVD using Android Studio or avdmanager."
        print_error "You can create one with: avdmanager create avd -n MyAVD -k \"system-images;android-34;google_apis;x86_64\""
        exit 1
    fi
    
    echo "$avd_list"
    return 0
}

# Function to start an AVD
start_avd() {
    print_status "Starting an Android Virtual Device..."
    
    # Get list of available AVDs
    local avd_list=$(emulator -list-avds)
    
    if [ -z "$avd_list" ]; then
        print_error "No AVDs available to start"
        exit 1
    fi
    
    # Use the first available AVD
    local first_avd=$(echo "$avd_list" | head -n 1)
    print_status "Starting AVD: $first_avd"
    
    # Start emulator in background with hardware acceleration
    emulator -avd "$first_avd" -no-snapshot-save -no-snapshot-load -accel kvm -gpu auto > /dev/null 2>&1 &
    local emulator_pid=$!
    
    print_status "Emulator started (PID: $emulator_pid). Waiting for device to be ready..."
    
    # Wait for device to be ready (max 60 seconds)
    local timeout=60
    local elapsed=0
    
    while [ $elapsed -lt $timeout ]; do
        if adb devices | grep -q "device$"; then
            print_success "AVD is ready!"
            # Additional wait to ensure device is fully booted
            print_status "Waiting for device to fully boot..."
            sleep 10
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
        echo -n "."
    done
    
    echo ""
    print_error "Timeout waiting for AVD to start"
    exit 1
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

# Main script execution
main() {
    echo "========================================="
    echo "  PubSub Android Development Helper"
    echo "========================================="
    echo ""
    
    # Check Android tools
    check_android_tools
    
    # Check if AVD is running, start one if not
    if ! check_avd_running; then
        list_avds
        echo ""
        start_avd
    fi
    
    echo ""
    
    # Build debug APK
    build_debug
    
    echo ""
    
    # Install APK
    install_apk
    
    echo ""
    print_success "Development setup complete!"
    print_status "You can now test your app on the emulator."
    print_status "To launch the app: adb shell am start -n com.cmdruid.pubsub.debug/.MainActivity"
}

# Run main function
main "$@"

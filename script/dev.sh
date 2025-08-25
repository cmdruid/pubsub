#!/bin/bash

# dev.sh - Start Android emulator for PubSub development
# Simple script to ensure an emulator is running and ready

set -e

# Global variables
EMULATOR_PID=""
AVD_NAME=""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

info() { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Cleanup on exit
cleanup() {
    echo ""
    info "Shutting down emulator..."
    
    if [ -n "$EMULATOR_PID" ] && kill -0 "$EMULATOR_PID" 2>/dev/null; then
        adb -s emulator-5554 emu kill 2>/dev/null || true
        sleep 2
        kill -TERM "$EMULATOR_PID" 2>/dev/null || true
        sleep 3
        kill -KILL "$EMULATOR_PID" 2>/dev/null || true
        
        # Clean up any lingering QEMU processes
        if [ -n "$AVD_NAME" ]; then
            pgrep -f "qemu-system.*$AVD_NAME" | xargs -r kill -KILL 2>/dev/null || true
        fi
    fi
    
    echo "Emulator stopped."
    exit 0
}

trap cleanup SIGINT SIGTERM EXIT

# Check tools
check_tools() {
    command -v adb >/dev/null || { error "adb not found. Install Android SDK."; exit 1; }
    command -v emulator >/dev/null || { error "emulator not found. Install Android SDK."; exit 1; }
}

# Check if device is running
device_running() {
    adb devices | grep -q "device$"
}

# Start emulator
start_emulator() {
    info "Starting Android emulator..."
    
    # Get first available AVD
    AVD_NAME=$(emulator -list-avds | head -n 1)
    [ -z "$AVD_NAME" ] && { error "No AVDs found. Create one in Android Studio."; exit 1; }
    
    info "Starting AVD: $AVD_NAME"
    export QT_QPA_PLATFORM=xcb
    
    emulator -avd "$AVD_NAME" -no-snapshot-save -no-snapshot-load -accel auto -gpu swiftshader_indirect &
    EMULATOR_PID=$!
    
    sleep 3
    kill -0 "$EMULATOR_PID" 2>/dev/null || { error "Emulator failed to start"; exit 1; }
    
    # Wait for device
    info "Waiting for emulator to be ready..."
    local timeout=120
    local elapsed=0
    
    while [ $elapsed -lt $timeout ]; do
        if device_running; then
            success "Emulator ready! (Press 'q' to quit)"
            adb wait-for-device
            sleep 3
            return 0
        fi
        
        echo -n "."
        sleep 3
        elapsed=$((elapsed + 3))
        
        [ $((elapsed % 30)) -eq 0 ] && echo "" && info "Still waiting... (${elapsed}/${timeout}s)"
    done
    
    error "Timeout waiting for emulator"
    exit 1
}

# Keep emulator running silently
keep_running() {
    # Wait silently for 'q' to quit
    while true; do
        read -n 1 -s key
        case "$key" in
            'q'|'Q')
                cleanup
                ;;
            *)
                # Ignore other keys silently
                ;;
        esac
    done
}

# Main
main() {
    echo "========================================"
    echo "   PubSub Android Development Helper"
    echo "========================================"
    echo ""
    
    check_tools
    info "Checking for running devices..."
    
    if device_running; then
        success "Device already running! (Press 'q' to quit)"
        adb devices | grep "device$"
    else
        info "No devices found"
        start_emulator
    fi
    
    keep_running
}

main "$@"
#!/bin/bash

# dev.sh - Start Android emulator for PubSub development
# Simple script to ensure an emulator is running and ready

set -e

# Global variables
EMULATOR_PID=""
AVD_NAME=""
PORTS=()

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

info() { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Show usage
show_usage() {
    echo "Usage: $0 [-p PORT1,PORT2,...] [-k] [-h]"
    echo ""
    echo "Options:"
    echo "  -p PORTS    Comma-separated list of ports to forward (e.g., -p 3000,8080)"
    echo "  -k          Kill all running emulators and exit"
    echo "  -h          Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                    # No port forwarding"
    echo "  $0 -p 3000           # Forward port 3000"
    echo "  $0 -p 3000,8080      # Forward ports 3000 and 8080"
    echo "  $0 -k                 # Kill all emulators"
}

# Cleanup on exit
cleanup() {
    echo ""
    info "Shutting down emulator..."
    
    # Step 1: Try graceful shutdown via ADB
    info "Attempting graceful shutdown..."
    adb devices | grep "emulator-" | awk '{print $1}' | while read device; do
        adb -s "$device" emu kill 2>/dev/null || true
    done
    sleep 3
    
    # Step 2: Kill emulator process if still running
    if [ -n "$EMULATOR_PID" ] && kill -0 "$EMULATOR_PID" 2>/dev/null; then
        info "Force terminating emulator process..."
        kill -TERM "$EMULATOR_PID" 2>/dev/null || true
        sleep 2
        
        # If still running, force kill
        if kill -0 "$EMULATOR_PID" 2>/dev/null; then
            kill -KILL "$EMULATOR_PID" 2>/dev/null || true
            sleep 1
        fi
    fi
    
    # Step 3: Clean up any lingering processes
    info "Cleaning up lingering processes..."
    
    # Kill all QEMU processes related to this AVD
    if [ -n "$AVD_NAME" ]; then
        pgrep -f "qemu-system.*$AVD_NAME" | xargs -r kill -KILL 2>/dev/null || true
    fi
    
    # Kill any remaining emulator processes
    pgrep -f "emulator.*-avd" | xargs -r kill -KILL 2>/dev/null || true
    
    # Kill any remaining adb processes that might be stuck
    pgrep -f "adb.*emulator" | xargs -r kill -KILL 2>/dev/null || true
    
    # Step 4: Clear port forwards
    info "Clearing port forwards..."
    adb reverse --remove-all 2>/dev/null || true
    
    success "Emulator shutdown complete."
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
    export ANDROID_EMULATOR_USE_SYSTEM_LIBS=1
    export QT_X11_NO_MITSHM=1
    export _JAVA_AWT_WM_NONREPARENTING=1
    
    emulator -avd "$AVD_NAME" -no-snapshot-save -no-snapshot-load -accel auto -gpu swiftshader_indirect -memory 4096 -partition-size 2048 -cores 2 -verbose &
    EMULATOR_PID=$!
    
    sleep 5
    kill -0 "$EMULATOR_PID" 2>/dev/null || { error "Emulator failed to start"; exit 1; }
    
    # Wait for device
    info "Waiting for emulator to be ready..."
    local timeout=120
    local elapsed=0
    
    while [ $elapsed -lt $timeout ]; do
        if device_running; then
            success "Emulator ready! Setting up port forwarding..."
            adb wait-for-device
            sleep 3
            
            # Set up reverse port forwarding
            setup_port_forwarding
            success "Ready! (Press 'q' to quit)"
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

# Set up port forwarding
setup_port_forwarding() {
    if [ ${#PORTS[@]} -eq 0 ]; then
        info "No ports specified for forwarding"
        return 0
    fi
    
    info "Setting up port forwarding for ports: ${PORTS[*]}"
    for port in "${PORTS[@]}"; do
        if adb reverse tcp:$port tcp:$port; then
            success "Port $port forwarded successfully"
        else
            error "Failed to forward port $port"
        fi
    done
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

# Force kill all emulators
force_kill_emulators() {
    info "Force killing all running emulators..."
    
    # Kill via ADB first
    adb devices | grep "emulator-" | awk '{print $1}' | while read device; do
        info "Killing emulator: $device"
        adb -s "$device" emu kill 2>/dev/null || true
    done
    sleep 2
    
    # Force kill all emulator processes
    pgrep -f "emulator.*-avd" | while read pid; do
        info "Force killing emulator process: $pid"
        kill -KILL "$pid" 2>/dev/null || true
    done
    
    # Force kill all QEMU processes
    pgrep -f "qemu-system" | while read pid; do
        info "Force killing QEMU process: $pid"
        kill -KILL "$pid" 2>/dev/null || true
    done
    
    # Clear all port forwards
    adb reverse --remove-all 2>/dev/null || true
    
    success "All emulators killed."
}

# Parse command line arguments
parse_args() {
    while getopts "p:kh" opt; do
        case $opt in
            p)
                IFS=',' read -ra PORTS <<< "$OPTARG"
                ;;
            k)
                force_kill_emulators
                exit 0
                ;;
            h)
                show_usage
                exit 0
                ;;
            \?)
                error "Invalid option: -$OPTARG"
                show_usage
                exit 1
                ;;
        esac
    done
}

# Main
main() {
    parse_args "$@"
    
    echo "========================================"
    echo "   PubSub Android Development Helper"
    echo "========================================"
    echo ""
    
    if [ ${#PORTS[@]} -gt 0 ]; then
        info "Ports to forward: ${PORTS[*]}"
    else
        info "No port forwarding configured"
    fi
    echo ""
    
    check_tools
    info "Checking for running devices..."
    
    if device_running; then
        success "Device already running! Setting up port forwarding..."
        adb devices | grep "device$"
        
        # Set up reverse port forwarding
        setup_port_forwarding
        success "Ready! (Press 'q' to quit)"
    else
        info "No devices found"
        start_emulator
    fi
    
    keep_running
}

main "$@"
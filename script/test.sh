#!/bin/bash

# test.sh - Run all tests for PubSub Android app
# Comprehensive test runner with different test types and reporting

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

info() { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }
warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }

# Global variables
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
FAILED_TESTS=()
TOTAL_TESTS=0
PASSED_TESTS=0

# Show usage
show_usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Run tests for PubSub Android app"
    echo ""
    echo "Options:"
    echo "  -u, --unit           Run only unit tests"
    echo "  -i, --instrumented   Run only instrumented tests (requires device/emulator)"
    echo "  -l, --lint           Run only lint checks"
    echo "  -c, --clean          Clean build before running tests"
    echo "  -r, --report         Generate HTML test reports"
    echo "  -v, --verbose        Verbose output"
    echo "  -h, --help           Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0                   # Run all tests"
    echo "  $0 -u                # Run only unit tests"
    echo "  $0 -c -r             # Clean build and generate reports"
    echo "  $0 -i                # Run instrumented tests (device required)"
}

# Check if we're in the right directory
check_project_root() {
    if [ ! -f "$PROJECT_ROOT/build.gradle.kts" ] || [ ! -f "$PROJECT_ROOT/settings.gradle.kts" ]; then
        error "Not in a valid Android project directory"
        error "Expected to find build.gradle.kts and settings.gradle.kts in: $PROJECT_ROOT"
        exit 1
    fi
}

# Check if gradlew exists and is executable
check_gradle() {
    local gradlew="$PROJECT_ROOT/gradlew"
    if [ ! -f "$gradlew" ]; then
        error "gradlew not found in project root: $PROJECT_ROOT"
        exit 1
    fi
    
    if [ ! -x "$gradlew" ]; then
        info "Making gradlew executable..."
        chmod +x "$gradlew"
    fi
}

# Clean build if requested
clean_build() {
    info "Cleaning project..."
    cd "$PROJECT_ROOT"
    ./gradlew clean
    success "Project cleaned"
}

# Run unit tests
run_unit_tests() {
    info "Running unit tests..."
    cd "$PROJECT_ROOT"
    
    local test_cmd="./gradlew test"
    if [ "$VERBOSE" = true ]; then
        test_cmd="$test_cmd --info"
    fi
    
    if $test_cmd; then
        success "Unit tests passed"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        error "Unit tests failed"
        FAILED_TESTS+=("Unit Tests")
    fi
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
}

# Run instrumented tests
run_instrumented_tests() {
    info "Checking for connected devices..."
    
    # Check if adb is available
    if ! command -v adb >/dev/null 2>&1; then
        warning "adb not found in PATH. Skipping instrumented tests."
        return
    fi
    
    # Check for connected devices
    local devices=$(adb devices | grep -v "List of devices" | grep "device$" | wc -l)
    if [ "$devices" -eq 0 ]; then
        warning "No connected devices found. Skipping instrumented tests."
        warning "Connect a device or start an emulator to run instrumented tests."
        return
    fi
    
    info "Found $devices connected device(s). Running instrumented tests..."
    cd "$PROJECT_ROOT"
    
    local test_cmd="./gradlew connectedAndroidTest"
    if [ "$VERBOSE" = true ]; then
        test_cmd="$test_cmd --info"
    fi
    
    if $test_cmd; then
        success "Instrumented tests passed"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        error "Instrumented tests failed"
        FAILED_TESTS+=("Instrumented Tests")
    fi
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
}

# Run lint checks
run_lint_checks() {
    info "Running lint checks..."
    cd "$PROJECT_ROOT"
    
    local lint_cmd="./gradlew lint"
    if [ "$VERBOSE" = true ]; then
        lint_cmd="$lint_cmd --info"
    fi
    
    if $lint_cmd; then
        success "Lint checks passed"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        error "Lint checks failed"
        FAILED_TESTS+=("Lint Checks")
    fi
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
}

# Generate test reports
generate_reports() {
    info "Generating test reports..."
    cd "$PROJECT_ROOT"
    
    # Generate test reports
    if ./gradlew testDebugUnitTest; then
        local report_dir="$PROJECT_ROOT/app/build/reports"
        if [ -d "$report_dir" ]; then
            success "Test reports generated in: $report_dir"
            
            # List available reports
            if [ -d "$report_dir/tests" ]; then
                info "Unit test reports: $report_dir/tests/"
                find "$report_dir/tests" -name "index.html" -exec echo "  - {}" \;
            fi
            
            if [ -d "$report_dir/lint-results" ]; then
                info "Lint reports: $report_dir/lint-results/"
                find "$report_dir/lint-results" -name "*.html" -exec echo "  - {}" \;
            fi
        fi
    else
        warning "Failed to generate some test reports"
    fi
}

# Print test summary
print_summary() {
    echo ""
    echo "========================================"
    echo "           TEST SUMMARY"
    echo "========================================"
    echo ""
    
    if [ ${#FAILED_TESTS[@]} -eq 0 ]; then
        success "All tests passed! ($PASSED_TESTS/$TOTAL_TESTS)"
    else
        error "Some tests failed ($PASSED_TESTS/$TOTAL_TESTS passed)"
        echo ""
        error "Failed test suites:"
        for test in "${FAILED_TESTS[@]}"; do
            echo "  - $test"
        done
    fi
    
    echo ""
    echo "Test results location:"
    echo "  - Unit tests: app/build/reports/tests/"
    echo "  - Lint results: app/build/reports/lint-results/"
    echo "  - Instrumented tests: app/build/reports/androidTests/"
    echo ""
    
    if [ ${#FAILED_TESTS[@]} -gt 0 ]; then
        exit 1
    fi
}

# Parse command line arguments
UNIT_ONLY=false
INSTRUMENTED_ONLY=false
LINT_ONLY=false
CLEAN_BUILD=false
GENERATE_REPORTS=false
VERBOSE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        -u|--unit)
            UNIT_ONLY=true
            shift
            ;;
        -i|--instrumented)
            INSTRUMENTED_ONLY=true
            shift
            ;;
        -l|--lint)
            LINT_ONLY=true
            shift
            ;;
        -c|--clean)
            CLEAN_BUILD=true
            shift
            ;;
        -r|--report)
            GENERATE_REPORTS=true
            shift
            ;;
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            show_usage
            exit 0
            ;;
        *)
            error "Unknown option: $1"
            show_usage
            exit 1
            ;;
    esac
done

# Main execution
main() {
    echo "========================================"
    echo "       PubSub Android Test Runner"
    echo "========================================"
    echo ""
    
    check_project_root
    check_gradle
    
    # Clean build if requested
    if [ "$CLEAN_BUILD" = true ]; then
        clean_build
    fi
    
    # Determine which tests to run
    if [ "$UNIT_ONLY" = true ]; then
        run_unit_tests
    elif [ "$INSTRUMENTED_ONLY" = true ]; then
        run_instrumented_tests
    elif [ "$LINT_ONLY" = true ]; then
        run_lint_checks
    else
        # Run all tests by default
        run_unit_tests
        run_instrumented_tests
        run_lint_checks
    fi
    
    # Generate reports if requested
    if [ "$GENERATE_REPORTS" = true ]; then
        generate_reports
    fi
    
    print_summary
}

main "$@"

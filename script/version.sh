#!/bin/bash

# PubSub Android Version Management Script
# Usage: ./script/version.sh [version] [--show] [--bump-major|--bump-minor|--bump-patch]
# If no version is provided, shows the current version

set -e  # Exit on any error

# Default values
SHOW_ONLY=false
BUMP_MAJOR=false
BUMP_MINOR=false
BUMP_PATCH=false
NEW_VERSION=""

# Parse command line arguments
for arg in "$@"; do
    case $arg in
        --show)
            SHOW_ONLY=true
            shift
            ;;
        --bump-major)
            BUMP_MAJOR=true
            shift
            ;;
        --bump-minor)
            BUMP_MINOR=true
            shift
            ;;
        --bump-patch)
            BUMP_PATCH=true
            shift
            ;;
        --help|-h)
            echo "PubSub Android Version Management Script"
            echo ""
            echo "Usage: ./script/version.sh [version] [options]"
            echo ""
            echo "Arguments:"
            echo "  version                 Version number (e.g., 1.0.0 or v1.0.0). If not provided, shows current version"
            echo ""
            echo "Options:"
            echo "  --show                  Show current version and exit"
            echo "  --bump-major            Increment major version (x.0.0)"
            echo "  --bump-minor            Increment minor version (x.y.0)"
            echo "  --bump-patch            Increment patch version (x.y.z)"
            echo "  --help, -h              Show this help message"
            echo ""
            echo "Examples:"
            echo "  ./script/version.sh                       # Show current version"
            echo "  ./script/version.sh --show                # Show current version"
            echo "  ./script/version.sh 1.2.0                 # Set version to 1.2.0"
            echo "  ./script/version.sh v1.2.0                # Same as above (v prefix is handled automatically)"
            echo "  ./script/version.sh --bump-patch          # Increment patch version (0.9.5 -> 0.9.6)"
            echo "  ./script/version.sh --bump-minor          # Increment minor version (0.9.5 -> 0.10.0)"
            echo "  ./script/version.sh --bump-major          # Increment major version (0.9.5 -> 1.0.0)"
            exit 0
            ;;
        -*)
            echo "❌ Unknown option: $arg"
            echo "Use --help for usage information"
            exit 1
            ;;
        *)
            if [ -z "$NEW_VERSION" ]; then
                NEW_VERSION="$arg"
            else
                echo "❌ Multiple version arguments provided"
                exit 1
            fi
            ;;
    esac
done

echo "=== PubSub Android Version Manager ==="
echo ""

# Check if we're in the correct directory
if [ ! -f "app/build.gradle.kts" ]; then
    echo "❌ app/build.gradle.kts not found"
    echo "   Make sure you're in the project root directory"
    exit 1
fi

# Function to get current version from build.gradle.kts
get_current_version() {
    grep -o 'versionName = "[^"]*"' app/build.gradle.kts | sed 's/versionName = "\(.*\)"/\1/'
}

# Function to normalize version (remove 'v' prefix if present)
normalize_version() {
    local version=$1
    echo "${version#v}"  # Remove 'v' prefix if present
}

# Function to validate version format (semantic versioning)
validate_version() {
    local version=$1
    if [[ ! $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "❌ Invalid version format: $version"
        echo "   Version must follow semantic versioning (x.y.z)"
        return 1
    fi
    return 0
}

# Function to parse version components
parse_version() {
    local version=$1
    echo "$version" | sed 's/\([0-9]*\)\.\([0-9]*\)\.\([0-9]*\)/\1 \2 \3/'
}

# Function to bump version
bump_version() {
    local current_version=$1
    local bump_type=$2
    
    read -r major minor patch <<< $(parse_version "$current_version")
    
    case $bump_type in
        "major")
            major=$((major + 1))
            minor=0
            patch=0
            ;;
        "minor")
            minor=$((minor + 1))
            patch=0
            ;;
        "patch")
            patch=$((patch + 1))
            ;;
    esac
    
    echo "$major.$minor.$patch"
}

# Function to update version in build.gradle.kts
update_gradle_version() {
    local new_version=$1
    echo "📝 Updating version in app/build.gradle.kts to $new_version"
    
    # Create backup
    cp app/build.gradle.kts app/build.gradle.kts.bak
    
    # Update version
    sed -i "s/versionName = \".*\"/versionName = \"$new_version\"/" app/build.gradle.kts
    
    # Verify the change was made
    local updated_version=$(get_current_version)
    if [ "$updated_version" = "$new_version" ]; then
        echo "✅ Version successfully updated to $new_version"
        rm app/build.gradle.kts.bak
    else
        echo "❌ Failed to update version"
        mv app/build.gradle.kts.bak app/build.gradle.kts
        exit 1
    fi
}

# Get current version
CURRENT_VERSION=$(get_current_version)
if [ -z "$CURRENT_VERSION" ]; then
    echo "❌ Failed to read current version from app/build.gradle.kts"
    exit 1
fi

echo "📦 Current version: $CURRENT_VERSION"

# Handle show-only mode
if [ "$SHOW_ONLY" = true ] && [ -z "$NEW_VERSION" ] && [ "$BUMP_MAJOR" = false ] && [ "$BUMP_MINOR" = false ] && [ "$BUMP_PATCH" = false ]; then
    echo ""
    echo "✅ Current version displayed above"
    exit 0
fi

# Handle version bumping
if [ "$BUMP_MAJOR" = true ] || [ "$BUMP_MINOR" = true ] || [ "$BUMP_PATCH" = true ]; then
    if [ -n "$NEW_VERSION" ]; then
        echo "❌ Cannot specify both a version and bump option"
        exit 1
    fi
    
    # Check for multiple bump options
    bump_count=0
    [ "$BUMP_MAJOR" = true ] && bump_count=$((bump_count + 1))
    [ "$BUMP_MINOR" = true ] && bump_count=$((bump_count + 1))
    [ "$BUMP_PATCH" = true ] && bump_count=$((bump_count + 1))
    
    if [ $bump_count -gt 1 ]; then
        echo "❌ Cannot specify multiple bump options"
        exit 1
    fi
    
    # Determine bump type
    if [ "$BUMP_MAJOR" = true ]; then
        BUMP_TYPE="major"
    elif [ "$BUMP_MINOR" = true ]; then
        BUMP_TYPE="minor"
    elif [ "$BUMP_PATCH" = true ]; then
        BUMP_TYPE="patch"
    fi
    
    NEW_VERSION=$(bump_version "$CURRENT_VERSION" "$BUMP_TYPE")
    echo "🔄 Bumping $BUMP_TYPE version: $CURRENT_VERSION → $NEW_VERSION"
fi

# Handle explicit version setting
if [ -n "$NEW_VERSION" ]; then
    # Normalize version (remove v prefix)
    NEW_VERSION=$(normalize_version "$NEW_VERSION")
    
    # Validate version format
    if ! validate_version "$NEW_VERSION"; then
        exit 1
    fi
    
    # Check if version is the same
    if [ "$NEW_VERSION" = "$CURRENT_VERSION" ]; then
        echo "⚠️  Version $NEW_VERSION is already set"
        echo ""
        echo "✅ No changes needed"
        exit 0
    fi
    
    # Update version
    echo ""
    update_gradle_version "$NEW_VERSION"
    
    echo ""
    echo "🎉 Version updated successfully!"
    echo "   Previous: $CURRENT_VERSION"
    echo "   Current:  $NEW_VERSION"
    echo ""
    echo "💡 Next steps:"
    echo "   • Commit the version change: git add app/build.gradle.kts && git commit -m \"Bump version to $NEW_VERSION\""
    echo "   • When ready to release: ./script/release.sh"
    
elif [ "$SHOW_ONLY" = false ]; then
    # No version provided and not show-only, treat as show current version
    echo ""
    echo "✅ Current version displayed above"
    echo ""
    echo "💡 To set a new version:"
    echo "   ./script/version.sh 1.0.0        # Set specific version"
    echo "   ./script/version.sh --bump-patch  # Increment patch version"
fi

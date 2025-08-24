#!/bin/bash

# Script to generate a release keystore for Google Play Store
# Run this script to create your signing keystore

echo "=== PubSub App Keystore Generation ==="
echo ""
echo "This script will generate a keystore for signing your app for Google Play Store."
echo "You will need to provide some information for the certificate."
echo ""

# Set keystore details
KEYSTORE_FILE="pubsub-release.keystore"
KEY_ALIAS="pubsub"
VALIDITY_DAYS=10000

echo "Generating keystore: $KEYSTORE_FILE"
echo "Key alias: $KEY_ALIAS"
echo "Validity: $VALIDITY_DAYS days"
echo ""

# Generate the keystore
keytool -genkey -v \
    -keystore $KEYSTORE_FILE \
    -alias $KEY_ALIAS \
    -keyalg RSA \
    -keysize 2048 \
    -validity $VALIDITY_DAYS

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Keystore generated successfully!"
    echo ""
    
    # Prepare GitHub Secrets
    echo "=== GitHub Actions Setup ==="
    echo ""
    echo "🔐 Encoding keystore for GitHub Actions..."
    KEYSTORE_BASE64=$(base64 -w 0 "$KEYSTORE_FILE")
    echo "✅ Keystore encoded successfully"
    echo ""
    
    echo "📋 Add these secrets to your GitHub repository:"
    echo "   Go to: Settings > Secrets and variables > Actions > New repository secret"
    echo ""
    
    echo "1. KEYSTORE_BASE64"
    echo "   Value: $KEYSTORE_BASE64"
    echo ""
    
    echo "2. KEYSTORE_PASSWORD"
    echo "   Value: [The keystore password you just entered]"
    echo ""
    
    echo "3. KEY_ALIAS"
    echo "   Value: $KEY_ALIAS"
    echo ""
    
    echo "4. KEY_PASSWORD"  
    echo "   Value: [The key password you just entered]"
    echo ""
    
    echo "⚠️  IMPORTANT SECURITY NOTES:"
    echo "   • Never commit these values to your repository"
    echo "   • Only add them as GitHub Secrets (encrypted)"
    echo "   • Store backup copies in a secure password manager"
    echo ""
    
    echo "📖 How to add secrets:"
    echo "   1. Go to your GitHub repository"
    echo "   2. Click Settings > Secrets and variables > Actions"
    echo "   3. Click 'New repository secret'"
    echo "   4. Add each secret name and value"
    echo ""
    
    echo "📝 Local Development Notes:"
    echo "   • Your keystore is ready for local builds"
    echo "   • The app/build.gradle.kts is configured for automatic signing"
    echo "   • Never commit your keystore or passwords to version control"
    echo ""
    
    echo "🚀 After adding secrets to GitHub, your Actions will build signed APKs!"
else
    echo "❌ Failed to generate keystore"
    exit 1
fi

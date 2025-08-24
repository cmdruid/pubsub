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
KEY_ALIAS="pubsub-key"
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
    echo "📝 Next steps:"
    echo "1. Keep your keystore file ($KEYSTORE_FILE) safe and secure"
    echo "2. Remember your keystore password and key password"
    echo "3. Update app/build.gradle.kts with your keystore details"
    echo "4. Never commit your keystore or passwords to version control"
    echo ""
    echo "🔧 Update your build.gradle.kts:"
    echo "   storeFile = file(\"../$KEYSTORE_FILE\")"
    echo "   storePassword = \"your_store_password\""
    echo "   keyAlias = \"$KEY_ALIAS\""
    echo "   keyPassword = \"your_key_password\""
else
    echo "❌ Failed to generate keystore"
    exit 1
fi

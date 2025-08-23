#!/bin/bash

echo "🧪 PubSub Demo Test Flow"
echo "======================"
echo ""

# Check if PWA server is running
if ! curl -s http://localhost:8000 > /dev/null 2>&1; then
    echo "❌ PWA server is not running on localhost:8000"
    echo "   Please start it first with: ./start.sh"
    exit 1
fi

echo "✅ PWA server is running on localhost:8000"
echo ""

# Generate a test deep link
echo "🔗 Generating test deep link..."

# Create a sample filter for text notes
FILTER='{"kinds":[1],"limit":5}'
FILTER_B64=$(echo -n "$FILTER" | base64 -w 0 | tr '+/' '-_' | tr -d '=')

# Build the deep link
DEEP_LINK="pubsub://register?label=Demo%20PWA&uri=http://localhost:8000&relay=wss://relay.damus.io&filter=$FILTER_B64"

echo "📋 Deep Link Generated:"
echo "$DEEP_LINK"
echo ""

# Test the deep link with the Android app
echo "📱 Testing deep link with PubSub app..."
adb shell am start -a android.intent.action.VIEW -d "$DEEP_LINK" 2>/dev/null

if [ $? -eq 0 ]; then
    echo "✅ Deep link sent to PubSub app successfully"
    echo ""
    echo "📋 Next Steps:"
    echo "1. Check your Android device/emulator"
    echo "2. You should see a registration dialog in the PubSub app"
    echo "3. Approve the registration"
    echo "4. Enable the 'Demo PWA' configuration"
    echo "5. Start the service to begin monitoring"
    echo "6. Watch http://localhost:8000 for incoming events"
else
    echo "❌ Could not send deep link to PubSub app"
    echo "   Make sure the PubSub app is installed and adb is connected"
fi

echo ""
echo "🌐 Open http://localhost:8000 in your browser to see the PWA interface"

#!/bin/bash

echo "🚀 Starting PubSub Demo PWA with ADB Port Forwarding"
echo "=================================================="
echo ""

# Set up port forwarding so Android can access localhost:8000
echo "🔗 Setting up ADB port forwarding..."
adb reverse tcp:8000 tcp:8000

if [ $? -eq 0 ]; then
    echo "✅ Port forwarding established: device:8000 -> host:8000"
    echo "📱 Android device can now access http://localhost:8000"
else
    echo "❌ Port forwarding failed. Make sure ADB is connected."
    exit 1
fi

echo ""
echo "🌐 Starting web server..."
echo "📂 PWA URL: http://localhost:8000"
echo "📱 Use http://localhost:8000 as target URI in the PWA"
echo ""
echo "💡 Instructions:"
echo "1. PWA will be accessible at http://localhost:8000 on both host and device"
echo "2. Configure the PWA with target URI: http://localhost:8000"
echo "3. Register with PubSub app using the generated deep link"
echo "4. Events will be sent back to the PWA on the device"
echo ""
echo "🛑 Press Ctrl+C to stop"
echo "=================================================="
echo ""

# Start the server
python3 serve.py

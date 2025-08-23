#!/bin/bash

echo "🚀 Starting PubSub Demo PWA Server"
echo "================================="
echo ""
echo "📂 Demo folder: $(pwd)"
echo "🌐 URL: http://localhost:8000"
echo "📱 Use this URL as your target URI in the PWA"
echo ""
echo "💡 Instructions:"
echo "1. Open http://localhost:8000 in your browser"
echo "2. Configure the PWA settings"  
echo "3. Click 'Open in PubSub App' to register"
echo "4. Enable the configuration in PubSub and start the service"
echo "5. Events will be sent back to this PWA"
echo ""
echo "🛑 Press Ctrl+C to stop the server"
echo "================================="
echo ""

# Start the Python server
python3 serve.py

#!/usr/bin/env python3
"""
Simple HTTP server for serving the PubSub Demo PWA locally.
Run with: python3 serve.py
"""

import http.server
import socketserver
import os
import sys
from urllib.parse import urlparse, parse_qs

class PubSubDemoHandler(http.server.SimpleHTTPRequestHandler):
    """Custom handler that logs incoming requests and handles event parameters"""
    
    def do_GET(self):
        parsed_url = urlparse(self.path)
        query_params = parse_qs(parsed_url.query)
        
        # Log all requests
        print(f"\n📨 Request: {self.path}")
        
        # Check for event parameters
        if 'id' in query_params:
            event_id = query_params['id'][0]
            event_data = query_params.get('event', [None])[0]
            
            print(f"🎯 Event ID: {event_id}")
            if event_data:
                print(f"📦 Event Data: {event_data[:50]}{'...' if len(event_data) > 50 else ''}")
                print(f"📏 Event Data Size: {len(event_data)} characters")
            else:
                print(f"ℹ️  Event data not included (likely > 500KB)")
            
            # Try to decode and display event
            if event_data:
                try:
                    import base64
                    import json
                    
                    # Decode base64url
                    padded = event_data + '=' * (4 - len(event_data) % 4)
                    decoded_bytes = base64.urlsafe_b64decode(padded)
                    event_json = decoded_bytes.decode('utf-8')
                    event = json.loads(event_json)
                    
                    print(f"✅ Event decoded successfully:")
                    print(f"   Kind: {event.get('kind', 'unknown')}")
                    print(f"   Author: {event.get('pubkey', 'unknown')[:16]}...")
                    print(f"   Content: {event.get('content', '')[:100]}{'...' if len(event.get('content', '')) > 100 else ''}")
                    print(f"   Created: {event.get('created_at', 'unknown')}")
                    
                except Exception as e:
                    print(f"❌ Error decoding event: {e}")
        
        # Serve the file
        super().do_GET()
    
    def log_message(self, format, *args):
        """Override to customize logging"""
        return  # Suppress default logging

def main():
    PORT = 8000
    
    # Change to the demo directory
    demo_dir = os.path.dirname(os.path.abspath(__file__))
    os.chdir(demo_dir)
    
    print("🚀 Starting PubSub Demo PWA Server")
    print(f"📂 Serving from: {demo_dir}")
    print(f"🌐 URL: http://localhost:{PORT}")
    print(f"📱 Use this URL as your target URI in the PWA")
    print("=" * 50)
    
    with socketserver.TCPServer(("", PORT), PubSubDemoHandler) as httpd:
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\n\n🛑 Server stopped")
            sys.exit(0)

if __name__ == "__main__":
    main()

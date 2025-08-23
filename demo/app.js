// PubSub Demo PWA JavaScript

// Configuration
let currentConfig = {
    appName: 'Demo PWA',
    targetUri: '',
    relayUrls: ['wss://relay.damus.io', 'wss://nos.lol'],
    filter: { kinds: [1], limit: 10 }
};

// PWA installation support
let deferredPrompt;

// Initialize the PWA
document.addEventListener('DOMContentLoaded', function() {
    initializePWA();
    setupEventHandling();
    setupPWAInstallation();
    loadStoredConfig();
});

function initializePWA() {
    // Set default target URI to current location
    const defaultUri = `${window.location.origin}${window.location.pathname}`;
    currentConfig.targetUri = defaultUri;
    
    // Set default filter (text notes)
    selectFilter('textNotes');
    
    log('PWA initialized with default configuration');
}

function setupEventHandling() {
    // Log current URL for debugging
    log(`🔍 Current URL: ${window.location.href}`);
    
    // Check if this is a nostr: URI with nevent
    const currentUrl = window.location.href;
    let eventId = null;
    let eventData = null;
    
    if (currentUrl.includes('nostr:nevent1')) {
        // Handle nostr: URI format
        const nostrMatch = currentUrl.match(/nostr:(nevent1[a-z0-9]+)/);
        if (nostrMatch) {
            const nevent = nostrMatch[1];
            eventId = decodeNeventToHex(nevent);
            log(`🔍 Nostr URI detected: nevent=${nevent.substring(0, 20)}..., decoded id=${eventId ? eventId.substring(0, 16) + '...' : 'null'}`);
        }
        
        // Check for event parameter in nostr URI
        const urlParams = new URLSearchParams(window.location.search);
        eventData = urlParams.get('event');
    } else {
        // Handle legacy URL parameters for backward compatibility
        const urlParams = new URLSearchParams(window.location.search);
        eventId = urlParams.get('id');
        eventData = urlParams.get('event');
        log(`🔍 Legacy URL Parameters: id=${eventId ? eventId.substring(0, 16) + '...' : 'null'}, event=${eventData ? 'present (' + eventData.length + ' chars)' : 'null'}`);
    }
    
    if (eventId) {
        // Show processing alert
        const eventAlert = document.getElementById('eventAlert');
        if (eventAlert) {
            eventAlert.style.display = 'block';
            eventAlert.innerHTML = '<strong>📨 Processing event from notification...</strong>';
        }
        
        handleIncomingEvent(eventId, eventData);
        
        // Hide alert after processing
        setTimeout(() => {
            if (eventAlert) {
                eventAlert.style.display = 'none';
            }
        }, 3000);
    } else {
        log(`ℹ️ No event identifier found - normal page load`);
    }
    
    // Handle hash changes for SPA-style routing
    window.addEventListener('hashchange', handleUrlChange);
    window.addEventListener('popstate', handleUrlChange);
    
    // Also check for events when the page becomes visible (for PWA/notification handling)
    document.addEventListener('visibilitychange', function() {
        if (!document.hidden) {
            log(`👁️ Page became visible, rechecking URL parameters`);
            const currentParams = new URLSearchParams(window.location.search);
            const currentId = currentParams.get('id');
            const currentData = currentParams.get('event');
            
            if (currentId && currentId !== eventId) {
                log(`🔄 New event detected on visibility change: ${currentId}`);
                handleIncomingEvent(currentId, currentData);
            }
        }
    });
}

function handleUrlChange() {
    const urlParams = new URLSearchParams(window.location.search);
    const eventId = urlParams.get('id');
    const eventData = urlParams.get('event');
    
    if (eventId) {
        handleIncomingEvent(eventId, eventData);
    }
}

function handleIncomingEvent(eventId, eventData) {
    log(`📨 Received event: ${eventId}`);
    log(`📦 Event data length: ${eventData ? eventData.length + ' chars' : 'null'}`);
    
    if (eventData) {
        try {
            // Decode base64url event data
            log(`🔄 Decoding base64url event data...`);
            const eventJson = atob(eventData.replace(/-/g, '+').replace(/_/g, '/'));
            log(`📝 Decoded JSON length: ${eventJson.length} chars`);
            
            const event = JSON.parse(eventJson);
            
            log(`✅ Event decoded successfully`);
            log(`📄 Content: ${event.content?.substring(0, 100)}${event.content?.length > 100 ? '...' : ''}`);
            log(`👤 Author: ${event.pubkey?.substring(0, 16)}...`);
            log(`🏷️ Kind: ${event.kind}`);
            log(`📅 Created: ${new Date(event.created_at * 1000).toLocaleString()}`);
            
            displayEvent(event);
            
            // Scroll to the event display
            const eventDisplay = document.getElementById('lastEvent');
            if (eventDisplay) {
                eventDisplay.scrollIntoView({ behavior: 'smooth' });
            }
            
        } catch (error) {
            log(`❌ Error decoding event: ${error.message}`);
            log(`🔍 Raw event data: ${eventData.substring(0, 200)}...`);
            
            // Show error in the event display
            const eventDisplay = document.getElementById('lastEvent');
            if (eventDisplay) {
                eventDisplay.innerHTML = `
                    <div class="status error">
                        <strong>❌ Event Decoding Error</strong><br>
                        <strong>Event ID:</strong> ${eventId}<br>
                        <strong>Error:</strong> ${error.message}<br>
                        <strong>Raw Data:</strong> ${eventData.substring(0, 100)}...
                    </div>
                `;
            }
        }
    } else {
        log(`ℹ️ Received event ID only (event was too large): ${eventId}`);
        
        // Show ID-only event in the display
        const eventDisplay = document.getElementById('lastEvent');
        if (eventDisplay) {
            eventDisplay.innerHTML = `
                <div class="status info">
                    <strong>📨 Large Event (ID Only)</strong><br>
                    <strong>Event ID:</strong> ${eventId}<br>
                    <em>Event was too large (>500KB) to include full data</em>
                </div>
            `;
        }
    }
    
    // Show notification if supported
    if ('Notification' in window && Notification.permission === 'granted') {
        new Notification('New Nostr Event', {
            body: `Received event: ${eventId.substring(0, 16)}...`,
            icon: 'icon-192.png'
        });
    }
}

function displayEvent(event) {
    const eventDisplay = document.getElementById('lastEvent');
    eventDisplay.innerHTML = `
        <div class="status info">
            <strong>📨 Latest Event</strong><br>
            <strong>ID:</strong> ${event.id}<br>
            <strong>Author:</strong> ${event.pubkey}<br>
            <strong>Kind:</strong> ${event.kind}<br>
            <strong>Created:</strong> ${new Date(event.created_at * 1000).toLocaleString()}<br>
            <strong>Content:</strong> ${event.content || '(no content)'}
        </div>
        <details>
            <summary>View Full Event JSON</summary>
            <div class="event-display">${JSON.stringify(event, null, 2)}</div>
        </details>
    `;
}

function selectFilter(type) {
    let filter;
    const now = Math.floor(Date.now() / 1000);
    
    switch (type) {
        case 'textNotes':
            filter = { kinds: [1], limit: 10 };
            break;
        case 'reactions':
            filter = { kinds: [7], limit: 20 };
            break;
        case 'mentions':
            filter = { "#p": ["npub1sw9mw2rmva6k9rs8hq8y3w9dknkxh9kp0h9qjgtp9r6jtv9ttvlqt72nqt"], limit: 15 };
            break;
        case 'hashtags':
            filter = { "#t": ["nostr", "bitcoin"], limit: 25 };
            break;
        case 'recent':
            filter = { kinds: [1], since: now, limit: 5 };
            break;
        case 'custom':
            filter = { kinds: [1], limit: 10 };
            break;
    }
    
    document.getElementById('filterJson').value = JSON.stringify(filter, null, 2);
    log(`🎯 Selected filter: ${type}`);
}



function openInPubSub() {
    try {
        // Get form values
        const appName = document.getElementById('appName').value.trim() || 'Demo PWA';
        const targetUri = currentConfig.targetUri;
        const relayUrls = currentConfig.relayUrls;
        const filter = currentConfig.filter;
        
        // Encode filter as base64url
        const filterBase64 = btoa(JSON.stringify(filter))
            .replace(/\+/g, '-')
            .replace(/\//g, '_')
            .replace(/=/g, '');
        
        // Build deep link URL
        const params = new URLSearchParams();
        params.append('label', appName);
        params.append('uri', targetUri);
        relayUrls.forEach(url => params.append('relay', url));
        params.append('filter', filterBase64);
        
        const deepLink = `pubsub://register?${params.toString()}`;
        
        // Try to open the deep link
        window.location.href = deepLink;
        log(`📱 Opening PubSub app for registration: ${appName}`);
        
        showStatus('success', `Opening PubSub app to register "${appName}"...`);
        
    } catch (error) {
        log(`❌ Error: ${error.message}`);
        showStatus('error', 'Could not open PubSub app. Make sure it is installed.');
    }
}

function simulateEvent() {
    const sampleEvent = {
        id: "abc123def456789",
        pubkey: "sample_pubkey_here_64_characters_long_hex_string_abcdef123456789",
        created_at: Math.floor(Date.now() / 1000),
        kind: 1,
        tags: [["p", "mentioned_pubkey"], ["t", "demo"]],
        content: "This is a simulated Nostr event for testing the PWA event handling!",
        sig: "sample_signature_here"
    };
    
    log(`🧪 Simulating incoming event`);
    displayEvent(sampleEvent);
}

function testEventDecoding() {
    log(`🔍 Testing event decoding from current URL...`);
    
    // Check current URL
    log(`🌐 Current URL: ${window.location.href}`);
    
    const currentUrl = window.location.href;
    let eventId = null;
    let eventData = null;
    
    if (currentUrl.includes('nostr:nevent1')) {
        // Handle nostr: URI format
        const nostrMatch = currentUrl.match(/nostr:(nevent1[a-z0-9]+)/);
        if (nostrMatch) {
            const nevent = nostrMatch[1];
            eventId = decodeNeventToHex(nevent);
            log(`📋 Found nostr URI:`);
            log(`   - nevent: ${nevent.substring(0, 20)}...`);
            log(`   - decoded id: ${eventId || 'null'}`);
        }
        
        // Check for event parameter in nostr URI
        const urlParams = new URLSearchParams(window.location.search);
        eventData = urlParams.get('event');
        log(`   - event: ${eventData ? `${eventData.length} characters` : 'null'}`);
    } else {
        // Parse legacy URL parameters manually
        const urlParams = new URLSearchParams(window.location.search);
        eventId = urlParams.get('id');
        eventData = urlParams.get('event');
        
        log(`📋 Found legacy parameters:`);
        log(`   - id: ${eventId || 'null'}`);
        log(`   - event: ${eventData ? `${eventData.length} characters` : 'null'}`);
    }
    
    if (eventId || eventData) {
        log(`🔄 Re-processing URL parameters...`);
        handleIncomingEvent(eventId, eventData);
    } else {
        log(`❌ No URL parameters found. Try opening a notification URL.`);
        
        // Show example URLs for testing (both legacy and new format)
        const testEvent = { id: "test123", content: "Test event", kind: 1, created_at: 1755900000, pubkey: "testkey123" };
        const testEventJson = JSON.stringify(testEvent);
        const testEventEncoded = btoa(testEventJson).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
        
        const legacyUrl = `${window.location.origin}${window.location.pathname}?id=test123&event=${testEventEncoded}`;
        const nostrUrl = `${window.location.origin}${window.location.pathname}nostr:nevent1test123example?event=${testEventEncoded}`;
        
        log(`💡 Example legacy URL: ${legacyUrl}`);
        log(`💡 Example nostr URL format: ${nostrUrl}`);
    }
}

function copyToClipboard(text) {
    navigator.clipboard.writeText(text).then(() => {
        showStatus('success', 'Deep link copied to clipboard!');
        log(`📋 Copied to clipboard`);
    }).catch(err => {
        showStatus('error', 'Failed to copy to clipboard');
        log(`❌ Copy failed: ${err.message}`);
    });
}

function showStatus(type, message) {
    const resultDiv = document.getElementById('deepLinkResult');
    resultDiv.innerHTML = `<div class="status ${type}">${message}</div>`;
    
    // Auto-hide after 5 seconds
    setTimeout(() => {
        if (resultDiv.innerHTML.includes(message)) {
            resultDiv.innerHTML = '';
        }
    }, 5000);
}

function log(message) {
    const logsDiv = document.getElementById('eventLogs');
    const timestamp = new Date().toLocaleTimeString();
    const logEntry = document.createElement('div');
    logEntry.className = 'log-entry';
    logEntry.innerHTML = `<span class="timestamp">[${timestamp}]</span> ${message}`;
    
    logsDiv.appendChild(logEntry);
    logsDiv.scrollTop = logsDiv.scrollHeight;
    
    console.log(`[PubSub Demo] ${message}`);
}

function clearLogs() {
    const logsDiv = document.getElementById('eventLogs');
    logsDiv.innerHTML = '<div class="log-entry"><span class="timestamp">[Cleared]</span> Logs cleared</div>';
    
    const lastEventDiv = document.getElementById('lastEvent');
    lastEventDiv.innerHTML = '';
}

function storeConfig() {
    localStorage.setItem('pubsubDemoConfig', JSON.stringify(currentConfig));
}

function loadStoredConfig() {
    try {
        const stored = localStorage.getItem('pubsubDemoConfig');
        if (stored) {
            const config = JSON.parse(stored);
            document.getElementById('appName').value = config.appName || 'Demo PWA';
            currentConfig = config;
            log(`📁 Loaded stored configuration: ${config.appName}`);
        }
    } catch (error) {
        log(`⚠️ Could not load stored config: ${error.message}`);
    }
}

// Request notification permission
function requestNotificationPermission() {
    if ('Notification' in window && Notification.permission === 'default') {
        Notification.requestPermission().then(permission => {
            if (permission === 'granted') {
                log('🔔 Notification permission granted');
            } else {
                log('🔕 Notification permission denied');
            }
        });
    }
}

// PWA Installation Setup
function setupPWAInstallation() {
    // Listen for the beforeinstallprompt event
    window.addEventListener('beforeinstallprompt', (e) => {
        console.log('PWA install prompt available');
        e.preventDefault();
        deferredPrompt = e;
        showInstallButton();
    });

    // Check if already installed
    window.addEventListener('appinstalled', () => {
        console.log('PWA was installed');
        log('📱 PWA installed successfully!');
        hideInstallButton();
    });

    // Check if running in standalone mode (already installed)
    if (window.matchMedia('(display-mode: standalone)').matches) {
        log('📱 PWA is running in standalone mode');
        hideInstallButton();
    }
}

function showInstallButton() {
    // Add install button to the header
    const header = document.querySelector('.header');
    if (!document.getElementById('installButton')) {
        const installButton = document.createElement('button');
        installButton.id = 'installButton';
        installButton.className = 'button';
        installButton.innerHTML = '📱 Install PWA';
        installButton.onclick = installPWA;
        installButton.style.marginTop = '15px';
        header.appendChild(installButton);
        
        log('📱 PWA installation available');
    }
}

function hideInstallButton() {
    const installButton = document.getElementById('installButton');
    if (installButton) {
        installButton.remove();
    }
}

function installPWA() {
    if (deferredPrompt) {
        deferredPrompt.prompt();
        deferredPrompt.userChoice.then((choiceResult) => {
            if (choiceResult.outcome === 'accepted') {
                log('📱 User accepted PWA installation');
            } else {
                log('📱 User dismissed PWA installation');
            }
            deferredPrompt = null;
        });
    }
}

// NIP-19 nevent decoding (simplified implementation)
function decodeNeventToHex(nevent) {
    try {
        if (!nevent.startsWith('nevent1')) return null;
        
        // This is a simplified bech32 decoder for demo purposes
        // In production, you'd use a proper bech32 library
        const bech32Alphabet = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l';
        const data = nevent.substring(7); // Remove 'nevent1' prefix
        
        // Convert bech32 to 5-bit values
        const values = [];
        for (let i = 0; i < data.length; i++) {
            const index = bech32Alphabet.indexOf(data[i].toLowerCase());
            if (index === -1) return null;
            values.push(index);
        }
        
        // Convert 5-bit groups to 8-bit bytes
        const bytes = [];
        let acc = 0;
        let bits = 0;
        
        for (const value of values) {
            acc = (acc << 5) | value;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bytes.push((acc >> bits) & 0xFF);
                acc = acc & ((1 << bits) - 1);
            }
        }
        
        // Parse TLV structure: skip type (1 byte) and length (1 byte), extract event ID (32 bytes)
        if (bytes.length < 34) return null;
        const type = bytes[0];
        const length = bytes[1];
        
        if (type !== 0 || length !== 32) return null;
        
        // Extract event ID bytes and convert to hex
        const eventIdBytes = bytes.slice(2, 34);
        return eventIdBytes.map(b => b.toString(16).padStart(2, '0')).join('');
    } catch (e) {
        log(`❌ Error decoding nevent: ${e.message}`);
        return null;
    }
}

// Request notification permission on load
setTimeout(requestNotificationPermission, 1000);

// Spotify WebView Bridge for Kilo
// Adapted from Myspoti/Orpheon player-bridge.js
// Runs inside open.spotify.com WebView

(function() {
    'use strict';

    // Message types sent to host (Android)
    const MESSAGE_TYPES = {
        READY: 'ready',
        STATE: 'state',
        QUEUE: 'queue',
        RESULT: 'result',
        ERROR: 'error'
    };

    // Host message handler name
    const HOST_INTERFACE = 'MyspotiHost';

    // Bridge state
    let bridgeReady = false;
    let messageId = 0;
    const pendingCalls = new Map();
    let spotifyServices = null;
    let currentState = null;
    let currentQueue = null;

    // Send message to Android host
    function postToHost(type, data) {
        const message = JSON.stringify({ type, ...data });
        if (window[HOST_INTERFACE] && window[HOST_INTERFACE].postMessage) {
            window[HOST_INTERFACE].postMessage(message);
        } else if (window.chrome && window.chrome.webview) {
            window.chrome.webview.postMessage(message);
        }
    }

    // Find Spotify's React service registry
    function findSpotifyServices() {
        try {
            // Find React Fiber root
            const rootElements = document.querySelectorAll('[data-reactroot], [data-react-container]');
            let fiber = null;

            for (const el of rootElements) {
                const key = Object.keys(el).find(k => k.startsWith('__reactFiber$') || k.startsWith('__reactInternalInstance$'));
                if (key) {
                    fiber = el[key];
                    break;
                }
            }

            if (!fiber) {
                // Fallback: search all elements
                const allElements = document.querySelectorAll('*');
                for (const el of allElements) {
                    const key = Object.keys(el).find(k => k.startsWith('__reactFiber$') || k.startsWith('__reactInternalInstance$'));
                    if (key) {
                        fiber = el[key];
                        break;
                    }
                }
            }

            if (!fiber) return null;

            // Traverse fiber tree to find context providers
            function traverseFiber(node) {
                if (!node) return null;

                // Check for Spotify's service registry in context
                if (node.memoizedProps && node.memoizedProps.value) {
                    const value = node.memoizedProps.value;
                    if (value && typeof value === 'object') {
                        // Look for known Spotify service keys
                        if (value.PlayerAPI || value.PlaybackAPI || value.LibraryAPI || value.TokenProvider) {
                            return value;
                        }
                        // Check nested
                        for (const k of Object.keys(value)) {
                            const v = value[k];
                            if (v && typeof v === 'object' && (v.PlayerAPI || v.PlaybackAPI || v.LibraryAPI || v.TokenProvider)) {
                                return v;
                            }
                        }
                    }
                }

                // Check memoizedState
                if (node.memoizedState && node.memoizedState.value) {
                    const value = node.memoizedState.value;
                    if (value && typeof value === 'object') {
                        if (value.PlayerAPI || value.PlaybackAPI || value.LibraryAPI || value.TokenProvider) {
                            return value;
                        }
                    }
                }

                // Traverse children
                let child = node.child;
                while (child) {
                    const found = traverseFiber(child);
                    if (found) return found;
                    child = child.sibling;
                }
                return null;
            }

            return traverseFiber(fiber);
        } catch (e) {
            console.warn('[KiloBridge] Error finding services:', e);
            return null;
        }
    }

    // Initialize bridge
    function initialize() {
        // Try to find services immediately
        spotifyServices = findSpotifyServices();

        if (spotifyServices) {
            onServicesReady();
        } else {
            // Retry periodically
            let attempts = 0;
            const maxAttempts = 50; // ~5 seconds
            const interval = setInterval(() => {
                attempts++;
                spotifyServices = findSpotifyServices();
                if (spotifyServices) {
                    clearInterval(interval);
                    onServicesReady();
                } else if (attempts >= maxAttempts) {
                    clearInterval(interval);
                    postToHost(MESSAGE_TYPES.ERROR, { error: 'Failed to find Spotify services' });
                }
            }, 100);
        }
    }

    function onServicesReady() {
        bridgeReady = true;
        postToHost(MESSAGE_TYPES.READY, { version: '1.0.0' });

        // Start polling for state changes
        startStatePolling();
    }

    // Poll for playback state changes
    function startStatePolling() {
        let lastState = null;
        let lastQueue = null;

        setInterval(() => {
            if (!bridgeReady || !spotifyServices) return;

            try {
                // Get playback state
                if (spotifyServices.PlaybackAPI) {
                    const state = spotifyServices.PlaybackAPI.getState?.();
                    if (state && JSON.stringify(state) !== JSON.stringify(lastState)) {
                        lastState = state;
                        currentState = state;
                        postToHost(MESSAGE_TYPES.STATE, { state });
                    }
                }

                // Get queue
                if (spotifyServices.PlayerAPI) {
                    const queue = spotifyServices.PlayerAPI.getQueue?.();
                    if (queue && JSON.stringify(queue) !== JSON.stringify(lastQueue)) {
                        lastQueue = queue;
                        currentQueue = queue;
                        postToHost(MESSAGE_TYPES.QUEUE, { queue });
                    }
                }
            } catch (e) {
                console.warn('[KiloBridge] Polling error:', e);
            }
        }, 500);
    }

    // Execute a call on Spotify services
    function executeCall(method, args) {
        if (!bridgeReady || !spotifyServices) {
            return Promise.reject(new Error('Bridge not ready'));
        }

        const id = ++messageId;

        return new Promise((resolve, reject) => {
            pendingCalls.set(id, { resolve, reject });

            try {
                // Parse method path (e.g., "player.play")
                const parts = method.split('.');
                let service = spotifyServices;

                for (let i = 0; i < parts.length - 1; i++) {
                    service = service[parts[i]];
                    if (!service) {
                        pendingCalls.delete(id);
                        reject(new Error(`Service not found: ${parts[i]}`));
                        return;
                    }
                }

                const fn = service[parts[parts.length - 1]];
                if (typeof fn !== 'function') {
                    pendingCalls.delete(id);
                    reject(new Error(`Method not found: ${method}`));
                    return;
                }

                // Call the method
                const result = fn.apply(service, args);

                // Handle promise
                if (result && typeof result.then === 'function') {
                    result.then(
                        (value) => {
                            pendingCalls.delete(id);
                            resolve(value);
                        },
                        (error) => {
                            pendingCalls.delete(id);
                            reject(error);
                        }
                    );
                } else {
                    pendingCalls.delete(id);
                    resolve(result);
                }
            } catch (e) {
                pendingCalls.delete(id);
                reject(e);
            }
        });
    }

    // Handle messages from Android host
    window.Myspoti = {
        call: function(id, method, args) {
            // This is called via evaluateJavascript from Android
            return executeCall(method, args || []);
        }
    };

    // Also listen for postMessage from Android (alternative channel)
    window.addEventListener('message', (event) => {
        // Only accept messages from our own origin
        if (event.origin !== window.location.origin) return;

        try {
            const data = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
            if (data && data.id && data.method) {
                executeCall(data.method, data.args || [])
                    .then(result => {
                        postToHost(MESSAGE_TYPES.RESULT, { id: data.id, ok: true, value: result });
                    })
                    .catch(error => {
                        postToHost(MESSAGE_TYPES.RESULT, { id: data.id, ok: false, error: error.message });
                    });
            }
        } catch (e) {
            console.warn('[KiloBridge] Message parse error:', e);
        }
    });

    // Initialize when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize);
    } else {
        initialize();
    }

    // Expose for debugging
    window.__KILO_BRIDGE__ = {
        getState: () => currentState,
        getQueue: () => currentQueue,
        services: () => spotifyServices
    };
})();
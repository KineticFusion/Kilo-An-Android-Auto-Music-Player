package com.example.kilo.spotify

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.net.http.SslError
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.kilo.streaming.HybridSourceRegistry

/**
 * Spotify Authentication Manager using in-app WebView (mirrors YouTube BitChordWebViewLoginDialog pattern)
 * Loads accounts.spotify.com/login, captures sp_dc cookie on open.spotify.com redirect
 */
object SpotifyAuthManager {
    private const val TAG = "KiloSpotifyAuth"
    private const val PREFS_NAME = "kilo_spotify_web_session"
    private const val KEY_SP_DC_COOKIE = "sp_dc_cookie"
    private const val KEY_COOKIE_EXPIRY = "cookie_expiry"

    /**
     * Saves the Spotify web session cookie (sp_dc) for API authentication
     */
    fun saveWebSession(context: Context, cookies: String) {
        try {
            // Extract sp_dc cookie
            val spDc = cookies.split("; ").find { it.startsWith("sp_dc=") }?.substringAfter("sp_dc=") ?: return

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Cookie expires in ~1 year typically, but we'll store for 30 days
            val expiry = System.currentTimeMillis() + (30L * 24 * 60 * 60 * 1000)
            prefs.edit()
                .putString(KEY_SP_DC_COOKIE, spDc)
                .putLong(KEY_COOKIE_EXPIRY, expiry)
                .apply()

            // Also save to HybridSourceRegistry for streaming
            HybridSourceRegistry.saveSpotifyWebSession(context, cookies)

            Log.d(TAG, "Spotify web session saved successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save web session", e)
        }
    }

    /**
     * Gets the stored sp_dc cookie for API requests
     */
    fun getSpDcCookie(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cookie = prefs.getString(KEY_SP_DC_COOKIE, null)
        val expiry = prefs.getLong(KEY_COOKIE_EXPIRY, 0)

        if (cookie != null && System.currentTimeMillis() < expiry) {
            return cookie
        }
        return null
    }

    /**
     * Clears all stored session data
     */
    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()

        // Also clear WebView cookies
        try {
            CookieManager.getInstance().apply {
                setCookie("https://open.spotify.com", "sp_dc=; Max-Age=0; Domain=.spotify.com; Path=/")
                setCookie("https://accounts.spotify.com", "sp_dc=; Max-Age=0; Domain=.spotify.com; Path=/")
                flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear cookies", e)
        }

        HybridSourceRegistry.clearSpotifyWebSession(context)
    }

    /**
     * Checks if user has a valid web session (sp_dc cookie)
     */
    fun isLoggedIn(context: Context): Boolean {
        return getSpDcCookie(context) != null
    }
}

/**
 * Spotify Login Dialog - MIRRORS BitChordWebViewLoginDialog exactly
 * Loads Spotify login page in WebView, captures sp_dc cookie on redirect to open.spotify.com
 * Responsive: fills remaining height, dynamic User-Agent, loading indicator
 * Includes manual sp_dc input field (YumaPlayer-style) for debug/backup
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginDialog(
    onLoginSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var showManualInput by remember { mutableStateOf(false) }
    var manualSpDc by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF07070B)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding() // Handle keyboard insets
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF141419))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Sign in to Spotify", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("KILO Direct Session Sync", color = Color(0xFF1DB954), fontSize = 11.sp)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Color.White)
                    }
                }

                // Loading indicator
                if (loading && !showManualInput) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF1DB954),
                        trackColor = Color(0xFF1DB954).copy(alpha = 0.3f),
                        progress = 0f
                    )
                }

                if (showManualInput) {
                    // Manual sp_dc input (YumaPlayer-style debug field)
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Manual sp_dc Cookie", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "Paste your sp_dc cookie from open.spotify.com (F12 → Application → Cookies)",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                        OutlinedTextField(
                            value = manualSpDc,
                            onValueChange = { manualSpDc = it; manualError = null },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF141419),
                                unfocusedContainerColor = Color(0xFF141419),
                                focusedIndicatorColor = Color(0xFF1DB954),
                                unfocusedIndicatorColor = Color.White.copy(alpha = 0.3f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF1DB954)
                            )
                        )
                        manualError?.let { error ->
                            Text(error, color = Color(0xFFE22134), fontSize = 11.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (manualSpDc.isNotBlank()) {
                                        // Validate and save manual sp_dc
                                        val cookies = "sp_dc=${manualSpDc.trim()}"
                                        SpotifyAuthManager.saveWebSession(context, cookies)
                                        onLoginSuccess()
                                    } else {
                                        manualError = "Please enter sp_dc cookie"
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1DB954),
                                    contentColor = Color.Black
                                )
                            ) {
                                Text("Save & Connect", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = { showManualInput = false }) {
                                Text("Back to WebView", color = Color.White.copy(alpha = 0.8f))
                            }
                        }
                    }
                } else {
                    // WebView fills remaining height dynamically (responsive on phone/tablet/foldable)
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = { context ->
                            WebView(context).apply {
                                // Fix 1: Explicit MATCH_PARENT layout params
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                // Fix 4: White background (not black)
                                setBackgroundColor(AndroidColor.WHITE)
                                // Hardware acceleration
                                setLayerType(View.LAYER_TYPE_HARDWARE, null)

                                val settings = this.settings.apply {
                                    // Fix 3: Required WebSettings
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    // Disable algorithmic darkening (API 33+)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        isAlgorithmicDarkeningAllowed = false
                                    }
                                    // Fix 2: Dynamic Device-Adaptive User-Agent (NO hardcoded device)
                                    val defaultUa = userAgentString
                                    userAgentString = defaultUa
                                        .replace("; wv", "")
                                        .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
                                }

                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                // Fix 5: WebChromeClient for console logging + loading state
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                        android.util.Log.d("KiloSpotifyAuth", "[JS Console] ${consoleMessage.message()} (line ${consoleMessage.lineNumber()})")
                                        return super.onConsoleMessage(consoleMessage)
                                    }
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        super.onProgressChanged(view, newProgress)
                                        android.util.Log.d("KiloSpotifyAuth", "Progress: $newProgress%")
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        android.util.Log.d("KiloSpotifyAuth", "Page started: $url")
                                        // Keep loading indicator visible
                                        loading = true
                                    }
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        android.util.Log.d("KiloSpotifyAuth", "Page finished: $url")
                                        loading = false
                                        // Check for sp_dc cookie on open.spotify.com
                                        val cookies = cookieManager.getCookie("https://open.spotify.com") ?: ""
                                        if (cookies.contains("sp_dc")) {
                                            cookieManager.flush()
                                            SpotifyAuthManager.saveWebSession(context, cookies)
                                            onLoginSuccess()
                                        }
                                    }
                                    // Keep ALL navigation inside WebView
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        return false
                                    }
                                    // Fix 5: Error logging
                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                        super.onReceivedError(view, request, error)
                                        android.util.Log.e("KiloSpotifyAuth", "WebView error: ${error?.description} for URL: ${request?.url}")
                                        loading = false
                                    }
                                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                        android.util.Log.e("KiloSpotifyAuth", "SSL error: ${error?.primaryError} for URL: ${error?.url}")
                                        handler?.proceed() // Allow proceeding for testing
                                        loading = false
                                    }
                                }
                                // Load Spotify login page (redirects to open.spotify.com after login)
                                loadUrl("https://accounts.spotify.com/login")
                            }
                        }
                    )
                }

                // Toggle to manual input
                if (!showManualInput) {
                    TextButton(
                        onClick = { showManualInput = true },
                        modifier = Modifier.padding(16.dp).fillMaxWidth()
                    ) {
                        Text("Enter sp_dc manually (debug)", color = Color(0xFF1DB954), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/**
 * Checks if Spotify WebView login dialog should be shown
 */
@Composable
fun maybeShowSpotifyLoginDialog(
    isLoggedIn: Boolean,
    onLoginSuccess: () -> Unit
) {
    var showDialog by remember { mutableStateOf(!isLoggedIn) }

    androidx.compose.runtime.LaunchedEffect(isLoggedIn) {
        if (!isLoggedIn) {
            showDialog = true
        }
    }

    if (showDialog) {
        SpotifyLoginDialog(
            onLoginSuccess = {
                showDialog = false
                onLoginSuccess()
            },
            onDismiss = {
                showDialog = false
            }
        )
    }
}
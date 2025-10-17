package app.rutube.clayza

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import app.rutube.clayza.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URISyntaxException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val adBlockList = mutableSetOf<String>()
    private var isShorts: Boolean = false
    private var logoDataUrl: String = ""
    private var homeImageDataUrl: String = ""
    private var lastUrl: String = HOME_URL

    companion object {
        private const val HOME_URL = "https://m.youtube.com/"
    }

    inner class ClayzaBridge {
        @JavascriptInterface
        fun setShortsMode(isShortsMode: Boolean) {
            runOnUiThread { binding.swipeRefresh.isEnabled = !isShortsMode }
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else emptyArray())
        fileCallback = null
    }

    private val builtInPatterns = listOf(
        "doubleclick.net","googleadservices.com","googlesyndication.com","googletagservices.com",
        "google-analytics.com","googletagmanager.com","pagead2.googlesyndication.com",
        "/api/stats/ads","/api/stats/atr","/ptracking","/pagead/","/ads/","/ad_","/ad?","/ad/",
        "get_video_info","generate_204"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = 0xFF000000.toInt()
        }
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT
        ) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        logoDataUrl = assetPngToDataUrl("logo.png")
        homeImageDataUrl = assetPngToDataUrl("home.jpg")

        loadBlocklist()

        val webView = binding.webView
        val progress = binding.progress

        webView.addJavascriptInterface(ClayzaBridge(), "ClayzaBridge")

        binding.swipeRefresh.setColorSchemeResources(android.R.color.white)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(android.R.color.darker_gray)
        binding.swipeRefresh.setOnRefreshListener {
            if (isOnline()) {
                webView.loadUrl(lastUrl.ifBlank { HOME_URL })
            } else {
                showOfflinePage(webView)
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(this, "Offline — periksa koneksi.", Toast.LENGTH_SHORT).show()
            }
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
            setSupportMultipleWindows(true)
            userAgentString = WebSettings.getDefaultUserAgent(this@MainActivity).replace("; wv", "")
            allowFileAccess = true
            allowContentAccess = true
        }

        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_ON)
            }
        } catch (_: Throwable) {}

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                if (isAdUrl(url)) {
                    Log.d("AdBlock", "Blocked: $url")
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                }
                if (url.contains(".m3u8", ignoreCase = true) && url.contains("ad", ignoreCase = true)) {
                    Log.d("AdBlock", "Blocked m3u8 segment: $url")
                    return WebResourceResponse("application/vnd.apple.mpegurl", "utf-8", ByteArrayInputStream("".toByteArray()))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return route(url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                progress.progress = 0
                url?.let { lastUrl = it }

                isShorts = (url?.contains("/shorts/") == true)
                binding.swipeRefresh.isEnabled = !(url?.contains("/shorts/") == true)

                if (!isOnline()) {
                    showOfflinePage(webView)
                    return
                }

                injectBrandingAndWatch(webView, logoDataUrl, homeImageDataUrl)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                binding.swipeRefresh.isEnabled = !(url?.contains("/shorts/") == true)

                if (!isOnline()) {
                    showOfflinePage(webView)
                    return
                }

                injectPowerAdShield(webView)
                injectBrandingAndWatch(webView, logoDataUrl, homeImageDataUrl)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showOfflinePage(webView)
                }
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                showOfflinePage(webView)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                Toast.makeText(this@MainActivity, "Koneksi tidak aman (SSL Error).", Toast.LENGTH_SHORT).show()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            private var customViewCallback: CustomViewCallback? = null
            private var originalSystemUiVisibility: Int = 0

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val tempView = WebView(this@MainActivity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                            val u = req?.url?.toString() ?: return false
                            if (!route(u)) binding.webView.loadUrl(u)
                            return true
                        }
                    }
                }
                transport.webView = tempView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val mime = fileChooserParams?.acceptTypes?.firstOrNull()?.ifBlank { "*/*" } ?: "*/*"
                pickFile.launch(mime)
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    customViewCallback?.onCustomViewHidden(); return
                }
                customView = view
                customViewCallback = callback
                originalSystemUiVisibility = window.decorView.systemUiVisibility
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                (binding.root as ViewGroup).addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                binding.webView.visibility = View.GONE

                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        )

                requestedOrientation = if (isShorts) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }

            override fun onHideCustomView() {
                customView?.let { (binding.root as ViewGroup).removeView(it) }
                customView = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.decorView.systemUiVisibility = originalSystemUiVisibility
                binding.webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        if (savedInstanceState == null) {
            if (isOnline()) {
                webView.loadUrl(HOME_URL)
            } else {
                showOfflinePage(webView)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun injectPowerAdShield(webView: WebView) {
        val js = """
            (function() {
              try {
                var old = document.getElementById('clayza-style');
                if (old) old.remove();

                var style = document.createElement('style');
                style.id = 'clayza-style';
                style.innerHTML = `
                  ytd-banner-promo-renderer,
                  ytm-promoted-sparkles-web-renderer,
                  ytm-companion-ad-renderer,
                  ytd-action-companion-ad-renderer,
                  ytd-display-ad-renderer,
                  ytd-promoted-video-renderer,
                  ytd-compact-promoted-video-renderer,
                  #player-ads,
                  #masthead-ad,
                  .ad-container,
                  ytm-promoted-sparkles-text-search-renderer,
                  ytm-promoted-video-renderer,
                  ytm-banner-promo-renderer {
                    display: none !important; visibility: hidden !important;
                    height: 0 !important; min-height: 0 !important;
                  }
                  .ytp-ad-skip-button, .ytp-skip-ad-button {
                    opacity: 1 !important; visibility: visible !important;
                    pointer-events: auto !important; z-index: 2147483647 !important; transform: none !important;
                  }
                  .ytp-ad-player-overlay, .ytp-ad-overlay-container { opacity: 1 !important; }
                `;
                document.head.appendChild(style);

                if (window.__cz_removeInterval__) { clearInterval(window.__cz_removeInterval__); }
                window.__cz_removeInterval__ = setInterval(function(){
                  var selectors = [
                    'a[href^="intent://"]',
                    'a[href^="vnd.youtube://"]',
                    'a[href*="play.google.com/store/apps/details?id=com.google.android.youtube"]',
                    'ytd-banner-promo-renderer',
                    'ytm-promoted-sparkles-web-renderer',
                    'ytm-companion-ad-renderer',
                    '#player-ads',
                    '#masthead-ad'
                  ];
                  try {
                    selectors.forEach(function(s){
                      document.querySelectorAll(s).forEach(function(el){ el.remove(); });
                    });
                  } catch(e){}
                }, 1000);

                if (window.__cz_skipObserver__) { window.__cz_skipObserver__.disconnect(); }
                window.__cz_skipObserver__ = new MutationObserver(function(){
                  var skip = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button');
                  if (skip) {
                    skip.style.opacity = '1';
                    skip.style.visibility = 'visible';
                    pointerEvents
                    skip.style.pointerEvents = 'auto';
                    skip.style.zIndex = '2147483647';
                    skip.style.transform = 'none';
                  }
                });
                window.__cz_skipObserver__.observe(document.documentElement, { childList: true, subtree: true });

                function handleAdState(){
                  try {
                    var player = document.querySelector('video.html5-main-video, ytm-player video, video');
                    var isAd = !!document.querySelector('.ad-showing, .ad-interrupting, .ytp-ad-player-overlay');
                    if (player && isAd) {
                      player.muted = true;
                      try {
                        if (player.duration && isFinite(player.duration) && player.duration > 1) {
                          var target = Math.max(0, player.duration - 0.3);
                          if (!isNaN(target) && isFinite(target) && target > 0) {
                            player.currentTime = target;
                          }
                        }
                      } catch(e){}
                      var btn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button');
                      if (btn && typeof btn.click === 'function') { btn.click(); }
                      var closeBtns = document.querySelectorAll('.ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container, .ytm-close, .close-button');
                      closeBtns.forEach(function(b){ try { b.click(); } catch(e){} });
                      var skip = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button');
                      if (skip) {
                        skip.style.opacity = '1';
                        skip.style.visibility = 'visible';
                        skip.style.pointerEvents = 'auto';
                        skip.style.zIndex = '2147483647';
                      }
                    }
                  } catch(e){}
                }

                if (window.__cz_adWatchdog__) { clearInterval(window.__cz_adWatchdog__); }
                window.__cz_adWatchdog__ = setInterval(handleAdState, 500);

                if (window.__cz_adSkip__) { clearInterval(window.__cz_adSkip__); }
                window.__cz_adSkip__ = setInterval(function(){
                  try {
                    var btn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button');
                    if (btn) { btn.click(); }
                  } catch(e){}
                }, 700);

                var css2 = document.getElementById('clayza-style-2');
                if (css2) css2.remove();
                css2 = document.createElement('style');
                css2.id = 'clayza-style-2';
                css2.innerHTML = `
                  html, body, ytm-app, ytd-app { background: var(--cz-bg, #0f0f0f) !important; color: var(--cz-fg, #fff) !important; }
                  a { color: #b8c7ff !important; }
                  .compact-media-item, .ytm-rich-item-renderer, .ytm-item-list-section-renderer, .ytm-media-item, .ytm-pivot-bar-item-tab {
                    background: transparent !important; border-color: rgba(255,255,255,0.08) !important;
                  }
                  .chip, .ytm-chip-cloud-chip-renderer { background: rgba(255,255,255,0.08) !important; border-radius: 999px !important; }
                  ytm-searchbox, ytd-searchbox, form[role="search"] input, input#search {
                    border-radius: 12px !important; padding: 10px 14px !important;
                    border: 1px solid rgba(255,255,255,0.06) !important;
                    box-shadow: 0 2px 12px rgba(0,0,0,0.05) !important;
                  }
                  ytm-search-suggestions, .sbsb_b, .sbdd_b, .ytm-search-suggestions {
                    background: var(--cz-bg, #111) !important; border-radius: 12px !important;
                    border: 1px solid rgba(255,255,255,0.08) !important; box-shadow: 0 8px 24px rgba(0,0,0,0.25) !important;
                    overflow: hidden !important;
                  }
                  ytm-search-suggestions .sbsb_c, .sbsb_c, .sbdd_a, ytm-search-suggestions li {
                    padding: 12px 14px !important; font-size: 14px !important;
                    border-bottom: 1px solid rgba(255,255,255,0.06) !important;
                  }
                  ytm-search-suggestions .sbsb_c:last-child, .sbsb_c:last-child, ytm-search-suggestions li:last-child { border-bottom: 0 !important; }
                  ytm-search-suggestions .sbsb_c:hover, .sbsb_c:hover, ytm-search-suggestions li:hover { background: rgba(255,255,255,0.05) !important; }
                `;
                document.head.appendChild(css2);

              } catch(e) {}
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun injectBrandingAndWatch(webView: WebView, logoDataUrl: String, homeImageDataUrl: String) {
        val js = """
    (function(){
      try {
        var LOGO_URL = ${jsonString(logoDataUrl)};
        var HOME_IMG_URL = ${jsonString(homeImageDataUrl)};
        var GAP_PX = 48;

        var HOME_EMPTY_TITLE = "Selamat Datang di RuTube";
        var HOME_EMPTY_SUBTITLE = "Di sini tidak ada drama. Hanya ada oshi kamu yang sudah terminated karena drama 😔💚";

        if (!document.getElementById('cz-logo-hide-native')) {
          var hide = document.createElement('style');
          hide.id = 'cz-logo-hide-native';
          hide.textContent = `
            :root { --cz-chip-top: 0px; }

            #home-icon svg,
            #home-icon ytm-logo,
            #home-icon #yt-ringo2-svg_yt1,
            #home-icon #youtube-paths_yt1,
            ytm-logo,
            ytm-mobile-topbar-renderer ytm-logo,
            #masthead-logo,
            [id^="yt-ringo2-svg_"] {
              display: none !important; visibility: hidden !important;
            }

            ytm-related-chip-cloud-renderer.chips-fixed-positioning {
              position: fixed !important;
              left: 0 !important; right: 0 !important;
              top: var(--cz-chip-top, 0px) !important;
              z-index: 2 !important;
              box-shadow: 0 2px 8px rgba(0,0,0,0.15);
              pointer-events: auto;
            }
          `;
          document.head.appendChild(hide);
        }

        function ensureSingleLogo(container) {
          if (!container) return;
          container.querySelectorAll('svg, ytm-logo, [id^="yt-ringo2-svg_"], #yt-ringo2-svg_yt1, #youtube-paths_yt1')
                   .forEach(function(n){ try{ n.remove(); }catch(e){} });
          container.querySelectorAll('img:not(#cz-logo)')
                   .forEach(function(n){ try{ n.remove(); }catch(e){} });
          var cz = container.querySelector('#cz-logo');
          if (!cz) {
            cz = document.createElement('img');
            cz.id = 'cz-logo';
            cz.src = LOGO_URL;
            cz.alt = 'Logo';
            cz.setAttribute('draggable', 'false');
            cz.style.cssText = 'display:block;width:100%;height:100%;object-fit:contain;pointer-events:none;';
            container.appendChild(cz);
          } else {
            if (cz.src !== LOGO_URL) cz.src = LOGO_URL;
            cz.style.cssText = 'display:block;width:100%;height:100%;object-fit:contain;pointer-events:none;';
          }
        }

        function replaceLogo() {
          var container =
              document.querySelector('#home-icon > span > div') ||
              document.querySelector('#home-icon > span') ||
              document.querySelector('#home-icon') ||
              document.querySelector('#masthead #home-icon') ||
              document.querySelector('ytm-mobile-topbar-renderer #home-icon');
          if (!container) return;
          ensureSingleLogo(container);
        }
        
        function enforceHeaderLogo() {
          var headerLogo = document.querySelector('ytm-home-logo img.entity-logo, .mobile-topbar-logo, .entity-logo-container img');
          if (headerLogo) {
            if (!headerLogo.dataset.czReplaced || /gstatic|youtube|ytimg/.test(headerLogo.src)) {
              headerLogo.src = LOGO_URL;
              headerLogo.dataset.czReplaced = '1';
            }
            headerLogo.alt = 'RuTube';
            headerLogo.style.height = '20px';
            headerLogo.style.width  = 'auto';
            headerLogo.style.maxWidth = '100px';
            headerLogo.style.objectFit = 'contain';
            headerLogo.style.display = 'block';
            headerLogo.style.margin = '2px auto';
            headerLogo.style.filter = 'drop-shadow(0 1px 2px rgba(0,0,0,0.25))';
          }
          var container = headerLogo?.closest('.entity-logo-container, ytm-home-logo, .mobile-topbar-header');
          if (container) {
            container.style.display = 'flex';
            container.style.alignItems = 'center';
            container.style.justifyContent = 'center';
            container.style.height = '44px';
            container.style.overflow = 'hidden';
          }
        }

        function restyleEmptyHome() {
          var entry = document.querySelector('ytm-search-bar-entry-point-view-model');
          if (entry) {
            var img = entry.querySelector('img.search-bar-entry-point-top-image, #cz-home-empty-logo');
            var allImgs = entry.querySelectorAll('img.search-bar-entry-point-top-image, #cz-home-empty-logo');
            if (allImgs.length > 1) {
              for (var i = 1; i < allImgs.length; i++) { try { allImgs[i].remove(); } catch(e){} }
            }
            if (img) {
              if (!img.dataset.czReplaced || /gstatic|youtube_main_icon/.test(img.src)) {
                img.src = HOME_IMG_URL;
                img.dataset.czReplaced = '1';
              }
              img.style.width = '90%';
              img.style.maxWidth = '320px';
              img.style.height = 'auto';
              img.style.objectFit = 'contain';
              img.style.display = 'block';
              img.style.margin = '12px auto 16px';
              img.style.borderRadius = '16px';
              img.style.boxShadow = '0 6px 18px rgba(0,0,0,0.25)';
            } else {
              var btnWrap = entry.querySelector('.search-bar-entry-point-buttons');
              if (btnWrap) {
                var newImg = document.createElement('img');
                newImg.id = 'cz-home-empty-logo';
                newImg.src = HOME_IMG_URL;
                newImg.alt = 'Home Illustration';
                newImg.dataset.czReplaced = '1';
                newImg.style.cssText = 'width:90%;max-width:320px;height:auto;object-fit:contain;display:block;margin:12px auto 16px;border-radius:16px;box-shadow:0 6px 18px rgba(0,0,0,0.25);';
                entry.insertBefore(newImg, btnWrap);
              }
            }
          }
          var nudge = document.querySelector('ytm-feed-nudge-renderer .feed-nudge-text-container');
          if (nudge) {
            var title = nudge.querySelector('.feed-nudge-title .yt-core-attributed-string');
            var subtitle = nudge.querySelector('.feed-nudge-subtitle .yt-core-attributed-string');
            if (title && title.textContent !== HOME_EMPTY_TITLE) title.textContent = HOME_EMPTY_TITLE;
            if (subtitle && subtitle.textContent !== HOME_EMPTY_SUBTITLE) subtitle.textContent = HOME_EMPTY_SUBTITLE;
          }
        }

        function createSakuraStyleOnce() {
          if (document.getElementById('cz-sakura-style')) return;
          var st = document.createElement('style');
          st.id = 'cz-sakura-style';
          st.textContent = `
            @keyframes cz-fall {
              0%   { transform: translate3d(var(--x,0), -10%, 0) rotate(0deg); opacity: 0; }
              10%  { opacity: 1; }
              100% { transform: translate3d(calc(var(--x,0) + var(--drift, 0px)), 110vh, 0) rotate(360deg); opacity: 1; }
            }
            .cz-sakura-layer {
              position: fixed; inset: 0;
              pointer-events: none; z-index: 9999;
              overflow: hidden;
            }
            .cz-petal {
              position: absolute;
              top: -10vh;
              line-height: 1;
              user-select: none;
              pointer-events: none;
              animation: cz-fall var(--dur, 8s) linear var(--delay, 0s) infinite;
              will-change: transform, opacity;
              transform: translate3d(0,-10%,0) rotate(0deg);
            }
          `;
          document.head.appendChild(st);
        }

        function enableSakuraEffect() {
          if (document.getElementById('cz-sakura-layer')) return;
          createSakuraStyleOnce();
          var layer = document.createElement('div');
          layer.id = 'cz-sakura-layer';
          layer.className = 'cz-sakura-layer';
          document.body.appendChild(layer);
          var PETALS = 24;
          for (var i = 0; i < PETALS; i++) {
            var p = document.createElement('div');
            p.className = 'cz-petal';
            p.textContent = '🌸';
            var startX = Math.random() * window.innerWidth;
            var drift = (Math.random() * 120 - 60) + 'px';
            var delay = (Math.random() * 6) + 's';
            var dur = (8 + Math.random() * 6) + 's';
            var fs = (16 + Math.random() * 10) + 'px';
            p.style.left = startX + 'px';
            p.style.fontSize = fs;
            p.style.setProperty('--x', '0px');
            p.style.setProperty('--drift', drift);
            p.style.setProperty('--delay', delay);
            p.style.setProperty('--dur', dur);
            layer.appendChild(p);
          }
        }

        function disableSakuraEffect() {
          var layer = document.getElementById('cz-sakura-layer');
          if (layer) try { layer.remove(); } catch(e){}
        }

        function updateSakuraForRoute() {
          var path = location.pathname || '';
          if (path.toLowerCase().indexOf('/@uruharushia') === 0) {
            enableSakuraEffect();
          } else {
            disableSakuraEffect();
          }
        }

        function measurePlayerBottomPx() {
          var player = document.querySelector('.player-container')
                   || document.querySelector('ytm-player')
                   || (document.querySelector('video') && document.querySelector('video').closest('.player-container'));
          if (!player) return 0;
          var rect = player.getBoundingClientRect();
          return Math.max(0, Math.round(rect.bottom + GAP_PX));
        }

        function updateChipTop() {
          var isWatch = location.pathname.indexOf('/watch') === 0 || /[?&]v=/.test(location.search);
          if (!isWatch) {
            document.documentElement.style.setProperty('--cz-chip-top', '0px');
            return;
          }
          var px = measurePlayerBottomPx();
          if (!px || px < 0) px = 56 + GAP_PX;
          document.documentElement.style.setProperty('--cz-chip-top', px + 'px');
        }

        function nukeWatchHeader() {
          var isWatch = location.pathname.indexOf('/watch') === 0 || /[?&]v=/.test(location.search);
          if (!isWatch) {
            var s = document.getElementById('cz-watch-fix');
            if (s) s.remove();
            return;
          }
          ['#header-bar > header','#header-bar','ytm-mobile-topbar-renderer','ytm-persistent-header-renderer','ytm-header-bar']
            .forEach(function(sel){
              document.querySelectorAll(sel).forEach(function(el){ try{ el.remove(); }catch(e){} });
            });
          if (!document.getElementById('cz-watch-fix')) {
            var st = document.createElement('style');
            st.id = 'cz-watch-fix';
            st.textContent = `
              ytm-app.sticky-player { padding-top: 0 !important; }
              .player-container.sticky-player { top: 0 !important; }
              :root { --ytd-masthead-height: 0px !important; }
            `;
            document.head.appendChild(st);
          }
          updateChipTop();
        }

        function updateShortsMode() {
          var isShorts = location.pathname.indexOf('/shorts/') === 0 || location.pathname.includes('/shorts/');
          try { if (window.ClayzaBridge && typeof ClayzaBridge.setShortsMode === 'function') {
            ClayzaBridge.setShortsMode(isShorts);
          }} catch(e){}
        }

        replaceLogo();
        nukeWatchHeader();
        updateChipTop();
        updateShortsMode();
        restyleEmptyHome();
        updateSakuraForRoute();
        enforceHeaderLogo();

        if (!window.__cz_brandingObs2__) {
          window.__cz_brandingObs2__ = new MutationObserver(function(){
            try {
              replaceLogo();
              nukeWatchHeader();
              updateChipTop();
              updateShortsMode();
              restyleEmptyHome();
              updateSakuraForRoute();
              enforceHeaderLogo();
            } catch(e){}
          });
          window.__cz_brandingObs2__.observe(document.documentElement, { childList: true, subtree: true });
        }

        if (!window.__cz_historyPatched2__) {
          window.__cz_historyPatched2__ = true;
          ['pushState','replaceState'].forEach(function(k){
            var orig = history[k];
            history[k] = function(){
              var ret = orig.apply(this, arguments);
              try {
                replaceLogo();
                nukeWatchHeader();
                updateChipTop();
                updateShortsMode();
                restyleEmptyHome();
                updateSakuraForRoute();
                enforceHeaderLogo();
              } catch(e){}
              return ret;
            }
          });
          window.addEventListener('popstate', function(){
            try {
              replaceLogo();
              nukeWatchHeader();
              updateChipTop();
              updateShortsMode();
              restyleEmptyHome();
              updateSakuraForRoute();
              enforceHeaderLogo();
            } catch(e){}
          });
        }

        if (!window.__cz_chipListeners__) {
          window.__cz_chipListeners__ = true;
          window.addEventListener('scroll', function(){ try { updateChipTop(); } catch(e){} }, { passive: true });
          window.addEventListener('resize', function(){ try { updateChipTop(); } catch(e){} });
          var retry = 0, t = setInterval(function(){
            try {
              updateChipTop();
              restyleEmptyHome();
              updateSakuraForRoute();
            } catch(e){}
            if (++retry > 15) clearInterval(t);
          }, 300);
        }

      } catch(e) {}
    })();
    """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun assetPngToDataUrl(name: String): String {
        return try {
            assets.open(name).use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                var read: Int
                while (true) {
                    read = input.read(chunk)
                    if (read == -1) break
                    buffer.write(chunk, 0, read)
                }
                val base64 = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
                "data:image/png;base64,$base64"
            }
        } catch (e: Exception) {
            Log.e("Logo", "Gagal baca asset $name: ${e.message}")
            ""
        }
    }

    private fun jsonString(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun loadBlocklist() {
        try {
            val inputStream = assets.open("blocklist.txt")
            inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        adBlockList.add(trimmed.lowercase())
                    }
                }
            }
            Log.d("AdBlock", "Loaded ${adBlockList.size} domains from blocklist")
            Toast.makeText(this, "Selamat Datang Di RuTube", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w("AdBlock", "No blocklist.txt, using built-ins")
            adBlockList.addAll(builtInPatterns.map { it.lowercase() })
        }
    }

    private fun isAdUrl(url: String): Boolean {
        val u = url.lowercase()
        if (adBlockList.any { d -> u.contains(d) }) return true
        return builtInPatterns.any { p -> u.contains(p.lowercase()) }
    }

    private fun route(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme ?: ""
        val host = uri.host ?: ""
        val isHttp = scheme == "http" || scheme == "https"

        if (url.startsWith("intent://") ||
            scheme.startsWith("vnd.") ||
            scheme == "market" ||
            host.equals("play.google.com", true)
        ) {
            if (url.startsWith("intent://")) {
                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    if (!fallback.isNullOrBlank()) {
                        binding.webView.loadUrl(fallback)
                        return true
                    }
                } catch (_: URISyntaxException) { }
            }
            if (host.equals("play.google.com", true) || scheme == "market" ||
                url.startsWith("vnd.youtube://") || url.startsWith("intent://")
            ) {
                binding.webView.loadUrl(HOME_URL)
                return true
            }
            return true
        }

        val keepInside = listOf(
            "m.youtube.com", "youtube.com", "www.youtube.com", "youtu.be",
            "accounts.google.com", "consent.youtube.com", "myaccount.google.com",
            "www.google.com", "consent.google.com", "policies.google.com"
        )
        if (isHttp && keepInside.any { host.endsWith(it) }) {
            return false
        }

        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun showOfflinePage(webView: WebView) {
        val html = """
            <!doctype html>
            <html lang="id">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>Offline</title>
              <style>
                :root { color-scheme: dark; }
                html, body {
                  margin:0; padding:0; height:100%;
                  background:#0f0f0f; color:#e6e6e6;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", "Apple Color Emoji","Segoe UI Emoji","Segoe UI Symbol","Noto Color Emoji", sans-serif;
                }
                .wrap {
                  min-height:100%;
                  display:flex; flex-direction:column;
                  align-items:center; justify-content:center;
                  padding:24px 16px;
                  text-align:center;
                }
                .card {
                  max-width:720px; width:100%;
                  background:rgba(255,255,255,0.04);
                  border:1px solid rgba(255,255,255,0.08);
                  border-radius:16px;
                  padding:16px;
                  box-shadow:0 10px 30px rgba(0,0,0,0.35);
                }
                .img {
                  width:100%;
                  border-radius:12px;
                  display:block;
                  aspect-ratio:16/9;
                  object-fit:cover;
                  margin-bottom:12px;
                }
                h1 { font-size:20px; margin:8px 0 6px; }
                p { opacity:0.9; margin:0 0 14px; line-height:1.5 }
                .btns { display:flex; gap:12px; justify-content:center; flex-wrap:wrap; }
                button {
                  appearance:none; border:none; border-radius:999px;
                  padding:10px 16px; font-weight:600; cursor:pointer;
                  background:#ffffff; color:#111; transition:transform .08s ease;
                }
                button:hover { transform: translateY(-1px); }
                .muted { font-size:12px; opacity:.7; margin-top:10px }
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="card">
                  <img class="img" src="file:///android_asset/error.jpg" alt="Offline" />
                  <h1>Tidak ada koneksi internet</h1>
                  <p>Periksa jaringan kamu, lalu coba lagi. Kamu juga bisa tarik turun untuk refresh saat sudah online.</p>
                  <div class="btns">
                    <button onclick="location.href='${HOME_URL}'">Coba ke Beranda</button>
                    <button onclick="location.reload()">Muat Ulang</button>
                  </div>
                  <div class="muted">Sayonara Uruha Rushia....</div>
                </div>
              </div>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(
            "file:///android_asset/",
            html,
            "text/html",
            "utf-8",
            null
        )
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(nw) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                            || caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH))
        } else {
            @Suppress("DEPRECATION")
            val ni = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            ni != null && ni.isConnected
        }
    }
}

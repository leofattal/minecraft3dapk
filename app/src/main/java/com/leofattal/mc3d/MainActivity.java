package com.leofattal.mc3d;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import com.leia.sdk.LeiaSDK;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hosts the bundled three.js voxel game (assets/www) in a WebView and wires
 * it to the Leia lightfield display:
 *
 *  - The page runs fully offline from assets via WebViewAssetLoader
 *    (served from https://appassets.androidxfer.dev).
 *  - A bundled WebXR shim lets the page start immersive sessions; each eye
 *    is rendered into one half of a side-by-side framebuffer.
 *  - {@link SurfaceAwareWebView} redirects the WebView's output into a
 *    SurfaceTexture consumed by {@link GameView}'s CNSDK interlacer, and the
 *    SDK switches the display backlight to stereo mode with face tracking.
 *  - On non-Leia devices the SDK initialization fails gracefully and the game
 *    runs as a normal 2D (or on-screen SBS preview) experience.
 */
public class MainActivity extends Activity {
    private static final String TAG = "MC3D";
    private static final String START_URL =
            "https://appassets.androidxfer.dev/assets/www/index.html";
    private static final int CAMERA_REQUEST = 100;

    private GameView gameView;
    private SurfaceAwareWebView webView;

    private LeiaSDK leiaSdk;
    private boolean leiaInitDone = false;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sdkExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stereoActive = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        gameView = findViewById(R.id.game_view);
        webView = findViewById(R.id.web_view);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/",
                        new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setBackgroundColor(Color.WHITE);
        webView.addJavascriptInterface(new LeiaBridge(), "NativeLeia");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d("WebConsole", "[" + message.messageLevel() + "] "
                        + message.message() + " @" + message.lineNumber()
                        + " (" + message.sourceId() + ")");
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // Opaque background covers the (behind-window) interlacer view
                // while running in 2D mode.
                webView.setBackgroundColor(Color.WHITE);
            }
        });

        webView.loadUrl(START_URL);

        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }

        initLeiaSdkInBackground();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onBackPressed() {
        // The XR session pushes a history entry so this also exits 3D mode
        // via the page's popstate handler before actually leaving.
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        gameView.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        gameView.onResume();
        webView.onResume();
        if (leiaSdk != null) {
            try {
                leiaSdk.onResume();
            } catch (Throwable t) {
                Log.w(TAG, "leiaSdk.onResume: " + t);
            }
        }
    }

    @Override
    protected void onDestroy() {
        sdkExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST
                && (grantResults.length == 0
                    || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Log.w(TAG, "Camera permission denied; 3D face tracking may not work");
        }
    }

    /** Initializes the Leia SDK off the main thread. Safe to call once. */
    private void initLeiaSdkInBackground() {
        sdkExecutor.execute(this::ensureLeiaSdk);
    }

    private synchronized void ensureLeiaSdk() {
        if (leiaInitDone) {
            return;
        }
        leiaInitDone = true;
        try {
            LeiaSDK.InitArgs args = new LeiaSDK.InitArgs();
            args.platform.context = getApplicationContext();
            args.enableFaceTracking = true;
            args.requiresFaceTrackingPermissionCheck = false;
            leiaSdk = LeiaSDK.createSDK(args);
            Log.i(TAG, "Leia SDK initialized: " + (leiaSdk != null));
        } catch (Throwable t) {
            Log.w(TAG, "Leia SDK unavailable (non-Leia device?): " + t);
            leiaSdk = null;
        }
    }

    private void setStereoMode(final boolean enabled) {
        main.post(() -> {
            if (enabled) {
                if (stereoActive.getAndSet(true)) {
                    return;
                }
                if (leiaSdk != null) {
                    try {
                        leiaSdk.enableBacklight(true);
                    } catch (Throwable t) {
                        Log.w(TAG, "enableBacklight(true): " + t);
                    }
                }
                webView.setRenderSurface(gameView.getWebViewSurface());
                gameView.setElevation(2f);
            } else {
                if (!stereoActive.getAndSet(false)) {
                    return;
                }
                webView.setRenderSurface(null);
                gameView.setElevation(0f);
                webView.setBackgroundColor(Color.WHITE);
                if (leiaSdk != null) {
                    try {
                        leiaSdk.enableBacklight(false);
                    } catch (Throwable t) {
                        Log.w(TAG, "enableBacklight(false): " + t);
                    }
                }
            }
        });
    }

    /** Exposed to the page as window.NativeLeia. All calls arrive on the
     *  WebView's JS bridge thread. */
    private class LeiaBridge {

        @JavascriptInterface
        public boolean isLeiaDevice() {
            // Optimistic while initialization is still in flight.
            return leiaSdk != null || !leiaInitDone;
        }

        @JavascriptInterface
        public void enable3D() {
            sdkExecutor.execute(() -> {
                ensureLeiaSdk();
                setStereoMode(true);
            });
        }

        @JavascriptInterface
        public void disable3D() {
            sdkExecutor.execute(() -> setStereoMode(false));
        }

        @JavascriptInterface
        public void vibrate(int milliseconds) {
            try {
                Vibrator vibrator = getSystemService(Vibrator.class);
                if (vibrator != null) {
                    vibrator.vibrate(Math.max(1, Math.min(100, milliseconds)));
                }
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public String deviceInfo() {
            return Build.MANUFACTURER + " " + Build.MODEL
                    + " (Android " + Build.VERSION.RELEASE + ", SDK " + Build.VERSION.SDK_INT + ")";
        }
    }
}

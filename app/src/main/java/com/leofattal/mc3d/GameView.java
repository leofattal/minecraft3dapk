package com.leofattal.mc3d;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.Surface;

import com.leia.sdk.views.InputViewsAsset;
import com.leia.sdk.views.InterlacedSurfaceView;

/**
 * The Leia interlaced surface view. Owns the SurfaceTexture that the WebView
 * renders into, and runs the CNSDK interlacer that converts the WebView's
 * side-by-side stereo output into the lightfield pattern shown on the
 * glasses-free 3D display (with face tracking).
 *
 * In 2D mode this view sits at elevation 0 behind the opaque WebView; in 3D
 * mode the activity raises its elevation so it composites on top.
 */
public class GameView extends InterlacedSurfaceView {
    private final QuadRenderer quadRenderer = new QuadRenderer();
    private final SurfaceTexture webTexture;
    private final Surface webSurface;

    public GameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // Created detached; QuadRenderer attaches it to the interlacer's GL context.
        webTexture = new SurfaceTexture(false);
        webSurface = new Surface(webTexture);
        quadRenderer.setSource(webTexture);
        setViewAsset(new InputViewsAsset(new InterlacedAsset(quadRenderer)));

        addOnLayoutChangeListener((v, left, top, right, bottom,
                                    oldLeft, oldTop, oldRight, oldBottom) -> {
            int w = right - left;
            int h = bottom - top;
            if (w > 0 && h > 0) {
                webTexture.setDefaultBufferSize(w, h);
            }
        });
    }

    /** The Surface the WebView should render into while 3D mode is active. */
    public Surface getWebViewSurface() {
        return webSurface;
    }
}

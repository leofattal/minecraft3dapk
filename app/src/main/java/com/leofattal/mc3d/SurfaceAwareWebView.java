package com.leofattal.mc3d;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.Surface;
import android.webkit.WebView;

/**
 * A WebView that can redirect its rendered output into an arbitrary Surface.
 *
 * In 2D mode (renderSurface == null) it draws normally to the screen. When a
 * Surface is attached (3D mode) every draw is redirected into that Surface,
 * whose backing SurfaceTexture is consumed by the Leia interlacer and shown
 * as a glasses-free stereo pair on the lightfield display.
 */
public class SurfaceAwareWebView extends WebView {
    private volatile Surface renderSurface;

    public SurfaceAwareWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setRenderSurface(Surface surface) {
        this.renderSurface = surface;
    }

    @SuppressLint({"CanvasSize", "DrawAllocation"})
    @Override
    public void draw(Canvas canvas) {
        Surface surface = renderSurface;
        if (surface != null && surface.isValid()) {
            try {
                Canvas surfaceCanvas = surface.lockHardwareCanvas();
                try {
                    float scale = surfaceCanvas.getWidth() / (float) canvas.getWidth();
                    surfaceCanvas.save();
                    surfaceCanvas.scale(scale, scale);
                    surfaceCanvas.translate(-getScrollX(), -getScrollY());
                    surfaceCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    super.draw(surfaceCanvas);
                } finally {
                    surfaceCanvas.restore();
                    surface.unlockCanvasAndPost(surfaceCanvas);
                }
            } catch (IllegalStateException e) {
                // Surface was released mid-draw; fall through to normal drawing.
                super.draw(canvas);
            }
        } else {
            super.draw(canvas);
        }
    }
}

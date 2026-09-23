package com.leofattal.mc3d;

import android.graphics.SurfaceTexture;
import android.opengl.GLES20;

import com.leia.sdk.graphics.Interlacer;
import com.leia.sdk.views.InputGLBinding;
import com.leia.sdk.views.InputViewsAsset;
import com.leia.sdk.views.InterlacedRenderer;

/**
 * Glue between the app's {@link QuadRenderer} and the Leia CNSDK interlacer.
 *
 * The CNSDK drives rendering: {@code update()} prepares an offscreen
 * framebuffer owned by us, and {@code render()} binds it, lets the
 * QuadRenderer paint the WebView's side-by-side stereo image into it, then
 * hands the texture to {@link Interlacer#doPostProcess} which writes the
 * lightfield-interlaced image to the display.
 */
public class InterlacedAsset extends InputViewsAsset.Impl {

    private final QuadRenderer renderer;

    public InterlacedAsset(QuadRenderer renderer) {
        super(null);
        this.renderer = renderer;
    }

    @Override
    protected InputGLBinding createGLBinding() {
        return new Binding();
    }

    private class Binding extends InputGLBinding {
        private final InputGLBinding.Texture texture = new InputGLBinding.Texture();
        private int framebuffer = -1;
        private int width = -1;
        private int height = -1;
        private boolean textureNeedsAlloc = true;

        Binding() {
            super(InterlacedAsset.this);
            texture.glId = -1;
            texture.glType = -1;
        }

        @Override
        protected void reset() {
            if (isValidGLContext()) {
                if (framebuffer >= 0) {
                    GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
                }
                if (texture.glId >= 0) {
                    GLES20.glDeleteTextures(1, new int[]{texture.glId}, 0);
                }
            }
            framebuffer = -1;
            texture.glId = -1;
            texture.glType = -1;
            textureNeedsAlloc = true;
            width = height = -1;
            super.reset();
        }

        @Override
        protected void update(InterlacedRenderer interlacer, boolean isProtected) {
            super.update(interlacer, isProtected);
            if (!mIsValid) {
                return;
            }
            InterlacedAsset asset = updateAsset(InterlacedAsset.class);
            if (asset == null) {
                return;
            }
            if (texture.glId == -1) {
                initTexture();
                initFramebuffer();
                textureNeedsAlloc = true;
                renderer.onSurfaceCreated();
            }
        }

        @Override
        protected void render(Interlacer interlacer, int viewportWidth, int viewportHeight) {
            InterlacedAsset asset = updateAsset(InterlacedAsset.class);
            if (asset == null || texture.glId == -1) {
                return;
            }

            if (width != viewportWidth || height != viewportHeight) {
                renderer.onSurfaceChanged(viewportWidth, viewportHeight);
                width = viewportWidth;
                height = viewportHeight;
                textureNeedsAlloc = true;
            }

            if (prepareFramebuffer()) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
                renderer.onDrawFrame();
            }
            interlacer.doPostProcess(viewportWidth, viewportHeight, texture.glId, texture.glType);
        }

        private void initTexture() {
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            texture.glId = ids[0];
            texture.glType = GLES20.GL_TEXTURE_2D;
        }

        private void initFramebuffer() {
            int[] ids = new int[1];
            GLES20.glGenFramebuffers(1, ids, 0);
            framebuffer = ids[0];
        }

        private boolean prepareFramebuffer() {
            if (framebuffer < 0 || width <= 0 || height <= 0 || texture.glId < 0) {
                return false;
            }
            if (!textureNeedsAlloc) {
                return true;
            }
            textureNeedsAlloc = false;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.glId);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, width, height, 0,
                    GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, texture.glId, 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                android.util.Log.w("InterlacedAsset", "incomplete framebuffer: " + status);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                return false;
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            return true;
        }
    }
}

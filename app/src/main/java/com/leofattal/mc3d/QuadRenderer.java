package com.leofattal.mc3d;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.AttributeSet;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Draws a single SurfaceTexture (the WebView content) as a fullscreen
 * quad into whatever framebuffer is currently bound. Used inside the
 * Leia interlacer's render pass: the WebView's side-by-side stereo
 * output is drawn into the interlacer's texture, which is then
 * post-processed onto the lightfield display.
 */
public class QuadRenderer {
    private static final String TAG = "QuadRenderer";

    private SurfaceTexture source;
    private int textureId = -1;
    private volatile boolean stale = false;

    private int program = -1;
    private int posLocation = -1;
    private int texCoordLocation = -1;
    private int mvLocation = -1;
    private int texLocation = -1;

    private int width = 1;
    private int height = 1;

    // Simple fullscreen quad, drawn with a matrix so callers can position it.
    private static final FloatBuffer SQUARE_POS = floatBuffer(
            -1f, +1f,
            -1f, -1f,
            +1f, -1f,
            +1f, +1f);
    private static final FloatBuffer SQUARE_TEX = floatBuffer(
            0f, 0f,
            0f, 1f,
            1f, 1f,
            1f, 0f);
    private static final ShortBuffer SQUARE_IDX = shortBuffer((short) 0, (short) 1, (short) 2,
            (short) 0, (short) 2, (short) 3);
    private static final float[] IDENTITY = new float[]{
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1};

    public void setSource(SurfaceTexture texture) {
        this.source = texture;
    }

    /** Runs on the interlacer's GL thread. */
    public void onSurfaceCreated() {
        if (source == null) {
            Log.w(TAG, "No source SurfaceTexture set");
            return;
        }
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        textureId = ids[0];
        source.setOnFrameAvailableListener(t -> stale = true);
        source.attachToGLContext(textureId);

        int vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            Log.e(TAG, "link: " + GLES20.glGetProgramInfoLog(program));
        }
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        posLocation = GLES20.glGetAttribLocation(program, "a_Pos");
        texCoordLocation = GLES20.glGetAttribLocation(program, "a_TexCoord");
        mvLocation = GLES20.glGetUniformLocation(program, "u_MV");
        texLocation = GLES20.glGetUniformLocation(program, "u_Texture");
    }

    public void onSurfaceChanged(int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
    }

    /** Runs on the interlacer's GL thread with the interlacer FBO bound. */
    public void onDrawFrame() {
        if (source == null || textureId == -1 || program == -1) {
            return;
        }
        if (stale) {
            stale = false;
            try {
                source.updateTexImage();
            } catch (Exception e) {
                Log.w(TAG, "updateTexImage: " + e);
                return;
            }
        }

        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(texLocation, 0);
        GLES20.glUniformMatrix4fv(mvLocation, 1, false, IDENTITY, 0);

        GLES20.glVertexAttribPointer(posLocation, 2, GLES20.GL_FLOAT, false, 0, SQUARE_POS);
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, SQUARE_TEX);
        GLES20.glEnableVertexAttribArray(posLocation);
        GLES20.glEnableVertexAttribArray(texCoordLocation);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, SQUARE_IDX);
    }

    private static int compile(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "compile: " + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private static final String VERTEX_SHADER = ""
            + "attribute vec4 a_Pos;\n"
            + "attribute vec2 a_TexCoord;\n"
            + "uniform mat4 u_MV;\n"
            + "varying vec2 v_TexCoord;\n"
            + "void main() {\n"
            + "  gl_Position = u_MV * a_Pos;\n"
            + "  // the Leia compositor expects the texture flipped vertically\n"
            + "  v_TexCoord = vec2(a_TexCoord.x, 1.0 - a_TexCoord.y);\n"
            + "}\n";

    private static final String FRAGMENT_SHADER = ""
            + "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\n"
            + "varying vec2 v_TexCoord;\n"
            + "uniform samplerExternalOES u_Texture;\n"
            + "void main() {\n"
            + "  gl_FragColor = texture2D(u_Texture, v_TexCoord);\n"
            + "}\n";

    private static FloatBuffer floatBuffer(float... elements) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(elements.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(elements).position(0);
        return buffer;
    }

    private static ShortBuffer shortBuffer(short... elements) {
        ShortBuffer buffer = ByteBuffer.allocateDirect(elements.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        buffer.put(elements).position(0);
        return buffer;
    }
}

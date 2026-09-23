/*
 * WebXR shim for Leia glasses-free 3D displays (Lume Pad and friends).
 *
 * The stock Android WebView has no WebXR implementation, and the Lume Pad's
 * stereo display is driven by the Leia CNSDK interlacer on the native side.
 * This shim implements the WebXR device API surface needed by three.js so a
 * page can start an "immersive-vr" session: each eye is rendered into one
 * half of a side-by-side framebuffer, which the native side of the app then
 * redirects into the lightfield display with face tracking.
 *
 * The rendering approach follows the architecture popularized by
 * dfattal.github.io's WebXR tools for Leia products (asymmetric stereo
 * window on lightfield displays) and the community LeiaWebXR experiment:
 * a fixed virtual display window with a tunable interocular distance and
 * convergence distance.
 *
 * URL parameters:
 *   ipd   - interocular distance in meters (default 0.03)
 *   conv  - convergence (screen window) distance in meters (default 2.5)
 *   fov   - vertical field of view in degrees (default 70)
 *   gyro  - 1 to rotate the world with the device orientation sensor
 *   xrshim - 1 to force the shim even when native WebXR exists (desktop SBS preview)
 *
 * Expects window.NativeLeia (injected by the host app) with enable3D() /
 * disable3D(); when absent (plain browser) sessions still run and render the
 * side-by-side pair to the canvas as a preview.
 */
(function () {
    "use strict";

    if (window.__mc3dXrShimInstalled) return;
    var force = false;
    try {
        var qp = new URLSearchParams(location.search);
        force = qp.get("xrshim") === "1";
    } catch (e) { /* ignore */ }

    if (!force && navigator.xr) return; // native WebXR present: don't interfere
    window.__mc3dXrShimInstalled = true;

    var CFG = {
        ipd: 0.03,
        conv: 2.5,
        fov: 70,
        viewerHeight: 1.6,
        gyro: false
    };
    try {
        var p = new URLSearchParams(location.search);
        if (p.get("ipd")) CFG.ipd = parseFloat(p.get("ipd")) || CFG.ipd;
        if (p.get("conv")) CFG.conv = parseFloat(p.get("conv")) || CFG.conv;
        if (p.get("fov")) CFG.fov = parseFloat(p.get("fov")) || CFG.fov;
        CFG.gyro = p.get("gyro") === "1";
    } catch (e) { /* ignore */ }

    // ------------------------------------------------------------------
    // Minimal matrix / quaternion math (column-major, WebGL conventions)
    // ------------------------------------------------------------------
    var IDENTITY = new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);

    function mat4Multiply(a, b) {
        var out = new Float32Array(16);
        for (var c = 0; c < 4; c++) {
            for (var r = 0; r < 4; r++) {
                out[c * 4 + r] =
                    a[r] * b[c * 4] +
                    a[4 + r] * b[c * 4 + 1] +
                    a[8 + r] * b[c * 4 + 2] +
                    a[12 + r] * b[c * 4 + 3];
            }
        }
        return out;
    }

    function mat4Invert(m) {
        var inv = new Float32Array(16);
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] +
            m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] -
            m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] +
            m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] -
            m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] -
            m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] +
            m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] -
            m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] +
            m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] +
            m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] -
            m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] +
            m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] -
            m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] -
            m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] +
            m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] -
            m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] +
            m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
        var det = m[0] * inv[0] + m[4] * inv[1] + m[8] * inv[2] + m[12] * inv[3];
        if (!det) return new Float32Array(IDENTITY);
        det = 1 / det;
        for (var i = 0; i < 16; i++) inv[i] *= det;
        return inv;
    }

    function translationMatrix(x, y, z) {
        var m = new Float32Array(IDENTITY);
        m[12] = x;
        m[13] = y;
        m[14] = z;
        return m;
    }

    function composeMatrix(pos, quat) {
        var x = quat.x, y = quat.y, z = quat.z, w = quat.w;
        var m = new Float32Array(16);
        m[0] = 1 - 2 * (y * y + z * z);
        m[1] = 2 * (x * y + z * w);
        m[2] = 2 * (x * z - y * w);
        m[3] = 0;
        m[4] = 2 * (x * y - z * w);
        m[5] = 1 - 2 * (x * x + z * z);
        m[6] = 2 * (y * z + x * w);
        m[7] = 0;
        m[8] = 2 * (x * z + y * w);
        m[9] = 2 * (y * z - x * w);
        m[10] = 1 - 2 * (x * x + y * y);
        m[11] = 0;
        m[12] = pos.x;
        m[13] = pos.y;
        m[14] = pos.z;
        m[15] = 1;
        return m;
    }

    function positionOfMatrix(m) {
        return { x: m[12], y: m[13], z: m[14], w: 1 };
    }

    function orientationOfMatrix(m) {
        var m00 = m[0], m11 = m[5], m22 = m[10];
        var qx, qy, qz, qw, s;
        if (m00 + m11 + m22 > 0) {
            s = Math.sqrt(m00 + m11 + m22 + 1) * 2;
            qx = (m[6] - m[9]) / s;
            qy = (m[8] - m[2]) / s;
            qz = (m[1] - m[4]) / s;
            qw = s / 4;
        } else if (m00 > m11 && m00 > m22) {
            s = Math.sqrt(1 + m00 - m11 - m22) * 2;
            qx = s / 4;
            qy = (m[1] + m[4]) / s;
            qz = (m[8] + m[2]) / s;
            qw = (m[6] - m[9]) / s;
        } else if (m11 > m22) {
            s = Math.sqrt(1 + m11 - m00 - m22) * 2;
            qx = (m[1] + m[4]) / s;
            qy = s / 4;
            qz = (m[6] + m[9]) / s;
            qw = (m[8] - m[2]) / s;
        } else {
            s = Math.sqrt(1 + m22 - m00 - m11) * 2;
            qx = (m[8] + m[2]) / s;
            qy = (m[6] + m[9]) / s;
            qz = s / 4;
            qw = (m[1] - m[4]) / s;
        }
        return { x: qx, y: qy, z: qz, w: qw };
    }

    // ------------------------------------------------------------------
    // WebXR DOM classes
    // ------------------------------------------------------------------

    function XRRigidTransform(position, orientation) {
        this._position = DOMPointReadOnly.fromPoint(position || { x: 0, y: 0, z: 0, w: 1 });
        this._orientation = DOMPointReadOnly.fromPoint(
            orientation || { x: 0, y: 0, z: 0, w: 1 });
        this._matrix = null;
        this._inverse = null;
    }

    Object.defineProperties(XRRigidTransform.prototype, {
        position: { get: function () { return this._position; } },
        orientation: { get: function () { return this._orientation; } },
        matrix: {
            get: function () {
                if (!this._matrix) {
                    this._matrix = composeMatrix(this._position, this._orientation);
                }
                return this._matrix;
            }
        },
        inverse: {
            get: function () {
                if (!this._inverse) {
                    var inv = mat4Invert(this.matrix);
                    this._inverse = XRRigidTransform._fromMatrix(inv);
                    this._inverse._inverse = this;
                }
                return this._inverse;
            }
        }
    });

    XRRigidTransform._fromMatrix = function (m) {
        var t = new XRRigidTransform(positionOfMatrix(m), orientationOfMatrix(m));
        t._matrix = m;
        return t;
    };

    function XRPose(transform) {
        this._transform = transform;
    }
    Object.defineProperties(XRPose.prototype, {
        transform: { get: function () { return this._transform; } },
        linearVelocity: { get: function () { return null; } },
        angularVelocity: { get: function () { return null; } },
        emulatedPosition: { get: function () { return false; } }
    });

    function XRViewerPose(transform, views) {
        XRPose.call(this, transform);
        this._views = views;
    }
    XRViewerPose.prototype = Object.create(XRPose.prototype);
    Object.defineProperty(XRViewerPose.prototype, "views", {
        get: function () { return this._views; }
    });

    function XRView(eye, viewSpace, projectionMatrix, transform) {
        this._eye = eye;
        this._viewSpace = viewSpace;
        this._projectionMatrix = projectionMatrix;
        this._transform = transform;
    }
    Object.defineProperties(XRView.prototype, {
        eye: { get: function () { return this._eye; } },
        projectionMatrix: { get: function () { return this._projectionMatrix; } },
        transform: { get: function () { return this._transform; } }
    });

    function XRViewport(x, y, width, height) {
        this._x = x;
        this._y = y;
        this._width = width;
        this._height = height;
    }
    Object.defineProperties(XRViewport.prototype, {
        x: { get: function () { return this._x; } },
        y: { get: function () { return this._y; } },
        width: { get: function () { return this._width; } },
        height: { get: function () { return this._height; } }
    });

    function XRReferenceSpace(type, basisMatrix, originOffset) {
        this._type = type;
        this._basis = basisMatrix;
        this._originOffset = originOffset;
    }

    XRReferenceSpace.prototype.getOffsetReferenceSpace = function (bounds) {
        return new XRReferenceSpace(
            this._type,
            this._basis,
            mat4Multiply(this._originOffset, bounds.matrix));
    };

    XRReferenceSpace.prototype._effective = function () {
        return mat4Multiply(this._basis, this._originOffset);
    };

    function XRFrame(session, isAnimationFrame) {
        this._session = session;
        this._isAnimationFrame = isAnimationFrame;
        this._time = 0;
    }

    XRFrame.prototype._setTimes = function (t) {
        this._time = t;
    };

    Object.defineProperties(XRFrame.prototype, {
        session: { get: function () { return this._session; } },
        _device: { get: function () { return this._session._device; } },
        predictedDisplayTime: { get: function () { return this._time; } }
    });

    XRFrame.prototype.getViewerPose = function (referenceSpace) {
        if (!this._isAnimationFrame) return null;
        var device = this._device;
        var head = device.getHeadMatrix();
        // The 'local-floor' basis already encodes the eye height above the
        // floor, so the viewer pose (= inverse of the effective basis, times
        // the head pose) sits at the right height with no extra correction.
        var viewer = mat4Multiply(mat4Invert(referenceSpace._effective()), head);

        var views = [];
        for (var i = 0; i < device.eyeCount; i++) {
            var eyeOffset = device.eyeOffsets[i];
            var viewMatrix = mat4Multiply(viewer, eyeOffset);
            var proj = device.eyeProjections[i];
            views.push(new XRView(
                device.eyes[i], null, proj, XRRigidTransform._fromMatrix(viewMatrix)));
        }
        return new XRViewerPose(XRRigidTransform._fromMatrix(viewer), views);
    };

    XRFrame.prototype.getPose = function (space, baseSpace) {
        if (!space || !baseSpace || !baseSpace._effective) return null;
        var rel = mat4Multiply(
            mat4Invert(baseSpace._effective()), space._originOffset || IDENTITY);
        return new XRPose(XRRigidTransform._fromMatrix(rel));
    };

    // ------------------------------------------------------------------
    // Devices
    // ------------------------------------------------------------------

    function StereoDevice(session) {
        this.session = session;
        this.eyes = ["left", "right"];
        this.eyeCount = 2;
        this.eyeOffsets = [
            translationMatrix(-CFG.ipd / 2, 0, 0),
            translationMatrix(CFG.ipd / 2, 0, 0)
        ];
        this.eyeProjections = [new Float32Array(16), new Float32Array(16)];

        this._context = null;
        this._framebuffer = null;
        this._colorTexture = null;
        this._depthBuffer = null;
        this._blitProgram = null;
        this._blitPosAttrib = -1;
        this._blitTexUniform = -1;

        this._viewports = null;
        this._lastWidth = -1;
        this._lastHeight = -1;
        this._lastNear = -1;
        this._lastFar = -1;

        this._sensor = null;
        this._sensorQuat = null;
        this._forwardQuat = null;
        if (CFG.gyro && typeof RelativeOrientationSensor !== "undefined") {
            try {
                this._sensor = new RelativeOrientationSensor({ frequency: 60 });
            } catch (e) {
                this._sensor = null;
            }
        }

        this._active = false;
    }

    StereoDevice.prototype.start = function () {
        this._active = true;
        var self = this;
        if (this._sensor) {
            try {
                this._sensor.addEventListener("reading", function () {
                    self._sensorQuat = self._sensor.quaternion
                        ? Array.from(self._sensor.quaternion)
                        : null;
                });
                this._sensor.start();
            } catch (e) {
                this._sensor = null;
            }
        }
        if (window.NativeLeia && typeof window.NativeLeia.enable3D === "function") {
            try {
                window.NativeLeia.enable3D();
            } catch (e) { /* ignore */ }
        }
        try {
            history.pushState({ mc3dXr: true }, "");
            var session = this.session;
            var onPop = function () {
                window.removeEventListener("popstate", onPop);
                if (session && !session._ended) session._shutdown();
            };
            window.addEventListener("popstate", onPop);
        } catch (e) { /* ignore */ }
    };

    StereoDevice.prototype.stop = function () {
        this._active = false;
        if (this._sensor) {
            try {
                this._sensor.stop();
            } catch (e) { /* ignore */ }
            this._sensor = null;
        }
        if (window.NativeLeia && typeof window.NativeLeia.disable3D === "function") {
            try {
                window.NativeLeia.disable3D();
            } catch (e) { /* ignore */ }
        }
        this._destroyGl();
    };

    StereoDevice.prototype.getHeadMatrix = function () {
        if (!this._sensorQuat) return IDENTITY;
        var q = this._sensorQuat.slice();
        // Sensor frame: device lies flat pointing up; rotate so that "up from
        // the screen" becomes "forward".
        var rot = quatMultiply(q, [Math.SQRT1_2, 0, 0, Math.SQRT1_2]);
        if (!this._forwardQuat) {
            // Calibrate: the initial direction the user faces becomes -Z.
            var f = lookStraightAhead(rot);
            this._forwardQuat = [f[0], -f[1], f[2], f[3]];
        }
        var oriented = quatMultiply(rot, this._forwardQuat);
        return composeMatrix({ x: 0, y: 0, z: 0, w: 1 }, {
            x: oriented[0], y: oriented[1], z: oriented[2], w: oriented[3]
        });
    };

    function quatMultiply(a, b) {
        var ax = a[0], ay = a[1], az = a[2], aw = a[3];
        var bx = b[0], by = b[1], bz = b[2], bw = b[3];
        return [
            ax * bw + aw * bx + ay * bz - az * by,
            ay * bw + aw * by + az * bx - ax * bz,
            az * bw + aw * bz + ax * by - ay * bx,
            aw * bw - ax * bx - ay * by - az * bz
        ];
    }

    function lookStraightAhead(q) {
        var y = q[1], w = q[3];
        var norm = Math.sqrt(y * y + w * w) || 1;
        return [0, y / norm, 0, w / norm];
    }

    StereoDevice.prototype.getReferenceSpace = function (type) {
        var basis = IDENTITY;
        if (type === "local-floor") {
            basis = translationMatrix(0, -CFG.viewerHeight, 0);
        } else if (type !== "local" && type !== "viewer") {
            return Promise.reject(
                new Error("Reference space type not supported: " + type));
        }
        return Promise.resolve(new XRReferenceSpace(type, basis, IDENTITY));
    };

    StereoDevice.prototype.updateDisplay = function (renderState) {
        var baseLayer = renderState.baseLayer;
        if (!baseLayer) return;
        var context = baseLayer.context || baseLayer._context;
        var canvas = context ? context.canvas : null;
        if (!canvas) return;

        var width = canvas.width;
        var height = canvas.height;
        var near = renderState.depthNear || 0.1;
        var far = renderState.depthFar || 1000;

        if (this._context !== context) {
            this._context = context;
            this._initGl(context, width, height);
            this._lastWidth = -1; // force viewport/projection rebuild
        }
        if (this._lastWidth !== width || this._lastHeight !== height ||
            this._lastNear !== near || this._lastFar !== far) {
            this._lastWidth = width;
            this._lastHeight = height;
            this._lastNear = near;
            this._lastFar = far;
            this._resizeGl(width, height);
            this._updateProjection(near, far);
            var halfWidth = Math.floor(width / 2);
            this._viewports = [
                new XRViewport(0, 0, halfWidth, height),
                new XRViewport(width - halfWidth, 0, halfWidth, height)
            ];
        }
    };

    StereoDevice.prototype._updateProjection = function (near, far) {
        var aspect = this._lastWidth / this._lastHeight;
        var t = Math.tan(CFG.fov * Math.PI / 360);
        var top = t * near;
        var bottom = -top;
        // Horizontal frustum shift so the stereo window converges at CFG.conv:
        // zero parallax (the screen plane) sits at that distance. The left
        // eye's frustum shifts right and the right eye's shifts left, which is
        // equivalent to toe-in without vertical skew.
        var conv = Math.max(0.1, CFG.conv);
        var ox = (CFG.ipd / 2) * (near / conv);

        for (var eye = 0; eye < 2; eye++) {
            var m = this.eyeProjections[eye];
            var sign = (eye === 0) ? 1 : -1;
            var shift = sign * ox;
            var left = -t * aspect * near + shift;
            var right = t * aspect * near + shift;
            m[0] = 2 * near / (right - left);
            m[5] = 2 * near / (top - bottom);
            m[8] = (right + left) / (right - left);
            m[9] = (top + bottom) / (top - bottom);
            m[10] = -(far + near) / (far - near);
            m[11] = -1;
            m[14] = -2 * far * near / (far - near);
            m[15] = 0;
        }
    };

    StereoDevice.prototype.getViewport = function (view) {
        if (!this._viewports) return null;
        var idx = view && view._eye === "left" ? 0 : 1;
        return this._viewports[idx];
    };

    Object.defineProperty(StereoDevice.prototype, "framebuffer", {
        get: function () { return this._framebuffer; }
    });

    StereoDevice.prototype._initGl = function (gl, width, height) {
        // Offscreen framebuffer: color texture + depth renderbuffer.
        this._colorTexture = gl.createTexture();
        gl.bindTexture(gl.TEXTURE_2D, this._colorTexture);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

        this._depthBuffer = gl.createRenderbuffer();
        this._framebuffer = gl.createFramebuffer();
        this._blitBuffer = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, this._blitBuffer);
        gl.bufferData(gl.ARRAY_BUFFER,
            new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
        gl.bindBuffer(gl.ARRAY_BUFFER, null);

        // Blit program: draws the framebuffer texture over the whole canvas.
        var vs = "attribute vec2 a_Pos;" +
            "varying vec2 v_Tex;" +
            "void main() { v_Tex = a_Pos * 0.5 + 0.5; gl_Position = vec4(a_Pos, 0.0, 1.0); }";
        var fs = "precision mediump float;" +
            "varying vec2 v_Tex; uniform sampler2D u_Tex;" +
            "void main() { gl_FragColor = texture2D(u_Tex, v_Tex); }";
        function compile(type, src) {
            var shader = gl.createShader(type);
            gl.shaderSource(shader, src);
            gl.compileShader(shader);
            return shader;
        }
        var program = gl.createProgram();
        gl.attachShader(program, compile(gl.VERTEX_SHADER, vs));
        gl.attachShader(program, compile(gl.FRAGMENT_SHADER, fs));
        gl.linkProgram(program);
        this._blitProgram = program;
        this._blitPosAttrib = gl.getAttribLocation(program, "a_Pos");
        this._blitTexUniform = gl.getUniformLocation(program, "u_Tex");

        this._lastWidth = -1;
    };

    StereoDevice.prototype._resizeGl = function (width, height) {
        var gl = this._context;
        if (!gl) return;
        gl.bindTexture(gl.TEXTURE_2D, this._colorTexture);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, width, height, 0,
            gl.RGBA, gl.UNSIGNED_BYTE, null);
        gl.bindRenderbuffer(gl.RENDERBUFFER, this._depthBuffer);
        gl.renderbufferStorage(gl.RENDERBUFFER, gl.DEPTH_COMPONENT16, width, height);
        gl.bindFramebuffer(gl.FRAMEBUFFER, this._framebuffer);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0,
            gl.TEXTURE_2D, this._colorTexture, 0);
        gl.framebufferRenderbuffer(gl.FRAMEBUFFER, gl.DEPTH_ATTACHMENT,
            gl.RENDERBUFFER, this._depthBuffer);
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        gl.bindTexture(gl.TEXTURE_2D, null);
        gl.bindRenderbuffer(gl.RENDERBUFFER, null);
    };

    StereoDevice.prototype._destroyGl = function () {
        var gl = this._context;
        if (!gl) return;
        try {
            if (this._framebuffer) gl.deleteFramebuffer(this._framebuffer);
            if (this._colorTexture) gl.deleteTexture(this._colorTexture);
            if (this._depthBuffer) gl.deleteRenderbuffer(this._depthBuffer);
            if (this._blitProgram) gl.deleteProgram(this._blitProgram);
            if (this._blitBuffer) gl.deleteBuffer(this._blitBuffer);
        } catch (e) { /* ignore */ }
        this._framebuffer = null;
        this._colorTexture = null;
        this._depthBuffer = null;
        this._blitProgram = null;
        this._blitBuffer = null;
        this._context = null;
    };

    /** Called after the session's animation callbacks: presents the
     *  side-by-side framebuffer to the canvas. */
    StereoDevice.prototype.blit = function () {
        var gl = this._context;
        if (!gl || !this._framebuffer || this._lastWidth <= 0) return;

        var fbBinding = gl.getParameter(gl.FRAMEBUFFER_BINDING);
        var program = gl.getParameter(gl.CURRENT_PROGRAM);
        var viewport = gl.getParameter(gl.VIEWPORT);
        var activeTex = gl.getParameter(gl.ACTIVE_TEXTURE);
        var texBinding = gl.getParameter(gl.TEXTURE_BINDING_2D);
        var depthEnabled = gl.isEnabled(gl.DEPTH_TEST);

        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        gl.viewport(0, 0, this._lastWidth, this._lastHeight);
        gl.disable(gl.DEPTH_TEST);
        gl.useProgram(this._blitProgram);
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, this._colorTexture);
        gl.uniform1i(this._blitTexUniform, 0);

        gl.bindBuffer(gl.ARRAY_BUFFER, this._blitBuffer);
        gl.enableVertexAttribArray(this._blitPosAttrib);
        gl.vertexAttribPointer(this._blitPosAttrib, 2, gl.FLOAT, false, 0, 0);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        gl.disableVertexAttribArray(this._blitPosAttrib);

        gl.bindTexture(gl.TEXTURE_2D, texBinding);
        gl.activeTexture(activeTex);
        if (depthEnabled) gl.enable(gl.DEPTH_TEST);
        gl.useProgram(program);
        gl.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        gl.bindFramebuffer(gl.FRAMEBUFFER, fbBinding);
    };

    // Minimal inline (non-immersive) device: a single centered view.
    function InlineDevice(session) {
        this.session = session;
        this.eyes = ["none"];
        this.eyeCount = 1;
        this.eyeOffsets = [IDENTITY];
        this.eyeProjections = [new Float32Array(16)];
        this._viewports = null;
    }

    InlineDevice.prototype.start = function () { };
    InlineDevice.prototype.stop = function () { };

    InlineDevice.prototype.getHeadMatrix = function () { return IDENTITY; };

    InlineDevice.prototype.getReferenceSpace = function (type) {
        if (type !== "viewer" && type !== "local" && type !== "local-floor") {
            return Promise.reject(new Error("Unsupported reference space: " + type));
        }
        return Promise.resolve(
            new XRReferenceSpace(type, IDENTITY, IDENTITY));
    };

    InlineDevice.prototype.updateDisplay = function (renderState) {
        var baseLayer = renderState.baseLayer;
        if (!baseLayer) return;
        var context = baseLayer.context || baseLayer._context;
        var canvas = context ? context.canvas : null;
        if (!canvas) return;
        var width = canvas.width;
        var height = canvas.height;
        var near = renderState.depthNear || 0.1;
        var far = renderState.depthFar || 1000;
        var fov = renderState.inlineVerticalFieldOfView || Math.PI / 2;
        if (this._w !== width || this._h !== height || this._n !== near ||
            this._f !== far || this._fov !== fov) {
            this._w = width;
            this._h = height;
            this._n = near;
            this._f = far;
            this._fov = fov;
            var t = Math.tan(fov / 2);
            var aspect = width / height;
            var m = this.eyeProjections[0];
            m[0] = t / aspect;
            m[5] = t;
            m[10] = -(far + near) / (far - near);
            m[11] = -1;
            m[14] = -2 * far * near / (far - near);
            this._viewports = [new XRViewport(0, 0, width, height)];
        }
    };

    InlineDevice.prototype.getViewport = function () {
        return this._viewports ? this._viewports[0] : null;
    };

    Object.defineProperty(InlineDevice.prototype, "framebuffer", {
        get: function () { return null; }
    });

    InlineDevice.prototype.blit = function () { };

    // ------------------------------------------------------------------
    // Layer / session / system
    // ------------------------------------------------------------------

    function XRWebGLLayer(session, context, layerInit) {
        this.session = session;
        this.context = context;
        this._layerInit = layerInit || {};
        var canvas = context.canvas;
        this._framebufferWidth = canvas ? canvas.width : 0;
        this._framebufferHeight = canvas ? canvas.height : 0;
    }

    Object.defineProperties(XRWebGLLayer.prototype, {
        framebuffer: { get: function () { return this.session._device.framebuffer; } },
        framebufferWidth: { get: function () { return this._framebufferWidth; } },
        framebufferHeight: { get: function () { return this._framebufferHeight; } }
    });

    XRWebGLLayer.prototype.getViewport = function (view) {
        return this.session._device.getViewport(view);
    };

    class XRSession extends EventTarget {
        constructor(mode, device) {
            super();
            this._mode = mode;
            this._device = device;
            this._ended = false;
            this._inputSources = [];

            var initialRenderState = Object.freeze({
                depthNear: 0.1,
                depthFar: 1000,
                inlineVerticalFieldOfView: mode === "inline" ? Math.PI / 2 : null,
                baseLayer: null
            });
            this._activeRenderState = initialRenderState;
            this._pendingRenderState = null;

            this._frame = new XRFrame(this, true);
            this._callbacks = {};
            this._nextCallbackId = 1;

            var session = this;
            var tick = function (time) {
                if (session._ended) return;
                session._rafHandle = requestAnimationFrame(tick);
                session._onFrame(time);
            };
            this._rafHandle = requestAnimationFrame(tick);
        }

        get mode() { return this._mode; }
        get renderState() { return this._activeRenderState; }
        get inputSources() { return this._inputSources; }
        get environmentBlendMode() { return "opaque"; }
        get visibilityState() { return "visible"; }

        updateRenderState(state) {
            if (this._ended) throw new Error("Session has ended");
            state = state || {};
            if (state.baseLayer && state.baseLayer.session !== this) {
                throw new Error("baseLayer belongs to a different session");
            }
            var next = {
                depthNear: this._activeRenderState.depthNear,
                depthFar: this._activeRenderState.depthFar,
                inlineVerticalFieldOfView: this._activeRenderState.inlineVerticalFieldOfView,
                baseLayer: this._activeRenderState.baseLayer
            };
            if (state.depthNear !== undefined) next.depthNear = state.depthNear;
            if (state.depthFar !== undefined) next.depthFar = state.depthFar;
            if (state.inlineVerticalFieldOfView !== undefined &&
                this._mode === "inline") {
                next.inlineVerticalFieldOfView = state.inlineVerticalFieldOfView;
            }
            if (state.baseLayer !== undefined) next.baseLayer = state.baseLayer;
            this._pendingRenderState = Object.freeze(next);
        }

        requestReferenceSpace(type) {
            if (this._ended) return Promise.reject(new Error("Session has ended"));
            return this._device.getReferenceSpace(type);
        }

        requestAnimationFrame(callback) {
            var id = this._nextCallbackId++;
            this._callbacks[id] = callback;
            return id;
        }

        cancelAnimationFrame(id) {
            delete this._callbacks[id];
        }

        end() {
            if (this._ended) return Promise.resolve();
            this._shutdown();
            return Promise.resolve();
        }

        _shutdown() {
            if (this._ended) return;
            this._ended = true;
            cancelAnimationFrame(this._rafHandle);
            this._callbacks = {};
            this._device.stop();
            this.dispatchEvent(new Event("end"));
        }

        _onFrame(time) {
            this._frame._setTimes(time);

            if (this._pendingRenderState) {
                this._activeRenderState = this._pendingRenderState;
                this._pendingRenderState = null;
            }

            var renderState = this._activeRenderState;
            if (!renderState.baseLayer) return;

            this._device.updateDisplay(renderState);

            var frame = this._frame;
            var callbacks = this._callbacks;
            this._callbacks = {};
            for (var id in callbacks) {
                if (callbacks.hasOwnProperty(id)) {
                    try {
                        callbacks[id](time, frame);
                    } catch (e) {
                        console.error("[mc3d-xr] animation callback failed", e);
                    }
                }
            }
            this._device.blit();
        }
    }

    function XRSystem() {
        this._immersiveSession = null;
    }

    XRSystem.prototype.isSessionSupported = function (mode) {
        return Promise.resolve(mode === "immersive-vr" || mode === "inline");
    };

    XRSystem.prototype.requestSession = function (mode, init) {
        var self = this;
        if (mode === "inline") {
            var inlineDevice = new InlineDevice(null);
            var inlineSession = new XRSession(mode, inlineDevice);
            inlineDevice.session = inlineSession;
            return Promise.resolve(inlineSession);
        }
        if (mode !== "immersive-vr") {
            return Promise.reject(new Error("Unsupported session mode: " + mode));
        }
        if (this._immersiveSession && !this._immersiveSession._ended) {
            return Promise.reject(new Error("An immersive session is already active"));
        }
        var session = new XRSession(mode, null);
        var device = new StereoDevice(session);
        session._device = device;
        session.addEventListener("end", function () {
            if (self._immersiveSession === session) self._immersiveSession = null;
        });
        this._immersiveSession = session;
        device.start();
        return Promise.resolve(session);
    };

    // Modern Chromium exposes navigator.xr as a getter-only accessor even
    // when no XR device is present, so plain assignment can throw.
    try {
        Object.defineProperty(navigator, "xr", {
            value: new XRSystem(),
            configurable: true
        });
    } catch (e) {
        try {
            navigator.xr = new XRSystem();
        } catch (e2) {
            console.warn("[mc3d-xr] could not install navigator.xr", e2);
        }
    }

    // Globals for spec compliance / direct page use.
    window.XRWebGLLayer = XRWebGLLayer;
    window.XRRigidTransform = XRRigidTransform;
    window.XRReferenceSpace = XRReferenceSpace;
    window.XRView = XRView;
    window.XRViewport = XRViewport;
    window.XRPose = XRPose;
    window.XRViewerPose = XRViewerPose;
    window.XRFrame = XRFrame;
    window.XRSession = XRSession;

    // The shim owns the WebXR surface: makeXRCompatible must resolve
    // immediately. (A native one, e.g. desktop Chrome without an XR device,
    // may stay pending forever.)
    if ("WebGLRenderingContext" in window) {
        WebGLRenderingContext.prototype.makeXRCompatible = function () {
            return Promise.resolve();
        };
    }
    if ("WebGL2RenderingContext" in window) {
        WebGL2RenderingContext.prototype.makeXRCompatible = function () {
            return Promise.resolve();
        };
    }
})();

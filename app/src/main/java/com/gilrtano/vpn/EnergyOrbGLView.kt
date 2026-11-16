package com.gilrtano.vpn

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.graphics.PixelFormat
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/** Real-time GLES 2.0 energy orb. Kotlin owns state; GLSL renders glass, neon and animated plasma. */
class EnergyOrbGLView(context: Context) : GLSurfaceView(context) {
    enum class OrbState(val code: Int) { IDLE(0), CONNECTING(1), CONNECTED(2) }
    private val requestedState = AtomicInteger(OrbState.IDLE.code)
    private val orbRenderer = OrbRenderer(requestedState)

    init {
        setEGLContextClientVersion(2)
        // Request an alpha-capable EGL surface before creating the renderer.
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setRenderer(orbRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        // Do not override media-overlay ordering with setZOrderOnTop(false).
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setOrbState(state: OrbState) { requestedState.set(state.code) }

    private class OrbRenderer(private val state: AtomicInteger) : Renderer {
        private var program = 0
        private var timeLocation = 0
        private var stateLocation = 0
        private var resolutionLocation = 0
        private lateinit var vertices: FloatBuffer
        private val start = System.nanoTime()
        private var surfaceWidth = 1
        private var surfaceHeight = 1
        private val vertexShader = """
            attribute vec2 aPosition;
            varying vec2 vUv;
            void main() {
                vUv = aPosition * 0.5 + 0.5;
                gl_Position = vec4(aPosition, 0.0, 1.0);
            }
        """.trimIndent()
        private val fragmentShader = """
            precision mediump float;
            varying vec2 vUv;
            uniform float uTime;
            uniform float uState;
            uniform vec2 uResolution;

            float sdRoundBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p) - b + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }
            float starFlare(vec2 p, float size) {
                float d = length(p);
                float crossGlow = exp(-abs(p.x) / (size * 0.13)) * exp(-abs(p.y) / (size * 1.8))
                                + exp(-abs(p.y) / (size * 0.13)) * exp(-abs(p.x) / (size * 1.8));
                float core = exp(-d * d / (size * size * 0.10));
                return crossGlow * 0.42 + core * 1.7;
            }
            void main() {
                vec2 p = (vUv - 0.5) * 2.0;
                p.x *= uResolution.x / uResolution.y;
                float t = uTime;
                float connected = step(1.5, uState);
                float connecting = step(0.5, uState) * (1.0 - connected);
                float pulse = 0.5 + 0.5 * sin(t * (connected > 0.5 ? 2.0 : 1.15));
                vec2 q = p - vec2(0.0, 0.065);
                float r = length(q);
                float angle = atan(q.y, q.x);

                vec3 col = vec3(0.0);
                float alpha = 0.0;

                // Layered emerald circular rim and soft external aura.
                float wobble = sin(angle * 9.0 + t * 1.8) * 0.0025;
                float rimD = abs(r - (0.685 + wobble));
                float outerD = abs(r - 0.725);
                float aura = exp(-rimD * 18.0);
                float neon = exp(-rimD * 86.0);
                float hotCore = exp(-rimD * 190.0);
                float fineRing = exp(-outerD * 105.0);
                col += vec3(0.015, 0.52, 0.015) * aura * (0.35 + pulse * 0.18);
                col += vec3(0.015, 0.95, 0.025) * neon * 0.95;
                col += vec3(0.52, 1.0, 0.12) * hotCore * 0.9;
                col += vec3(0.05, 0.8, 0.015) * fineRing * 0.65;
                alpha = max(alpha, aura * 0.48);
                alpha = max(alpha, max(neon, fineRing) * 0.96);

                // Dark smoked-glass lens with a subtle green inner edge.
                float glassMask = 1.0 - smoothstep(0.655, 0.69, r);
                float innerEdge = exp(-abs(r - 0.635) * 70.0);
                col += vec3(0.002, 0.012, 0.006) * glassMask;
                col += vec3(0.015, 0.12, 0.025) * innerEdge;
                float glossA = exp(-pow((q.x + 0.245) * 8.0, 2.0) - pow((q.y - 0.34) * 5.0, 2.0));
                float glossB = exp(-pow((q.x + 0.36) * 15.0, 2.0) - pow((q.y - 0.20) * 8.0, 2.0));
                col += vec3(0.45, 0.72, 0.58) * glossA * 0.48;
                col += vec3(0.82, 1.0, 0.88) * glossB * 0.78;
                alpha = max(alpha, glassMask * 0.98);

                // Central power symbol: bright stem and interrupted circular arc.
                float stemD = sdRoundBox(p - vec2(0.0, 0.36), vec2(0.026, 0.115), 0.026);
                float arcD = abs(length(p - vec2(0.0, 0.105)) - 0.205);
                float arcAngle = atan(p.x, -(p.y - 0.105));
                float arcVisible = step(0.48, abs(arcAngle));
                arcD += (1.0 - arcVisible) * 0.20;
                float glyphD = min(stemD, arcD);
                float glyphGlow = exp(-max(glyphD, 0.0) * 36.0);
                float glyphHot = exp(-abs(glyphD) * 115.0);
                float powerColor = 0.75 + 0.25 * pulse;
                col += vec3(0.02, 0.62, 0.005) * glyphGlow * powerColor;
                col += vec3(0.35, 1.0, 0.015) * glyphHot * 1.15;
                alpha = max(alpha, glyphGlow * 0.72);

                // Empty illuminated status capsule.
                vec2 cp = p - vec2(0.0, -0.285);
                float capsuleD = sdRoundBox(cp, vec2(0.335, 0.062), 0.062);
                float capsuleGlow = exp(-abs(capsuleD) * 42.0);
                float capsuleLine = exp(-abs(capsuleD) * 125.0);
                float capsuleInside = 1.0 - smoothstep(-0.008, 0.008, capsuleD);
                col += vec3(0.015, 0.48, 0.025) * capsuleGlow * 0.55;
                col += vec3(0.12, 1.0, 0.035) * capsuleLine;
                col += vec3(0.001, 0.012, 0.004) * capsuleInside;
                alpha = max(alpha, capsuleGlow * 0.55);

                // Four sharp star flares on the ring, breathing gently.
                float flareScale = 0.105 + 0.012 * pulse;
                float flares = starFlare(q - vec2(0.0, 0.685), flareScale)
                             + starFlare(q - vec2(0.0, -0.685), flareScale)
                             + starFlare(q - vec2(0.685, 0.0), flareScale)
                             + starFlare(q - vec2(-0.685, 0.0), flareScale);
                col += vec3(0.42, 1.0, 0.035) * flares * 0.9;
                alpha = max(alpha, min(flares, 1.0) * 0.95);

                // Three soft green reflections beneath the orb.
                float floorY = p.y + 0.79;
                float leftPool = exp(-pow((p.x + 0.48) * 7.0, 2.0) - pow(floorY * 13.0, 2.0));
                float centerPool = exp(-pow(p.x * 5.0, 2.0) - pow(floorY * 16.0, 2.0));
                float rightPool = exp(-pow((p.x - 0.48) * 7.0, 2.0) - pow(floorY * 13.0, 2.0));
                float reflections = leftPool * 0.75 + centerPool + rightPool * 0.75;
                col += vec3(0.02, 0.72, 0.015) * reflections * (0.65 + pulse * 0.25);
                col += vec3(0.18, 1.0, 0.035) * exp(-pow(p.x * 8.0, 2.0) - pow(floorY * 38.0, 2.0)) * 0.75;
                alpha = max(alpha, reflections * 0.42);

                // Connection states change only the energy intensity, not geometry.
                float stateBoost = 1.0 + connected * 0.24 + connecting * (0.10 + 0.12 * sin(t * 7.0));
                col *= stateBoost;
                gl_FragColor = vec4(col, clamp(alpha, 0.0, 1.0));
            }
        """

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f,0f,0f,0f)
            program = linkProgram(vertexShader, fragmentShader)
            timeLocation = GLES20.glGetUniformLocation(program,"uTime")
            stateLocation = GLES20.glGetUniformLocation(program,"uState")
            resolutionLocation = GLES20.glGetUniformLocation(program,"uResolution")
            val data = floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)
            vertices = ByteBuffer.allocateDirect(data.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertices.put(data).position(0)
        }
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            surfaceWidth = max(1,width)
            surfaceHeight = max(1,height)
            GLES20.glViewport(0,0,width,height)
        }
        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program,"aPosition")
            GLES20.glEnableVertexAttribArray(pos)
            vertices.position(0)
            GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,vertices)
            GLES20.glUniform1f(timeLocation,(System.nanoTime()-start)/1_000_000_000f)
            GLES20.glUniform1f(stateLocation,state.get().toFloat())
            GLES20.glUniform2f(resolutionLocation,surfaceWidth.toFloat(),surfaceHeight.toFloat())
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            GLES20.glDisableVertexAttribArray(pos)
        }
        private fun linkProgram(vertex: String, fragment: String): Int {
            fun compile(type: Int, source: String): Int {
                val shader=GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader)
                val ok=IntArray(1); GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,ok,0)
                check(ok[0]==GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
                return shader
            }
            val vs=compile(GLES20.GL_VERTEX_SHADER,vertex)
            val fs=compile(GLES20.GL_FRAGMENT_SHADER,fragment)
            val p=GLES20.glCreateProgram()
            GLES20.glAttachShader(p,vs); GLES20.glAttachShader(p,fs); GLES20.glLinkProgram(p)
            val ok=IntArray(1); GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0)
            check(ok[0]==GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(p) }
            GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
            return p
        }
    }
}

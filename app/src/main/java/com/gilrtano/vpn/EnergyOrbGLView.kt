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
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(orbRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        setZOrderOnTop(false)
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

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
            }
            float noise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                f = f*f*(3.0-2.0*f);
                return mix(mix(hash(i), hash(i+vec2(1.0,0.0)), f.x),
                           mix(hash(i+vec2(0.0,1.0)), hash(i+vec2(1.0,1.0)), f.x), f.y);
            }
            float sdRoundBox(vec2 p, vec2 b, float r) {
                vec2 q = abs(p)-b+r;
                return length(max(q,0.0))+min(max(q.x,q.y),0.0)-r;
            }
            void main() {
                vec2 p = (vUv-0.5) * 2.0;
                p.x *= uResolution.x/uResolution.y;
                float t = uTime;
                float active = step(1.5,uState);
                float connecting = step(0.5,uState)* (1.0-step(1.5,uState));
                float breathe = 0.5+0.5*sin(t*2.0);
                float radius = length(p);
                float angle = atan(p.y,p.x);
                float n = noise(vec2(angle*5.0-t*1.8, radius*12.0+t*2.4));
                float n2 = noise(vec2(angle*11.0+t*1.1, radius*20.0-t*3.0));
                float flameRadius = 0.78 + 0.07*n + 0.035*n2;
                float flame = smoothstep(flameRadius+0.10, flameRadius-0.015, radius);
                flame *= smoothstep(0.58,0.78,radius);
                flame *= (0.38 + 0.62*active + 0.38*connecting);
                float ringD = abs(radius-0.73);
                float ring = exp(-ringD*48.0);
                float halo = exp(-ringD*13.0);
                float glass = smoothstep(0.70,0.66,radius);
                vec3 col = vec3(0.0);
                col += vec3(0.05,1.0,0.08)*(halo*0.30 + ring*(0.65+0.35*breathe));
                col += vec3(0.08,1.0,0.12)*flame*(0.18+0.75*n);
                col += vec3(0.02,0.08,0.035)*glass;
                float spec = pow(max(0.0,1.0-length((p-vec2(-0.22,0.28))*vec2(1.2,1.8))),12.0);
                col += vec3(0.32,0.62,0.42)*spec*glass;

                // Power glyph: vertical stem plus a broken circular arc.
                float stem = sdRoundBox(p-vec2(0.0,0.29),vec2(0.035,0.17),0.035);
                float a = abs(atan(p.x,-p.y));
                float arc = abs(length(p-vec2(0.0,0.02))-0.235);
                float gap = step(0.48,a);
                float glyph = min(abs(stem), arc + (1.0-gap)*0.18);
                float power = exp(-glyph*95.0);
                col += vec3(0.25,1.0,0.05)*power*1.5;

                // Empty status capsule; Kotlin draws the live caption above the GL surface.
                float capsule = abs(sdRoundBox(p-vec2(0.0,-0.34),vec2(0.36,0.075),0.075));
                col += vec3(0.10,1.0,0.08)*exp(-capsule*90.0)*0.95;
                col += vec3(0.015,0.04,0.02)*smoothstep(0.01,-0.02,sdRoundBox(p-vec2(0.0,-0.34),vec2(0.35,0.065),0.065));
                float alpha = max(max(halo*0.45, flame*0.62), max(ring*0.95, max(power*0.95, glass*0.98)));
                gl_FragColor = vec4(col, clamp(alpha,0.0,1.0));
            }
        """.trimIndent()

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

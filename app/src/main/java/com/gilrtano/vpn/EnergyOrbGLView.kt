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
            float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
            float noise(vec2 p){
                vec2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f);
                return mix(mix(hash(i),hash(i+vec2(1.0,0.0)),f.x),
                           mix(hash(i+vec2(0.0,1.0)),hash(i+vec2(1.0,1.0)),f.x),f.y);
            }
            float fireBand(vec2 p,float t,float seed){
                float a=atan(p.y,p.x);
                float n=noise(vec2(a*8.0+seed,length(p)*13.0-t*2.4));
                float n2=noise(vec2(a*17.0-seed,length(p)*25.0-t*3.8));
                float wave=sin(a*13.0+t*3.0+seed)*0.035;
                float radius=0.79+wave+(n-0.5)*0.13+(n2-0.5)*0.055;
                float d=abs(length(p)-radius);
                return (1.0-smoothstep(0.018,0.085,d))*smoothstep(0.58,0.68,length(p));
            }
            void main(){
                vec2 p=(vUv-0.5)*2.0; p.x*=uResolution.x/uResolution.y;
                float t=uTime;
                float connected=step(1.5,uState);
                float connecting=step(0.5,uState)*(1.0-connected);
                float pulse=0.72+0.28*sin(t*4.0);
                float boost=1.0+connected*0.45+connecting*(0.2+0.25*sin(t*8.0));
                float f1=fireBand(p,t,0.7);
                float f2=fireBand(p*vec2(1.03,0.97),-t*0.82,2.4)*0.72;
                float tongues=pow(max(0.0,f1+f2),1.25);
                float halo=exp(-abs(length(p)-0.79)*17.0);
                float hot=exp(-abs(length(p)-0.79)*55.0);
                vec3 col=vec3(0.02,0.85,0.015)*tongues*1.35;
                col+=vec3(0.10,1.0,0.015)*hot*0.85;
                col+=vec3(0.015,0.42,0.01)*halo*0.45;
                float sparks=0.0;
                for(int i=0;i<12;i++){
                    float fi=float(i), a=fi*0.523599+t*(0.35+0.025*fi);
                    vec2 s=vec2(cos(a),sin(a))*0.84;
                    float flicker=0.35+0.65*sin(t*6.0+fi*2.1);
                    sparks+=exp(-dot(p-s,p-s)*1800.0)*flicker;
                }
                col+=vec3(0.45,1.0,0.06)*sparks*1.2;
                float alpha=clamp(tongues*0.82+halo*0.20+hot*0.35+sparks*0.9,0.0,0.92);
                gl_FragColor=vec4(col*boost*pulse,alpha);
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

package com.anjy.atlas

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Atlas's face: a living drop of dark glass with streams of blue light flowing inside, a silver ribbed shell
 * lit from the top left, and a warm edge at the lower right. Drawn by the graphics chip at the screen's full
 * sharpness (shader: assets/orb.frag, previewed on a computer with tools/orb/).
 *
 * Its first frame is checked before it's shown. If the chip can't draw it properly (a black square or nothing),
 * it switches to [CpuOrbView], the same idea painted on the processor, so no phone ever shows a broken orb.
 *
 * At rest it drifts slowly (30 frames a second); while Atlas listens or works ([energy]) the light flows faster
 * at 60, and [level] swells it with your voice. Reduce Motion ([still]) draws one frame and holds it.
 */
class OrbView(context: Context) : FrameLayout(context) {
    /** Loudness of the voice it's hearing, 0..1 (eased while drawing). */
    var level = 0f
        set(v) { field = v; gl?.level = v; cpu?.level = v }
    /** 0 at rest, 1 while thinking or listening. */
    var energy = 0f
        set(v) { field = v; gl?.energy = v; cpu?.energy = v }
    /** Reduce Motion: one frame, holding still. */
    var still = false
        set(v) { field = v; gl?.still = v; cpu?.still = v }

    private var gl: GlOrbView? = null
    private var cpu: CpuOrbView? = null

    init {
        clipChildren = false
        if (gpuBroken) useCpu() else {
            val g = GlOrbView(context) { reason -> post { onGpuFailed(reason) } }
            gl = g
            addView(g, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    // a watchdog: an orb that is on screen but hasn't shown a frame within 4 s (say, in a window the graphics
    // chip doesn't draw) switches to the processor rather than leaving an empty space
    private val watchdog = Runnable {
        val g = gl
        if (g != null && !g.hasShown && isShown && windowVisibility == View.VISIBLE) onGpuFailed("no frame within 4 s" + if (!isHardwareAccelerated) " (window not hardware-drawn)" else "")
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); armWatchdog() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); armWatchdog() }
    override fun onDetachedFromWindow() { removeCallbacks(watchdog); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); armWatchdog() }
    private fun armWatchdog() {
        removeCallbacks(watchdog)
        if (gl?.hasShown == false && windowVisibility == View.VISIBLE) postDelayed(watchdog, 4000)
    }

    private fun onGpuFailed(reason: String) {
        if (gl == null) return
        removeCallbacks(watchdog)
        Log.w(TAG, "graphics chip can't draw the orb ($reason); painting it on the processor instead")
        gpuBroken = true; gpuProblem = reason
        gl?.let { removeView(it) }; gl = null
        useCpu()
    }

    private fun useCpu() {
        if (cpu != null) return
        val c = CpuOrbView(context).also { it.level = level; it.energy = energy; it.still = still }
        cpu = c
        addView(c, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    companion object {
        private const val TAG = "AtlasOrb"
        /** Set once the graphics chip has failed the first-frame check: every orb then uses the processor. */
        @Volatile var gpuBroken = false; private set
        /** Why, for Me → Diagnostics. */
        @Volatile var gpuProblem: String? = null; private set
        private val t0 = SystemClock.elapsedRealtime()

        /** Seconds on the shared orb clock, so every orb on screen moves in step. Wraps every ~5.5 h. */
        fun clock(): Float = ((SystemClock.elapsedRealtime() - t0) % 20_000_000L) / 1000f

        /** How fast the light inside flows: slow at rest, livelier while busy or hearing a voice. */
        fun flowRate(energy: Float, level: Float) = 0.045f + 0.09f * energy + 0.05f * level
    }
}

/** The orb on the graphics chip: OpenGL ES 3 on its own thread, drawing into a see-through [TextureView]. */
class GlOrbView(context: Context, private val onFailed: (String) -> Unit) : TextureView(context), TextureView.SurfaceTextureListener {
    @Volatile var level = 0f
    @Volatile var energy = 0f
    @Volatile var still = false
        set(v) { field = v; thread?.wake() }
    private var thread: Renderer? = null
    private var shown = false
    /** Whether its first good frame has been shown. */
    val hasShown get() = shown

    init {
        isOpaque = false
        surfaceTextureListener = this
        alpha = 0f      // fades in once the first good frame is ready
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        thread = Renderer(st, w, h).also { it.start() }
        updatePaused()
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { thread?.resize(w, h) }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        thread?.finish(); thread = null
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); updatePaused() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updatePaused() }
    private fun updatePaused() { thread?.paused = !(isShown && windowVisibility == View.VISIBLE) }

    private fun firstFrameShown() {
        if (shown) return
        shown = true
        animate().alpha(1f).setDuration(260).start()
    }

    private inner class Renderer(private val st: SurfaceTexture, @Volatile private var w: Int, @Volatile private var h: Int) : Thread("atlas-orb-gl") {
        private val lock = Object()
        @Volatile private var running = true
        @Volatile var paused = false
            set(v) { field = v; wake() }
        @Volatile private var resized = false
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var prog = 0
        private var uRes = 0; private var uTime = 0; private var uEnergy = 0; private var uLevel = 0; private var uFlow = 0
        private var checked = false
        private var drewStill = false
        private var shownE = 0f; private var shownL = 0f
        private var flow = 0.0
        private var last = 0L
        private val main = Handler(Looper.getMainLooper())
        private var config: EGLConfig? = null
        /** Share of full sharpness it draws at; lowered if the chip is slow (the screen scales it up smoothly). */
        private var scale = 1f
        private var scaleApplied = 1f
        private var bw = 0; private var bh = 0
        private var frames = 0
        private val recent = FloatArray(8)

        fun wake() = synchronized(lock) { lock.notifyAll() }
        fun resize(nw: Int, nh: Int) { w = nw; h = nh; resized = true; drewStill = false; wake() }
        fun finish() { running = false; wake(); try { join(1500) } catch (_: InterruptedException) {} }

        override fun run() {
            try {
                setup()
                while (running) {
                    synchronized(lock) {
                        while (running && (paused || (still && drewStill))) lock.wait()
                    }
                    if (!running) break
                    val busy = energy > 0.05f || shownE > 0.05f || level > 0.02f
                    val gapNs = if (busy) 16_000_000L else 33_000_000L
                    val now = System.nanoTime()
                    val wait = last + gapNs - now
                    if (wait > 1_000_000L && !still) { sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt()); continue }
                    val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
                    last = now
                    shownL += (level - shownL) * (1f - Math.pow(0.0005, dt.toDouble()).toFloat())
                    shownE += (energy - shownE) * (1f - Math.pow(0.08, dt.toDouble()).toFloat())
                    flow += dt * OrbView.flowRate(shownE, shownL)
                    if (!draw()) break
                    if (still) drewStill = true
                }
            } catch (t: Throwable) {
                Log.e("AtlasOrb", "orb renderer", t)
                if (!checked) main.post { onFailed(t.message ?: t.javaClass.simpleName) }
            } finally {
                release()
            }
        }

        private fun setup() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize" }
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no 8-bit RGBA ES3 config" }
            ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext ${EGL14.eglGetError()}" }
            config = configs[0]
            surface = EGL14.eglCreateWindowSurface(display, configs[0], st, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface ${EGL14.eglGetError()}" }
            check(EGL14.eglMakeCurrent(display, surface, surface, ctx)) { "eglMakeCurrent" }
            EGL14.eglSwapInterval(display, 1)

            val frag = context.assets.open("orb.frag").bufferedReader().use { it.readText() }
            val vs = compile(GLES30.GL_VERTEX_SHADER, VERTEX)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, frag)
            prog = GLES30.glCreateProgram()
            GLES30.glAttachShader(prog, vs); GLES30.glAttachShader(prog, fs)
            GLES30.glBindAttribLocation(prog, 0, "v")
            GLES30.glLinkProgram(prog)
            val ok = IntArray(1); GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "link: " + GLES30.glGetProgramInfoLog(prog) }
            GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
            uRes = GLES30.glGetUniformLocation(prog, "uRes"); uTime = GLES30.glGetUniformLocation(prog, "uTime")
            uEnergy = GLES30.glGetUniformLocation(prog, "uEnergy"); uLevel = GLES30.glGetUniformLocation(prog, "uLevel")
            uFlow = GLES30.glGetUniformLocation(prog, "uFlow")
            val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
            quadRef = quad
        }
        private var quadRef: java.nio.FloatBuffer? = null

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "compile: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }

        /** Draws one frame; false if the surface is gone. */
        private fun draw(): Boolean {
            if (w <= 0 || h <= 0) return true
            if (resized || scale != scaleApplied || bw == 0) { resized = false; applyScale() }
            val cw = bw; val ch = bh
            val started = System.nanoTime()
            GLES30.glViewport(0, 0, cw, ch)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(prog)
            GLES30.glUniform2f(uRes, cw.toFloat(), ch.toFloat())
            GLES30.glUniform1f(uTime, if (still) 4f else OrbView.clock())
            GLES30.glUniform1f(uEnergy, shownE)
            GLES30.glUniform1f(uLevel, shownL)
            GLES30.glUniform1f(uFlow, if (still) 0.2f else flow.toFloat())
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            if (!checked) {
                // look at the first frame before showing it: a broken driver gives black, nothing, or an error
                val problem = inspect(cw, ch)
                if (problem != null) { main.post { onFailed(problem) }; running = false; return false }
                checked = true
                main.post { firstFrameShown() }
            }
            GLES30.glFinish()
            if (!judge((System.nanoTime() - started) / 1e6f)) return false
            if (!EGL14.eglSwapBuffers(display, surface)) {
                val err = EGL14.eglGetError()
                if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW) return false
                Log.w("AtlasOrb", "swap failed: $err")
            }
            return true
        }

        /** Draws at [scale] of the view's size: a smaller buffer that the screen stretches to fit. */
        private fun applyScale() {
            bw = (w * scale).toInt().coerceAtLeast(8); bh = (h * scale).toInt().coerceAtLeast(8)
            if (scale == scaleApplied && bw == w && bh == h) return
            st.setDefaultBufferSize(bw, bh)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            surface = EGL14.eglCreateWindowSurface(display, config, st, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface ${EGL14.eglGetError()}" }
            check(EGL14.eglMakeCurrent(display, surface, surface, ctx)) { "eglMakeCurrent" }
            scaleApplied = scale
        }

        /**
         * Keeps the orb from ever hogging the graphics chip (that would stall the whole screen). A phone's chip
         * draws a frame in a few milliseconds; if frames take far longer, it draws at lower sharpness, and if
         * that is still slow, hands over to the processor. False when it has handed over.
         */
        private fun judge(ms: Float): Boolean {
            val n = frames++
            recent[n % recent.size] = ms
            val avg = if (n + 1 >= recent.size) recent.average().toFloat() else 0f
            // the first two frames may include the driver preparing the shader: only an extreme time counts
            val slow = if (n < 2) ms > 800f else ms > 120f || avg > 18f
            if (!slow) return true
            Log.w("AtlasOrb", "frame took ${"%.1f".format(ms)} ms (average ${"%.1f".format(avg)}) at ${(scale * 100).toInt()}% sharpness")
            frames = 0; recent.fill(0f)
            scale = when (scale) { 1f -> 0.6f; 0.6f -> 0.4f; else -> 0f }
            if (scale == 0f) { running = false; main.post { onFailed("too slow to draw (${"%.0f".format(ms)} ms a frame)") }; return false }
            return true
        }

        /** The middle row of the frame must hold the lit glass (bright blue, fully opaque); the corners must be clear. */
        private fun inspect(cw: Int, ch: Int): String? {
            GLES30.glFinish()
            val glErr = GLES30.glGetError()
            if (glErr != GLES30.GL_NO_ERROR) return "GL error $glErr"
            val row = ByteBuffer.allocateDirect(cw * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, ch / 2, cw, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, row)
            var maxA = 0; var maxB = 0
            for (x in 0 until cw) {
                val b = row.get(x * 4 + 2).toInt() and 0xFF; val a = row.get(x * 4 + 3).toInt() and 0xFF
                if (a > maxA) maxA = a; if (b > maxB) maxB = b
            }
            val corner = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, corner)
            val cornerA = corner.get(3).toInt() and 0xFF
            return when {
                maxA < 200 -> "nothing drawn (alpha $maxA)"
                maxB < 60 -> "drew a dark shape with no light (blue $maxB)"
                cornerA > 40 -> "drew a filled square (corner alpha $cornerA)"
                else -> null
            }
        }

        private fun release() {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    if (prog != 0) GLES30.glDeleteProgram(prog)
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
                    EGL14.eglReleaseThread()
                }
            } catch (t: Throwable) { Log.w("AtlasOrb", "release", t) }
        }
    }

    companion object {
        private const val VERTEX = "#version 300 es\nlayout(location = 0) in vec2 v;\nvoid main() { gl_Position = vec4(v, 0.0, 1.0); }\n"
    }
}

package com.gilrtano.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import android.provider.MediaStore
import android.net.Uri
import android.graphics.BitmapFactory
import android.widget.Toast
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.view.WindowInsets
import android.view.WindowInsetsController
import kotlin.math.min
import kotlin.math.max

/** Full-screen FlClashX v0.5.7-inspired QR scanner: live camera, animated scan beam,
 * rotating luminous frame, torch and gallery import. Returns the decoded raw value. */
class FlClashXScanActivity : Activity() {
    private lateinit var camera: DecoratedBarcodeView
    private lateinit var overlay: ScanOverlayView
    private var delivered = false
    private var torchEnabled = false
    private val cameraPermissionRequest = 7310
    private val galleryRequest = 7311

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(WindowInsets.Type.statusBars())
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
        camera = DecoratedBarcodeView(this).apply {
            statusView.visibility = View.GONE
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(camera, FrameLayout.LayoutParams(-1, -1))
        overlay = ScanOverlayView(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        overlay.onClose = { finish() }
        overlay.onGallery = {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
            }, galleryRequest)
        }
        overlay.onTorch = {
            torchEnabled = !torchEnabled
            runCatching { if (torchEnabled) camera.setTorchOn() else camera.setTorchOff() }
        }
        camera.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                val value = result?.text?.trim().orEmpty()
                if (value.isNotEmpty() && !delivered) {
                    delivered = true
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, value))
                    finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (!::camera.isInitialized || delivered) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            camera.resume()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraPermissionRequest)
        }
    }

    @Deprecated("Deprecated by Android API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == cameraPermissionRequest) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) camera.resume()
            else {
                Toast.makeText(this, "Для сканирования нужен доступ к камере", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    override fun onPause() { if (::camera.isInitialized) camera.pause(); super.onPause() }

    @Deprecated("Deprecated by Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != galleryRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val decoded = runCatching { decodeImage(uri) }.getOrNull()
            runOnUiThread {
                if (decoded != null) {
                    delivered = true
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, decoded))
                    finish()
                } else Toast.makeText(this, "На изображении не найден QR-код", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun decodeImage(uri: Uri): String? {
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val reader = MultiFormatReader()
        reader.setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
        return try { reader.decode(BinaryBitmap(HybridBinarizer(source))).text } finally { reader.reset(); bitmap.recycle() }
    }

    companion object { const val EXTRA_RESULT = "gilrtano.scan.result" }
}

private class ScanOverlayView(context: android.content.Context) : View(context) {
    var onClose: (() -> Unit)? = null
    var onGallery: (() -> Unit)? = null
    var onTorch: (() -> Unit)? = null
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 15f; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD0D0D0.toInt(); textSize = 14f; textAlign = Paint.Align.CENTER }
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(7f); maskFilter = BlurMaskFilter(dp(8f), BlurMaskFilter.Blur.NORMAL) }
    private val button = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC17191D.toInt() }
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private var phase = 0f
    private var torchOn = false
    private val runner = object : Runnable {
        override fun run() { phase = (phase + .008f) % 1f; invalidate(); postDelayed(this, 16) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(runner) }
    override fun onDetachedFromWindow() { removeCallbacks(runner); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val insetTop = dp(64f)
        val bottomReserve = dp(188f)
        val side = min(dp(400f), min(w * .78f, h - insetTop - bottomReserve)).coerceAtLeast(dp(190f))
        val cx = w / 2f
        val cy = (insetTop + (h - bottomReserve)) / 2f
        val rect = RectF(cx - side/2, cy-side/2, cx+side/2, cy+side/2)
        val radius = dp(22f)
        val shade = Path().apply { fillType = Path.FillType.EVEN_ODD; addRect(0f,0f,w,h,Path.Direction.CW); addRoundRect(rect,radius,radius,Path.Direction.CW) }
        canvas.drawPath(shade, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() })
        val sweep = SweepGradient(cx,cy, intArrayOf(Color.TRANSPARENT,0xFF8DFFE0.toInt(),0xFF20DFA0.toInt(),Color.TRANSPARENT), floatArrayOf(0f,.55f,.9f,1f))
        val matrix = Matrix().apply { setRotate(phase*360f,cx,cy) }; sweep.setLocalMatrix(matrix)
        glow.shader=sweep; border.shader=sweep
        canvas.drawRoundRect(rect,radius,radius,glow)
        canvas.drawRoundRect(rect,radius,radius,border)
        // Scanner sweep beam and soft grid trail.
        val y = rect.top + rect.height() * ((1f - kotlin.math.cos(phase * Math.PI * 2).toFloat()) / 2f)
        val trail = RectF(rect.left+dp(2f), max(rect.top,y-rect.height()*.42f), rect.right-dp(2f), y)
        if (trail.height()>0) {
            beam.shader=LinearGradient(0f,trail.top,0f,trail.bottom, intArrayOf(Color.TRANSPARENT,0x3324F5B2,0x5524F5B2),null,Shader.TileMode.CLAMP)
            canvas.save(); canvas.clipPath(Path().apply { addRoundRect(rect,radius,radius,Path.Direction.CW) }); canvas.drawRect(trail,beam)
            val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0x3324F5B2; strokeWidth=dp(1f) }
            var gx=rect.left
            while(gx<rect.right){canvas.drawLine(gx,trail.top,gx,trail.bottom,grid);gx+=side/18f}
            canvas.restore()
        }
        beam.shader=LinearGradient(rect.left,y,rect.right,y,intArrayOf(Color.TRANSPARENT,0xFF20E6A5.toInt(),0xFFB7FFE9.toInt(),0xFF20E6A5.toInt(),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        canvas.drawRect(rect.left,y-dp(1f),rect.right,y+dp(1f),beam)
        // Top actions.
        drawAction(canvas,dp(42f),dp(44f),"×")
        drawAction(canvas,w-dp(44f),dp(44f),"▧")
        canvas.drawText("Наведите камеру на QR-код профиля",cx,rect.bottom+dp(38f),muted)
        canvas.drawText("Код будет распознан автоматически",cx,rect.bottom+dp(60f),muted)
        val ty=h-dp(86f); val radiusButton=dp(31f)
        canvas.drawCircle(cx,ty,radiusButton,button)
        canvas.drawCircle(cx,ty,radiusButton,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=if(torchOn) 0xFF20E6A5.toInt() else 0xFF666A70.toInt(); style=Paint.Style.STROKE; strokeWidth=dp(1.5f) })
        text.textSize=dp(25f); canvas.drawText("ϟ",cx,ty+dp(9f),text); text.textSize=dp(15f)
        canvas.drawText("Фонарик",cx,ty+dp(55f),muted)
    }

    private fun drawAction(c:Canvas,x:Float,y:Float,label:String){
        c.drawCircle(x,y,dp(22f),button)
        text.textSize=dp(25f); c.drawText(label,x,y+dp(8f),text); text.textSize=dp(15f)
    }
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if(event.action!=android.view.MotionEvent.ACTION_UP) return true
        val w=width.toFloat(); val h=height.toFloat()
        if(event.y<dp(82f) && event.x<dp(85f)){onClose?.invoke();return true}
        if(event.y<dp(82f) && event.x>w-dp(90f)){onGallery?.invoke();return true}
        if(event.y>h-dp(145f) && event.x>w*.32f && event.x<w*.68f){torchOn=!torchOn;onTorch?.invoke();invalidate();return true}
        return true
    }
}

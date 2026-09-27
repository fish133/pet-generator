package com.doubao.pet.littlewhale

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

class PetFloatingService : Service() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "pet_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var petView: View
    private lateinit var ivPet: ImageView
    private lateinit var tvBubble: TextView
    private var layoutParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private lateinit var prefs: SharedPreferences
    private var petSize = 180

    private val defaultBubbleTexts = listOf("你好呀", "今天也要加油哦", "摸摸~")
    private var bubbleTexts: List<String> = defaultBubbleTexts

    private fun loadBubbleTexts() {
        try {
            if (!::prefs.isInitialized) return
            val saved = prefs.getString(MainActivity.KEY_BUBBLE_TEXTS, "")
            if (!saved.isNullOrEmpty()) {
                val list = saved.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (list.isNotEmpty()) { bubbleTexts = list; return }
            }
            bubbleTexts = defaultBubbleTexts
        } catch (e: Exception) { bubbleTexts = defaultBubbleTexts }
    }

    private var gifNames: List<String> = emptyList()
    private var currentFrames: List<Bitmap> = emptyList()
    private var currentGifIndex = 0
    private var isPlayingCustom = false
    private var customFrames: List<Bitmap> = emptyList()

    private fun loadGifList() {
        gifNames = GifUtils.getGifList(this)
        if (currentGifIndex >= gifNames.size) currentGifIndex = 0
    }

    private fun loadFramesForGif(index: Int): List<Bitmap> {
        currentFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
        if (index >= 0 && index < gifNames.size) {
            currentFrames = GifUtils.loadGifFrames(this, gifNames[index])
        } else {
            currentFrames = emptyList()
        }
        return currentFrames
    }

    private fun getCurrentIdleFrames(): List<Bitmap> {
        if (currentFrames.isNotEmpty()) return currentFrames
        if (gifNames.isNotEmpty() && currentGifIndex < gifNames.size) return loadFramesForGif(currentGifIndex)
        return emptyList()
    }

    private val sizeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.doubao.pet.UPDATE_SIZE") updatePetSize(intent.getIntExtra("size", petSize))
        }
    }
    private val bubbleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.doubao.pet.UPDATE_BUBBLE") loadBubbleTexts()
        }
    }
    private val refreshGifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.doubao.pet.REFRESH_GIFS") {
                loadGifList(); loadFramesForGif(currentGifIndex); currentFrame = 0
                Toast.makeText(this@PetFloatingService, "素材已刷新", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentFrame = 0

    private val animationRunnable = object : Runnable {
        override fun run() {
            val frames = if (isPlayingCustom && customFrames.isNotEmpty()) customFrames else getCurrentIdleFrames()
            if (frames.isEmpty()) { handler.postDelayed(this, 100); return }
            if (currentFrame >= frames.size) {
                if (isPlayingCustom) {
                    isPlayingCustom = false
                    customFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
                    customFrames = emptyList(); currentFrame = 0
                } else currentFrame = 0
            }
            if (currentFrame < frames.size) {
                try { ivPet.setImageBitmap(frames[currentFrame]) } catch (_: Exception) {}
                currentFrame++
            }
            handler.postDelayed(this, 80)
        }
    }

    private val bubbleRunnable = object : Runnable {
        override fun run() {
            try { if (!isPlayingCustom && ::tvBubble.isInitialized) showRandomBubble() } catch (_: Exception) {}
            handler.postDelayed(this, (10000 + Math.random() * 30000).toLong())
        }
    }

    private fun showRandomBubble() {
        if (bubbleTexts.isEmpty()) return
        tvBubble.text = bubbleTexts.random()
        tvBubble.visibility = View.VISIBLE
        handler.postDelayed({ tvBubble.visibility = View.GONE }, 3000)
    }

    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var isDragging = false; private var downTime = 0L

    private val touchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams?.x ?: 0; initialY = layoutParams?.y ?: 0
                initialTouchX = event.rawX; initialTouchY = event.rawY
                isDragging = false; downTime = System.currentTimeMillis(); true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX; val dy = event.rawY - initialTouchY
                if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                if (isDragging) {
                    layoutParams?.x = initialX + dx.toInt(); layoutParams?.y = initialY + dy.toInt()
                    try { windowManager.updateViewLayout(petView, layoutParams) } catch (_: Exception) {}
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - downTime
                if (!isDragging && duration < 500) playNextGif()
                else if (!isDragging && duration >= 500) showEmojiMenu()
                true
            }
            else -> false
        }
    }

    private fun playNextGif() {
        if (gifNames.size <= 1) { currentFrame = 0; return }
        val nextIndex = (currentGifIndex + 1) % gifNames.size
        val frames = GifUtils.loadGifFrames(this, gifNames[nextIndex])
        if (frames.isNotEmpty()) {
            customFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
            customFrames = frames; isPlayingCustom = true; currentFrame = 0
        }
    }

    private fun showEmojiMenu() {
        if (menuView != null) return
        val menuOverlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        menuView = LayoutInflater.from(this).inflate(R.layout.pet_menu, null)
        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            menuOverlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        menuParams.gravity = Gravity.CENTER
        val menuRoot = menuView!!.findViewById<View>(R.id.menuRoot)
        val tvTitle = menuView!!.findViewById<TextView>(R.id.tvMenuTitle)
        val btnClose = menuView!!.findViewById<View>(R.id.btnMenuClose)
        val scrollContent = menuView!!.findViewById<LinearLayout>(R.id.menuContent)
        val btnExit = menuView!!.findViewById<Button>(R.id.btnExitPet)
        tvTitle.text = "选择 GIF（${gifNames.size}个）"
        menuRoot.setOnClickListener { hideEmojiMenu() }
        btnClose.setOnClickListener { hideEmojiMenu() }
        scrollContent.removeAllViews()
        if (gifNames.isEmpty()) {
            val empty = TextView(this).apply {
                text = "还没有上传 GIF\n请在主界面点击「上传 GIF 表情包」"
                textSize = 14f; setTextColor(0xFF888888.toInt())
                gravity = android.view.Gravity.CENTER; setPadding(0, 40, 0, 40)
            }
            scrollContent.addView(empty)
        } else {
            gifNames.forEachIndexed { index, name ->
                val item = createMenuItem(name, index == currentGifIndex)
                item.setOnClickListener {
                    currentGifIndex = index; isPlayingCustom = false
                    customFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
                    customFrames = emptyList(); loadFramesForGif(index); currentFrame = 0
                    hideEmojiMenu()
                    Toast.makeText(this, "已切换到 GIF ${index + 1}", Toast.LENGTH_SHORT).show()
                }
                item.setOnLongClickListener {
                    if (gifNames.size > 1) {
                        GifUtils.deleteGif(this, name); loadGifList()
                        if (currentGifIndex >= gifNames.size) currentGifIndex = 0
                        loadFramesForGif(currentGifIndex); currentFrame = 0
                        hideEmojiMenu()
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(this, "至少保留一个 GIF", Toast.LENGTH_SHORT).show()
                    true
                }
                scrollContent.addView(item)
            }
        }
        btnExit.setOnClickListener { hideEmojiMenu(); stopSelf() }
        try { windowManager.addView(menuView, menuParams) } catch (e: Exception) { menuView = null }
    }

    private fun createMenuItem(name: String, isActive: Boolean): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(40, 24, 40, 24)
            gravity = android.view.Gravity.CENTER_VERTICAL
            if (isActive) setBackgroundColor(0x1A4CAF50)
        }
        val indicator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(12, 12)
            setBackgroundColor(if (isActive) 0xFF4CAF50.toInt() else 0xFFCCCCCC.toInt())
        }
        val label = TextView(this).apply {
            text = "  GIF ${gifNames.indexOf(name) + 1}"; textSize = 15f
            setTextColor(if (isActive) 0xFF2E7D32.toInt() else 0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hint = TextView(this).apply { this.text = "长按删除"; textSize = 11f; setTextColor(0xFFAAAAAA.toInt()) }
        layout.addView(indicator); layout.addView(label); layout.addView(hint)
        return layout
    }

    private fun hideEmojiMenu() {
        menuView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        menuView = null
    }

    private fun updatePetSize(size: Int) {
        petSize = size; layoutParams?.width = size; layoutParams?.height = size
        try { windowManager.updateViewLayout(petView, layoutParams) } catch (_: Exception) {}
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        petSize = prefs.getInt(MainActivity.KEY_SIZE, MainActivity.DEFAULT_SIZE)
        loadBubbleTexts(); loadGifList(); loadFramesForGif(currentGifIndex)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        petView = LayoutInflater.from(this).inflate(R.layout.pet_floating, null)
        ivPet = petView.findViewById(R.id.ivPet)
        tvBubble = petView.findViewById(R.id.tvBubble)
        ivPet.setImageBitmap(createPlaceholderBitmap())
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        layoutParams = WindowManager.LayoutParams(petSize, petSize, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 100; y = 300 }
        petView.setOnTouchListener(touchListener)
        try { windowManager.addView(petView, layoutParams) } catch (e: Exception) { e.printStackTrace(); stopSelf(); return }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(sizeReceiver, IntentFilter("com.doubao.pet.UPDATE_SIZE"), Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(bubbleReceiver, IntentFilter("com.doubao.pet.UPDATE_BUBBLE"), Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(refreshGifReceiver, IntentFilter("com.doubao.pet.REFRESH_GIFS"), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(sizeReceiver, IntentFilter("com.doubao.pet.UPDATE_SIZE"))
                registerReceiver(bubbleReceiver, IntentFilter("com.doubao.pet.UPDATE_BUBBLE"))
                registerReceiver(refreshGifReceiver, IntentFilter("com.doubao.pet.REFRESH_GIFS"))
            }
        } catch (e: Exception) { e.printStackTrace() }
        handler.post(animationRunnable)
        handler.postDelayed(bubbleRunnable, 5000)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) { try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) {} }
    }

    private fun createPlaceholderBitmap(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF4A90D9.toInt()
        canvas.drawCircle(size/2f, size/2f, size*0.38f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        val cx = size/2f; val cy = size*0.55f
        canvas.drawOval(cx-size*0.12f, cy-size*0.09f, cx+size*0.12f, cy+size*0.09f, paint)
        val toeR = size*0.04f; val toeY = size*0.38f
        for (dx in floatArrayOf(-size*0.12f, -size*0.04f, size*0.04f, size*0.12f)) canvas.drawCircle(cx+dx, toeY, toeR, paint)
        return bmp
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "桌宠服务", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("桌宠运行中").setContentText("点击返回主界面")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(pi).setOngoing(true).build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(sizeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(bubbleReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(refreshGifReceiver) } catch (_: Exception) {}
        hideEmojiMenu()
        try { windowManager.removeView(petView) } catch (_: Exception) {}
        currentFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
        customFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
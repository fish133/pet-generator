package com.petgen.app

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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.media.SoundPool
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class PetFloatingService : Service() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "pet_service_channel"
        private const val NOTIFICATION_ID = 1001
        // 统一后台线程池，避免频繁 new Thread 的开销
        private val ioExecutor = Executors.newFixedThreadPool(2)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var petView: View
    private lateinit var ivPet: ImageView
    private lateinit var tvBubble: TextView
    private var layoutParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private lateinit var prefs: SharedPreferences
    private var petSize = 280

    // 音效
    private var soundPool: SoundPool? = null
    private var duckSoundId = 0
    private var bingbingSoundId = 0
    private var clickSoundEnabled = false
    private var soundType = "duck"

    private fun loadSoundSettings() {
        try {
            if (!::prefs.isInitialized) return
            clickSoundEnabled = prefs.getBoolean(MainActivity.KEY_CLICK_SOUND, false)
            soundType = prefs.getString(MainActivity.KEY_SOUND_TYPE, "duck") ?: "duck"
        } catch (_: Exception) {}
    }

    private fun playClickSound() {
        if (!clickSoundEnabled) return
        val sp = soundPool ?: return
        val soundId = if (soundType == "bingbing") bingbingSoundId else duckSoundId
        if (soundId != 0) sp.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    // 默认气泡文案（用户未自定义时使用，留空则不显示）
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

    // ===== GIF 素材管理（按需加载，避免 OOM）=====
    private var gifNames: List<String> = emptyList()       // 所有 GIF 名称
    private var currentFrames: List<Bitmap> = emptyList()   // 当前正在播放的帧
    private var currentGifIndex = 0                          // 当前 idle 用的 GIF 索引
    private var isPlayingCustom = false                       // 是否正在播放点击触发的 GIF
    private var customFrames: List<Bitmap> = emptyList()     // 点击触发播放的帧

    private fun loadGifList() {
        gifNames = GifUtils.getGifList(this)
        if (currentGifIndex >= gifNames.size) currentGifIndex = 0
    }

    /** 加载指定 GIF 的帧到 currentFrames（延迟回收旧帧避免动画线程竞争） */
    @Synchronized
    private fun loadFramesForGif(index: Int): List<Bitmap> {
        val oldFrames = currentFrames
        currentFrames = if (index >= 0 && index < gifNames.size) {
            GifUtils.loadGifFrames(this, gifNames[index])
        } else {
            emptyList()
        }
        // 延迟回收旧帧，等待动画线程切换到新帧后再回收，避免 recycled bitmap 崩溃
        handler.postDelayed({
            oldFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
        }, 500)
        return currentFrames
    }

    private fun getCurrentIdleFrames(): List<Bitmap> {
        if (currentFrames.isNotEmpty()) return currentFrames
        if (gifNames.isNotEmpty() && currentGifIndex < gifNames.size) {
            ioExecutor.execute { loadFramesForGif(currentGifIndex) }
        }
        return emptyList()
    }

    // ===== 广播接收器 =====
    private val sizeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.petgen.app.UPDATE_SIZE") {
                updatePetSize(intent.getIntExtra("size", petSize))
            }
        }
    }

    private val bubbleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.petgen.app.UPDATE_BUBBLE") loadBubbleTexts()
        }
    }

    private val soundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.petgen.app.UPDATE_SOUND") loadSoundSettings()
        }
    }

    private val refreshGifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.petgen.app.REFRESH_GIFS") {
                ioExecutor.execute {
                    loadGifList()
                    loadFramesForGif(currentGifIndex)
                    handler.post {
                        currentFrame = 0
                        Toast.makeText(this@PetFloatingService, "素材已刷新", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ===== 动画播放 =====
    private val handler = Handler(Looper.getMainLooper())
    private var currentFrame = 0

    private val animationRunnable = object : Runnable {
        override fun run() {
            var frames = if (isPlayingCustom && customFrames.isNotEmpty()) {
                customFrames
            } else {
                getCurrentIdleFrames()
            }

            if (frames.isEmpty()) {
                handler.postDelayed(this, 100)
                return
            }

            if (currentFrame >= frames.size) {
                if (isPlayingCustom) {
                    // 自定义 GIF 播放完，回收并回到 idle
                    isPlayingCustom = false
                    customFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
                    customFrames = emptyList()
                    currentFrame = 0
                    // 重新获取 idle 帧，避免访问已回收的位图
                    frames = getCurrentIdleFrames()
                    if (frames.isEmpty()) {
                        handler.postDelayed(this, 100)
                        return
                    }
                } else {
                    currentFrame = 0
                }
            }

            if (currentFrame < frames.size) {
                try { ivPet.setImageBitmap(frames[currentFrame]) } catch (_: Exception) {}
                currentFrame++
            }

            handler.postDelayed(this, 80)
        }
    }

    // ===== 气泡 =====
    private val bubbleRunnable = object : Runnable {
        override fun run() {
            try {
                if (!isPlayingCustom && ::tvBubble.isInitialized) {
                    showRandomBubble()
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, (10000 + Math.random() * 30000).toLong())
        }
    }

    private fun showRandomBubble() {
        if (bubbleTexts.isEmpty()) return
        tvBubble.text = bubbleTexts.random()
        tvBubble.visibility = View.VISIBLE
        handler.postDelayed({ tvBubble.visibility = View.GONE }, 3000)
    }

    // ===== 随机表情（每 10~40 秒随机播放一个表情）=====
    private val randomEmojiRunnable = object : Runnable {
        override fun run() {
            try {
                if (!isPlayingCustom && gifNames.size > 1) {
                    playRandomEmoji()
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, (10000 + Math.random() * 30000).toLong())
        }
    }

    /** 随机挑选一个与当前待机不同的 GIF，播放一遍后自动回到待机 */
    private fun playRandomEmoji() {
        val count = gifNames.size
        if (count <= 1) return
        var target = currentGifIndex
        while (target == currentGifIndex) {
            target = (Math.random() * count).toInt()
        }
        val idx = target
        ioExecutor.execute {
            val frames = GifUtils.loadGifFrames(this, gifNames[idx])
            handler.post {
                if (frames.isNotEmpty()) {
                    customFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
                    customFrames = frames
                    isPlayingCustom = true
                    currentFrame = 0
                }
            }
        }
    }

    // ===== 触摸/拖动 =====
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var downTime = 0L
    private var longPressTriggered = false
    private val longPressRunnable = Runnable {
        if (!isDragging) {
            longPressTriggered = true
            showEmojiMenu()
        }
    }

    private val touchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams?.x ?: 0
                initialY = layoutParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                longPressTriggered = false
                downTime = System.currentTimeMillis()
                handler.postDelayed(longPressRunnable, 350)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (Math.abs(dx) > 25 || Math.abs(dy) > 25) {
                    if (!isDragging) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                }
                if (isDragging) {
                    layoutParams?.x = initialX + dx.toInt()
                    layoutParams?.y = initialY + dy.toInt()
                    try { windowManager.updateViewLayout(petView, layoutParams) } catch (_: Exception) {}
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                val duration = System.currentTimeMillis() - downTime
                if (!isDragging && !longPressTriggered && duration < 350) {
                    playNextGif()
                    playClickSound()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                true
            }
            else -> false
        }
    }

    private fun playNextGif() {
        if (gifNames.size <= 1) {
            currentFrame = 0
            return
        }
        val nextIndex = (currentGifIndex + 1) % gifNames.size
        ioExecutor.execute {
            val frames = GifUtils.loadGifFrames(this, gifNames[nextIndex])
            handler.post {
                if (frames.isNotEmpty()) {
                    customFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
                    customFrames = frames
                    isPlayingCustom = true
                    currentFrame = 0
                }
            }
        }
    }

    // ===== 表情菜单 =====
    private fun showEmojiMenu() {
        if (menuView != null) return

        val menuOverlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_PetGen)
        menuView = LayoutInflater.from(themedContext).inflate(R.layout.pet_menu, null)
        val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            menuOverlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        menuParams.gravity = Gravity.CENTER

        val menuRoot = menuView!!.findViewById<View>(R.id.menuRoot)
        val tvTitle = menuView!!.findViewById<TextView>(R.id.tvMenuTitle)
        val btnClose = menuView!!.findViewById<View>(R.id.btnMenuClose)
        val scrollContent = menuView!!.findViewById<LinearLayout>(R.id.menuContent)
        val btnExit = menuView!!.findViewById<TextView>(R.id.btnExitPet)

        tvTitle.text = "选择 GIF（${gifNames.size}个）"

        // 点击外部关闭（panel 不消费事件，子按钮可正常点击；点击 panel 空白会冒泡到 menuRoot 关闭）
        menuRoot.setOnClickListener { hideEmojiMenu() }
        btnClose.setOnClickListener { hideEmojiMenu() }

        // 列出所有 GIF
        scrollContent.removeAllViews()
        if (gifNames.isEmpty()) {
            val empty = TextView(this).apply {
                text = "还没有上传 GIF\n请在主界面点击「上传 GIF 表情包」"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(0, 40, 0, 40)
            }
            scrollContent.addView(empty)
        } else {
            gifNames.forEachIndexed { index, name ->
                val item = createMenuItem(name, index == currentGifIndex)
                item.setOnClickListener { switchToGif(index) }
                item.setOnLongClickListener {
                    deleteGifFromMenu(name)
                    true
                }
                scrollContent.addView(item)
            }
        }

        btnExit.setOnClickListener {
            hideEmojiMenu()
            stopSelf()
        }

        try {
            windowManager.addView(menuView, menuParams)
        } catch (e: Exception) {
            menuView = null
        }
    }

    private fun switchToGif(index: Int) {
        currentGifIndex = index
        isPlayingCustom = false
        customFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
        customFrames = emptyList()
        ioExecutor.execute {
            loadFramesForGif(index)
            handler.post {
                currentFrame = 0
                hideEmojiMenu()
                Toast.makeText(this@PetFloatingService, "已切换到 GIF ${index + 1}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteGifFromMenu(name: String) {
        if (gifNames.size <= 1) {
            Toast.makeText(this, "至少保留一个 GIF", Toast.LENGTH_SHORT).show()
            return
        }
        ioExecutor.execute {
            GifUtils.deleteGif(this, name)
            handler.post {
                loadGifList()
                if (currentGifIndex >= gifNames.size) currentGifIndex = 0
                ioExecutor.execute {
                    loadFramesForGif(currentGifIndex)
                    handler.post {
                        currentFrame = 0
                        hideEmojiMenu()
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun createMenuItem(name: String, isActive: Boolean): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 24, 40, 24)
            gravity = android.view.Gravity.CENTER_VERTICAL
            if (isActive) setBackgroundColor(0x1A4CAF50)
        }
        val indicator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(12, 12)
            setBackgroundColor(if (isActive) 0xFF4CAF50.toInt() else 0xFFCCCCCC.toInt())
        }
        val label = TextView(this).apply {
            text = "  GIF ${gifNames.indexOf(name) + 1}"
            textSize = 15f
            setTextColor(if (isActive) 0xFF2E7D32.toInt() else 0xFF333333.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hint = TextView(this).apply {
            this.text = "长按删除"
            textSize = 11f
            setTextColor(0xFFAAAAAA.toInt())
        }
        layout.addView(indicator)
        layout.addView(label)
        layout.addView(hint)
        return layout
    }

    private fun hideEmojiMenu() {
        menuView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        menuView = null
    }

    // ===== 大小 =====
    private fun updatePetSize(size: Int) {
        petSize = size
        layoutParams?.width = size
        layoutParams?.height = size
        try { windowManager.updateViewLayout(petView, layoutParams) } catch (_: Exception) {}
    }

    // ===== 生命周期 =====
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        petSize = prefs.getInt(MainActivity.KEY_SIZE, MainActivity.DEFAULT_SIZE)
        loadBubbleTexts()
        loadSoundSettings()
        loadGifList()

        // 初始化音效
        soundPool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_GAME)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            ).build()
        duckSoundId = soundPool?.load(this, R.raw.duck, 1) ?: 0
        bingbingSoundId = soundPool?.load(this, R.raw.bingbing, 1) ?: 0

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        petView = LayoutInflater.from(this).inflate(R.layout.pet_floating, null)
        ivPet = petView.findViewById(R.id.ivPet)
        tvBubble = petView.findViewById(R.id.tvBubble)
        // 没素材时显示默认占位
        placeholderBitmap = createPlaceholderBitmap()
        ivPet.setImageBitmap(placeholderBitmap)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            petSize, petSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        petView.setOnTouchListener(touchListener)

        try {
            windowManager.addView(petView, layoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
            return
        }

        // 注册广播（NOT_EXPORTED 防止任意 App 伪造广播操控桌宠）
        try {
            val fSize = IntentFilter("com.petgen.app.UPDATE_SIZE")
            val fBubble = IntentFilter("com.petgen.app.UPDATE_BUBBLE")
            val fRefresh = IntentFilter("com.petgen.app.REFRESH_GIFS")
            val fSound = IntentFilter("com.petgen.app.UPDATE_SOUND")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(sizeReceiver, fSize, Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(bubbleReceiver, fBubble, Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(refreshGifReceiver, fRefresh, Context.RECEIVER_NOT_EXPORTED)
                registerReceiver(soundReceiver, fSound, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ContextCompat.registerReceiver(this, sizeReceiver, fSize, ContextCompat.RECEIVER_NOT_EXPORTED)
                ContextCompat.registerReceiver(this, bubbleReceiver, fBubble, ContextCompat.RECEIVER_NOT_EXPORTED)
                ContextCompat.registerReceiver(this, refreshGifReceiver, fRefresh, ContextCompat.RECEIVER_NOT_EXPORTED)
                ContextCompat.registerReceiver(this, soundReceiver, fSound, ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        } catch (e: Exception) { e.printStackTrace() }

        // 启动动画、气泡和随机表情
        handler.post(animationRunnable)
        handler.postDelayed(bubbleRunnable, 5000)
        handler.postDelayed(randomEmojiRunnable, 8000)

        // 后台加载初始帧，避免阻塞主线程
        ioExecutor.execute {
            loadFramesForGif(currentGifIndex)
        }

        // Android 14+ 必须传入前台服务类型；用 specialUse 适合长期运行的桌宠
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            // 兜底：不带类型启动
            try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) {}
        }
    }

    private var placeholderBitmap: Bitmap? = null

    private fun createPlaceholderBitmap(): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f
        val cy = size / 2f
        // 蓝色圆形身体
        paint.color = 0xFF4A90D9.toInt()
        canvas.drawCircle(cx, cy, size * 0.40f, paint)
        // 白色爪印：掌垫 + 四指（坐标均相对圆心，确保在圆内可见）
        paint.color = 0xFFFFFFFF.toInt()
        val padW = size * 0.14f
        val padH = size * 0.10f
        val padTop = cy + size * 0.04f
        canvas.drawOval(cx - padW, padTop, cx + padW, padTop + padH, paint)
        val toeR = size * 0.045f
        val toeY = cy - size * 0.08f
        for (dx in floatArrayOf(-size * 0.13f, -size * 0.045f, size * 0.045f, size * 0.13f)) {
            canvas.drawCircle(cx + dx, toeY, toeR, paint)
        }
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
            .setContentTitle("桌宠运行中")
            .setContentText("点击返回主界面")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(sizeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(bubbleReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(refreshGifReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(soundReceiver) } catch (_: Exception) {}
        try { soundPool?.release() } catch (_: Exception) {}
        soundPool = null
        hideEmojiMenu()
        try { windowManager.removeView(petView) } catch (_: Exception) {}
        // 回收位图
        currentFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
        customFrames.forEach { try { it.recycle() } catch (_: Exception) {} }
        placeholderBitmap?.let { try { it.recycle() } catch (_: Exception) {} }
        placeholderBitmap = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

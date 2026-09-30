package com.petgen.app

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.petgen.app.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isRunning = false
    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "pet_prefs"
        const val KEY_SIZE = "pet_size"
        const val KEY_BUBBLE_TEXTS = "bubble_texts"
        const val DEFAULT_SIZE = 220
        private const val REQUEST_PICK_GIF = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
        }

        val savedSize = prefs.getInt(KEY_SIZE, DEFAULT_SIZE)
        binding.sbSize.progress = savedSize - 80
        binding.tvSizeValue.text = "${savedSize}dp"

        val savedBubble = prefs.getString(KEY_BUBBLE_TEXTS, "")
        if (!savedBubble.isNullOrEmpty()) {
            binding.etBubbleTexts.setText(savedBubble)
        }

        binding.btnSaveBubble.setOnClickListener {
            val text = binding.etBubbleTexts.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "请输入至少一句文案", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_BUBBLE_TEXTS, text).apply()
            if (isRunning) {
                sendBroadcast(Intent("com.petgen.app.UPDATE_BUBBLE"))
            }
            Toast.makeText(this, "气泡文案已更新", Toast.LENGTH_SHORT).show()
        }

        binding.btnUploadGif.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/gif"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            try {
                startActivityForResult(intent, REQUEST_PICK_GIF)
            } catch (e: Exception) {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/gif"
                }
                try {
                    startActivityForResult(fallback, REQUEST_PICK_GIF)
                } catch (ex: Exception) {
                    Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnToggle.setOnClickListener {
            if (isRunning) stopPet() else startPet()
        }

        binding.sbSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = 80 + progress
                binding.tvSizeValue.text = "${size}dp"
                prefs.edit().putInt(KEY_SIZE, size).apply()
                if (isRunning) {
                    val intent = Intent("com.petgen.app.UPDATE_SIZE")
                    intent.putExtra("size", size)
                    sendBroadcast(intent)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        updateGifCount()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_GIF && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                handleGifUpload(uri)
            }
        }
    }

    private fun handleGifUpload(uri: Uri) {
        Toast.makeText(this, "正在解析 GIF...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val gifName = "gif_${System.currentTimeMillis()}"
                val outputDir = File(GifUtils.getGifRootDir(this), gifName)
                val frames = GifUtils.extractGifFrames(this, uri, outputDir, maxFrames = 15, targetSize = 512)

                runOnUiThread {
                    if (frames.isNotEmpty()) {
                        Toast.makeText(this, "上传成功！已提取 ${frames.size} 帧", Toast.LENGTH_SHORT).show()
                        updateGifCount()
                        if (isRunning) {
                            sendBroadcast(Intent("com.petgen.app.REFRESH_GIFS"))
                        }
                    } else {
                        Toast.makeText(this, "解析失败，请确认是 GIF 格式", Toast.LENGTH_SHORT).show()
                        outputDir.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "解析出错：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun updateGifCount() {
        val count = GifUtils.getGifList(this).size
        binding.tvGifCount.text = "已上传 $count 个 GIF"
    }

    override fun onResume() {
        super.onResume()
        isRunning = PetFloatingService.isRunning
        updateUI()
        updateGifCount()
    }

    private fun startPet() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay_permission, Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        val intent = Intent(this, PetFloatingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isRunning = true
        updateUI()
        Toast.makeText(this, R.string.pet_running, Toast.LENGTH_SHORT).show()
    }

    private fun stopPet() {
        stopService(Intent(this, PetFloatingService::class.java))
        isRunning = false
        updateUI()
        Toast.makeText(this, R.string.pet_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        if (isRunning) {
            binding.btnToggle.text = getString(R.string.stop_pet)
            binding.tvStatus.text = getString(R.string.pet_running)
        } else {
            binding.btnToggle.text = getString(R.string.start_pet)
            binding.tvStatus.text = getString(R.string.pet_stopped)
        }
    }
}
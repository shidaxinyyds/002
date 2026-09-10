package com.antigravity.mahjong.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.antigravity.mahjong.profile.RegionManager
import com.antigravity.mahjong.service.OverlayService
import com.antigravity.mahjong.service.ScreenCaptureService

/**
 * 主控制台界面 (Dashboard Activity)
 * 负责展示系统状态、功能总览、一键启动/停止服务与引导授权
 */
class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
            startOverlayService()
            Toast.makeText(this, "麻将智囊助手已启动，请切换至麻将游戏！", Toast.LENGTH_LONG).show()
            finish()
        } else {
            Toast.makeText(this, "需授权屏幕捕获方可进行麻将视觉分析", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        initDashboardUI()
    }

    private fun initDashboardUI() {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A")) // 极夜蓝黑底色
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 60, 48, 60)
        }

        // 标题卡片
        val tvTitle = TextView(this).apply {
            text = "🀄 麻将智囊 Agent"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
        container.addView(tvTitle)

        val tvSubtitle = TextView(this).apply {
            text = "四川麻将·血战到底/血流成河 工业级实时分析系统"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8, 0, 36)
        }
        container.addView(tvSubtitle)

        // 核心特性卡片
        val featureCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 30, 36, 30)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 24f
                setStroke(1, Color.parseColor("#334155"))
            }
        }

        val profile = RegionManager.getInstance(this).currentProfile
        val tvProfileInfo = TextView(this).apply {
            text = "当前加载预设: ${profile.name}\n" +
                    "• 视觉引擎: 零切分误差 NCC 模板匹配 (100% 准确率)\n" +
                    "• 头像定缺: HSV 动态自适应感知 (缺条/缺筒/缺万)\n" +
                    "• 108 牌守恒: 单调累加记牌池 + 9x3 存活矩阵\n" +
                    "• 决策内核: 定缺天条门控 + 真实余量进张加权"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setLineSpacing(12f, 1f)
        }
        featureCard.addView(tvProfileInfo)
        container.addView(featureCard)

        // 占位
        val space = LinearLayout(this).apply {
            setPadding(0, 36, 0, 0)
        }
        container.addView(space)

        // 启动主按钮
        val btnStart = Button(this).apply {
            text = "🚀 启动实时分析助手"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 28, 0, 28)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7")) // 科技蓝
                cornerRadius = 20f
            }
            setOnClickListener {
                checkAndRequestPermissions()
            }
        }
        container.addView(btnStart)

        // 停止按钮
        val btnStop = Button(this).apply {
            text = "⏹️ 停止分析服务"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 14f
            setPadding(0, 24, 0, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = 20f
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 24, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "麻将分析服务已完全停止", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(btnStop)

        root.addView(container)
        setContentView(root)
    }

    private fun checkAndRequestPermissions() {
        // 1. 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予麻将助手【悬浮窗】权限", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // 2. 申请屏幕录制授权
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val metrics = resources.displayMetrics
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_WIDTH, metrics.widthPixels)
            putExtra(ScreenCaptureService.EXTRA_HEIGHT, metrics.heightPixels)
            putExtra(ScreenCaptureService.EXTRA_DENSITY, metrics.densityDpi)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        startService(intent)
    }
}

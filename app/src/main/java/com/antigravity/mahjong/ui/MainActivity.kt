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
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.antigravity.mahjong.coordinator.MahjongCoordinator
import com.antigravity.mahjong.profile.RegionManager
import com.antigravity.mahjong.service.OverlayService
import com.antigravity.mahjong.service.ScreenCaptureService

/**
 * 主控制台界面 (Dashboard Activity)
 * 具备：
 * 1. 实时权限与运行状态可视化看板 (悬浮窗 / 屏幕录制 / 国产机防阻拦说明)
 * 2. 一键启动完整服务 (自动拉起浮窗与投屏)
 * 3. 独立悬浮窗唤出测试 (免投屏直接把玩交互)
 * 4. 内置实战数据算法模拟 (一键注入实战数据，验证推荐打牌与 9x3 记牌器)
 * 5. 全屏半透明区域校准直开
 */
class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvCaptureStatus: TextView

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
            tvCaptureStatus.text = "屏幕捕获状态: 🟢 正在捕获画面"
            tvCaptureStatus.setTextColor(Color.parseColor("#10B981"))
            Toast.makeText(this, "✅ 麻将智囊助手已全速运行！悬浮窗已置顶，请进入游戏！", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "未授予屏幕录制权限，视觉分析暂未启动 (悬浮窗仍可手动使用)", Toast.LENGTH_SHORT).show()
            tvCaptureStatus.text = "屏幕捕获状态: ⚪ 未授权"
            tvCaptureStatus.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Android 13+ 通知权限申请
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        initDashboardUI()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        if (hasOverlay) {
            tvOverlayStatus.text = "悬浮窗权限: 🟢 已授权 (可正常弹出)"
            tvOverlayStatus.setTextColor(Color.parseColor("#10B981"))
        } else {
            tvOverlayStatus.text = "悬浮窗权限: 🔴 未授权 (点击立即开启)"
            tvOverlayStatus.setTextColor(Color.parseColor("#EF4444"))
        }
    }

    private fun initDashboardUI() {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A")) // 极夜黑蓝
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 50, 40, 60)
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
            text = "四川麻将·血战到底/血流成河 工业级视觉辅助"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 8, 0, 24)
        }
        container.addView(tvSubtitle)

        // 状态与权限面板卡片
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 20f
                setStroke(1, Color.parseColor("#334155"))
            }
        }

        val tvCardTitle = TextView(this).apply {
            text = "【系统服务与权限状态】"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 12)
        }
        statusCard.addView(tvCardTitle)

        tvOverlayStatus = TextView(this).apply {
            text = "悬浮窗权限: 检测中..."
            textSize = 13f
            setPadding(0, 4, 0, 8)
            setOnClickListener {
                requestOverlayPermission()
            }
        }
        statusCard.addView(tvOverlayStatus)

        tvCaptureStatus = TextView(this).apply {
            text = "屏幕捕获状态: ⚪ 未开启"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, 4, 0, 12)
        }
        statusCard.addView(tvCaptureStatus)

        val tvRomTips = TextView(this).apply {
            text = "⚠️ 小米/华为/OPPO/vivo 提示:\n若点击启动后桌面未看到悬浮窗，请前往手机【系统设置 -> 应用权限】，手动开启【后台弹出界面】或【显示悬浮窗】权限。"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 11f
            setLineSpacing(6f, 1f)
        }
        statusCard.addView(tvRomTips)
        container.addView(statusCard)

        // 间距
        val dividerSpace = LinearLayout(this).apply { setPadding(0, 28, 0, 0) }
        container.addView(dividerSpace)

        // 按钮 1: 🚀 一键启动完整分析 (浮窗 + 抓屏)
        val btnStartAll = Button(this).apply {
            text = "🚀 一键启动实时分析 (推荐)"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, 28, 0, 28)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0284C7")) // 科技蓝
                cornerRadius = 20f
            }
            setOnClickListener {
                startFullPipeline()
            }
        }
        container.addView(btnStartAll)

        // 按钮 2: 👁️ 仅唤出悬浮窗 (免投屏直测)
        val btnStartOverlayOnly = Button(this).apply {
            text = "👁️ 唤出/测试悬浮窗 (免投屏)"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 24, 0, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F766E")) // 墨绿
                cornerRadius = 20f
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 16, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                if (checkOverlayPermissionOnly()) {
                    startOverlayService()
                    Toast.makeText(this@MainActivity, "✅ 悬浮窗已成功唤出！支持拖动、展开与设置", Toast.LENGTH_SHORT).show()
                }
            }
        }
        container.addView(btnStartOverlayOnly)

        // 按钮 3: 🧪 运行实战样本算法模拟 (立即向悬浮窗注入数据)
        val btnMockSimulation = Button(this).apply {
            text = "🧪 注入实战样本 (测试推荐与9x3记牌器)"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 24, 0, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#6D28D9")) // 紫色
                cornerRadius = 20f
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 16, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                if (checkOverlayPermissionOnly()) {
                    startOverlayService()
                    MahjongCoordinator.triggerMockSimulation()
                    Toast.makeText(this@MainActivity, "🀄 已向悬浮窗注入实战牌局数据！请查看浮窗打牌建议与剩余牌矩阵！", Toast.LENGTH_LONG).show()
                }
            }
        }
        container.addView(btnMockSimulation)

        // 按钮 4: 📐 打开全屏区域校准层
        val btnCalibrator = Button(this).apply {
            text = "📐 打开全屏框选校准器 (微调区域)"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 24, 0, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 20f
                setStroke(1, Color.parseColor("#38BDF8"))
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 16, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                openCalibratorDirectly()
            }
        }
        container.addView(btnCalibrator)

        // 按钮 5: 📱 隐藏控制台进入游戏
        val btnEnterGame = Button(this).apply {
            text = "📱 最小化控制台 (进入游戏)"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 14f
            setPadding(0, 22, 0, 22)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = 20f
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 16, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                moveTaskToBack(true)
            }
        }
        container.addView(btnEnterGame)

        // 按钮 6: 🛑 停止所有服务
        val btnStop = Button(this).apply {
            text = "🛑 停止所有分析服务"
            setTextColor(Color.parseColor("#EF4444"))
            textSize = 14f
            setPadding(0, 22, 0, 22)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#450A0A"))
                cornerRadius = 20f
                setStroke(1, Color.parseColor("#EF4444"))
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 16, 0, 0)
            }
            layoutParams = lp
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                tvCaptureStatus.text = "屏幕捕获状态: ⚪ 已完全停止"
                tvCaptureStatus.setTextColor(Color.parseColor("#94A3B8"))
                Toast.makeText(this@MainActivity, "麻将分析服务及悬浮窗已全部关闭", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(btnStop)

        root.addView(container)
        setContentView(root)
    }

    private fun checkOverlayPermissionOnly(): Boolean {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        if (!hasOverlay) {
            showOverlayPermissionDialog()
            return false
        }
        return true
    }

    private fun showOverlayPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ 需要开启【悬浮窗】权限")
            .setMessage("麻将智囊助手需要将【推荐打牌】和【9x3记牌器】悬浮在游戏上方展示。\n\n点击【立即去开启】将跳转至系统设置：\n1. 请找到并勾选【允许在其他应用上层显示】；\n2. （小米/华为/OPPO/vivo 手机）：请在权限中额外允许【后台弹出界面】。\n\n开启后直接返回本应用即可看到悬浮窗！")
            .setPositiveButton("🚀 立即去开启") { _, _ ->
                requestOverlayPermission()
            }
            .setNeutralButton("📱 打开应用详情") { _, _ ->
                openAppDetailsSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, "正在打开系统设置，请允许【在其他应用上层显示】", Toast.LENGTH_LONG).show()
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                openAppDetailsSettings()
            }
        }
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "请前往手机【设置-应用管理】手动开启权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startFullPipeline() {
        if (!checkOverlayPermissionOnly()) return

        // 1. 立即启动并呈现悬浮窗 (用户立即看到直观反馈)
        startOverlayService()

        // 2. 申请屏幕抓屏授权
        try {
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "申请屏幕录制授权失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        startService(intent)
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

    private fun openCalibratorDirectly() {
        val rootLayout = findViewById<FrameLayout>(android.R.id.content)
        var calibrator: RegionCalibratorView? = null
        calibrator = RegionCalibratorView(this, null) {
            calibrator?.let { rootLayout.removeView(it) }
        }
        rootLayout.addView(calibrator, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }
}

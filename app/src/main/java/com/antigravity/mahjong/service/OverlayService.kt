package com.antigravity.mahjong.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.antigravity.mahjong.solver.SichuanSolver

/**
 * 悬浮交互服务 (Smart Overlay Service)
 * 1. 胶囊态 (默认): 极简展示推荐出牌与存活叫口，不遮挡游戏
 * 2. 展开态: 点击后展示听牌明细与一键切换定缺
 * 3. 触摸拖拽防误触
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatView: LinearLayout
    private lateinit var tvRecommendation: TextView
    private lateinit var tvDingqueBadge: TextView
    private lateinit var tvDetails: TextView

    private var currentDingque = "条"
    private val dingqueOptions = listOf("条", "筒", "万")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initOverlayViews()
        com.antigravity.mahjong.coordinator.MahjongCoordinator.registerOverlay(this)
    }

    private fun initOverlayViews() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 50
        }

        // 胶囊外壳容器
        floatView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE1E293B")) // 半透明极简深蓝灰
                cornerRadius = 28f
                setStroke(2, Color.parseColor("#38BDF8")) // 科技蓝描边
            }
        }

        // 顶部胶囊行 (定缺徽章 + 推荐打牌 + 展开/折叠 + 设置)
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 定缺快捷切换按钮
        tvDingqueBadge = TextView(this).apply {
            text = "缺$currentDingque"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(16, 8, 16, 8)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981")) // 绿色代表条
                cornerRadius = 16f
            }
            setOnClickListener {
                val nextIdx = (dingqueOptions.indexOf(currentDingque) + 1) % dingqueOptions.size
                currentDingque = dingqueOptions[nextIdx]
                text = "缺$currentDingque"
                (background as GradientDrawable).setColor(
                    when (currentDingque) {
                        "条" -> Color.parseColor("#10B981")
                        "筒" -> Color.parseColor("#F59E0B")
                        else -> Color.parseColor("#EF4444")
                    }
                )
                com.antigravity.mahjong.coordinator.MahjongCoordinator.onUserToggleDingque(currentDingque)
            }
        }
        topRow.addView(tvDingqueBadge)

        // 核心推荐文字
        tvRecommendation = TextView(this).apply {
            text = "  推荐出牌: 分析中..."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(12, 0, 12, 0)
        }
        topRow.addView(tvRecommendation)

        // 展开/收起面板按钮
        val btnExpand = TextView(this).apply {
            text = "展开"
            setTextColor(Color.parseColor("#FCD34D"))
            textSize = 12f
            setPadding(12, 6, 12, 6)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#334155"))
                cornerRadius = 12f
                setStroke(1, Color.parseColor("#FCD34D"))
            }
            setOnClickListener {
                if (expandPanel.visibility == View.VISIBLE) {
                    expandPanel.visibility = View.GONE
                    text = "展开"
                } else {
                    expandPanel.visibility = View.VISIBLE
                    text = "收起"
                }
            }
        }
        topRow.addView(btnExpand)

        // 设置区域按钮 (方案 Section 5 核心入口)
        val btnSetting = TextView(this).apply {
            text = " ⚙️设置"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            setPadding(12, 6, 12, 6)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = 12f
                setStroke(1, Color.parseColor("#38BDF8"))
            }
            setOnClickListener {
                openRegionCalibrator()
            }
        }
        topRow.addView(btnSetting)
        floatView.addView(topRow)

        // 胶囊态简要叫口/进张展示
        tvDetails = TextView(this).apply {
            text = "叫口详情: 等待手牌稳定"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            setPadding(0, 10, 0, 0)
        }
        floatView.addView(tvDetails)

        // ==================== 全展开 9x3 记牌器与决策面板 ====================
        initExpandPanel()
        floatView.addView(expandPanel)

        // 支持手势自由拖动浮窗位置
        setupTouchDrag(floatView, params)

        windowManager.addView(floatView, params)
    }

    private lateinit var expandPanel: LinearLayout
    private val matrixCells = Array(27) { TextView(this) }
    private lateinit var tvFullAnalysis: TextView

    private fun initExpandPanel() {
        expandPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 14, 0, 0)
        }

        // 分割线
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 6, 0, 10)
            }
        }
        expandPanel.addView(divider)

        tvFullAnalysis = TextView(this).apply {
            text = "决策深度分析: 实时监控全场 108 张牌"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            setPadding(0, 0, 0, 8)
        }
        expandPanel.addView(tvFullAnalysis)

        // 9x3 矩阵说明标题
        val tvMatrixTitle = TextView(this).apply {
            text = "【全场 108 牌动态余量记牌器】 (红:绝张 橙:1~2张 绿:3~4张)"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11f
            setPadding(0, 4, 0, 6)
        }
        expandPanel.addView(tvMatrixTitle)

        // 9x3 表格容器
        val tableContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                cornerRadius = 16f
                setStroke(1, Color.parseColor("#1E293B"))
            }
            setPadding(10, 8, 10, 8)
        }

        val suits = listOf("条", "筒", "万") // 0..8条, 9..17筒, 18..26万
        for (suitIdx in 0..2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 3, 0, 3)
            }

            // 花色标签
            val suitLabel = TextView(this).apply {
                text = suits[suitIdx]
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                setPadding(6, 4, 10, 4)
            }
            row.addView(suitLabel)

            // 1~9 列
            for (num in 1..9) {
                val tileId = suitIdx * 9 + (num - 1)
                val cell = TextView(this).apply {
                    text = "$num:4"
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setPadding(8, 4, 8, 4)
                    setTextColor(Color.parseColor("#10B981"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1E293B"))
                        cornerRadius = 8f
                    }
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(2, 0, 2, 0)
                    }
                    layoutParams = lp
                }
                matrixCells[tileId] = cell
                row.addView(cell)
            }
            tableContainer.addView(row)
        }
        expandPanel.addView(tableContainer)
    }

    /**
     * 外部更新决策结果与记牌矩阵接口
     */
    fun updateResult(result: SichuanSolver.AnalysisResult, remainingCounts: IntArray? = null) {
        tvRecommendation.text = "  打【${result.recommendedDiscardName}】"
        if (result.bestWaits.isNotEmpty()) {
            tvDetails.visibility = View.VISIBLE
            tvDetails.text = "听: ${result.bestWaits.joinToString()} (共${result.totalRealWins}张存活)"
            tvFullAnalysis.text = "【听牌叫口】可胡: ${result.bestWaits.joinToString()} | 牌池真实存活: ${result.totalRealWins}张"
        } else {
            tvDetails.text = result.message
            tvFullAnalysis.text = "【打牌建议】打出【${result.recommendedDiscardName}】: ${result.message}"
        }

        // 刷新 9x3 剩余牌矩阵仪表盘
        if (remainingCounts != null && remainingCounts.size == 27) {
            for (i in 0..26) {
                val rem = remainingCounts[i]
                val num = (i % 9) + 1
                val cell = matrixCells[i]
                cell.text = "$num:$rem"
                when (rem) {
                    0 -> { // 绝张 (标红警示)
                        cell.setTextColor(Color.parseColor("#EF4444"))
                        (cell.background as? GradientDrawable)?.setColor(Color.parseColor("#450A0A"))
                    }
                    1, 2 -> { // 紧缺 (橙黄)
                        cell.setTextColor(Color.parseColor("#F59E0B"))
                        (cell.background as? GradientDrawable)?.setColor(Color.parseColor("#451A03"))
                    }
                    else -> { // 充裕 (青绿)
                        cell.setTextColor(Color.parseColor("#10B981"))
                        (cell.background as? GradientDrawable)?.setColor(Color.parseColor("#064E3B"))
                    }
                }
            }
        }
    }

    /**
     * 打开全屏半透明区域校准器 (Region Calibrator)
     */
    private fun openRegionCalibrator() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val fullParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        var calibratorView: com.antigravity.mahjong.ui.RegionCalibratorView? = null
        calibratorView = com.antigravity.mahjong.ui.RegionCalibratorView(this, null) {
            calibratorView?.let { windowManager.removeView(it) }
        }

        windowManager.addView(calibratorView, fullParams)
    }

    private fun setupTouchDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        com.antigravity.mahjong.coordinator.MahjongCoordinator.unregisterOverlay()
        if (::floatView.isInitialized) {
            windowManager.removeView(floatView)
        }
        super.onDestroy()
    }
}

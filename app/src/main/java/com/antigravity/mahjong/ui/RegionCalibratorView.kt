package com.antigravity.mahjong.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.antigravity.mahjong.profile.GameProfile
import com.antigravity.mahjong.profile.RectRatio
import com.antigravity.mahjong.profile.RegionManager
import kotlin.math.max
import kotlin.math.min

/**
 * 游戏内全屏半透明框选配置层 (RegionCalibratorView)
 * 严格实现方案 Section 5:
 * 5.1 点击“设置区域”进入
 * 5.2 冻结当前屏幕，显示半透明层
 * 5.3 用户分别框选手牌、牌河与碰杠区
 * 5.4 保存至 GameProfile 并即时生效
 */
class RegionCalibratorView(
    context: Context,
    private val frozenBitmap: Bitmap?,
    private val onFinishCallback: () -> Unit
) : FrameLayout(context) {

    enum class EditMode {
        HAND,       // 框选手牌
        DISCARD,    // 框选牌河
        MELD        // 框选碰杠
    }

    private var currentMode = EditMode.HAND
    private val regionManager = RegionManager.getInstance(context)
    private val editingProfile = regionManager.currentProfile.copy()

    // 绘制画笔
    private val paintBox = Paint().apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val paintFill = Paint().apply {
        color = Color.parseColor("#3338BDF8")
        style = Paint.Style.FILL
    }

    private val paintHandle = Paint().apply {
        color = Color.parseColor("#0284C7")
        style = Paint.Style.FILL
    }

    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
    }

    // 触摸拖拽控制变量
    private var activeHandle = -1 // -1: none, 0: move, 1: LT, 2: RT, 3: RB, 4: LB
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val canvasView = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 绘制半透明冻结背景
            frozenBitmap?.let {
                canvas.drawBitmap(it, 0f, 0f, null)
            }
            canvas.drawColor(Color.parseColor("#66000000")) // 半透明遮罩

            val w = width.toFloat()
            val h = height.toFloat()

            // 绘制当前编辑区域
            val activeRect = when (currentMode) {
                EditMode.HAND -> editingProfile.handRegion
                EditMode.DISCARD -> editingProfile.discardRegion
                EditMode.MELD -> editingProfile.meldRegion
            }

            val left = activeRect.xRatio * w
            val top = activeRect.yRatio * h
            val right = (activeRect.xRatio + activeRect.wRatio) * w
            val bottom = (activeRect.yRatio + activeRect.hRatio) * h

            // 切换画笔颜色以示区分
            val themeColor = when (currentMode) {
                EditMode.HAND -> Color.parseColor("#10B981") // 绿
                EditMode.DISCARD -> Color.parseColor("#38BDF8") // 蓝
                EditMode.MELD -> Color.parseColor("#F59E0B") // 橙
            }
            paintBox.color = themeColor
            paintHandle.color = themeColor
            paintFill.color = Color.argb(40, Color.red(themeColor), Color.green(themeColor), Color.blue(themeColor))

            // 画主矩形选框
            canvas.drawRect(left, top, right, bottom, paintFill)
            canvas.drawRect(left, top, right, bottom, paintBox)

            // 画 4 个拖动圆柄手柄
            val handleRadius = 24f
            canvas.drawCircle(left, top, handleRadius, paintHandle)
            canvas.drawCircle(right, top, handleRadius, paintHandle)
            canvas.drawCircle(right, bottom, handleRadius, paintHandle)
            canvas.drawCircle(left, bottom, handleRadius, paintHandle)

            // 绘制提示标签
            val modeName = when (currentMode) {
                EditMode.HAND -> "【当前框选：手牌区域】"
                EditMode.DISCARD -> "【当前框选：牌河区域】"
                EditMode.MELD -> "【当前框选：碰杠区域】"
            }
            canvas.drawText("$modeName (拖动四角微调，中心平移)", left + 10, max(top - 15, 40f), paintText)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            val w = width.toFloat()
            val h = height.toFloat()

            val rect = when (currentMode) {
                EditMode.HAND -> editingProfile.handRegion
                EditMode.DISCARD -> editingProfile.discardRegion
                EditMode.MELD -> editingProfile.meldRegion
            }

            val left = rect.xRatio * w
            val top = rect.yRatio * h
            val right = (rect.xRatio + rect.wRatio) * w
            val bottom = (rect.yRatio + rect.hRatio) * h

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = x
                    lastTouchY = y
                    val r = 40f
                    activeHandle = when {
                        Math.hypot((x - left).toDouble(), (y - top).toDouble()) < r -> 1
                        Math.hypot((x - right).toDouble(), (y - top).toDouble()) < r -> 2
                        Math.hypot((x - right).toDouble(), (y - bottom).toDouble()) < r -> 3
                        Math.hypot((x - left).toDouble(), (y - bottom).toDouble()) < r -> 4
                        x in left..right && y in top..bottom -> 0 // 整体拖拽
                        else -> -1
                    }
                    return activeHandle != -1
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeHandle == -1) return false
                    val dx = (x - lastTouchX) / w
                    val dy = (y - lastTouchY) / h

                    when (activeHandle) {
                        0 -> { // 平移
                            rect.xRatio = (rect.xRatio + dx).coerceIn(0f, 1f - rect.wRatio)
                            rect.yRatio = (rect.yRatio + dy).coerceIn(0f, 1f - rect.hRatio)
                        }
                        1 -> { // 左上角
                            val newL = (rect.xRatio + dx).coerceIn(0f, rect.xRatio + rect.wRatio - 0.05f)
                            val newT = (rect.yRatio + dy).coerceIn(0f, rect.yRatio + rect.hRatio - 0.05f)
                            rect.wRatio += (rect.xRatio - newL)
                            rect.hRatio += (rect.yRatio - newT)
                            rect.xRatio = newL
                            rect.yRatio = newT
                        }
                        3 -> { // 右下角
                            rect.wRatio = (rect.wRatio + dx).coerceIn(0.05f, 1f - rect.xRatio)
                            rect.hRatio = (rect.hRatio + dy).coerceIn(0.05f, 1f - rect.yRatio)
                        }
                    }
                    lastTouchX = x
                    lastTouchY = y
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeHandle = -1
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    init {
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setupControls()
    }

    private fun setupControls() {
        // 顶部模式切换 Bar
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(30, 20, 30, 20)
            setBackgroundColor(Color.parseColor("#CC0F172A"))
        }

        fun createTab(name: String, mode: EditMode): TextView {
            return TextView(context).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(24, 12, 24, 12)
                setOnClickListener {
                    currentMode = mode
                    canvasView.invalidate()
                }
            }
        }

        topBar.addView(createTab("手牌区", EditMode.HAND))
        topBar.addView(createTab("牌河区", EditMode.DISCARD))
        topBar.addView(createTab("碰杠区", EditMode.MELD))

        // 右侧保存与取消按钮
        val btnSave = TextView(context).apply {
            text = "保存配置"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#10B981"))
            setOnClickListener {
                regionManager.saveProfile(editingProfile)
                onFinishCallback()
            }
        }

        val btnCancel = TextView(context).apply {
            text = "退出"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#EF4444"))
            setOnClickListener {
                onFinishCallback()
            }
        }

        topBar.addView(btnSave)
        topBar.addView(btnCancel)

        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}

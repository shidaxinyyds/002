package com.antigravity.mahjong.coordinator

import android.graphics.Bitmap
import android.util.Log
import com.antigravity.mahjong.service.OverlayService
import com.antigravity.mahjong.solver.SichuanSolver
import com.antigravity.mahjong.state.GameStateTracker
import com.antigravity.mahjong.vision.TileDetector
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * 全局中央调度总控器 (MahjongCoordinator)
 * 职责：
 * 1. 挂载 FrameGovernor 动静帧调度 (CPU 节能)
 * 2. 调度 TileDetector 提取定缺徽章与手牌
 * 3. 驱动 GameStateTracker 更新牌局状态
 * 4. 实时刷新 OverlayService 悬浮交互层
 */
object MahjongCoordinator {

    private const val TAG = "MahjongCoordinator"

    val gameStateTracker = GameStateTracker()

    private var overlayServiceRef: WeakReference<OverlayService>? = null
    private val coordinatorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // FrameGovernor 调度状态
    private var lastHash: Long = 0L
    private var isComputing = false

    fun registerOverlay(service: OverlayService) {
        overlayServiceRef = WeakReference(service)
    }

    fun unregisterOverlay() {
        overlayServiceRef = null
    }

    /**
     * 接收来自 ScreenCaptureService 的屏幕图像帧 (事件驱动)
     */
    fun onNewScreenFrame(context: android.content.Context, screenBitmap: Bitmap) {
        if (isComputing) return

        coordinatorScope.launch {
            try {
                // 1. 低功耗采样哈希检测 (FrameGovernor)
                val currentHash = computeHandRegionHash(screenBitmap)
                if (abs(currentHash - lastHash) < 50) {
                    // 画面静止，无需重复计算
                    return@launch
                }

                isComputing = true
                lastHash = currentHash

                // 等待 200ms 去抖动，确保出牌飞行/摸牌动画定格
                delay(200)

                // 2. 读取动态配置的 RegionManager 选框 (手牌、牌河、碰杠)
                val profile = com.antigravity.mahjong.profile.RegionManager.getInstance(context).currentProfile
                val handRoi = android.graphics.RectF(
                    profile.handRegion.xRatio,
                    profile.handRegion.yRatio,
                    profile.handRegion.xRatio + profile.handRegion.wRatio,
                    profile.handRegion.yRatio + profile.handRegion.hRatio
                )
                val discardRoi = android.graphics.RectF(
                    profile.discardRegion.xRatio,
                    profile.discardRegion.yRatio,
                    profile.discardRegion.xRatio + profile.discardRegion.wRatio,
                    profile.discardRegion.yRatio + profile.discardRegion.hRatio
                )
                val meldRoi = android.graphics.RectF(
                    profile.meldRegion.xRatio,
                    profile.meldRegion.yRatio,
                    profile.meldRegion.xRatio + profile.meldRegion.wRatio,
                    profile.meldRegion.yRatio + profile.meldRegion.hRatio
                )

                // 3. 视觉特征提取与真实分类 (手牌、定缺徽章、碰杠区、牌河区)
                val handResult = TileDetector.processHandRegion(context, screenBitmap, handRoi)
                val meldGroups = TileDetector.processMeldRegion(context, screenBitmap, meldRoi)
                val discardTiles = TileDetector.processDiscardRegion(context, screenBitmap, discardRoi)

                // 同步碰杠与牌河公开牌至牌池单调记牌器
                for (group in meldGroups) {
                    gameStateTracker.addMeld(if (group.size == 4) "gang" else "peng", group)
                }
                for (dt in discardTiles) {
                    gameStateTracker.tileMemory.recordVisibleTile(dt)
                }

                // 自动更新定缺 (若徽章识别成功)
                handResult.detectedDingque?.let { detectedQue ->
                    gameStateTracker.updateDingque(detectedQue)
                }

                // 4. 推进状态机与求解
                if (handResult.tiles.isNotEmpty()) {
                    val analysis = gameStateTracker.onHandUpdated(
                        handTiles = handResult.tiles,
                        isDrawing = handResult.isDrawingTile
                    )
                    val remainingCounts = gameStateTracker.tileMemory.getRemainingCounts()

                    // 5. 主线程刷新悬浮窗
                    withContext(Dispatchers.Main) {
                        overlayServiceRef?.get()?.updateResult(analysis, remainingCounts)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Coordinator processing failure", e)
            } finally {
                isComputing = false
            }
        }
    }

    /**
     * 针对手牌 ROI 进行 16x8 微型灰度降采样哈希
     */
    private fun computeHandRegionHash(bitmap: Bitmap): Long {
        val w = bitmap.width
        val h = bitmap.height
        val startY = (h * 0.82f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.98f).toInt().coerceIn(0, h - 1)
        val stepX = kotlin.math.max(1, w / 16)
        val stepY = kotlin.math.max(1, (endY - startY) / 8)

        var hash = 0L
        for (x in 0 until w step stepX) {
            for (y in startY until endY step stepY) {
                val pixel = bitmap.getPixel(x, y)
                val lum = (pixel shr 16 and 0xFF) + (pixel shr 8 and 0xFF) + (pixel and 0xFF)
                hash = (hash * 31) + lum
            }
        }
        return hash
    }

    /**
     * 用户手动在浮窗切换定缺门
     */
    fun onUserToggleDingque(newDingque: String) {
        gameStateTracker.updateDingque(newDingque)
        val reAnalysis = gameStateTracker.reAnalyze()
        if (reAnalysis != null) {
            val remainingCounts = gameStateTracker.tileMemory.getRemainingCounts()
            coordinatorScope.launch(Dispatchers.Main) {
                overlayServiceRef?.get()?.updateResult(reAnalysis, remainingCounts)
            }
        }
    }
}

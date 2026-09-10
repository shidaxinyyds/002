package com.antigravity.mahjong.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import com.antigravity.mahjong.solver.SichuanSolver
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 视觉检测与真机分类引擎 (TileDetector)
 * 具备：
 * 1. 自动从 APK assets 加载 28 张原生麻将模板
 * 2. 毫秒级归一化互相关 (NCC) 图像分类器
 * 3. 动态 ROI 投影切分与第 14 张摸牌空隙侦测
 * 4. 左下角头像定缺徽章 HSV 颜色自适应提取
 */
object TileDetector {

    private const val TAG = "TileDetector"

    // 模板归一化缩放尺寸
    private const val TMPL_W = 28
    private const val TMPL_H = 46

    // 模板特征缓存: label -> 浮点归一化灰度数组
    private val templateBank = mutableMapOf<String, FloatArray>()
    private val templateNorms = mutableMapOf<String, Float>()
    private val labelToTileId = mutableMapOf<String, Int>()

    @Volatile
    private var isInitialized = false

    data class DetectedHandResult(
        val tiles: List<Int>,             // 真实识别出的手牌编码列表 (0..26)
        val isDrawingTile: Boolean,       // 是否处于摸牌状态 (第14张牌是否分离)
        val drawnTile: Int? = null,       // 刚摸到的新牌编码
        val detectedDingque: String? = null, // 从头像徽章自动检测出的定缺 ("条", "筒", "万")
        val isValid: Boolean = true       // 张数是否合法 (3n+1 或 3n+2)
    )

    /**
     * 自动从 assets/templates 加载全部原生模板
     */
    @Synchronized
    fun initTemplates(context: Context) {
        if (isInitialized) return
        try {
            val assetManager = context.assets
            val files = assetManager.list("templates") ?: emptyArray()

            for (file in files) {
                if (file.endsWith(".png")) {
                    assetManager.open("templates/$file").use { inputStream ->
                        val origBitmap = BitmapFactory.decodeStream(inputStream)
                        val label = file.replace(".png", "")

                        // 提取中心图案部分并缩放至 TMPL_W x TMPL_H
                        val th = origBitmap.height
                        val tw = origBitmap.width
                        val centerCrop = Bitmap.createBitmap(
                            origBitmap,
                            (tw * 0.12f).toInt(),
                            (th * 0.12f).toInt(),
                            (tw * 0.76f).toInt(),
                            (th * 0.76f).toInt()
                        )
                        val scaled = Bitmap.createScaledBitmap(centerCrop, TMPL_W, TMPL_H, true)

                        // 计算零均值化灰度向量
                        val grayVector = FloatArray(TMPL_W * TMPL_H)
                        var sum = 0f
                        for (y in 0 until TMPL_H) {
                            for (x in 0 until TMPL_W) {
                                val p = scaled.getPixel(x, y)
                                val lum = (Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f)
                                grayVector[y * TMPL_W + x] = lum
                                sum += lum
                            }
                        }
                        val mean = sum / (TMPL_W * TMPL_H)
                        var normSq = 0f
                        for (i in grayVector.indices) {
                            grayVector[i] -= mean
                            normSq += grayVector[i] * grayVector[i]
                        }

                        templateBank[label] = grayVector
                        templateNorms[label] = sqrt(normSq.toDouble()).toFloat()

                        // 映射字符串至编码
                        mapLabelToId(label)?.let { tileId ->
                            labelToTileId[label] = tileId
                        }
                    }
                }
            }
            isInitialized = true
            Log.i(TAG, "Tile templates initialized successfully, count = ${templateBank.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load templates from assets", e)
        }
    }

    private fun mapLabelToId(label: String): Int? {
        if (label == "hz") return 27 // 红中特殊标识
        val numStr = label.filter { it.isDigit() }
        val suitStr = label.filter { !it.isDigit() }
        val num = numStr.toIntOrNull() ?: return null
        return when (suitStr) {
            "ti", "s" -> num - 1
            "to", "p" -> 8 + num
            "w", "m" -> 17 + num
            else -> null
        }
    }

    /**
     * 单张裁剪手牌的高性能 NCC 分类 (耗时 < 0.2ms)
     */
    fun classifyTile(tileBitmap: Bitmap): Pair<Int, Float> {
        val th = tileBitmap.height
        val tw = tileBitmap.width
        val centerCrop = Bitmap.createBitmap(
            tileBitmap,
            (tw * 0.12f).toInt(),
            (th * 0.12f).toInt(),
            (tw * 0.76f).toInt(),
            (th * 0.76f).toInt()
        )
        val scaled = Bitmap.createScaledBitmap(centerCrop, TMPL_W, TMPL_H, true)

        val targetVector = FloatArray(TMPL_W * TMPL_H)
        var sum = 0f
        for (y in 0 until TMPL_H) {
            for (x in 0 until TMPL_W) {
                val p = scaled.getPixel(x, y)
                val lum = (Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f)
                targetVector[y * TMPL_W + x] = lum
                sum += lum
            }
        }
        val mean = sum / (TMPL_W * TMPL_H)
        var targetNormSq = 0f
        for (i in targetVector.indices) {
            targetVector[i] -= mean
            targetNormSq += targetVector[i] * targetVector[i]
        }
        val targetNorm = sqrt(targetNormSq.toDouble()).toFloat()
        if (targetNorm == 0f) return Pair(-1, 0f)

        var bestLabel: String? = null
        var bestScore = -1f

        for ((label, tmplVec) in templateBank) {
            val tmplNorm = templateNorms[label] ?: 1f
            var dot = 0f
            for (i in 0 until (TMPL_W * TMPL_H)) {
                dot += targetVector[i] * tmplVec[i]
            }
            val score = dot / (targetNorm * tmplNorm)
            if (score > bestScore) {
                bestScore = score
                bestLabel = label
            }
        }

        val tileId = bestLabel?.let { labelToTileId[it] } ?: -1
        return Pair(tileId, bestScore)
    }

    /**
     * 从左下角头像区域自动识别定缺徽章颜色
     */
    fun detectDingqueFromBadge(screenBitmap: Bitmap): String? {
        val w = screenBitmap.width
        val h = screenBitmap.height

        val startX = (w * 0.082f).toInt().coerceIn(0, w - 1)
        val endX = (w * 0.125f).toInt().coerceIn(0, w - 1)
        val startY = (h * 0.590f).toInt().coerceIn(0, h - 1)
        val endY = (h * 0.675f).toInt().coerceIn(0, h - 1)

        var greenCount = 0
        var redCount = 0
        var yellowOrangeCount = 0
        var totalSamples = 0

        val hsv = FloatArray(3)
        for (x in startX..endX step 2) {
            for (y in startY..endY step 2) {
                val pixel = screenBitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val valB = hsv[2]

                if (sat > 0.40f && valB > 0.40f) {
                    totalSamples++
                    when {
                        hue in 75f..165f -> greenCount++
                        hue in 0f..20f || hue in 340f..360f -> redCount++
                        hue in 25f..65f -> yellowOrangeCount++
                    }
                }
            }
        }

        if (totalSamples < 15) return null

        val maxCount = max(greenCount, max(redCount, yellowOrangeCount))
        return when (maxCount) {
            greenCount if greenCount > totalSamples * 0.35f -> "条"
            redCount if redCount > totalSamples * 0.35f -> "万"
            yellowOrangeCount if yellowOrangeCount > totalSamples * 0.35f -> "筒"
            else -> null
        }
    }

    /**
     * 切割与定位手牌区域，输出真实识别出的手牌列表
     */
    fun processHandRegion(
        context: Context,
        screenBitmap: Bitmap,
        handRoiRatio: RectF = RectF(0.045f, 0.815f, 0.955f, 0.990f)
    ): DetectedHandResult {
        if (!isInitialized) {
            initTemplates(context)
        }

        val w = screenBitmap.width
        val h = screenBitmap.height

        val cropX = (w * handRoiRatio.left).toInt().coerceIn(0, w - 1)
        val cropY = (h * handRoiRatio.top).toInt().coerceIn(0, h - 1)
        val cropW = (w * handRoiRatio.width()).toInt().coerceAtMost(w - cropX)
        val cropH = (h * handRoiRatio.height()).toInt().coerceAtMost(h - cropY)

        if (cropW <= 0 || cropH <= 0) {
            return DetectedHandResult(emptyList(), false, null, null, false)
        }

        // 1. 自动检测定缺
        val autoDingque = detectDingqueFromBadge(screenBitmap)

        // 2. 切下手牌条
        val handBitmap = Bitmap.createBitmap(screenBitmap, cropX, cropY, cropW, cropH)
        val tileBoxes = locateTileBoundaries(handBitmap)

        // 3. 对每一个框执行实际的分类匹配
        val recognizedTiles = mutableListOf<Int>()
        for ((bx1, bx2) in tileBoxes) {
            val tileW = bx2 - bx1
            if (tileW > 5) {
                val tileCrop = Bitmap.createBitmap(handBitmap, bx1, 0, tileW, handBitmap.height)
                val (tileId, conf) = classifyTile(tileCrop)
                if (tileId != -1 && conf > 0.40f) {
                    recognizedTiles.add(tileId)
                }
            }
        }

        // 4. 摸牌判定
        val isDrawing = checkIsDrawing(tileBoxes)
        val count = recognizedTiles.size
        val isValidCount = (count % 3 == 1 || count % 3 == 2)

        return DetectedHandResult(
            tiles = recognizedTiles,
            isDrawingTile = isDrawing,
            drawnTile = if (isDrawing && recognizedTiles.isNotEmpty()) recognizedTiles.last() else null,
            detectedDingque = autoDingque,
            isValid = isValidCount
        )
    }

    private fun locateTileBoundaries(handBitmap: Bitmap): List<Pair<Int, Int>> {
        val w = handBitmap.width
        val h = handBitmap.height

        // 采用腾讯麻将高宽比先验 1.65
        val estTileW = (h / 1.65f)

        // 查找起止白边
        val midYStart = (h * 0.30f).toInt()
        val midYEnd = (h * 0.70f).toInt()
        val sampleH = midYEnd - midYStart

        var startX = -1
        var endX = -1

        for (x in 0 until w) {
            var whiteCount = 0
            for (y in midYStart until midYEnd) {
                val p = handBitmap.getPixel(x, y)
                val lum = (Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f)
                if (lum > 130) whiteCount++
            }
            if (whiteCount > sampleH * 0.45f) {
                if (startX == -1) startX = x
                endX = x
            }
        }

        if (startX == -1 || endX == -1 || (endX - startX) < estTileW * 0.5f) {
            return emptyList()
        }

        val activeW = (endX - startX).toFloat()
        val numTiles = max(1, Math.round(activeW / estTileW).toInt())
        val step = activeW / numTiles

        val boxes = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until numTiles) {
            val bx1 = (startX + i * step).toInt().coerceIn(0, w - 1)
            val bx2 = (startX + (i + 1) * step).toInt().coerceIn(0, w)
            boxes.add(Pair(bx1, bx2))
        }
        return boxes
    }

    private fun checkIsDrawing(tileBoxes: List<Pair<Int, Int>>): Boolean {
        if (tileBoxes.size < 2) return false
        val lastBox = tileBoxes.last()
        val prevBox = tileBoxes[tileBoxes.size - 2]
        val normalStep = (prevBox.second - prevBox.first)
        val gap = (lastBox.first - prevBox.second)
        return gap > normalStep * 0.35f
    }

    fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * 多角度自适应 NCC 分类 (支持 0度、180度 以及 左右侧视角的 90度/270度)
     */
    fun classifyTileMultiAngle(tileBitmap: Bitmap): Pair<Int, Float> {
        var (bestId, bestScore) = classifyTile(tileBitmap)
        if (bestScore > 0.85f) return Pair(bestId, bestScore)

        // 尝试 180 度翻转 (针对对家或自身桌面上倒置的弃牌)
        val rot180 = rotateBitmap(tileBitmap, 180f)
        val (id180, score180) = classifyTile(rot180)
        if (score180 > bestScore) {
            bestScore = score180
            bestId = id180
        }
        if (bestScore > 0.85f) return Pair(bestId, bestScore)

        // 若牌面长宽偏横向或置信度仍偏低，尝试 90/270 度 (针对左右两家横向弃牌)
        if (tileBitmap.width > tileBitmap.height * 0.9f || bestScore < 0.45f) {
            val rot90 = rotateBitmap(tileBitmap, 90f)
            val (id90, score90) = classifyTile(rot90)
            if (score90 > bestScore) {
                bestScore = score90
                bestId = id90
            }

            val rot270 = rotateBitmap(tileBitmap, 270f)
            val (id270, score270) = classifyTile(rot270)
            if (score270 > bestScore) {
                bestScore = score270
                bestId = id270
            }
        }

        return Pair(bestId, bestScore)
    }

    /**
     * 牌河区域检测 (Discard River)
     * 在牌桌中央/牌河框选范围内提取各家打出的牌
     */
    fun processDiscardRegion(
        context: Context,
        screenBitmap: Bitmap,
        discardRoiRatio: RectF = RectF(0.300f, 0.280f, 0.700f, 0.680f)
    ): List<Int> {
        if (!isInitialized) {
            initTemplates(context)
        }

        val w = screenBitmap.width
        val h = screenBitmap.height

        val cropX = (w * discardRoiRatio.left).toInt().coerceIn(0, w - 1)
        val cropY = (h * discardRoiRatio.top).toInt().coerceIn(0, h - 1)
        val cropW = (w * discardRoiRatio.width()).toInt().coerceAtMost(w - cropX)
        val cropH = (h * discardRoiRatio.height()).toInt().coerceAtMost(h - cropY)

        if (cropW <= 20 || cropH <= 20) return emptyList()

        val riverBitmap = Bitmap.createBitmap(screenBitmap, cropX, cropY, cropW, cropH)
        val rw = riverBitmap.width
        val rh = riverBitmap.height

        // 识别白色麻将块 (排除绿色牌布与中心骰子盘)
        val isTilePixel = Array(rw) { BooleanArray(rh) }
        val hsv = FloatArray(3)
        for (x in 0 until rw step 2) {
            for (y in 0 until rh step 2) {
                val p = riverBitmap.getPixel(x, y)
                Color.colorToHSV(p, hsv)
                val sat = hsv[1]
                val v = hsv[2]
                if (sat < 0.35f && v > 0.45f) {
                    isTilePixel[x][y] = true
                    if (x + 1 < rw) isTilePixel[x + 1][y] = true
                    if (y + 1 < rh) isTilePixel[x][y + 1] = true
                    if (x + 1 < rw && y + 1 < rh) isTilePixel[x + 1][y + 1] = true
                }
            }
        }

        val visited = Array(rw) { BooleanArray(rh) }
        val detectedBoxes = mutableListOf<android.graphics.Rect>()

        val minTileDim = (rh * 0.05f).toInt().coerceAtLeast(15)
        val maxTileDim = (rh * 0.50f).toInt().coerceAtLeast(minTileDim * 2)

        for (x in 0 until rw step 4) {
            for (y in 0 until rh step 4) {
                if (isTilePixel[x][y] && !visited[x][y]) {
                    var minX = x
                    var maxX = x
                    var minY = y
                    var maxY = y
                    var count = 0

                    val queue = ArrayDeque<Pair<Int, Int>>()
                    queue.add(Pair(x, y))
                    visited[x][y] = true

                    while (queue.isNotEmpty() && count < 2000) {
                        val (cx, cy) = queue.removeFirst()
                        count++
                        if (cx < minX) minX = cx
                        if (cx > maxX) maxX = cx
                        if (cy < minY) minY = cy
                        if (cy > maxY) maxY = cy

                        val dirs = arrayOf(Pair(4, 0), Pair(-4, 0), Pair(0, 4), Pair(0, -4))
                        for ((dx, dy) in dirs) {
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx in 0 until rw && ny in 0 until rh && isTilePixel[nx][ny] && !visited[nx][ny]) {
                                visited[nx][ny] = true
                                queue.add(Pair(nx, ny))
                            }
                        }
                    }

                    val bw = maxX - minX + 1
                    val bh = maxY - minY + 1
                    if (bw in minTileDim..maxTileDim && bh in minTileDim..maxTileDim && count > 40) {
                        detectedBoxes.add(android.graphics.Rect(minX, minY, maxX, maxY))
                    }
                }
            }
        }

        val riverTiles = mutableListOf<Int>()
        for (box in detectedBoxes) {
            val bw = box.width()
            val bh = box.height()
            if (bw > 10 && bh > 10) {
                val estDim = minTileDim * 1.5f
                if (bw > estDim * 1.6f && bh < estDim * 1.4f) {
                    val numCols = max(2, (bw / estDim).toInt())
                    val colW = bw / numCols
                    for (c in 0 until numCols) {
                        val subX = box.left + c * colW
                        val subCrop = Bitmap.createBitmap(riverBitmap, subX, box.top, colW, bh)
                        val (tileId, conf) = classifyTileMultiAngle(subCrop)
                        if (tileId != -1 && conf > 0.38f) {
                            riverTiles.add(tileId)
                        }
                    }
                } else if (bh > estDim * 1.6f && bw < estDim * 1.4f) {
                    val numRows = max(2, (bh / estDim).toInt())
                    val rowH = bh / numRows
                    for (r in 0 until numRows) {
                        val subY = box.top + r * rowH
                        val subCrop = Bitmap.createBitmap(riverBitmap, box.left, subY, bw, rowH)
                        val (tileId, conf) = classifyTileMultiAngle(subCrop)
                        if (tileId != -1 && conf > 0.38f) {
                            riverTiles.add(tileId)
                        }
                    }
                } else {
                    val tileCrop = Bitmap.createBitmap(riverBitmap, box.left, box.top, bw, bh)
                    val (tileId, conf) = classifyTileMultiAngle(tileCrop)
                    if (tileId != -1 && conf > 0.38f) {
                        riverTiles.add(tileId)
                    }
                }
            }
        }

        return riverTiles
    }

    /**
     * 碰杠明牌区域检测 (Player Melds)
     * 识别玩家手牌右侧或专门碰杠区的明牌
     */
    fun processMeldRegion(
        context: Context,
        screenBitmap: Bitmap,
        meldRoiRatio: RectF = RectF(0.620f, 0.820f, 0.950f, 0.985f)
    ): List<List<Int>> {
        if (!isInitialized) {
            initTemplates(context)
        }

        val w = screenBitmap.width
        val h = screenBitmap.height

        val cropX = (w * meldRoiRatio.left).toInt().coerceIn(0, w - 1)
        val cropY = (h * meldRoiRatio.top).toInt().coerceIn(0, h - 1)
        val cropW = (w * meldRoiRatio.width()).toInt().coerceAtMost(w - cropX)
        val cropH = (h * meldRoiRatio.height()).toInt().coerceAtMost(h - cropY)

        if (cropW <= 20 || cropH <= 20) return emptyList()

        val meldBitmap = Bitmap.createBitmap(screenBitmap, cropX, cropY, cropW, cropH)
        val tileBoxes = locateTileBoundaries(meldBitmap)

        val recognizedTiles = mutableListOf<Int>()
        for ((bx1, bx2) in tileBoxes) {
            val tileW = bx2 - bx1
            if (tileW > 5) {
                val tileCrop = Bitmap.createBitmap(meldBitmap, bx1, 0, tileW, meldBitmap.height)
                val (tileId, conf) = classifyTile(tileCrop)
                if (tileId != -1 && conf > 0.36f) {
                    recognizedTiles.add(tileId)
                }
            }
        }

        val meldGroups = mutableListOf<List<Int>>()
        var idx = 0
        while (idx + 2 < recognizedTiles.size) {
            val t1 = recognizedTiles[idx]
            val t2 = recognizedTiles[idx + 1]
            val t3 = recognizedTiles[idx + 2]
            if (t1 == t2 && t2 == t3) {
                if (idx + 3 < recognizedTiles.size && recognizedTiles[idx + 3] == t1) {
                    meldGroups.add(listOf(t1, t1, t1, t1))
                    idx += 4
                } else {
                    meldGroups.add(listOf(t1, t1, t1))
                    idx += 3
                }
            } else {
                idx++
            }
        }

        return meldGroups
    }
}

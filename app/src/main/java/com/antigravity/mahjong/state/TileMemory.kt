package com.antigravity.mahjong.state

import com.antigravity.mahjong.solver.SichuanSolver
import kotlin.math.max

/**
 * 牌池守恒追踪器 (TileMemory)
 * 严格维护 108 张牌的真实余量: Remaining = 4 - (手牌) - (已见碰杠) - (已见牌河)
 * 单调累加防丢失，总数上限 4 强制守恒截断
 */
class TileMemory {

    private val handCounts = IntArray(27)
    private val visibleRiverCounts = IntArray(27)
    private val meldsCounts = IntArray(27)

    fun reset() {
        handCounts.fill(0)
        visibleRiverCounts.fill(0)
        meldsCounts.fill(0)
    }

    /**
     * 更新当前玩家自身手牌
     */
    fun updateHand(tiles: List<Int>) {
        handCounts.fill(0)
        for (t in tiles) {
            if (t in 0..26) {
                handCounts[t]++
            }
        }
    }

    /**
     * 新增公开见牌 (牌河、打出的牌)
     * 单调累加器: 只增不减
     */
    fun recordVisibleTile(tile: Int) {
        if (tile in 0..26) {
            // 物理守恒：已见 + 碰杠 不得超过 4 - 当前手牌持有个数
            val maxAllowed = 4 - handCounts[tile]
            if (visibleRiverCounts[tile] + meldsCounts[tile] < maxAllowed) {
                visibleRiverCounts[tile]++
            }
        }
    }

    /**
     * 记录自己或对手的碰杠明牌
     */
    fun recordMeld(tiles: List<Int>) {
        for (t in tiles) {
            if (t in 0..26) {
                meldsCounts[t]++
            }
        }
    }

    /**
     * 计算各牌当前全场存活真实余量
     */
    fun getRemainingCounts(): IntArray {
        val rem = IntArray(27)
        for (i in 0..26) {
            rem[i] = max(0, 4 - handCounts[i] - meldsCounts[i] - visibleRiverCounts[i])
        }
        return rem
    }

    /**
     * 获取全场已见牌字典 (用于求解器输入)
     */
    fun getVisibleTilesMap(): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        for (i in 0..26) {
            val seen = meldsCounts[i] + visibleRiverCounts[i]
            if (seen > 0) {
                map[i] = seen
            }
        }
        return map
    }
}

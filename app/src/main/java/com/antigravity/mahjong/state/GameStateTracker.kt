package com.antigravity.mahjong.state

import com.antigravity.mahjong.solver.SichuanSolver

/**
 * 牌局状态流转机 (GameStateTracker)
 * 维护当前牌局阶段、定缺状态与最新决策结果
 */
class GameStateTracker {

    enum class GamePhase {
        INITIAL,        // 开局/换三张
        DINGQUE_STAGE,  // 定缺中
        NORMAL_PLAY,    // 正常摸打
        READY_HAND,     // 已听牌
        HU_LOCKED,      // 已胡牌(血流成河摸打锁死)
        GAME_OVER       // 结算
    }

    var currentPhase: GamePhase = GamePhase.NORMAL_PLAY
        private set

    var currentDingque: String = "条"
        private set

    val tileMemory = TileMemory()
    val myMelds = mutableListOf<SichuanSolver.Meld>()

    var latestHandTiles: List<Int> = emptyList()
        private set

    var latestAnalysis: SichuanSolver.AnalysisResult? = null
        private set

    fun updateDingque(dingque: String) {
        if (dingque in listOf("条", "筒", "万")) {
            currentDingque = dingque
        }
    }

    /**
     * 当视觉引擎识别出新手牌时调用
     */
    fun onHandUpdated(
        handTiles: List<Int>,
        isDrawing: Boolean
    ): SichuanSolver.AnalysisResult {
        latestHandTiles = handTiles
        // 1. 同步至记牌器
        tileMemory.updateHand(handTiles)

        // 2. 调用四川麻将求解器计算
        val visibleMap = tileMemory.getVisibleTilesMap()
        val result = SichuanSolver.analyzeHand(
            handTiles = handTiles,
            dingque = currentDingque,
            melds = myMelds,
            visibleTiles = visibleMap
        )

        // 3. 更新内部阶段状态
        currentPhase = when (result.phase) {
            "DINGQUE_FORCED" -> GamePhase.DINGQUE_STAGE
            "READY_HAND_TING" -> GamePhase.READY_HAND
            else -> GamePhase.NORMAL_PLAY
        }

        latestAnalysis = result
        return result
    }

    fun reAnalyze(): SichuanSolver.AnalysisResult? {
        if (latestHandTiles.isEmpty()) return null
        return onHandUpdated(latestHandTiles, false)
    }

    fun addMeld(type: String, tiles: List<Int>) {
        myMelds.add(SichuanSolver.Meld(type, tiles))
        tileMemory.recordMeld(tiles)
    }

    fun resetRound() {
        currentPhase = GamePhase.NORMAL_PLAY
        tileMemory.reset()
        myMelds.clear()
        latestAnalysis = null
    }
}

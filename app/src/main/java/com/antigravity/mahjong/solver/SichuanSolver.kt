package com.antigravity.mahjong.solver

import kotlin.math.max

/**
 * 四川麻将核心求解器 (纯 Kotlin 高性能实现，耗时 < 1ms)
 * 具备强制定缺门控、真实剩余张数加权和叫听分析
 */
object SichuanSolver {

    val SUIT_NAMES = mapOf(0 to "条", 9 to "筒", 18 to "万")
    val SUIT_CODES = mapOf("条" to 0, "筒" to 9, "万" to 18)

    data class Meld(val type: String, val tiles: List<Int>)

    data class DefenseRating(
        val tile: Int,
        val tileName: String,
        val level: String, // "SAFE" | "SUSPICIOUS" | "DANGER"
        val reason: String
    )

    data class HuanSanZhangResult(
        val suit: String,
        val recommendedTiles: List<Int>,
        val tileNames: List<String>,
        val rationale: String
    )

    data class DingqueResult(
        val recommendedDingque: String,
        val rationale: String
    )

    data class DiscardChoice(
        val tile: Int,
        val tileName: String,
        val waits: Map<Int, Int>, // tileId -> 真实存活张数
        val totalRealWins: Int,
        val waitingDetails: List<String>
    )

    data class AnalysisResult(
        val phase: String, // "DINGQUE_FORCED" | "DECISION_DUAL_MODE" | "READY_HAND_TING" | "SEARCHING_SHANTEN"
        val message: String,
        val recommendedDiscard: Int,
        val recommendedDiscardName: String,
        val bestWaits: List<String> = emptyList(),
        val totalRealWins: Int = 0,
        val discardsRanked: List<DiscardChoice> = emptyList(),
        val defenseRadar: List<DefenseRating> = emptyList(),
        val bigFanDiscardName: String? = null
    )

    fun tileToStr(tile: Int): String {
        return when (tile) {
            in 0..8 -> "${tile + 1}条"
            in 9..17 -> "${tile - 8}筒"
            in 18..26 -> "${tile - 17}万"
            else -> "未知($tile)"
        }
    }

    fun analyzeHand(
        handTiles: List<Int>,
        dingque: String,
        melds: List<Meld> = emptyList(),
        visibleTiles: Map<Int, Int> = emptyMap(),
        opponentsDingque: List<String> = emptyList()
    ): AnalysisResult {
        val handCounts = IntArray(27)
        for (t in handTiles) {
            if (t in 0..26) handCounts[t]++
        }

        // 计算场上真实剩余存活数 (4 - 手牌 - 已见 - 碰杠)
        val remainingCounts = IntArray(27)
        for (t in 0..26) {
            val seen = visibleTiles[t] ?: 0
            val inMelds = melds.sumOf { m -> m.tiles.count { it == t } }
            remainingCounts[t] = max(0, 4 - handCounts[t] - seen - inMelds)
        }

        val radar = evaluateDefenseRadar(handTiles, visibleTiles, opponentsDingque)

        // 1. 强制定缺天条检查
        val queStart = SUIT_CODES[dingque] ?: 0
        val queTilesInHand = handTiles.filter { it in queStart until (queStart + 9) }

        if (queTilesInHand.isNotEmpty()) {
            // 孤张 1/9 优先打
            val sortedQue = queTilesInHand.sortedWith(compareBy { tile ->
                val pos = tile % 9
                val count = handCounts[tile]
                val isEdge = (pos == 0 || pos == 8)
                if (count == 1) (if (isEdge) 1 else 2) else (if (isEdge) 3 else 4)
            })
            val bestQue = sortedQue.first()
            return AnalysisResult(
                phase = "DINGQUE_FORCED",
                message = "强制定缺门控：手牌尚有【$dingque】门牌，必须优先打出！",
                recommendedDiscard = bestQue,
                recommendedDiscardName = tileToStr(bestQue),
                discardsRanked = sortedQue.distinct().map { t ->
                    DiscardChoice(
                        tile = t,
                        tileName = tileToStr(t),
                        waits = emptyMap(),
                        totalRealWins = 0,
                        waitingDetails = listOf(if (handCounts[t] == 1) "孤张优先切出" else "搭子拆出")
                    )
                },
                defenseRadar = radar
            )
        }

        // 2. 定缺已打完，选叫求解
        val numFixed = melds.size
        val uniqueHand = handTiles.distinct().sorted()
        val discardChoices = mutableListOf<DiscardChoice>()

        // 搏大番清一色检测
        val suitCounts = mutableMapOf("条" to 0, "筒" to 0, "万" to 0)
        for (t in handTiles) {
            if (t in 0..26) suitCounts[getSuit(t)] = (suitCounts[getSuit(t)] ?: 0) + 1
        }
        val primarySuit = suitCounts.maxByOrNull { it.value }?.key ?: "条"
        val primaryCount = suitCounts[primarySuit] ?: 0
        var bigFanDiscardName: String? = null
        if (primaryCount >= 7) {
            val otherTiles = uniqueHand.filter { getSuit(it) != primarySuit && it in 0..26 }
            if (otherTiles.isNotEmpty()) {
                bigFanDiscardName = tileToStr(otherTiles.first())
            }
        }

        for (d in uniqueHand) {
            val tempHand = handCounts.clone()
            tempHand[d]--

            val waits = mutableMapOf<Int, Int>()

            for (w in 0..26) {
                if (w in queStart until (queStart + 9)) continue

                val testHand = tempHand.clone()
                testHand[w]++

                if (canHu(testHand, numFixed)) {
                    val rem = remainingCounts[w]
                    waits[w] = rem
                }
            }

            val totalRealWins = waits.values.sum()
            if (totalRealWins > 0) {
                val details = waits.map { (w, cnt) -> "${tileToStr(w)}(存活${cnt}张)" }
                discardChoices.add(
                    DiscardChoice(
                        tile = d,
                        tileName = tileToStr(d),
                        waits = waits,
                        totalRealWins = totalRealWins,
                        waitingDetails = details
                    )
                )
            }
        }

        return if (discardChoices.isNotEmpty()) {
            discardChoices.sortByDescending { it.totalRealWins }
            val best = discardChoices.first()
            AnalysisResult(
                phase = "READY_HAND_TING",
                message = "已进入叫听！推荐打出【${best.tileName}】，叫口最大！",
                recommendedDiscard = best.tile,
                recommendedDiscardName = best.tileName,
                bestWaits = best.waitingDetails,
                totalRealWins = best.totalRealWins,
                discardsRanked = discardChoices,
                defenseRadar = radar,
                bigFanDiscardName = bigFanDiscardName
            )
        } else {
            // 3. 牌型尚未叫听 (1-向听或2-向听)，计算最大有效进张
            val shantenChoices = findBestShantenDiscard(handCounts, uniqueHand, queStart, numFixed, remainingCounts)
            if (shantenChoices.isNotEmpty()) {
                val best = shantenChoices.first()
                AnalysisResult(
                    phase = "1_SHANTEN_IMPROVEMENT",
                    message = "当前为 1 进听，推荐打【${best.tileName}】，有效进张最大 (${best.totalRealWins}张)，进张后即可听牌！",
                    recommendedDiscard = best.tile,
                    recommendedDiscardName = best.tileName,
                    bestWaits = best.waitingDetails,
                    totalRealWins = best.totalRealWins,
                    discardsRanked = shantenChoices,
                    defenseRadar = radar,
                    bigFanDiscardName = bigFanDiscardName
                )
            } else {
                AnalysisResult(
                    phase = "SEARCHING_SHANTEN",
                    message = "手牌较散 (2进听以上)，建议保留搭子，打出孤张偏张。",
                    recommendedDiscard = uniqueHand.lastOrNull() ?: 0,
                    recommendedDiscardName = tileToStr(uniqueHand.lastOrNull() ?: 0),
                    defenseRadar = radar,
                    bigFanDiscardName = bigFanDiscardName
                )
            }
        }
    }

    fun evaluateDefenseRadar(
        handTiles: List<Int>,
        visibleTiles: Map<Int, Int>,
        opponentsDingque: List<String> = emptyList()
    ): List<DefenseRating> {
        val uniqueHand = handTiles.distinct().sorted()
        val ratings = mutableListOf<DefenseRating>()
        for (t in uniqueHand) {
            val sname = getSuit(t)
            val seen = visibleTiles[t] ?: 0
            val pos = t % 9
            val isEdge = (pos == 0 || pos == 8)
            val isMiddle = (pos in 3..5)

            if (opponentsDingque.contains(sname)) {
                ratings.add(DefenseRating(t, tileToStr(t), "SAFE", "对手定缺【$sname】，对其为绝对安全牌"))
            } else if (seen >= 3) {
                ratings.add(DefenseRating(t, tileToStr(t), "SAFE", "场上已见 $seen 张(绝张/准绝张)，点炮率极低"))
            } else if (isEdge && seen >= 1) {
                ratings.add(DefenseRating(t, tileToStr(t), "SAFE", "边张且场上已见，相对安全"))
            } else if (isMiddle && seen == 0) {
                ratings.add(DefenseRating(t, tileToStr(t), "DANGER", "⚠️ 中张 $pos 纯生张(未见)，极高点炮风险！"))
            } else {
                ratings.add(DefenseRating(t, tileToStr(t), "SUSPICIOUS", "疑牌：场上已见 $seen 张，小心防守"))
            }
        }
        return ratings
    }

    fun recommendHuanSanZhang(handTiles: List<Int>): HuanSanZhangResult {
        val suits = mutableMapOf("条" to mutableListOf<Int>(), "筒" to mutableListOf<Int>(), "万" to mutableListOf<Int>())
        for (t in handTiles) {
            if (t in 0..26) suits[getSuit(t)]?.add(t)
        }
        val suitWithAtLeast3 = suits.filter { it.value.size >= 3 }
        val targetSuit = if (suitWithAtLeast3.isNotEmpty()) {
            suitWithAtLeast3.minByOrNull { it.value.size }?.key ?: "条"
        } else {
            suits.maxByOrNull { it.value.size }?.key ?: "条"
        }
        val candTiles = suits[targetSuit]!!.sortedWith(compareBy { t ->
            val pos = t % 9
            if (pos == 0 || pos == 8) 1 else 2
        }).take(3)

        return HuanSanZhangResult(
            suit = targetSuit,
            recommendedTiles = candTiles,
            tileNames = candTiles.map { tileToStr(it) },
            rationale = "【$targetSuit】手牌最少，拆换代价极低，避免换出连牌给对手送大牌！"
        )
    }

    fun recommendDingque(handTiles: List<Int>): DingqueResult {
        val counts = mutableMapOf("条" to 0, "筒" to 0, "万" to 0)
        for (t in handTiles) {
            if (t in 0..26) counts[getSuit(t)] = (counts[getSuit(t)] ?: 0) + 1
        }
        val bestQue = counts.minByOrNull { it.value }?.key ?: "条"
        return DingqueResult(
            recommendedDingque = bestQue,
            rationale = "【$bestQue】持牌仅 ${counts[bestQue]} 张，断门速度最快，直奔叫听！"
        )
    }

    fun getSuit(tile: Int): String {
        return when (tile) {
            in 0..8 -> "条"
            in 9..17 -> "筒"
            in 18..26 -> "万"
            else -> "字"
        }
    }

    /**
     * 1-向听有效进张计算：寻找打出某张牌后，摸入哪些牌能直接叫听
     */
    private fun findBestShantenDiscard(
        handCounts: IntArray,
        uniqueHand: List<Int>,
        queStart: Int,
        numFixed: Int,
        remainingCounts: IntArray
    ): List<DiscardChoice> {
        val choices = mutableListOf<DiscardChoice>()

        for (d in uniqueHand) {
            val tempHand = handCounts.clone()
            tempHand[d]--

            val effectiveDraws = mutableMapOf<Int, Int>()

            // 遍历所有可能的合法摸牌 (排除定缺门)
            for (draw in 0..26) {
                if (draw in queStart until (queStart + 9)) continue

                val testHand = tempHand.clone()
                testHand[draw]++

                // 检查摸入 draw 之后，能否再打出一张牌实现听牌
                var canTingAfterDraw = false
                for (secondD in 0..26) {
                    if (testHand[secondD] > 0) {
                        val tingTest = testHand.clone()
                        tingTest[secondD]--

                        for (waitTile in 0..26) {
                            if (waitTile in queStart until (queStart + 9)) continue
                            val huTest = tingTest.clone()
                            huTest[waitTile]++
                            if (canHu(huTest, numFixed)) {
                                canTingAfterDraw = true
                                break
                            }
                        }
                        if (canTingAfterDraw) break
                    }
                }

                if (canTingAfterDraw) {
                    effectiveDraws[draw] = remainingCounts[draw]
                }
            }

            val totalRealWins = effectiveDraws.values.sum()
            if (totalRealWins > 0) {
                val details = effectiveDraws.map { (w, cnt) -> "${tileToStr(w)}(存活${cnt}张)" }
                choices.add(
                    DiscardChoice(
                        tile = d,
                        tileName = tileToStr(d),
                        waits = effectiveDraws,
                        totalRealWins = totalRealWins,
                        waitingDetails = details
                    )
                )
            }
        }

        choices.sortByDescending { it.totalRealWins }
        return choices
    }

    private fun canHu(hand: IntArray, numFixed: Int): Boolean {
        val total = hand.sum()
        val numMeldsNeeded = 4 - numFixed
        if (total != 2 + numMeldsNeeded * 3) return false

        // 四川麻将核心牌型：七对判定 (门前清 14 张手牌，支持普通七对与龙七对)
        if (numFixed == 0 && total == 14) {
            val pairsCount = (0..26).sumOf { hand[it] / 2 }
            if (pairsCount == 7) return true
        }

        for (pair in 0..26) {
            if (hand[pair] >= 2) {
                val temp = hand.clone()
                temp[pair] -= 2
                if (checkMeldsRecursive(temp, numMeldsNeeded)) return true
            }
        }
        return false
    }

    private fun checkMeldsRecursive(hand: IntArray, meldsLeft: Int): Boolean {
        if (meldsLeft == 0) return hand.all { it == 0 }

        var idx = 0
        while (idx < 27 && hand[idx] == 0) idx++
        if (idx >= 27) return true

        // 尝试刻子
        if (hand[idx] >= 3) {
            hand[idx] -= 3
            if (checkMeldsRecursive(hand, meldsLeft - 1)) {
                hand[idx] += 3
                return true
            }
            hand[idx] += 3
        }

        // 尝试顺子 (同花色连续 3 张)
        val posInSuit = idx % 9
        if (posInSuit <= 6) {
            if (hand[idx + 1] > 0 && hand[idx + 2] > 0) {
                hand[idx]--
                hand[idx + 1]--
                hand[idx + 2]--
                if (checkMeldsRecursive(hand, meldsLeft - 1)) {
                    hand[idx]++
                    hand[idx + 1]++
                    hand[idx + 2]++
                    return true
                }
                hand[idx]++
                hand[idx + 1]++
                hand[idx + 2]++
            }
        }
        return false
    }
}

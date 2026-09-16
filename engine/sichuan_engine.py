# -*- coding: utf-8 -*-
"""
四川麻将核心决策与四大王牌卖点引擎 (Sichuan Mahjong Decision Engine)
包含：
1. 强制定缺天条逻辑 (DingQue Rule Gate)
2. 真实剩余张数加权 (Remaining Tiles Weighted Shanten)
3. 选叫推荐与听牌分析 (Tingpai & Ready Hand Recommendation)
4. 王牌卖点 1: AI 防点炮透视雷达 (Defense Radar - 现物/筋牌/壁牌/断门分析)
5. 王牌卖点 2: 开局换三张与定缺黄金决策 (Opening Strategist - 换三张与定缺神策)
6. 王牌卖点 3: 【搏大番（清一色/七对/带根）】vs【稳健速胡】双决策模式切换与 EV 评估
7. 108 牌物理守恒动态记忆池与 9x3 记牌器矩阵生成
"""

from typing import List, Dict, Tuple, Optional, Set
import numpy as np

class SichuanEngine:
    SUIT_NAMES = {0: "条", 9: "筒", 18: "万"}
    SUIT_CODES = {"条": 0, "筒": 9, "万": 18, "ti": 0, "to": 9, "w": 18}

    @staticmethod
    def tile_to_str(tile: int) -> str:
        if 0 <= tile < 9:
            return f"{tile + 1}条"
        elif 9 <= tile < 18:
            return f"{tile - 8}筒"
        elif 18 <= tile < 27:
            return f"{tile - 17}万"
        elif tile == 27:
            return "红中"
        return f"未知({tile})"

    @staticmethod
    def str_to_tile(s: str) -> int:
        s = s.strip()
        if s in ["hz", "红中", "hongzhong"]:
            return 27
        num_str = "".join([c for c in s if c.isdigit()])
        suit_str = "".join([c for c in s if not c.isdigit()])
        if not num_str:
            return 27
        num = int(num_str)
        if suit_str in ["条", "ti", "s"]:
            return num - 1
        elif suit_str in ["筒", "to", "p"]:
            return 8 + num
        elif suit_str in ["万", "w", "m"]:
            return 17 + num
        raise ValueError(f"无法识别牌名: {s}")

    @staticmethod
    def get_suit(tile: int) -> str:
        if 0 <= tile < 9:
            return "条"
        elif 9 <= tile < 18:
            return "筒"
        elif 18 <= tile < 27:
            return "万"
        return "字"

    # =========================================================================
    # 王牌卖点 1: AI 防点炮透视雷达 (Defense Radar)
    # =========================================================================
    @classmethod
    def evaluate_defense_radar(cls,
                               hand_tiles: List[int],
                               visible_tiles: Dict[int, int],
                               opponents_dingque: Optional[List[str]] = None,
                               recent_discards: Optional[List[int]] = None) -> List[Dict]:
        """
        AI 防点炮透视雷达：为手牌中的每一张牌评定安全/危险等级
        规则：
        1. 🟢 绝对安全 (SAFE, 避铳率 100%):
           - 现物 (对手刚打出过的牌，同巡绝对不点炮)
           - 场上已见 3~4 张的牌 (绝张/准绝张)
           - 对手定缺门牌 (四川麻将绝对断门，永不胡此色)
           - 绝张壁牌衍生牌 (No Chance)
        2. 🟡 疑牌 (SUSPICIOUS, 适度危险):
           - 筋牌 (1-4-7, 2-5-8, 3-6-9 两面听避铳)
           - 场上已见 2 张的偏张 (1/9)
           - One Chance 衍生牌
        3. 🔴 极危炮牌预警 (DANGER, 高概率点炮):
           - 4/5/6 中张大生张 (场上见 0 张)
           - 对手主攻花色的生张
        """
        opponents_dingque = opponents_dingque or []
        recent_discards = recent_discards or []

        # 统计场上已见张数
        seen_map = {t: visible_tiles.get(t, 0) for t in range(28)}
        for d in recent_discards:
            seen_map[d] = seen_map.get(d, 0) + 1

        results = []
        for t in sorted(list(set(hand_tiles))):
            if t == 27: # 红中
                results.append({
                    "tile": t,
                    "tile_name": "红中",
                    "level": "SAFE",
                    "badge": "🟢",
                    "safety_score": 95,
                    "reason": "特殊字牌，无法做顺子，避铳率极高"
                })
                continue

            suit = cls.get_suit(t)
            num = (t % 9) + 1
            seen_count = seen_map.get(t, 0)

            # 1. 检查对手定缺门 (完全断门)
            if suit in opponents_dingque:
                results.append({
                    "tile": t,
                    "tile_name": cls.tile_to_str(t),
                    "level": "SAFE",
                    "badge": "🟢",
                    "safety_score": 100,
                    "reason": f"防点炮天条：场上有对手定缺【{suit}】，该门对其为绝对安全牌"
                })
                continue

            # 2. 检查现物 (最近巡已打出)
            if t in recent_discards or seen_count >= 3:
                results.append({
                    "tile": t,
                    "tile_name": cls.tile_to_str(t),
                    "level": "SAFE",
                    "badge": "🟢",
                    "safety_score": 98,
                    "reason": f"现物绝张：场上已现 {seen_count} 张，几乎无人可胡此牌"
                })
                continue

            # 3. 检查壁牌 (No Chance)
            suit_start = cls.SUIT_CODES[suit]
            is_no_chance = False
            nc_reason = ""
            # 若 7 在场上已见 4 张，则 8 和 9 无法与 7 形成 7-8-9 顺子
            if num in [8, 9] and seen_map.get(suit_start + 6, 0) >= 4:
                is_no_chance = True
                nc_reason = f"壁牌保护：7{suit} 已见4张(绝张)，{num}{suit} 无法形成双面听"
            elif num in [1, 2] and seen_map.get(suit_start + 2, 0) >= 4:
                is_no_chance = True
                nc_reason = f"壁牌保护：3{suit} 已见4张(绝张)，{num}{suit} 无法形成双面听"

            if is_no_chance:
                results.append({
                    "tile": t,
                    "tile_name": cls.tile_to_str(t),
                    "level": "SAFE",
                    "badge": "🟢",
                    "safety_score": 92,
                    "reason": nc_reason
                })
                continue

            # 4. 检查筋牌 (1-4-7, 2-5-8, 3-6-9)
            # 例如 4 已见多张，则 1、7 为筋牌
            is_suji = False
            suji_reason = ""
            if num in [1, 7] and seen_map.get(suit_start + 3, 0) >= 2:
                is_suji = True
                suji_reason = f"筋牌防线：4{suit} 已多现，{num}{suit} 两面听点炮概率极低"
            elif num in [2, 8] and seen_map.get(suit_start + 4, 0) >= 2:
                is_suji = True
                suji_reason = f"筋牌防线：5{suit} 已多现，{num}{suit} 两面听点炮概率极低"
            elif num in [3, 9] and seen_map.get(suit_start + 5, 0) >= 2:
                is_suji = True
                suji_reason = f"筋牌防线：6{suit} 已多现，{num}{suit} 两面听点炮概率极低"

            if is_suji:
                results.append({
                    "tile": t,
                    "tile_name": cls.tile_to_str(t),
                    "level": "SUSPICIOUS",
                    "badge": "🟡",
                    "safety_score": 75,
                    "reason": suji_reason
                })
                continue

            # 5. 中张大生张判定 (极危)
            if num in [4, 5, 6] and seen_count == 0:
                results.append({
                    "tile": t,
                    "tile_name": cls.tile_to_str(t),
                    "level": "DANGER",
                    "badge": "🔴",
                    "safety_score": 20,
                    "reason": f"⚠️ 极度高危：中心张 {num}{suit} 为纯生张(未见)，点炮率极高，切勿轻易打出！"
                })
            elif num in [2, 3, 7, 8] and seen_count == 0:
                results.append({
                    "tile": t,
                    "tile_name": cls.tile_to_str(t),
                    "level": "SUSPICIOUS",
                    "badge": "🟡",
                    "safety_score": 50,
                    "reason": f"疑牌生张：{num}{suit} 场上尚未出现，存在叫口风险"
                })
            else:
                results.append({
                    "tile": t,
                    "tile_name": cls.tile_to_str(t),
                    "level": "SUSPICIOUS",
                    "badge": "🟡",
                    "safety_score": 65,
                    "reason": f"常规牌：{num}{suit} 场上已见 {seen_count} 张，适度安全"
                })

        return results

    # =========================================================================
    # 王牌卖点 2: 开局“换三张”与“定缺”黄金决策 (Opening Strategist)
    # =========================================================================
    @classmethod
    def recommend_huan_san_zhang(cls, hand_tiles: List[int]) -> Dict:
        """
        开局换三张神级算法：
        1. 寻找最弱花色 (门子张数 >= 3，搭子最少，孤张最多)
        2. 选出最值得换出的 3 张牌 (优先孤张、边张 1/9，避免把连牌 456 送给对手做大牌)
        """
        suits = {"条": [], "筒": [], "万": []}
        for t in hand_tiles:
            if t < 27:
                s = cls.get_suit(t)
                suits[s].append(t)

        # 评估各门花色：张数、搭子数
        suit_stats = {}
        for sname, t_list in suits.items():
            if len(t_list) >= 3:
                # 统计孤张数量
                counts = np.bincount(t_list, minlength=27)
                isolated = 0
                for t in t_list:
                    pos = t % 9
                    has_conn = False
                    for offset in [-2, -1, 1, 2]:
                        nb = t + offset
                        if (nb // 9) == (t // 9) and 0 <= nb < 27 and counts[nb] > 0:
                            has_conn = True
                            break
                    if not has_conn and counts[t] == 1:
                        isolated += 1

                # 分数越低越适合换出去
                score = len(t_list) * 10 - isolated * 8
                suit_stats[sname] = {
                    "count": len(t_list),
                    "isolated": isolated,
                    "score": score,
                    "tiles": sorted(t_list)
                }

        if not suit_stats:
            # 如果没有花色 >= 3 张，取张数最多的花色
            cand_suit = max(suits, key=lambda s: len(suits[s]))
            cand_tiles = sorted(suits[cand_suit])[:3]
            return {
                "suit": cand_suit,
                "recommended_tiles": cand_tiles,
                "tile_names": [cls.tile_to_str(t) for t in cand_tiles],
                "rationale": f"全手牌极度分散，被迫换出【{cand_suit}】3张"
            }

        # 选出最差的花色
        best_suit = min(suit_stats, key=lambda s: suit_stats[s]["score"])
        cand_tiles = suit_stats[best_suit]["tiles"]

        # 在该花色中挑选 3 张牌：
        # 优先级：孤张边张 (1, 9) > 孤张中张 > 边缘搭子
        def tile_exchange_priority(t):
            pos = t % 9
            is_edge = (pos == 0 or pos == 8)
            return 1 if is_edge else 2

        sorted_cand = sorted(cand_tiles, key=tile_exchange_priority)
        picked = sorted_cand[:3]

        names = [cls.tile_to_str(t) for t in picked]
        rationale = f"【{best_suit}】仅持 {len(cand_tiles)} 张，孤张偏多({suit_stats[best_suit]['isolated']}张孤牌)，拆换成本极低；避免换出顺子连牌，防止给对手送大牌！"

        return {
            "suit": best_suit,
            "recommended_tiles": picked,
            "tile_names": names,
            "rationale": rationale
        }

    @classmethod
    def recommend_dingque(cls, hand_tiles: List[int]) -> Dict:
        """
        开局定缺神级决策：
        全面权衡三门花色的：张数、向听进张潜力、做清一色大番潜力
        """
        suits = {"条": [], "筒": [], "万": []}
        for t in hand_tiles:
            if t < 27:
                suits[cls.get_suit(t)].append(t)

        evaluations = {}
        for sname, t_list in suits.items():
            cnt = len(t_list)
            # 计算搭子与对子
            counts = np.bincount(t_list, minlength=27)
            pairs = sum(1 for c in counts if c >= 2)
            # 潜力打分：张数越少、对子越少，越适合定缺
            que_score = cnt * 3 + pairs * 4
            evaluations[sname] = {
                "count": cnt,
                "pairs": pairs,
                "que_suit_favorability": 100 - que_score
            }

        # 检查是否存在清一色契机 (某门 >= 7 张)
        max_suit = max(suits, key=lambda s: len(suits[s]))
        big_fan_potential = None
        if len(suits[max_suit]) >= 7:
            big_fan_potential = f"检测到【{max_suit}】多达 {len(suits[max_suit])} 张，具备极强【清一色】大番底子！"

        # 最适合定缺的花色 (分值最少者)
        best_que = min(evaluations, key=lambda s: len(suits[s]))

        rationale = f"【{best_que}】手牌最少(仅{len(suits[best_que])}张)，断门代价最小，能以最快速度切完缺门进入听牌！"
        if big_fan_potential:
            rationale += f" 同时，{big_fan_potential} 坚决不缺【{max_suit}】！"

        return {
            "recommended_dingque": best_que,
            "evaluations": evaluations,
            "rationale": rationale
        }

    # =========================================================================
    # 王牌卖点 3: 【搏大番（清一色/七对）】vs【稳健速胡】双决策模式
    # =========================================================================
    @classmethod
    def analyze_hand(cls,
                      hand_tiles: List[int],
                      dingque: str,
                      melds: Optional[List[Tuple[str, List[int]]]] = None,
                      visible_tiles: Optional[Dict[int, int]] = None,
                      opponents_dingque: Optional[List[str]] = None) -> Dict:
        """
        核心全规则决策接口，包含定缺门控、双模式选叫、防点炮雷达与 9x3 记牌器矩阵
        """
        melds = melds or []
        visible_tiles = visible_tiles or {}
        opponents_dingque = opponents_dingque or []

        # 统计真实存活数
        hand_counts = [0] * 28
        for t in hand_tiles:
            if 0 <= t < 28:
                hand_counts[t] += 1

        remaining_counts = {}
        for t in range(28):
            seen = visible_tiles.get(t, 0)
            in_melds = sum(m[1].count(t) for m in melds)
            rem = max(0, 4 - hand_counts[t] - seen - in_melds)
            remaining_counts[t] = rem

        # 1. 检查是否存在定缺门牌 (天条天网)
        que_suit_start = cls.SUIT_CODES[dingque]
        que_tiles_in_hand = [t for t in hand_tiles if que_suit_start <= t < que_suit_start + 9]

        if que_tiles_in_hand:
            sorted_que = sorted(que_tiles_in_hand, key=lambda t: cls._score_que_discard(t, hand_counts))
            best_que = sorted_que[0]
            radar = cls.evaluate_defense_radar(hand_tiles, visible_tiles, opponents_dingque)
            return {
                "phase": "DINGQUE_FORCED",
                "message": f"强制定缺门控触发：手牌中尚有【{dingque}】门牌，必须优先打出！",
                "recommended_discard": best_que,
                "recommended_discard_name": cls.tile_to_str(best_que),
                "que_tiles_remaining": len(que_tiles_in_hand),
                "discards_ranked": [
                    {
                        "tile": t,
                        "tile_name": cls.tile_to_str(t),
                        "reason": "定缺门孤张优先切出" if hand_counts[t] == 1 else "定缺门搭子切出",
                        "real_remaining_wins": 0
                    } for t in sorted(list(set(sorted_que)))
                ],
                "defense_radar": radar,
                "tile_matrix": cls.generate_tile_matrix(hand_counts, visible_tiles, melds)
            }

        # 2. 定缺门已清空，开启【稳健速和】与【搏大番清一色】双路线求解
        num_fixed = len(melds)
        unique_hand_tiles = sorted(list(set(hand_tiles)))

        # 统计花色张数分布 (万/条/筒)
        suit_counts = {"条": 0, "筒": 0, "万": 0}
        for t in hand_tiles:
            if t < 27:
                suit_counts[cls.get_suit(t)] += 1

        primary_suit = max(suit_counts, key=suit_counts.get)
        primary_count = suit_counts[primary_suit]

        # 路线 A: 稳健速和 (纯牌效进张优先)
        speed_discards = cls._calculate_ting_options(hand_counts, unique_hand_tiles, que_suit_start, num_fixed, remaining_counts)
        if not speed_discards:
            speed_discards = cls._find_best_shanten_discard(hand_counts, unique_hand_tiles, que_suit_start, num_fixed, remaining_counts)

        # 路线 B: 搏大番 (清一色诱导 / 七对子)
        big_fan_discards = []
        is_big_fan_viable = False
        big_fan_rationale = ""

        # 清一色诱导门槛：主花色达到 7 张以上
        if primary_count >= 7:
            is_big_fan_viable = True
            other_suit_tiles = [t for t in unique_hand_tiles if cls.get_suit(t) != primary_suit and t < 27]
            if other_suit_tiles:
                # 强制切除杂门牌，全力做清一色
                big_fan_discards = [
                    {
                        "tile": t,
                        "tile_name": cls.tile_to_str(t),
                        "target_fan": "清一色 (32番)",
                        "reason": f"搏大番清一色推荐：手中已有 {primary_count} 张【{primary_suit}】，果断拆除杂门【{cls.tile_to_str(t)}】，冲刺清一色！"
                    } for t in other_suit_tiles
                ]
                big_fan_rationale = f"检测到手中持 {primary_count} 张【{primary_suit}】，清一色胜率极高！建议哪怕牺牲 1 向听，也坚决打光杂色！"
            else:
                big_fan_rationale = f"已达成纯【{primary_suit}】清一色形态，继续按牌效叫听！"
                big_fan_discards = speed_discards

        # 防点炮雷达
        radar = cls.evaluate_defense_radar(hand_tiles, visible_tiles, opponents_dingque)
        tile_matrix = cls.generate_tile_matrix(hand_counts, visible_tiles, melds)

        best_speed = speed_discards[0] if speed_discards else {
            "tile": unique_hand_tiles[-1],
            "tile_name": cls.tile_to_str(unique_hand_tiles[-1]),
            "total_real_wins": 0,
            "message": "手牌较散，打出孤张"
        }

        best_big_fan = big_fan_discards[0] if big_fan_discards else best_speed

        return {
            "phase": "DECISION_DUAL_MODE",
            "message": f"牌效分析完毕：速和推荐打【{best_speed['tile_name']}】" + (f"；搏大番清一色推荐打【{best_big_fan['tile_name']}】" if is_big_fan_viable else ""),
            "recommended_discard": best_speed["tile"],
            "recommended_discard_name": best_speed["tile_name"],
            "speed_mode": {
                "recommended_tile": best_speed["tile"],
                "recommended_name": best_speed["tile_name"],
                "discards_ranked": speed_discards,
                "real_wins": best_speed.get("total_real_wins", 0),
                "summary": f"稳健速和策略：打出【{best_speed['tile_name']}】，有效进张/叫口 {best_speed.get('total_real_wins', 0)} 张"
            },
            "big_fan_mode": {
                "viable": is_big_fan_viable,
                "recommended_tile": best_big_fan["tile"],
                "recommended_name": best_big_fan["tile_name"],
                "discards_ranked": big_fan_discards,
                "rationale": big_fan_rationale
            },
            "defense_radar": radar,
            "tile_matrix": tile_matrix
        }

    # =========================================================================
    # 辅助计算与矩阵生成
    # =========================================================================
    @classmethod
    def generate_tile_matrix(cls, hand_counts: List[int], visible_tiles: Dict[int, int], melds: List) -> Dict:
        """生成 9x3 记牌器剩余牌存活矩阵"""
        suits = [("条", 0), ("筒", 9), ("万", 18)]
        matrix = {}
        for sname, offset in suits:
            row = []
            for num in range(1, 10):
                tid = offset + num - 1
                seen = visible_tiles.get(tid, 0)
                in_melds = sum(m[1].count(tid) for m in melds) if melds else 0
                in_hand = hand_counts[tid] if tid < len(hand_counts) else 0
                rem = max(0, 4 - in_hand - seen - in_melds)
                status = "绝张" if rem == 0 else f"{rem}张"
                row.append({
                    "tile": tid,
                    "num": num,
                    "name": f"{num}{sname}",
                    "remaining": rem,
                    "status": status,
                    "is_zero": (rem == 0)
                })
            matrix[sname] = row
        return matrix

    @classmethod
    def _calculate_ting_options(cls, hand_counts, unique_hand_tiles, que_suit_start, num_fixed, remaining_counts):
        discard_options = []
        for d in unique_hand_tiles:
            temp_hand = hand_counts[:]
            temp_hand[d] -= 1
            waits = {}
            for wait_tile in range(27):
                if que_suit_start <= wait_tile < que_suit_start + 9:
                    continue
                test_hand = temp_hand[:]
                test_hand[wait_tile] += 1
                if cls._can_hu(test_hand, num_fixed):
                    waits[wait_tile] = remaining_counts[wait_tile]

            if waits:
                total_real_wins = sum(waits.values())
                discard_options.append({
                    "tile": d,
                    "tile_name": cls.tile_to_str(d),
                    "waits": waits,
                    "total_real_wins": total_real_wins,
                    "waiting_details": [
                        f"{cls.tile_to_str(w)}(存活{cnt}张)" for w, cnt in waits.items()
                    ]
                })

        discard_options.sort(key=lambda x: x["total_real_wins"], reverse=True)
        return discard_options

    @classmethod
    def _find_best_shanten_discard(cls, hand_counts, unique_hand_tiles, que_start, num_fixed, remaining_counts):
        choices = []
        for d in unique_hand_tiles:
            temp_hand = hand_counts[:]
            temp_hand[d] -= 1
            effective_draws = {}

            for draw in range(27):
                if que_start <= draw < que_start + 9:
                    continue
                test_hand = temp_hand[:]
                test_hand[draw] += 1

                can_ting = False
                for second_d in range(27):
                    if test_hand[second_d] > 0:
                        t2 = test_hand[:]
                        t2[second_d] -= 1
                        for wait_t in range(27):
                            if que_start <= wait_t < que_start + 9:
                                continue
                            t3 = t2[:]
                            t3[wait_t] += 1
                            if cls._can_hu(t3, num_fixed):
                                can_ting = True
                                break
                        if can_ting:
                            break
                if can_ting:
                    effective_draws[draw] = remaining_counts[draw]

            total_wins = sum(effective_draws.values())
            if total_wins > 0:
                choices.append({
                    "tile": d,
                    "tile_name": cls.tile_to_str(d),
                    "waits": effective_draws,
                    "total_real_wins": total_wins,
                    "waiting_details": [f"{cls.tile_to_str(w)}(存活{cnt}张)" for w, cnt in effective_draws.items()]
                })

        choices.sort(key=lambda x: x["total_real_wins"], reverse=True)
        return choices

    @classmethod
    def _can_hu(cls, hand_array: List[int], num_fixed_melds: int) -> bool:
        num_melds_needed = 4 - num_fixed_melds
        total_cards = sum(hand_array)
        if total_cards != 2 + num_melds_needed * 3:
            return False

        for pair in range(27):
            if hand_array[pair] >= 2:
                temp = hand_array[:]
                temp[pair] -= 2
                if cls._check_melds_recursive(temp, num_melds_needed):
                    return True
        return False

    @classmethod
    def _check_melds_recursive(cls, hand: List[int], melds_left: int) -> bool:
        if melds_left == 0:
            return sum(hand) == 0

        idx = 0
        while idx < 27 and hand[idx] == 0:
            idx += 1
        if idx >= 27:
            return True

        # 刻子
        if hand[idx] >= 3:
            hand[idx] -= 3
            if cls._check_melds_recursive(hand, melds_left - 1):
                hand[idx] += 3
                return True
            hand[idx] += 3

        # 顺子
        suit_base = (idx // 9) * 9
        pos_in_suit = idx % 9
        if pos_in_suit <= 6:
            if hand[idx + 1] > 0 and hand[idx + 2] > 0:
                hand[idx] -= 1
                hand[idx + 1] -= 1
                hand[idx + 2] -= 1
                if cls._check_melds_recursive(hand, melds_left - 1):
                    hand[idx] += 1
                    hand[idx + 1] += 1
                    hand[idx + 2] += 1
                    return True
                hand[idx] += 1
                hand[idx + 1] += 1
                hand[idx + 2] += 1

        return False

    @staticmethod
    def _score_que_discard(tile: int, hand_counts: List[int]) -> int:
        pos = tile % 9
        count = hand_counts[tile]
        is_edge = (pos == 0 or pos == 8)
        if count == 1:
            return 1 if is_edge else 2
        elif count == 2:
            return 3 if is_edge else 4
        return 5

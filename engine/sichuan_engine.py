# -*- coding: utf-8 -*-
"""
四川麻将核心决策引擎 (Sichuan Mahjong Decision Engine)
包含：
1. 强制定缺天条逻辑 (Dingque Rule)
2. 真实剩余张数加权 (Remaining Tiles Weighted Shanten)
3. 选叫推荐与听牌分析 (Tingpai & Ready Hand Recommendation)
4. 碰杠/门前搭子支持 (Melds Support)
"""

from typing import List, Dict, Tuple, Optional, Set

# 编码定义:
# 0~8: 1条~9条 (ti)
# 9~17: 1筒~9筒 (to)
# 18~26: 1万~9万 (w)

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
        if s in ["hz", "红中"]:
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
        return "万"

    @classmethod
    def analyze_hand(cls, 
                     hand_tiles: List[int], 
                     dingque: str, 
                     melds: Optional[List[Tuple[str, List[int]]]] = None,
                     visible_tiles: Optional[Dict[int, int]] = None) -> Dict:
        """
        核心决策分析接口
        :param hand_tiles: 玩家手牌编码列表 (长度一般为 14/11/8/5/2 张摸打态，或 13/10/7/4/1 张等摸态)
        :param dingque: 玩家定缺花色 ("条", "筒", "万")
        :param melds: 玩家已碰杠列表 [("peng", [t, t, t]), ...]
        :param visible_tiles: 桌面已见牌统计 {tile_id: seen_count}
        :return: 包含推荐打牌、进听分析、叫口详情的决策字典
        """
        melds = melds or []
        visible_tiles = visible_tiles or {}
        
        # 计算每种牌在场上真实的剩余张数: 4 - (手牌内张数) - (已见公开张数)
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

        # 1. 检查是否存在定缺门牌 (天条规则)
        que_suit_start = cls.SUIT_CODES[dingque]
        que_tiles_in_hand = [t for t in hand_tiles if que_suit_start <= t < que_suit_start + 9]
        
        if que_tiles_in_hand:
            # 必须立即打出缺门牌！按孤张、边张优先策略排序
            sorted_que = sorted(que_tiles_in_hand, key=lambda t: cls._score_que_discard(t, hand_counts))
            best_que = sorted_que[0]
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
                ]
            }

        # 2. 定缺门已打完，进入正规牌效与选叫求解
        num_fixed = len(melds)
        is_discard_turn = (len(hand_tiles) % 3 == 2)
        
        # 寻找打哪张牌能够叫听 (0 进听选叫分析)
        discard_options = []
        unique_hand_tiles = sorted(list(set(hand_tiles)))
        
        for d in unique_hand_tiles:
            # 模拟打出 d
            temp_hand = hand_counts[:]
            temp_hand[d] -= 1
            
            # 测试摸入每种合法花色的牌能否胡牌
            waits = {}
            for wait_tile in range(27):
                # 缺门牌不可胡
                if que_suit_start <= wait_tile < que_suit_start + 9:
                    continue
                
                # 剩余张数为 0 的虽然理论能胡，但实际进张为 0
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

        if discard_options:
            # 按照真实存活叫口总数降序排列
            discard_options.sort(key=lambda x: x["total_real_wins"], reverse=True)
            best_choice = discard_options[0]
            return {
                "phase": "READY_HAND_TING",
                "message": f"已进入叫听状态！最佳推荐打出【{best_choice['tile_name']}】，叫口最大！",
                "recommended_discard": best_choice["tile"],
                "recommended_discard_name": best_choice["tile_name"],
                "best_waits": best_choice["waiting_details"],
                "total_real_wins": best_choice["total_real_wins"],
                "discards_ranked": discard_options
            }
        else:
            # 未进入叫听态，计算 1-向听最大有效进张
            shanten_choices = cls._find_best_shanten_discard(hand_counts, unique_hand_tiles, que_suit_start, num_fixed, remaining_counts)
            if shanten_choices:
                best = shanten_choices[0]
                return {
                    "phase": "1_SHANTEN_IMPROVEMENT",
                    "shanten": 1,
                    "message": f"当前为 1 进听，推荐打【{best['tile_name']}】，有效进张最大 ({best['total_real_wins']}张)，进张后即可叫听！",
                    "recommended_discard": best["tile"],
                    "recommended_discard_name": best["tile_name"],
                    "best_waits": best["waiting_details"],
                    "total_real_wins": best["total_real_wins"],
                    "discards_ranked": shanten_choices
                }
            else:
                shanten = cls._calculate_shanten(hand_counts, num_fixed)
                return {
                    "phase": "SEARCHING_SHANTEN",
                    "shanten": shanten,
                    "message": f"手牌较散 ({shanten}进听以上)，建议保留搭子，打出孤张偏张。",
                    "recommended_discard": unique_hand_tiles[-1],
                    "recommended_discard_name": cls.tile_to_str(unique_hand_tiles[-1]),
                    "discards_ranked": []
                }

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
        """四川麻将标准胡牌判定 (对子 + 面子)"""
        num_melds_needed = 4 - num_fixed_melds
        total_cards = sum(hand_array)
        if total_cards != 2 + num_melds_needed * 3:
            return False

        # 尝试以每张牌作为雀头（对子）
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

        # 找到第一张不为 0 的牌
        idx = 0
        while idx < 27 and hand[idx] == 0:
            idx += 1
        if idx >= 27:
            return True

        # 尝试刻子 (3张相同)
        if hand[idx] >= 3:
            hand[idx] -= 3
            if cls._check_melds_recursive(hand, melds_left - 1):
                hand[idx] += 3
                return True
            hand[idx] += 3

        # 尝试顺子 (仅同花色连续 3 张，不可跨花色)
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
    def _calculate_shanten(hand_array: List[int], num_fixed: int) -> int:
        """简易向听数评估 (0进听=叫听，1进听，2进听)"""
        # 统计对子、面子数量
        pairs = sum(1 for c in hand_array if c >= 2)
        total = sum(hand_array)
        return max(1, 4 - num_fixed - (total // 3))

    @staticmethod
    def _score_que_discard(tile: int, hand_counts: List[int]) -> int:
        """定缺牌切出优先级评分：孤张 1/9 最优先打 (分值越小越优先)"""
        pos = tile % 9
        count = hand_counts[tile]
        is_edge = (pos == 0 or pos == 8)
        # 单张边张最优先打
        if count == 1:
            return 1 if is_edge else 2
        elif count == 2:
            return 3 if is_edge else 4
        return 5

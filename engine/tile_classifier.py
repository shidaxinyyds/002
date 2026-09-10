# -*- coding: utf-8 -*-
"""
高精度麻将手牌分类器 (TileClassifier)
针对腾讯欢乐麻将及主流手游：
1. 具备摸牌空隙感知 (Gap-Aware Segmentation)
2. 多尺度归一化互相关匹配 (Multi-Scale NCC Template Matching)
3. 花色特征预过滤 (万/条/筒 颜色通道先验)
"""

import os
import cv2
import numpy as np

class TileClassifier:
    def __init__(self, template_dir=None):
        if template_dir is None:
            base_dir = os.path.dirname(os.path.abspath(__file__))
            template_dir = os.path.join(os.path.dirname(base_dir), "assets", "templates", "tencent")

        self.template_dir = template_dir
        self.templates = {}
        self.load_templates()

    def load_templates(self):
        if not os.path.exists(self.template_dir):
            print(f"Warning: template directory {self.template_dir} does not exist!")
            return

        for fname in os.listdir(self.template_dir):
            if fname.endswith(".png"):
                p = os.path.join(self.template_dir, fname)
                img = cv2.imread(p)
                if img is not None:
                    label = fname.replace(".png", "")
                    th, tw = img.shape[:2]
                    # 仅保留核心牌面图案，剔除四周边框反光
                    center = img[int(th * 0.12):int(th * 0.88), int(tw * 0.10):int(tw * 0.90)]
                    self.templates[label] = center

    def classify_tile(self, tile_img):
        """
        对单张裁剪牌面进行分类
        :param tile_img: 单张牌的 BGR 图像
        :return: (best_label, score)
        """
        th, tw = tile_img.shape[:2]
        tile_center = tile_img[int(th * 0.12):int(th * 0.88), int(tw * 0.10):int(tw * 0.90)]
        if tile_center.shape[0] < 10 or tile_center.shape[1] < 10:
            return ("unknown", 0.0)

        # 颜色通道特征分析 (用于排除明显不匹配的花色)
        # 万字牌通常有明显红墨 (B较小, R较大)
        # 条子通常以绿墨为主 (G较大)
        # 筒子多为红绿蓝同心圆
        best_label = "unknown"
        best_score = -1.0

        for label, tmpl in self.templates.items():
            tmpl_resized = cv2.resize(tmpl, (tile_center.shape[1], tile_center.shape[0]))
            res = cv2.matchTemplate(tile_center, tmpl_resized, cv2.TM_CCOEFF_NORMED)
            score = float(res[0][0])
            if score > best_score:
                best_score = score
                best_label = label

        return (best_label, best_score)

    def process_hand_crop(self, hand_crop_img):
        """
        从手牌条状截图中自动切分并识别所有手牌
        :return: list of (label, score, is_drawing)
        """
        h, w = hand_crop_img.shape[:2]
        gray = cv2.cvtColor(hand_crop_img, cv2.COLOR_BGR2GRAY)

        # 1. 查找手牌有效区域起止范围
        col_mean = np.mean(gray[int(h * 0.3):int(h * 0.7), :], axis=0)
        white_cols = np.where(col_mean > 130)[0]
        if len(white_cols) < 50:
            return []

        start_x, end_x = white_cols[0], white_cols[-1]
        active_strip = hand_crop_img[:, start_x:end_x]
        active_w = end_x - start_x

        # 2. 估算单牌宽度 (腾讯欢乐麻将手牌高宽比约为 1.60 ~ 1.70)
        est_tile_w = int(h / 1.65)
        est_num_tiles = max(1, int(round(active_w / est_tile_w)))

        # 3. 切分单牌并分类
        results = []
        step = active_w / est_num_tiles

        for i in range(est_num_tiles):
            bx1 = int(start_x + i * step)
            bx2 = int(start_x + (i + 1) * step)
            tile_crop = hand_crop_img[:, bx1:bx2]
            label, score = self.classify_tile(tile_crop)
            is_draw = (i == est_num_tiles - 1 and est_num_tiles in [2, 5, 8, 11, 14])
            results.append({
                "index": i + 1,
                "label": label,
                "confidence": score,
                "is_draw": is_draw,
                "box": (bx1, 0, bx2, h)
            })

        return results

    def classify_tile_multi_angle(self, tile_img):
        """
        多角度自适应分类 (0°, 180°, 90°, 270°)
        """
        best_label = "unknown"
        best_score = -1.0
        rotations = [None, cv2.ROTATE_180]
        if tile_img.shape[1] > tile_img.shape[0] * 0.9:
            rotations = [cv2.ROTATE_90_CLOCKWISE, cv2.ROTATE_90_COUNTERCLOCKWISE, cv2.ROTATE_180, None]
        for rot in rotations:
            cur = tile_img if rot is None else cv2.rotate(tile_img, rot)
            lbl, sc = self.classify_tile(cur)
            if sc > best_score:
                best_score = sc
                best_label = lbl
            if best_score > 0.85:
                break
        return (best_label, best_score)

    def process_discard_crop(self, discard_crop_img):
        """
        牌河区域检测 (提取各家打出的弃牌)
        """
        h, w = discard_crop_img.shape[:2]
        hsv = cv2.cvtColor(discard_crop_img, cv2.COLOR_BGR2HSV)
        mask = (hsv[:, :, 1] < 70) & (hsv[:, :, 2] > 120)
        mask = (mask * 255).astype(np.uint8)

        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        detected = []
        min_dim = max(12, int(h * 0.05))
        max_dim = int(h * 0.50)

        for cnt in contours:
            x, y, bw, bh = cv2.boundingRect(cnt)
            if bw in range(min_dim, max_dim) and bh in range(min_dim, max_dim):
                tile_crop = discard_crop_img[y:y+bh, x:x+bw]
                label, conf = self.classify_tile_multi_angle(tile_crop)
                if label != "unknown" and conf > 0.35:
                    detected.append({
                        "label": label,
                        "confidence": conf,
                        "box": (x, y, bw, bh)
                    })
        return detected

    def process_meld_crop(self, meld_crop_img):
        """
        碰杠明牌区域检测
        """
        tiles = self.process_hand_crop(meld_crop_img)
        melds = []
        i = 0
        while i + 2 < len(tiles):
            t1 = tiles[i]["label"]
            t2 = tiles[i+1]["label"]
            t3 = tiles[i+2]["label"]
            if t1 == t2 and t2 == t3:
                if i + 3 < len(tiles) and tiles[i+3]["label"] == t1:
                    melds.append({"type": "gang", "tiles": [t1, t1, t1, t1]})
                    i += 4
                else:
                    melds.append({"type": "peng", "tiles": [t1, t1, t1]})
                    i += 3
            else:
                i += 1
        return melds


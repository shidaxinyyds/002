package com.antigravity.mahjong.profile

import org.json.JSONObject

/**
 * 麻将游戏区域配置文件数据模型
 * 严格遵从项目方案 Section 5.4 规范
 */
data class RectRatio(
    var xRatio: Float = 0f,
    var yRatio: Float = 0f,
    var wRatio: Float = 1f,
    var hRatio: Float = 1f
) {
    fun toJSONObject(): JSONObject {
        return JSONObject().apply {
            put("xRatio", xRatio.toDouble())
            put("yRatio", yRatio.toDouble())
            put("wRatio", wRatio.toDouble())
            put("hRatio", hRatio.toDouble())
        }
    }

    companion object {
        fun fromJSONObject(obj: JSONObject): RectRatio {
            return RectRatio(
                xRatio = obj.optDouble("xRatio", 0.0).toFloat(),
                yRatio = obj.optDouble("yRatio", 0.0).toFloat(),
                wRatio = obj.optDouble("wRatio", 1.0).toFloat(),
                hRatio = obj.optDouble("hRatio", 1.0).toFloat()
            )
        }
    }
}

data class GameProfile(
    var name: String = "默认四川麻将配置",
    var screenWidth: Int = 2400,
    var screenHeight: Int = 1080,
    var handRegion: RectRatio = RectRatio(0.045f, 0.815f, 0.910f, 0.175f),
    var discardRegion: RectRatio = RectRatio(0.300f, 0.280f, 0.400f, 0.400f),
    var meldRegion: RectRatio = RectRatio(0.620f, 0.820f, 0.330f, 0.165f)
) {
    fun toJSONString(): String {
        val root = JSONObject()
        root.put("name", name)

        val screen = JSONObject()
        screen.put("width", screenWidth)
        screen.put("height", screenHeight)
        root.put("screen", screen)

        val regions = JSONObject()
        regions.put("hand", handRegion.toJSONObject())
        regions.put("discard", discardRegion.toJSONObject())
        regions.put("melds", meldRegion.toJSONObject())
        root.put("regions", regions)

        return root.toString(2)
    }

    companion object {
        fun fromJSONString(jsonStr: String): GameProfile {
            val root = JSONObject(jsonStr)
            val profile = GameProfile()
            profile.name = root.optString("name", "默认配置")

            val screen = root.optJSONObject("screen")
            if (screen != null) {
                profile.screenWidth = screen.optInt("width", 2400)
                profile.screenHeight = screen.optInt("height", 1080)
            }

            val regions = root.optJSONObject("regions")
            if (regions != null) {
                regions.optJSONObject("hand")?.let { profile.handRegion = RectRatio.fromJSONObject(it) }
                regions.optJSONObject("discard")?.let { profile.discardRegion = RectRatio.fromJSONObject(it) }
                regions.optJSONObject("melds")?.let { profile.meldRegion = RectRatio.fromJSONObject(it) }
            }
            return profile
        }
    }
}

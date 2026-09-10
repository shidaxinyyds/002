package com.antigravity.mahjong.profile

import android.content.Context
import android.content.SharedPreferences

/**
 * 动态区域配置管理器 (RegionManager)
 * 严格遵从项目方案 Section 5 核心设计要求：
 * 1. 管理游戏内动态框选区域 (手牌/牌河/碰杠)
 * 2. 支持当前配置本地持久化与读取
 * 3. 运行中支持随时点击“重新设置区域”并保存覆盖
 */
class RegionManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("mahjong_profiles", Context.MODE_PRIVATE)
    var currentProfile: GameProfile = GameProfile()
        private set

    init {
        loadActiveProfile()
    }

    fun loadActiveProfile(): GameProfile {
        val savedJson = prefs.getString(KEY_ACTIVE_PROFILE, null)
        currentProfile = if (savedJson != null) {
            try {
                GameProfile.fromJSONString(savedJson)
            } catch (e: Exception) {
                GameProfile()
            }
        } else {
            GameProfile()
        }
        return currentProfile
    }

    fun saveProfile(profile: GameProfile) {
        currentProfile = profile
        prefs.edit().putString(KEY_ACTIVE_PROFILE, profile.toJSONString()).apply()
    }

    fun updateHandRegion(x: Float, y: Float, w: Float, h: Float) {
        currentProfile.handRegion = RectRatio(x, y, w, h)
        saveProfile(currentProfile)
    }

    fun updateDiscardRegion(x: Float, y: Float, w: Float, h: Float) {
        currentProfile.discardRegion = RectRatio(x, y, w, h)
        saveProfile(currentProfile)
    }

    fun updateMeldRegion(x: Float, y: Float, w: Float, h: Float) {
        currentProfile.meldRegion = RectRatio(x, y, w, h)
        saveProfile(currentProfile)
    }

    companion object {
        private const val KEY_ACTIVE_PROFILE = "active_game_profile"

        @Volatile
        private var instance: RegionManager? = null

        fun getInstance(context: Context): RegionManager {
            return instance ?: synchronized(this) {
                instance ?: RegionManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

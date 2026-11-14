package com.pyeonangil.data

import android.content.Context
import com.pyeonangil.engine.FamilyProfile

/** 가족 민감도와 지난 편안함 점수를 기기에 저장한다. 개인 정보는 저장하지 않는다. */
class ProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("family_profile", Context.MODE_PRIVATE)

    fun load(): FamilyProfile = FamilyProfile(
        sensitivity = prefs.getFloat(KEY_SENSITIVITY, 1.0f),
        lastComfortScore = if (prefs.contains(KEY_LAST_SCORE)) prefs.getInt(KEY_LAST_SCORE, 0) else null,
    )

    fun save(profile: FamilyProfile) {
        prefs.edit().apply {
            putFloat(KEY_SENSITIVITY, profile.sensitivity)
            profile.lastComfortScore?.let { putInt(KEY_LAST_SCORE, it) }
        }.apply()
    }

    private companion object {
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_LAST_SCORE = "last_score"
    }
}

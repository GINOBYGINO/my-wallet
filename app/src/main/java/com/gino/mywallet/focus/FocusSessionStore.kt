package com.gino.mywallet.focus

import android.content.Context
import com.gino.mywallet.domain.FocusMode
import com.gino.mywallet.domain.FocusRules
import com.gino.mywallet.domain.ShortFocusDuration

/**
 * 進行中專注場次落盤，避免行程被殺後 START_STICKY／重開 App 歸零。
 */
object FocusSessionStore {
    private const val PREFS = "focus_session_v1"
    private const val KEY_ACTIVE = "active"

    data class Snapshot(
        val phase: FocusPhase,
        val mode: FocusMode,
        val shortMinutes: Int?,
        val topic: String,
        val category: String,
        val wallStartAt: Long,
        val accumulatedMs: Long,
        val cashSteps: List<Int>,
        val ringSeed: Int,
        val leftAppPaused: Boolean
    )

    fun save(context: Context, snap: Snapshot) {
        val phaseOk = when (snap.phase) {
            FocusPhase.Running, FocusPhase.Paused, FocusPhase.ShortComplete -> true
            else -> false
        }
        if (!phaseOk) {
            clear(context)
            return
        }
        val safeMs = FocusRules.sanitizeAccumulatedMs(snap.accumulatedMs, snap.wallStartAt)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString("phase", snap.phase.name)
            .putString("mode", snap.mode.name)
            .putInt("shortMinutes", snap.shortMinutes ?: -1)
            .putString("topic", snap.topic)
            .putString("category", snap.category)
            .putLong("wallStartAt", snap.wallStartAt)
            .putLong("accumulatedMs", safeMs)
            .putString("cashSteps", snap.cashSteps.joinToString(","))
            .putInt("ringSeed", snap.ringSeed)
            .putBoolean("leftAppPaused", snap.leftAppPaused)
            .apply()
    }

    fun load(context: Context): Snapshot? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_ACTIVE, false)) return null
        val phase = runCatching {
            FocusPhase.valueOf(p.getString("phase", null) ?: return null)
        }.getOrNull() ?: return null
        if (phase != FocusPhase.Running &&
            phase != FocusPhase.Paused &&
            phase != FocusPhase.ShortComplete
        ) {
            return null
        }
        val mode = runCatching {
            FocusMode.valueOf(p.getString("mode", null) ?: return null)
        }.getOrNull() ?: return null
        val shortRaw = p.getInt("shortMinutes", -1)
        val shortMinutes = shortRaw.takeIf { it > 0 }
        val steps = p.getString("cashSteps", "")
            .orEmpty()
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
        val wallStartAt = p.getLong("wallStartAt", System.currentTimeMillis())
        val rawMs = p.getLong("accumulatedMs", 0L)
        return Snapshot(
            phase = phase,
            mode = mode,
            shortMinutes = shortMinutes,
            topic = p.getString("topic", "").orEmpty(),
            category = p.getString("category", "專注").orEmpty().ifBlank { "專注" },
            wallStartAt = wallStartAt,
            accumulatedMs = FocusRules.sanitizeAccumulatedMs(rawMs, wallStartAt),
            cashSteps = steps,
            ringSeed = p.getInt("ringSeed", 0),
            leftAppPaused = p.getBoolean("leftAppPaused", false)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun shortDurationOf(minutes: Int?): ShortFocusDuration? =
        minutes?.let { runCatching { ShortFocusDuration.fromMinutes(it) }.getOrNull() }
}

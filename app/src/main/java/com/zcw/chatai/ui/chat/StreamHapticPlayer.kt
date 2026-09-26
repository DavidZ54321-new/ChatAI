package com.zcw.chatai.ui.chat

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 很短的一下轻触，接近 ChatGPT 吐字时的 tick。没有马达（模拟器）时直接返回。
 * 调用方只在 [StreamHapticClock] 判定该震、且用户开着开关时调用。
 */
class StreamHapticPlayer(context: Context) {
    private val vibrator: Vibrator? = context.applicationContext.systemVibrator()

    fun tick() {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(tickEffect(vibrator))
    }

    private fun tickEffect(vibrator: Vibrator): VibrationEffect {
        if (Build.VERSION.SDK_INT >= 31 &&
            vibrator.arePrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK).firstOrNull() == true
        ) {
            return VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, TICK_SCALE)
                .compose()
        }
        return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    }

    private companion object {
        /** 0..1，比系统默认 tick 再轻一档，避免像通知。 */
        const val TICK_SCALE = 0.45f
    }
}

private fun Context.systemVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= 31) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        getSystemService(Vibrator::class.java)
    }

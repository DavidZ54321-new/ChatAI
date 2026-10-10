package com.zcw.chatai.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract

/**
 * 拍照并写入调用方给的 Uri。
 *
 * `ACTION_IMAGE_CAPTURE` 不指定镜头时，不少系统相机会先开前置（人像）。
 * [rearLensExtras] 是各家相机认的非公开附加字段：Camera1 的后置是 0，并明确关掉前置。
 */
object CapturePhoto {
    const val EXTRA_CAMERA_FACING = "android.intent.extras.CAMERA_FACING"
    const val EXTRA_USE_FRONT_CAMERA = "android.intent.extra.USE_FRONT_CAMERA"
    const val EXTRA_LENS_FACING_FRONT = "android.intent.extras.LENS_FACING_FRONT"

    /** 相机 Intent 的附加字段。类型写死，避免 `Map<String, Any>` 在写入时静默丢掉新类型。 */
    sealed class LensExtra {
        abstract val key: String

        data class IntValue(override val key: String, val value: Int) : LensExtra()
        data class BoolValue(override val key: String, val value: Boolean) : LensExtra()
    }

    fun rearLensExtras(): List<LensExtra> = listOf(
        LensExtra.IntValue(EXTRA_CAMERA_FACING, 0),
        LensExtra.BoolValue(EXTRA_USE_FRONT_CAMERA, false),
        LensExtra.IntValue(EXTRA_LENS_FACING_FRONT, 0),
    )
}

class CapturePhotoContract : ActivityResultContract<Uri, Boolean>() {
    override fun createIntent(context: Context, input: Uri): Intent {
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, input)
            CapturePhoto.rearLensExtras().forEach { extra ->
                when (extra) {
                    is CapturePhoto.LensExtra.IntValue -> putExtra(extra.key, extra.value)
                    is CapturePhoto.LensExtra.BoolValue -> putExtra(extra.key, extra.value)
                }
            }
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
        resultCode == Activity.RESULT_OK
}

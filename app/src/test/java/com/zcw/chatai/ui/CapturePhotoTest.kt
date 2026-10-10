package com.zcw.chatai.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CapturePhotoTest {

    @Test
    fun rearLensExtrasPreferTheBackCamera() {
        val extras = CapturePhoto.rearLensExtras()
        assertEquals(
            listOf(
                CapturePhoto.LensExtra.IntValue(CapturePhoto.EXTRA_CAMERA_FACING, 0),
                CapturePhoto.LensExtra.BoolValue(CapturePhoto.EXTRA_USE_FRONT_CAMERA, false),
                CapturePhoto.LensExtra.IntValue(CapturePhoto.EXTRA_LENS_FACING_FRONT, 0),
            ),
            extras,
        )
    }
}

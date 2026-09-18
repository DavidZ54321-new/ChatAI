package com.zcw.chatai.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTypographyTest {

    @Test
    fun bodyTextScalesWithWindowWidth() {
        val typography = ChatTypography.Default.forWindowWidth(360.dp)

        assertEquals(360f * ChatTypography.BODY_TEXT_FRACTION, typography.messageBody.fontSize.value, 0.01f)
    }

    @Test
    fun lineHeightKeepsBodyRatio() {
        val typography = ChatTypography.Default.forWindowWidth(360.dp)

        assertEquals(
            360f * ChatTypography.BODY_TEXT_FRACTION * ChatTypography.BODY_LINE_HEIGHT_RATIO,
            typography.messageBody.lineHeight.value,
            0.01f,
        )
    }

    @Test
    fun unspecifiedWindowFallsBackToDefault() {
        val typography = ChatTypography.Default.forWindowWidth(Dp.Unspecified)

        assertEquals(18f, typography.messageBody.fontSize.value, 0.01f)
        assertEquals(29f, typography.messageBody.lineHeight.value, 0.01f)
    }

    @Test
    fun zeroWindowFallsBackToDefault() {
        val typography = ChatTypography.Default.forWindowWidth(0.dp)

        assertEquals(18f, typography.messageBody.fontSize.value, 0.01f)
    }
}

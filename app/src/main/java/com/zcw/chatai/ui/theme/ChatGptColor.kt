package com.zcw.chatai.ui.theme

import androidx.compose.ui.graphics.Color

// ChatGPT / OpenAI 产品面：单色（黑 / 白 / 灰），chrome 里零色相。
// 实测 openai.com CSS token：primary #000000、background #ffffff、surface #f1f1f1、
// border #dddee1、muted text #767881；唯一颜色是 #3b82f680 的蓝色 focus ring（本主题不用于 chrome）。
val GptBlack = Color(0xFF000000)
val GptWhite = Color(0xFFFFFFFF)
val GptSurface = Color(0xFFF1F1F1)
val GptSurfaceStrong = Color(0xFFE5E5E5)
val GptBorder = Color(0xFFDDDEE1)
val GptBorderSoft = Color(0xFFECECEC)
val GptBodyText = Color(0xFF333333)
val GptMuted = Color(0xFF767881)
val GptMutedSoft = Color(0xFF8E8E93)
val GptCodeBackground = Color(0xFF0D0D0D)
val GptCodeButtonBackground = Color(0xFF2F2F2F)
val GptRedSoft = Color(0xFFFDE7E7)

// 语义色（状态，不是 chrome）：成功复用品牌绿，警告琥珀，错误红。
val GptSuccess = Color(0xFF10A37F)
val GptAmber = Color(0xFFF5A623)
val GptRed = Color(0xFFEF4146)

// 深色面（暗色原生）：画布用纯黑（真实 ChatGPT 是 #212121，本 App 为高对比改纯黑）；
// #171717 下沉 / #2f2f2f 抬升 / #383838 最高。
val GptDarkCanvas = Color(0xFF000000)
val GptDarkSunken = Color(0xFF171717)
val GptDarkRaised = Color(0xFF2F2F2F)
val GptDarkHigh = Color(0xFF383838)
val GptDarkBorder = Color(0xFF3E3E42)
val GptDarkText = Color(0xFFECECEC)
val GptDarkMuted = Color(0xFFB4B4B4)
val GptDarkSoft = Color(0xFF8F8F8F)
val GptDarkAmberContainer = Color(0xFF3D2F1C)
val GptDarkOnAmberContainer = Color(0xFFF6DCC0)
val GptDarkError = Color(0xFFE08585)
val GptDarkOnError = Color(0xFF3A1414)
val GptDarkErrorContainer = Color(0xFF4A1F1F)

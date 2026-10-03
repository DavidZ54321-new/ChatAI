package com.zcw.chatai.data.image

import com.zcw.chatai.data.image.qwen.QwenImageModelList
import com.zcw.chatai.data.image.qwen.QwenModelListOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenImageModelListTest {

    @Test
    fun urlUsesOriginAndImageFilters() {
        val url = QwenImageModelList.url(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            pageNo = 2,
        )
        assertEquals(
            "https://dashscope.aliyuncs.com/api/v1/models?capabilities=IG&providers=qwen&page_no=2&page_size=100",
            url,
        )
    }

    @Test
    fun workspaceHostKeepsItsOwnOrigin() {
        val url = QwenImageModelList.url(
            "https://ws.cn-beijing.maas.aliyuncs.com/api/v1",
            pageNo = 1,
        )
        assertTrue(url!!.startsWith("https://ws.cn-beijing.maas.aliyuncs.com/api/v1/models?"))
    }

    @Test
    fun blankBaseUrlHasNoEndpoint() {
        assertNull(QwenImageModelList.url("  ", 1))
    }

    @Test
    fun parseKeepsImageInputFlagAndDropsOtherCapabilities() {
        val raw = """
            {
              "success": true,
              "output": {
                "total": 3,
                "page_no": 1,
                "page_size": 100,
                "models": [
                  {
                    "model": "qwen-image-max",
                    "name": "Qwen-Image-Max",
                    "capabilities": ["IG"],
                    "inference_metadata": {"request_modality": ["Text"], "response_modality": ["Image"]}
                  },
                  {
                    "model": "qwen-image-3.0-pro",
                    "name": "",
                    "capabilities": ["IG"],
                    "inference_metadata": {"request_modality": ["Text", "Image"]}
                  },
                  {
                    "model": "wan2.7-image-pro",
                    "name": "万相",
                    "capabilities": ["VG"]
                  }
                ]
              }
            }
        """.trimIndent()
        val page = QwenImageModelList.parse(raw) as QwenModelListOutcome.Page
        assertEquals(3, page.total)
        assertEquals(2, page.models.size)
        assertEquals("qwen-image-max", page.models[0].id)
        assertEquals("Qwen-Image-Max", page.models[0].label)
        assertFalse(page.models[0].acceptsImageInput)
        assertEquals("qwen-image-3.0-pro", page.models[1].label)
        assertTrue(page.models[1].acceptsImageInput)
    }

    @Test
    fun missingModalityStillAcceptsImages() {
        val raw = """
            {"success":true,"output":{"total":1,"models":[{"model":"qwen-image-2.0","capabilities":["IG"]}]}}
        """.trimIndent()
        val page = QwenImageModelList.parse(raw) as QwenModelListOutcome.Page
        assertTrue(page.models.single().acceptsImageInput)
    }

    @Test
    fun businessFailure() {
        val outcome = QwenImageModelList.parse("""{"code":"InvalidApiKey","message":"无效的令牌"}""")
        val failure = outcome as QwenModelListOutcome.Failure
        assertEquals("无效的令牌", failure.message)
    }
}

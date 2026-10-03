package com.zcw.chatai.data.image

import com.zcw.chatai.data.image.openai.OpenAiImageModels
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiImageModelsTest {

    @Test
    fun keepsGptImageIdsAndDropsChatAndDalle() {
        val filtered = OpenAiImageModels.filter(
            listOf(
                "gpt-6-astra",
                " gpt-image-2.5-sunburst ",
                "gpt-image-2.5-sunburst",
                "chatgpt-image-latest",
                "dall-e-3",
                "dall-e-2",
                "",
            ),
        )
        assertEquals(
            listOf("gpt-image-2.5-sunburst", "chatgpt-image-latest"),
            filtered.map { it.id },
        )
        assertEquals(true, filtered.all { it.acceptsImageInput })
    }
}

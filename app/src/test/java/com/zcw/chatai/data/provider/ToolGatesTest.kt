package com.zcw.chatai.data.provider

import com.zcw.chatai.data.web.WebTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGatesTest {

    @Test
    fun globeOffInjectsNothing() {
        assertEquals(
            emptyList<String>(),
            ToolGates.enabledNames(
                webSearchOn = false,
                policy = ToolPolicy(),
                textAvailable = true,
                imageAvailable = true,
            ),
        )
    }

    @Test
    fun defaultsInjectEveryAvailableTool() {
        assertEquals(
            listOf(
                WebTools.SEARCH,
                WebTools.SEARCH_IMAGES,
                WebTools.FIND_SIMILAR_IMAGES,
                WebTools.FETCH,
            ),
            names(text = true, image = true),
        )
    }

    @Test
    fun offGatesDropThatToolOnly() {
        assertEquals(
            listOf(WebTools.FETCH),
            names(
                text = true,
                image = true,
                search = ToolFollow.OFF,
                imageSearch = ToolFollow.OFF,
                imageSimilar = ToolFollow.OFF,
            ),
        )
    }

    @Test
    fun imageToolsNeedQwenEvenWhenFollowing() {
        assertEquals(
            listOf(WebTools.SEARCH, WebTools.FETCH),
            names(text = true, image = false),
        )
    }

    @Test
    fun fetchDoesNotNeedASearchBackend() {
        assertEquals(
            listOf(WebTools.FETCH),
            names(text = false, image = false),
        )
    }

    @Test
    fun hostedSearchIsJustTextAvailable() {
        assertEquals(
            listOf(WebTools.FETCH),
            names(text = true, image = false, search = ToolFollow.OFF),
        )
        assertTrue(
            ToolGates.anyAvailable(
                policy = ToolPolicy(
                    fetch = ToolFollow.OFF,
                    imageSearch = ToolFollow.OFF,
                    imageSimilar = ToolFollow.OFF,
                ),
                textAvailable = true,
                imageAvailable = false,
            ),
        )
    }

    @Test
    fun globeIsUnavailableWhenNothingCanRun() {
        assertFalse(
            ToolGates.anyAvailable(
                policy = ToolPolicy(fetch = ToolFollow.OFF),
                textAvailable = false,
                imageAvailable = false,
            ),
        )
        assertFalse(
            ToolGates.anyAvailable(
                policy = ToolPolicy(
                    search = ToolFollow.OFF,
                    fetch = ToolFollow.OFF,
                    imageSearch = ToolFollow.OFF,
                    imageSimilar = ToolFollow.OFF,
                ),
                textAvailable = true,
                imageAvailable = true,
            ),
        )
    }

    private fun names(
        text: Boolean,
        image: Boolean,
        search: ToolFollow = ToolFollow.FOLLOW,
        fetch: ToolFollow = ToolFollow.FOLLOW,
        imageSearch: ToolFollow = ToolFollow.FOLLOW,
        imageSimilar: ToolFollow = ToolFollow.FOLLOW,
    ): List<String> = ToolGates.enabledNames(
        webSearchOn = true,
        policy = ToolPolicy(
            search = search,
            fetch = fetch,
            imageSearch = imageSearch,
            imageSimilar = imageSimilar,
        ),
        textAvailable = text,
        imageAvailable = image,
    )
}

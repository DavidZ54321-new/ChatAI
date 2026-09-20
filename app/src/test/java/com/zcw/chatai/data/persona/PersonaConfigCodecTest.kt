package com.zcw.chatai.data.persona

import com.zcw.chatai.data.prefs.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaConfigCodecTest {

    @Test
    fun roundTripKeepsAllEntries() {
        val personas = mapOf(
            "p1" to PersonaEntry(
                name = "翻译官",
                systemPrompt = "translate everything",
                temperature = 0.3,
                reasoningEffort = ReasoningEffort.LOW,
                maxTokens = 2048,
                extraParams = "{\"top_k\":20}",
            ),
            PersonaConfigCodec.DEFAULT_ID to PersonaEntry(name = PersonaConfigCodec.DEFAULT_NAME),
        )
        assertEquals(personas, PersonaConfigCodec.decode(PersonaConfigCodec.encode(personas)))
    }

    @Test
    fun malformedOrMissingJsonDecodesEmpty() {
        assertTrue(PersonaConfigCodec.decode(null).isEmpty())
        assertTrue(PersonaConfigCodec.decode("").isEmpty())
        assertTrue(PersonaConfigCodec.decode("   ").isEmpty())
        assertTrue(PersonaConfigCodec.decode("{").isEmpty())
        assertTrue(PersonaConfigCodec.decode("[1,2,3]").isEmpty())
    }

    @Test
    fun unknownFieldsAreIgnoredAndMissingFieldsFallBack() {
        val decoded = PersonaConfigCodec.decode(
            """{"p1":{"name":"A","future_field":1}}""",
        )
        assertEquals(PersonaEntry(name = "A"), decoded.getValue("p1"))
    }

    @Test
    fun unknownReasoningEffortFallsBackToDefault() {
        val decoded = PersonaConfigCodec.decode(
            """{"p1":{"name":"A","reasoningEffort":"ULTRA"}}""",
        )
        assertEquals(ReasoningEffort.FOLLOW_DEFAULT, decoded.getValue("p1").reasoningEffort)
    }

    @Test
    fun legacyGlobalsBecomeDefaultPersona() {
        val migrated = PersonaConfigCodec.fromLegacy(
            systemPrompt = "be nice",
            temperature = 0.7,
            reasoningEffort = ReasoningEffort.OFF,
            maxTokens = 1024,
            extraParams = "{\"top_k\":20}",
        )
        assertEquals(setOf(PersonaConfigCodec.DEFAULT_ID), migrated.keys)
        val entry = migrated.getValue(PersonaConfigCodec.DEFAULT_ID)
        assertEquals(PersonaConfigCodec.DEFAULT_NAME, entry.name)
        assertEquals("be nice", entry.systemPrompt)
        assertEquals(0.7, entry.temperature)
        assertEquals(ReasoningEffort.OFF, entry.reasoningEffort)
        assertEquals(1024, entry.maxTokens)
    }

    @Test
    fun resolveActiveIdFallsBackToFirstEntry() {
        val personas = mapOf("a" to PersonaEntry("A"), "b" to PersonaEntry("B"))
        assertEquals("b", PersonaConfigCodec.resolveActiveId(personas, "b"))
        assertEquals("a", PersonaConfigCodec.resolveActiveId(personas, "deleted"))
        assertEquals("a", PersonaConfigCodec.resolveActiveId(personas, null))
        assertEquals(
            PersonaConfigCodec.DEFAULT_ID,
            PersonaConfigCodec.resolveActiveId(emptyMap(), "anything"),
        )
    }

    @Test
    fun resolveEffectivePrefersBindingThenActive() {
        val personas = mapOf(
            "active" to PersonaEntry("Active", systemPrompt = "active prompt"),
            "bound" to PersonaEntry("Bound", systemPrompt = "bound prompt"),
        )
        assertEquals(
            "bound prompt",
            PersonaConfigCodec.resolveEffective(personas, "active", "bound").systemPrompt,
        )
        // 空串 = 跟随激活（与 provider_id 同语义）
        assertEquals(
            "active prompt",
            PersonaConfigCodec.resolveEffective(personas, "active", "").systemPrompt,
        )
        assertEquals(
            "active prompt",
            PersonaConfigCodec.resolveEffective(personas, "active", null).systemPrompt,
        )
        // 绑定了已删除的角色 → 回退激活
        assertEquals(
            "active prompt",
            PersonaConfigCodec.resolveEffective(personas, "active", "deleted").systemPrompt,
        )
    }

    @Test
    fun resolveEffectiveWithEmptyTableDegradesToBlankEntry() {
        val entry = PersonaConfigCodec.resolveEffective(emptyMap(), "nothing", "also-nothing")
        assertEquals(PersonaConfigCodec.DEFAULT_NAME, entry.name)
        assertEquals("", entry.systemPrompt)
        assertNull(entry.temperature)
    }

    @Test
    fun validateNameRejectsBlankAndTooLong() {
        assertEquals("请输入角色名称", PersonaConfigCodec.validateName("   "))
        assertEquals(
            "角色名称最多 24 个字",
            PersonaConfigCodec.validateName("一".repeat(25)),
        )
        assertNull(PersonaConfigCodec.validateName("翻译官"))
    }
}

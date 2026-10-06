package dev.past.jetbrains.junie

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JunieConsentTest {
    @Test
    fun `nothing is sent before the person answers`() {
        // An IDE that never asked stores nothing: that must read as not asked, and send nothing.
        assertEquals(JunieConsent.NotAsked, JunieConsent.parse(null))
        assertFalse(JunieConsent.parse(null).sends)
    }

    @Test
    fun `an answer it cannot read sends nothing`() {
        assertFalse(JunieConsent.parse("").sends)
        assertFalse(JunieConsent.parse("true").sends)
        assertFalse(JunieConsent.parse("allowed").sends)
    }

    @Test
    fun `only a yes sends, and every answer reads back as given`() {
        assertTrue(JunieConsent.Allowed.sends)
        assertFalse(JunieConsent.Refused.sends)
        for (answer in JunieConsent.entries) assertEquals(answer, JunieConsent.parse(answer.name))
    }
}

package com.example.spaceinvaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRulesTest {

    @Test
    fun comboRank_abaixoDe3_eC() {
        assertEquals("C", GameRules.comboRank(0))
        assertEquals("C", GameRules.comboRank(2))
    }

    @Test
    fun comboRank_entre3e5_eB() {
        assertEquals("B", GameRules.comboRank(3))
        assertEquals("B", GameRules.comboRank(5))
    }

    @Test
    fun comboRank_entre6e9_eA() {
        assertEquals("A", GameRules.comboRank(6))
        assertEquals("A", GameRules.comboRank(9))
    }

    @Test
    fun comboRank_10ouMais_eS() {
        assertEquals("S", GameRules.comboRank(10))
        assertEquals("S", GameRules.comboRank(50))
    }

    @Test
    fun rankValue_ordenaPostos() {
        assertTrue(GameRules.rankValue("S") > GameRules.rankValue("A"))
        assertTrue(GameRules.rankValue("A") > GameRules.rankValue("B"))
        assertTrue(GameRules.rankValue("B") > GameRules.rankValue("C"))
    }

    @Test
    fun bestRankLabel_converteIndiceEmRotulo() {
        assertEquals("C", GameRules.bestRankLabel(0))
        assertEquals("B", GameRules.bestRankLabel(1))
        assertEquals("A", GameRules.bestRankLabel(2))
        assertEquals("S", GameRules.bestRankLabel(3))
    }

    @Test
    fun scoreMultiplier_cresceACada5DeCombo() {
        assertEquals(1, GameRules.scoreMultiplier(0))
        assertEquals(1, GameRules.scoreMultiplier(4))
        assertEquals(2, GameRules.scoreMultiplier(5))
        assertEquals(3, GameRules.scoreMultiplier(10))
    }

    @Test
    fun invaderBaseScore_valoresConhecidos() {
        assertEquals(30, GameRules.invaderBaseScore(0))
        assertEquals(40, GameRules.invaderBaseScore(2))
        assertEquals(60, GameRules.invaderBaseScore(4))
        assertEquals(15, GameRules.invaderBaseScore(8))
        assertEquals(20, GameRules.invaderBaseScore(99))
    }

    @Test
    fun shipLevelFor_sobeACada3PowerupsETravaEm5() {
        assertEquals(1, GameRules.shipLevelFor(0))
        assertEquals(1, GameRules.shipLevelFor(2))
        assertEquals(2, GameRules.shipLevelFor(3))
        assertEquals(3, GameRules.shipLevelFor(6))
        assertEquals(5, GameRules.shipLevelFor(12))
        assertEquals(5, GameRules.shipLevelFor(100))
    }
}

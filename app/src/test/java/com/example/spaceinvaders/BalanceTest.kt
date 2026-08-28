package com.example.spaceinvaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes dos valores de tuning em [Balance].
 *
 * Flutuantes usam tolerancia: a representacao float32 de 0.03f nao e exata e o
 * resultado varia levemente entre plataformas, entao comparar por igualdade
 * direta falharia em CI mesmo com codigo correto.
 */
class BalanceTest {

    private fun assertEqualsFloat(expected: Float, actual: Float, label: String) {
        assertEquals("$label (esperado $expected, obtido $actual)", expected, actual, 1e-3f)
    }

    // ---------- Cadencia de tiro ----------

    @Test
    fun fireCooldown_plasma_semUpgrade_semRapid_180ms() {
        assertEqualsFloat(0.18f, Balance.fireCooldown(0, 0, false), "PLASMA base")
    }

    @Test
    fun fireCooldown_spread_semUpgrade_semRapid_260ms() {
        assertEqualsFloat(0.26f, Balance.fireCooldown(1, 0, false), "SPREAD base")
    }

    @Test
    fun fireCooldown_laser_semUpgrade_semRapid_300ms() {
        assertEqualsFloat(0.3f, Balance.fireCooldown(2, 0, false), "LASER base")
    }

    @Test
    fun fireCooldown_missile_semUpgrade_semRapid_340ms() {
        assertEqualsFloat(0.34f, Balance.fireCooldown(3, 0, false), "MISSILE base")
    }

    @Test
    fun fireCooldown_rapid_deixaATodosDispararemMaisRapido() {
        for (w in 0..3) {
            assertTrue(
                "arma $w deve disparar mais rapido em rapid",
                Balance.fireCooldown(w, 0, true) < Balance.fireCooldown(w, 0, false)
            )
        }
    }

    @Test
    fun fireCooldown_plasma_cannonUpDecresceMaisQueAsDemais() {
        // PLASMA usa decremento 0.03 por nivel; as demaais usam 0.02.
        val plasmaGain = Balance.fireCooldown(0, 0, false) - Balance.fireCooldown(0, 2, false)
        val laserGain = Balance.fireCooldown(2, 0, false) - Balance.fireCooldown(2, 2, false)
        assertTrue("PLASMA 0.06 vs LASER 0.04: $plasmaGain vs $laserGain", plasmaGain > laserGain)
    }

    @Test
    fun fireCooldown_cadaUpgradeAceleraODisparo() {
        for (w in 0..3) {
            for (rapid in listOf(false, true)) {
                val c0 = Balance.fireCooldown(w, 0, rapid)
                val c1 = Balance.fireCooldown(w, 1, rapid)
                val c2 = Balance.fireCooldown(w, 2, rapid)
                assertTrue("arma $w rapid=$rapid nao acelera: $c0 -> $c1 -> $c2", c0 > c1 && c1 > c2)
            }
        }
    }

    @Test
    fun fireCooldown_plasmaRapidCannonUp2_atingeOPiso() {
        // 0.07f - 2*0.03f = 0.01f: o teto real de cannonUp toca o piso da cadencia.
        val v = Balance.fireCooldown(0, 2, true)
        assertEqualsFloat(Balance.MIN_FIRE_COOLDOWN, v, "PLASMA rapid cannonUp=2")
    }

    @Test
    fun fireCooldown_nuncaFicaAbaixoDoPisoMesmoComUpgradeAbsurdo() {
        // cannonUp acima do teto in-game (2) nao pode produzir disparo instantaneo.
        assertEqualsFloat(
            Balance.MIN_FIRE_COOLDOWN, Balance.fireCooldown(0, 100, true), "caso patologico"
        )
    }

    // ---------- Progressao de horda ----------

    @Test
    fun invaderBulletSpeed_cresceComWave() {
        val w1 = Balance.invaderBulletSpeed(1)
        val w10 = Balance.invaderBulletSpeed(10)
        assertTrue("onda 10 deve ser mais rapida: $w1 vs $w10", w10 > w1)
    }

    @Test
    fun invaderBulletSpeed_respeitaO tetoDe900() {
        assertEqualsFloat(900f, Balance.invaderBulletSpeed(1000), "teto")
        assertEqualsFloat(900f, Balance.invaderBulletSpeed(50), "acima do teto")
    }

    @Test
    fun invaderBulletSpeed_ondaUmUsaAValorBaseMaisPerWave() {
        // 500 + 1*18 = 518
        assertEqualsFloat(518f, Balance.invaderBulletSpeed(1), "onda 1")
    }

    // ---------- Constantes que o jogo depende ----------

    @Test
    fun vidaExtraNaoUltrapassaOMaximo() {
        assertEquals(5, Balance.MAX_LIVES)
        assertTrue("start deve ser <= max", Balance.STARTING_LIVES <= Balance.MAX_LIVES)
    }

    @Test
    fun precoDasSkins_estaEmOrdemEAPrimeiraEGratis() {
        assertEquals(3, Balance.SKIN_PRICES.size)
        assertEquals(0, Balance.SKIN_PRICES[0])
        assertTrue(Balance.SKIN_PRICES[1] > 0)
        assertTrue(Balance.SKIN_PRICES[2] > 0)
    }

    @Test
    fun capDeParticulasEPositivoEPowerupDropEntreZeroEUm() {
        assertTrue(Balance.PARTICLE_CAP > 0)
        assertTrue(Balance.POWERUP_DROP_CHANCE > 0f && Balance.POWERUP_DROP_CHANCE < 1f)
    }

    @Test
    fun campanhaMudaDeSetorAMaisFrequenciaQueBoss() {
        // Sector a cada 3 waves, boss a cada 4: o jogo deve passar por varios setores.
        assertTrue(Balance.WAVES_PER_SECTOR <= Balance.WAVES_PER_BOSS)
        assertTrue(Balance.WAVES_PER_BOSS > 1)
        assertTrue(Balance.HORDE_STARTS_AT_WAVE > 1)
    }
}

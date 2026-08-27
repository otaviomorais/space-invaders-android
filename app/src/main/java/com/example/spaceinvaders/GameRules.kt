package com.example.spaceinvaders

/**
 * Regras puras do jogo, sem dependencia de Android.
 * Extraidas de GameView para permitir testes de unidade e reduzir a god class.
 */
object GameRules {

    /** Posto do combo atual (S/A/B/C). */
    fun comboRank(combo: Int): String = when {
        combo >= 10 -> "S"
        combo >= 6 -> "A"
        combo >= 3 -> "B"
        else -> "C"
    }

    /** Valor numerico do posto, para comparar o melhor posto alcancado. */
    fun rankValue(rank: String): Int = when (rank) {
        "S" -> 3
        "A" -> 2
        "B" -> 1
        else -> 0
    }

    /** Rotulo do melhor posto salvo (0=C, 1=B, 2=A, 3=S). */
    fun bestRankLabel(bestRank: Int): String = when (bestRank) {
        3 -> "S"
        2 -> "A"
        1 -> "B"
        else -> "C"
    }

    /** Multiplicador de score/coins baseado no combo. */
    fun scoreMultiplier(combo: Int): Int = 1 + combo / 5

    /** Score base por variante de invasor. */
    fun invaderBaseScore(variant: Int): Int = when (variant) {
        0 -> 30
        2 -> 40
        3 -> 35
        4 -> 60
        5 -> 25
        6 -> 45
        7 -> 50
        8 -> 15
        9 -> 35
        10 -> 30
        else -> 20
    }

    /** Nivel da nave a partir do total de power-ups coletados (max 5). */
    fun shipLevelFor(powerupsCollected: Int): Int =
        (1 + powerupsCollected / 3).coerceAtMost(5)
}

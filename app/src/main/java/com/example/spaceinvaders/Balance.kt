package com.example.spaceinvaders

/**
 * Tuning central do jogo.
 *
 * Mantido fora de [GameView] para que balancear dificuldade, cadência de tiro e
 * probabilidades nao exija ler a god class, e para que os valores possam ser
 * testados com unidade depois que a logica deixar de viver na View.
 *
 * Todos os numeros sao os valores efetivos antes desta extracao -- a joga
 * nao muda, apenas fica documentada e editavel em um unico lugar.
 *
 * Nao instanciar: os valores sao lidos diretamente (`Balance.X`).
 */
object Balance {

    // ---------- Progressao de campanha ----------

    /** Sector (fundo) muda a cada N waves. */
    const val WAVES_PER_SECTOR = 3

    /** A cada N waves aparece o boss da mothership. */
    const val WAVES_PER_BOSS = 4

    /** A partir desta wave entram os modificadores de horda. */
    const val HORDE_STARTS_AT_WAVE = 12

    // ---------- Power-ups ----------

    /** Chance de um inimigo destruido dropar uma capsula. */
    const val POWERUP_DROP_CHANCE = 0.14f

    /** Duração dos power-ups temporários. */
    const val POWERUP_DURATION = 8f
    const val RAPID_DURATION = 8f
    const val TRIPLE_DURATION = 8f

    // ---------- Caminho do tiro inimigo ----------

    /** Velocidade do tiro inimigo, escalada por onda e limitada por um teto. */
    fun invaderBulletSpeed(wave: Int): Float =
        ((ENEMY_BULLET_SPEED_BASE + wave * ENEMY_BULLET_SPEED_PER_WAVE)
            .coerceAtMost(ENEMY_BULLET_SPEED_MAX))

    // ---------- Armas secundárias ----------

    const val DASH_COOLDOWN = 2.5f
    const val DASH_INVINCIBLE = 0.6f
    const val DASH_DISTANCE = 180f
    const val MINE_DURATION = 6f
    const val MINE_DEFAULT_COUNT = 1

    // ---------- Cadência de tiro ----------
    //
    // Cada arma tem sua propria cadencia, com bonus de cannonUp e atalho de
    // RAPID; PLASMA decresce mais por upgrade porque e a arma de tiro rapido.
    // Mantido como tabela, nao como uma constante unica: um "FIRE_COOLDOWN"
    // descreveria apenas uma arma e enganaria quem balanceasse.

    const val PLASMA_COOLDOWN = 0.18f
    const val PLASMA_COOLDOWN_RAPID = 0.07f
    const val PLASMA_COOLDOWN_PER_CANNON_UP = 0.03f

    const val SPREAD_COOLDOWN = 0.26f
    const val SPREAD_COOLDOWN_RAPID = 0.16f

    const val LASER_COOLDOWN = 0.3f
    const val LASER_COOLDOWN_RAPID = 0.2f

    const val MISSILE_COOLDOWN = 0.34f
    const val MISSILE_COOLDOWN_RAPID = 0.22f

    /** Decremento por nivel de cannonUp nas armas de cadencia lenta. */
    const val SLOW_COOLDOWN_PER_CANNON_UP = 0.02f

    /** Piso da cadencia, para que upgrade nao produza disparo instantaneo. */
    const val MIN_FIRE_COOLDOWN = 0.01f

    /**
     * Cadencia resultante de upgrade/atalho combinados, nunca abaixo de [MIN_FIRE_COOLDOWN].
     *
     * [weapon] espelha a ordenação de GameView.Weapon (PLASMA=0, SPREAD=1,
     * LASER=2, MISSILE=3); o chamador passa `weapon.ordinal`.
     */
    fun fireCooldown(weapon: Int, cannonUp: Int, rapid: Boolean): Float =
        when (weapon) {
            0 -> (if (rapid) PLASMA_COOLDOWN_RAPID else PLASMA_COOLDOWN) - cannonUp * PLASMA_COOLDOWN_PER_CANNON_UP
            1 -> (if (rapid) SPREAD_COOLDOWN_RAPID else SPREAD_COOLDOWN) - cannonUp * SLOW_COOLDOWN_PER_CANNON_UP
            2 -> (if (rapid) LASER_COOLDOWN_RAPID else LASER_COOLDOWN) - cannonUp * SLOW_COOLDOWN_PER_CANNON_UP
            else -> (if (rapid) MISSILE_COOLDOWN_RAPID else MISSILE_COOLDOWN) - cannonUp * SLOW_COOLDOWN_PER_CANNON_UP
        }.coerceAtLeast(MIN_FIRE_COOLDOWN)

    // ---------- Loja ----------

    /** Preço de cada skin; index 0 (padrão) já vem desbloqueada. */
    val SKIN_PRICES = intArrayOf(0, 500, 500)
    const val SKIN_PRICE = 500

    /** Vidas iniciais de cada partida. */
    const val STARTING_LIVES = 3

    /** Vidas máximas (o power-up de vida extra não ultrapassa). */
    const val MAX_LIVES = 5

    // ---------- Efeitos ----------

    /** Partículas simultâneas; além disso descartam-se as mais antigas. */
    const val PARTICLE_CAP = 600

    // ---------- Progressão da horda ----------

    /** Velocidade do tiro inimigo base, escalada por wave em [invaderBulletSpeed]. */
    const val ENEMY_BULLET_SPEED_BASE = 500f
    const val ENEMY_BULLET_SPEED_PER_WAVE = 18f
    const val ENEMY_BULLET_SPEED_MAX = 900f
}

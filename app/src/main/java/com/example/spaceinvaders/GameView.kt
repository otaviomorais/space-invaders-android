package com.example.spaceinvaders

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private enum class State { PLAYING, GAME_OVER }

    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var surfaceReady = false

    // Screen
    private var w = 0f
    private var h = 0f
    private var scale = 1f

    // Game state
    private var state = State.PLAYING
    private var score = 0
    private var lives = 3
    private var wave = 1
    private var shake = 0f
    private var flashAlpha = 0f
    private var gameOverTimer = 0f
    private var waveBannerTimer = 0f
    private var bgTime = 0f

    // Player
    private var playerX = 0f
    private val playerY get() = h - 110 * scale
    private var playerW = 90f
    private var targetX = 0f
    private var fireCooldown = 0f
    private var invincible = 0f

    // Input (relative drag)
    private var dragging = false
    private var lastTouchX = 0f

    // Formation
    private var formOffX = 0f
    private var formDirX = 1f
    private var entering = false
    private var diveTimer = 7f

    // Entities
    private val bullets = mutableListOf<Bullet>()
    private val enemyBullets = mutableListOf<Bullet>()
    private val invaders = mutableListOf<Invader>()
    private val particles = mutableListOf<Particle>()
    private val stars = mutableListOf<Star>()
    private var ufo: Ufo? = null
    private var ufoTimer = 9f
    private var invaderFireTimer = 1.8f

    // Power-ups & cosmic FX
    private val powerUps = mutableListOf<PowerUp>()
    private val meteors = mutableListOf<Meteor>()
    private val dust = mutableListOf<Dust>()
    private val floatTexts = mutableListOf<FloatText>()
    private var rapidTimer = 0f
    private var tripleTimer = 0f
    private var shieldUp = false
    private var hitStop = 0f
    private var damagePulse = 0f
    private var meteorTimer = 4f

    // Runtime error surfaced on-screen for diagnosis
    @Volatile private var fatal: Throwable? = null

    // Ship upgrades (per run)
    private var engineUp = 0
    private var cannonUp = 0
    private var wingUp = 0
    private var hullUp = 0

    // Depth & reactive ambience
    private val debris = mutableListOf<Debris>()
    private var camX = 0f
    private var screenPunch = 0f
    private var ambR = 0f
    private var ambG = 0f
    private var ambB = 0f

    // Campaign: sectors, boss, weapons, armor, missions, combo
    private var sectorBannerTimer = 0f
    private var damageTakenThisWave = false
    private var weapon = Weapon.PLASMA
    private var armor = 0
    private var combo = 0
    private var comboTimer = 0f
    private var boss: Boss? = null
    private var bossWave = false
    private var mission: Mission? = null
    private var missionCooldown = 0

    private enum class Weapon { PLASMA, SPREAD, LASER, MISSILE }

    private class SectorDef(val name: String, val top: Int, val mid: Int, val bot: Int, val neb: IntArray, val accent: Int)

    private val sectors = arrayOf(
        SectorDef(
            "SETOR NEBULOSA", Color.rgb(3, 2, 12), Color.rgb(16, 7, 38), Color.rgb(30, 9, 48),
            intArrayOf(
                Color.argb(60, 90, 30, 190), Color.argb(55, 20, 140, 165), Color.argb(50, 190, 30, 110),
                Color.argb(45, 40, 60, 210), Color.argb(42, 120, 80, 255)
            ),
            Color.rgb(255, 90, 200)
        ),
        SectorDef(
            "SETOR GLACIAL", Color.rgb(2, 6, 16), Color.rgb(8, 20, 44), Color.rgb(14, 36, 68),
            intArrayOf(
                Color.argb(60, 40, 140, 200), Color.argb(55, 80, 200, 230), Color.argb(50, 150, 230, 255),
                Color.argb(45, 60, 100, 220), Color.argb(42, 180, 240, 255)
            ),
            Color.rgb(120, 230, 255)
        ),
        SectorDef(
            "SETOR VULCANICO", Color.rgb(10, 3, 4), Color.rgb(32, 10, 8), Color.rgb(56, 18, 8),
            intArrayOf(
                Color.argb(60, 200, 60, 20), Color.argb(55, 230, 110, 30), Color.argb(50, 255, 160, 40),
                Color.argb(45, 160, 40, 20), Color.argb(42, 255, 90, 60)
            ),
            Color.rgb(255, 130, 60)
        ),
        SectorDef(
            "O VAZIO", Color.rgb(2, 2, 6), Color.rgb(6, 4, 14), Color.rgb(10, 6, 22),
            intArrayOf(
                Color.argb(40, 70, 40, 130), Color.argb(38, 50, 30, 150), Color.argb(36, 90, 60, 170),
                Color.argb(34, 40, 40, 140), Color.argb(32, 120, 80, 200)
            ),
            Color.rgb(200, 160, 255)
        )
    )

    private class Boss(
        var x: Float,
        var y: Float,
        val maxHp: Int,
        var hp: Int,
        var phase: Int = 0,
        var timer: Float = 1.6f,
        var cycle: Float = 6.5f,
        var vx: Float = 1f,
        var spiral: Float = 0f,
        var pulse: Float = 0f,
        var entering: Boolean = true
    )

    private class Mission(val text: String, val target: Int, val kind: Int, var progress: Int = 0)

    // Paints
    private val bgPaint = Paint()
    private val nebulae = arrayOf(
        Paint(Paint.ANTI_ALIAS_FLAG), Paint(Paint.ANTI_ALIAS_FLAG), Paint(Paint.ANTI_ALIAS_FLAG),
        Paint(Paint.ANTI_ALIAS_FLAG), Paint(Paint.ANTI_ALIAS_FLAG)
    )
    private val galaxyPaints = arrayOf(Paint(Paint.ANTI_ALIAS_FLAG), Paint(Paint.ANTI_ALIAS_FLAG))
    private val planetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val damagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bgW = 0f
    private var bgH = 0f
    private var minDim = 0f
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 48f
        textAlign = Paint.Align.LEFT
        setShadowLayer(12f, 0f, 0f, Color.CYAN)
    }
    private val bigTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        setShadowLayer(20f, 0f, 0f, Color.MAGENTA)
    }

    init {
        holder.addCallback(this)
        focusable = FOCUSABLE
    }

    // ---------- Lifecycle ----------

    fun resume() {
        running = true
        thread = Thread(this).also { it.start() }
    }

    fun pause() {
        running = false
        try {
            thread?.join()
        } catch (_: InterruptedException) {
        }
        thread = null
    }

    override fun run() {
        var lastTime = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = min((now - lastTime) / 1_000_000_000f, 0.05f)
            lastTime = now

            if (surfaceReady && w > 0) {
                try {
                    var effDt = dt
                    if (hitStop > 0f) {
                        hitStop -= dt
                        effDt = dt * 0.12f
                    }
                    update(effDt)
                    draw()
                } catch (t: Throwable) {
                    if (fatal == null) {
                        fatal = t
                        Log.e("SpaceInvaders", "Fatal game loop error", t)
                    }
                }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        scale = min(w / 1280f, h / 720f).coerceAtLeast(0.4f)
        playerW = 90f * scale
        if (playerX == 0f) playerX = w / 2f
        targetX = playerX
        try {
            initStars()
            initDust()
            initDebris()
            initBackgrounds()
        } catch (t: Throwable) {
            // A bad background shader must never prevent the game from rendering
            Log.e("SpaceInvaders", "initBackgrounds failed", t)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    // ---------- Setup ----------

    private fun initBackgrounds() {
        if (bgW == w && bgH == h) return
        bgW = w
        bgH = h
        minDim = min(w, h)
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.rgb(3, 2, 12),
                Color.rgb(13, 6, 34),
                Color.rgb(30, 9, 48)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        // Deep-space nebulae: the void of infinity
        val defs = arrayOf(
            Triple(Color.argb(60, 90, 30, 190), 0.16f, 0.26f),
            Triple(Color.argb(55, 20, 140, 165), 0.80f, 0.18f),
            Triple(Color.argb(50, 190, 30, 110), 0.55f, 0.88f),
            Triple(Color.argb(45, 40, 60, 210), 0.32f, 0.62f),
            Triple(Color.argb(42, 120, 80, 255), 0.90f, 0.68f)
        )
        for (i in nebulae.indices) {
            val (col, fx, fy) = defs[i]
            nebulae[i].shader = RadialGradient(
                w * fx, h * fy, minDim * 0.52f,
                col, Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        // Spiral galaxy cores
        val gals = arrayOf(
            floatArrayOf(w * 0.74f, h * 0.28f, minDim * 0.17f),
            floatArrayOf(w * 0.16f, h * 0.76f, minDim * 0.115f)
        )
        for (i in galaxyPaints.indices) {
            val g = gals[i]
            galaxyPaints[i].shader = RadialGradient(
                g[0], g[1], g[2],
                intArrayOf(
                    Color.argb(200, 255, 250, 235),
                    Color.argb(90, 205, 175, 255),
                    Color.argb(35, 130, 100, 230),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.22f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        // Distant planet with lit limb
        val px = w * 1.04f
        val py = -h * 0.24f
        val pr = h * 0.44f
        planetPaint.shader = RadialGradient(
            px - pr * 0.5f, py + pr * 0.5f, pr * 1.45f,
            intArrayOf(
                Color.rgb(38, 26, 64),
                Color.rgb(14, 9, 30),
                Color.rgb(4, 3, 12)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // Vignette: edges dissolve into the void
        vignettePaint.shader = RadialGradient(
            w / 2f, h / 2f, hypot(w, h) * 0.55f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(170, 1, 0, 10)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        damagePaint.shader = RadialGradient(
            w / 2f, h / 2f, hypot(w, h) * 0.5f,
            intArrayOf(Color.TRANSPARENT, Color.argb(180, 255, 25, 55)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun initDust() {
        dust.clear()
        repeat(26) {
            dust.add(
                Dust(
                    Random.nextFloat() * w,
                    Random.nextFloat() * h,
                    (Random.nextFloat() * 3f + 1.2f) * scale,
                    (Random.nextFloat() - 0.5f) * 7f * scale,
                    Random.nextFloat() * 16f + 7f
                )
            )
        }
    }

    private fun initStars() {
        stars.clear()
        repeat(140) {
            stars.add(
                Star(
                    Random.nextFloat() * w,
                    Random.nextFloat() * h,
                    Random.nextFloat() * 2.2f + 0.6f,
                    Random.nextFloat() * 46f + 14f,
                    (Random.nextFloat() * 130 + 80).toInt(),
                    Random.nextFloat() * 6.28f,
                    Random.nextFloat() * 0.6f + 0.4f
                )
            )
        }
    }

    private fun initDebris() {
        debris.clear()
        repeat(9) {
            debris.add(
                Debris(
                    Random.nextFloat() * w,
                    Random.nextFloat() * h,
                    (Random.nextFloat() * 14f + 9f) * scale,
                    (Random.nextFloat() * 130f + 150f) * scale,
                    Random.nextFloat() * 360f,
                    (Random.nextFloat() - 0.5f) * 90f,
                    (Random.nextFloat() - 0.5f) * 40f * scale,
                    (Random.nextFloat() * 26f + 22).toInt()
                )
            )
        }
    }

    private fun updateDebris(dt: Float) {
        for (d in debris) {
            d.y += d.speed * dt
            d.x += d.drift * dt
            d.rot += d.rotSpeed * dt
            if (d.y > h + d.size * 2f) {
                d.y = -d.size * 2f
                d.x = Random.nextFloat() * w
            }
        }
    }

    private fun invaderColor(variant: Int): Int = when (variant) {
        0 -> Color.rgb(235, 70, 160)    // crab - magenta
        1 -> Color.rgb(70, 215, 250)    // squid - cyan
        2 -> Color.rgb(120, 230, 95)    // armored - green
        3 -> Color.rgb(255, 150, 50)    // hunter - orange
        4 -> Color.rgb(235, 80, 60)     // bomber - red
        else -> Color.rgb(170, 255, 90) // splitter - lime
    }

    private fun setSector(index: Int) {
        val s = sectors[index.coerceIn(0, sectors.lastIndex)]
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(s.top, s.mid, s.bot),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        val spots = arrayOf(
            Triple(0.16f, 0.26f), Triple(0.80f, 0.18f), Triple(0.55f, 0.88f),
            Triple(0.32f, 0.62f), Triple(0.90f, 0.68f)
        )
        for (i in nebulae.indices) {
            val (fx, fy) = spots[i]
            nebulae[i].shader = RadialGradient(
                w * fx, h * fy, minDim * 0.52f,
                s.neb[i], Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
    }

    /** Multiply RGB channels by factor f (>1 lightens, <1 darkens). */
    private fun shade(color: Int, f: Float): Int {
        val r = ((color shr 16 and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = ((color shr 8 and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun spawnWave() {
        invaders.clear()
        formOffX = 0f
        formDirX = 1f
        entering = true
        diveTimer = 7f
        damageTakenThisWave = false

        // Sector changes every 3 waves
        val sectorIndex = ((wave - 1) / 3).coerceAtMost(sectors.lastIndex)
        if (bgW != 0f) setSector(sectorIndex)
        sectorBannerTimer = 2f
        flashAlpha = maxOf(flashAlpha, 0.2f)

        // Every 4th wave: MOTHERSHIP BOSS
        if (wave % 4 == 0) {
            bossWave = true
            entering = false
            boss = Boss(
                x = w / 2f, y = -180f * scale,
                maxHp = 55 + sectorIndex * 25 + wave * 3
            ).also { it.hp = it.maxHp }
            addFloat("ALERTA: NAVE-MAE!", w / 2f, h * 0.35f, Color.rgb(255, 80, 120))
            shake = maxOf(shake, 10f)
            return
        }
        bossWave = false

        // Mission assignment
        if (mission == null) {
            if (missionCooldown > 0) {
                missionCooldown--
            } else if (Random.nextFloat() < 0.75f) {
                val kinds = listOf(0, 1, 2, 3)
                mission = when (kinds.random()) {
                    0 -> Mission("Destrua ${8 + wave} inimigos", 8 + wave, 0)
                    1 -> Mission("Colete 2 power-ups", 2, 1)
                    2 -> Mission("Abata 3 mergulhadores", 3, 2)
                    else -> Mission("Onda perfeita: sem dano", 1, 3)
                }
                addFloat("NOVA MISSAO!", w / 2f, h * 0.3f, Color.rgb(255, 216, 120))
            }
        }

        val cols = min(5 + wave / 2, 9)
        val rows = min(3 + (wave - 1) / 2, 5)
        val marginX = w * 0.13f
        val spacingX = if (cols > 1) (w - marginX * 2) / (cols - 1) else 0f

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var variant = r % 3
                val roll = Random.nextFloat()
                if (wave >= 2 && roll < 0.14f) variant = 3
                else if (wave >= 3 && roll < 0.24f) variant = 4
                else if (wave >= 4 && roll < 0.34f) variant = 5
                val size = (when (variant) {
                    0 -> 40f
                    1 -> 36f
                    3 -> 34f
                    4 -> 52f
                    5 -> 34f
                    else -> 46f
                }) * scale
                invaders.add(
                    Invader(
                        homeX = marginX + c * spacingX,
                        homeY = h * 0.14f + r * h * 0.055f,
                        x = marginX + c * spacingX,
                        y = -h * (0.25f + c * 0.06f + r * 0.12f) - size,
                        size = size,
                        color = invaderColor(variant),
                        variant = variant,
                        hp = when (variant) {
                            2 -> 2
                            4 -> 3
                            else -> if (r == 0 && wave >= 4) 2 else 1
                        }
                    )
                )
            }
        }
        waveBannerTimer = 1.6f
        // Reinforced hull regenerates its shield every wave
        if (hullUp > 0) shieldUp = true
    }

    private fun resetGame() {
        score = 0
        lives = 3
        wave = 1
        bullets.clear()
        enemyBullets.clear()
        particles.clear()
        powerUps.clear()
        floatTexts.clear()
        ufo = null
        ufoTimer = 9f
        state = State.PLAYING
        invincible = 0f
        rapidTimer = 0f
        tripleTimer = 0f
        shieldUp = false
        engineUp = 0
        cannonUp = 0
        wingUp = 0
        hullUp = 0
        camX = 0f
        screenPunch = 0f
        ambR = 0f
        ambG = 0f
        ambB = 0f
        weapon = Weapon.PLASMA
        armor = 0
        combo = 0
        comboTimer = 0f
        boss = null
        bossWave = false
        mission = null
        missionCooldown = 0
        sectorBannerTimer = 0f
        initDebris()
        spawnWave()
    }

    // ---------- Input ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                dragging = true
                lastTouchX = event.x
                if (state == State.GAME_OVER && gameOverTimer > 1.2f) resetGame()
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    targetX += (event.x - lastTouchX) * (1.8f + engineUp * 0.2f)
                    lastTouchX = event.x
                    targetX = targetX.coerceIn(playerW, w - playerW)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
            }
        }
        return true
    }

    // ---------- Update ----------

    private fun update(dt: Float) {
        bgTime += dt
        updateStars(dt)

        if (state == State.GAME_OVER) {
            gameOverTimer += dt
            updateParticles(dt)
            shake *= 0.9f
            flashAlpha *= 0.92f
            return
        }

        // Player follows finger with smoothing (engine upgrades = snappier)
        playerX += (targetX - playerX) * min((16f + engineUp * 4f) * dt, 1f)

        fireCooldown -= dt
        invincible -= dt
        shake *= 0.88f
        flashAlpha *= 0.9f
        waveBannerTimer -= dt

        if (dragging && fireCooldown <= 0f) shoot()

        updateBullets(dt)
        updateInvaders(dt)
        updateBoss(dt)
        updateUfo(dt)
        updateParticles(dt)
        updatePowerUps(dt)
        updateMeteors(dt)
        updateDust(dt)
        updateFloatTexts(dt)
        checkCollisions()

        rapidTimer -= dt
        tripleTimer -= dt
        sectorBannerTimer -= dt
        damagePulse = (damagePulse - dt * 2f).coerceAtLeast(0f)

        // Battle combo decay
        comboTimer -= dt
        if (comboTimer <= 0f) combo = 0

        // Depth camera: world pans slightly against the player
        camX += ((playerX - w / 2f) - camX) * min(4f * dt, 1f)
        screenPunch = (screenPunch - dt * 3.5f).coerceAtLeast(0f)
        updateDebris(dt)

        // Reactive ambience: the screen mood follows the action
        var tr = 0f; var tg = 0f; var tb = 0f
        if (rapidTimer > 0f) { tr += 24f; tg += 12f }
        if (tripleTimer > 0f) { tb += 24f; tg += 8f }
        if (shieldUp) { tb += 28f; tg += 10f }
        if (ufo != null) { tg += 14f; tb += 20f }
        if (lives <= 1) tr += 42f
        if (waveBannerTimer > 0f) { tg += 10f; tb += 16f }
        ambR += (tr - ambR) * min(3f * dt, 1f)
        ambG += (tg - ambG) * min(3f * dt, 1f)
        ambB += (tb - ambB) * min(3f * dt, 1f)

        // ---- Enemy fire (harder AI) ----
        invaderFireTimer -= dt
        if (invaderFireTimer <= 0f && invaders.isNotEmpty()) {
            val alive = invaders.filter { it.alive && !it.diving && it.y > 0f }
            if (alive.isNotEmpty()) {
                val bombers = alive.filter { it.variant == 4 }
                val shooter = if (bombers.isNotEmpty() && Random.nextFloat() < 0.45f) bombers.random() else alive.random()
                if (shooter.variant == 4) {
                    // Bomber drops a 3-shot cluster fan
                    val speed = (380f + wave * 14f) * scale
                    for (a in floatArrayOf(-0.3f, 0f, 0.3f)) {
                        enemyBullets.add(
                            Bullet(shooter.x, shooter.y + shooter.size, speed,
                                Color.rgb(255, 140, 60), sin(a) * speed)
                        )
                    }
                } else {
                    fireAimed(shooter)
                }
                var interval = (Random.nextFloat() * 0.7f + 1.5f / wave).coerceAtLeast(0.22f)
                val aliveCount = invaders.count { it.alive }
                if (aliveCount <= 3) interval *= 0.55f // desperate survivors shoot much faster
                invaderFireTimer = interval
            } else {
                invaderFireTimer = 0.4f
            }
        }

        // ---- Divers ----
        if (!entering && boss == null) {
            diveTimer -= dt
            if (diveTimer <= 0f) {
                val candidates = invaders.filter { it.alive && !it.diving }
                if (candidates.size > 2) {
                    candidates.random().diving = true
                }
                diveTimer = (7f - wave * 0.5f).coerceAtLeast(2.2f) + Random.nextFloat() * 2.5f
            }
        }

        // Wave cleared (boss must fall too)
        if (!entering && boss == null && !bossWave && invaders.none { it.alive }) {
            // Perfect wave mission
            val m = mission
            if (m != null && m.kind == 3 && !damageTakenThisWave) missionProgress(3, 1)
            wave++
            addScore(100)
            spawnWave()
        }
    }

    /** Fires a projectile from the shooter towards the player's current position. */
    private fun fireAimed(shooter: Invader) {
        val speed = ((500f + wave * 18f).coerceAtMost(900f)) * scale
        val travelTime = (shooter.y - playerY) / speed
        var vx = if (travelTime > 0f) (playerX - shooter.x) / travelTime * 0.65f else 0f
        vx = vx.coerceIn(-240f * scale, 240f * scale)
        enemyBullets.add(Bullet(shooter.x, shooter.y + shooter.size, speed, Color.rgb(255, 90, 60), vx))
    }

    private fun updateStars(dt: Float) {
        for (s in stars) {
            s.y += s.speed * scale * dt * s.z * (if (state == State.GAME_OVER) 0.2f else 1f)
            if (s.y > h) {
                s.y = 0f
                s.x = Random.nextFloat() * w
            }
        }
    }

    private fun updateInvaders(dt: Float) {
        if (invaders.isEmpty()) return
        val speed = (60f + wave * 24f) * scale
        val descendRate = (9f + wave * 2.4f) * scale

        if (entering) {
            var allSettled = true
            for (inv in invaders) {
                if (!inv.alive) continue
                inv.pulse += dt * 6f
                val tx = inv.homeX + formOffX
                inv.x += (tx - inv.x) * min(5f * dt, 1f)
                inv.y += (inv.homeY - inv.y) * min(5f * dt, 1f)
                if (abs(tx - inv.x) > 4f || abs(inv.homeY - inv.y) > 4f) allSettled = false
            }
            if (allSettled) entering = false
        } else {
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxHalf = 0f
            var maxY = 0f
            for (inv in invaders) {
                if (!inv.alive || inv.diving) continue
                inv.pulse += dt * 6f
                minX = min(minX, inv.homeX)
                maxX = maxOf(maxX, inv.homeX)
                maxHalf = maxOf(maxHalf, inv.size)
            }
            if (minX < maxX) {
                formDirX = when {
                    formDirX > 0 && maxX + formOffX + maxHalf > w - 50 * scale -> -1f
                    formDirX < 0 && minX + formOffX - maxHalf < 50 * scale -> 1f
                    else -> formDirX
                }
                formOffX += formDirX * speed * dt
            }

            for (inv in invaders) {
                if (!inv.alive || inv.diving) continue
                inv.pulse += dt * 6f
                inv.x = inv.homeX + formOffX
                inv.homeY += descendRate * dt
                inv.y = inv.homeY
                // Hunters strafe inside the formation
                if (inv.variant == 3) inv.x += sin(inv.pulse * 1.3f) * 42f * scale
                maxY = maxOf(maxY, inv.y)
            }

            if (maxY > playerY - 100 * scale) hitPlayer(instantDeath = true)

            // Divers: S-curve dive towards the player
            for (inv in invaders) {
                if (!inv.alive || !inv.diving) continue
                inv.pulse += dt * 12f
                inv.divePhase += dt * 5f
                inv.y += (330f + wave * 22f) * scale * dt
                inv.x += sin(inv.divePhase) * 170f * scale * dt
                inv.x += kotlin.math.sign(playerX - inv.x) * 70f * scale * dt
                inv.x = inv.x.coerceIn(inv.size, w - inv.size)
                if (Random.nextFloat() < dt * 30f) {
                    spawnSparks(inv.x, inv.y - inv.size * 0.4f, inv.color, count = 1, small = true, spreadUp = true)
                }
                // Collides with player?
                if (hypot(playerX - inv.x, playerY - inv.y) < inv.size + playerW * 0.5f) {
                    explode(inv.x, inv.y, inv.color, big = true)
                    inv.alive = false
                    inv.diving = false
                    hitPlayer(false)
                }
                // Left the screen
                if (inv.y > h + inv.size * 2f) {
                    inv.alive = false
                    inv.diving = false
                }
            }
        }
    }

    private fun updateUfo(dt: Float) {        val current = ufo
        if (current != null) {
            current.x += current.vx * dt
            current.blink += dt * 8f
            if (current.x < -150 * scale || current.x > w + 150 * scale) ufo = null
        } else {
            ufoTimer -= dt
            if (ufoTimer <= 0f) {
                val fromLeft = Random.nextBoolean()
                ufo = Ufo(
                    x = if (fromLeft) -120 * scale else w + 120 * scale,
                    y = h * 0.07f,
                    vx = (if (fromLeft) 1f else -1f) * (230f + wave * 14f) * scale
                )
                ufoTimer = Random.nextFloat() * 8f + 10f
            }
        }
    }

    // ---------- Boss: Mothership ----------

    private fun updateBoss(dt: Float) {
        val b = boss ?: return
        b.pulse += dt * 4f
        if (b.entering) {
            b.y += (h * 0.18f - b.y) * min(2f * dt, 1f)
            if (abs(b.y - h * 0.18f) < 8f * scale) b.entering = false
            return
        }

        val enraged = b.hp <= b.maxHp / 3
        val speedMul = if (enraged) 1.6f else 1f
        b.timer -= dt * speedMul
        b.cycle -= dt

        when (b.phase) {
            0 -> { // Sweep + aimed volleys
                b.x += b.vx * (120f + wave * 6f) * scale * dt * speedMul
                if (b.x < 160f * scale) { b.x = 160f * scale; b.vx = 1f }
                if (b.x > w - 160f * scale) { b.x = w - 160f * scale; b.vx = -1f }
                if (b.timer <= 0f) {
                    fireBossVolley(b)
                    b.timer = 1.3f
                }
            }
            1 -> { // Rotating spiral barrage
                b.spiral += dt * 2.6f * speedMul
                if (b.timer <= 0f) {
                    fireBossSpiral(b)
                    b.timer = 0.32f
                }
            }
            else -> { // Summon minions
                if (b.timer <= 0f) {
                    summonMinions(b)
                    b.phase = 0
                    b.timer = 1.6f
                }
            }
        }

        if (b.cycle <= 0f) {
            b.phase = (b.phase + 1) % 3
            b.timer = if (b.phase == 2) 0.6f else 1.2f
            b.cycle = 6.5f
        }
    }

    private fun fireBossVolley(b: Boss) {
        val speed = (430f + wave * 12f) * scale
        for (a in floatArrayOf(-0.28f, 0f, 0.28f)) {
            enemyBullets.add(
                Bullet(b.x + a * 160f * scale, b.y + 80f * scale, speed,
                    Color.rgb(255, 70, 170), sin(a) * speed * 0.8f)
            )
        }
    }

    private fun fireBossSpiral(b: Boss) {
        val speed = 300f * scale
        for (i in 0 until 6) {
            val angle = b.spiral + i * (Math.PI.toFloat() / 3f)
            enemyBullets.add(
                Bullet(b.x, b.y + 40f * scale, abs(sin(angle)) * speed + 60f * scale,
                    Color.rgb(255, 120, 220), cos(angle) * speed)
            )
        }
    }

    private fun summonMinions(b: Boss) {
        repeat(4) { i ->
            val variant = if (i % 2 == 0) 0 else 1
            invaders.add(
                Invader(
                    homeX = b.x + (i - 1.5f) * 90f * scale, homeY = b.y + 120f * scale,
                    x = b.x + (i - 1.5f) * 90f * scale, y = b.y,
                    size = 30f * scale, color = invaderColor(variant), variant = variant, hp = 1
                )
            )
        }
        addFloat("REFUERÇOS!", b.x, b.y + 140f * scale, Color.rgb(255, 120, 220))
    }

    private fun bossDeath(b: Boss) {
        boss = null
        bossWave = false
        repeat(3) { i ->
            explode(b.x + (i - 1) * 70f * scale, b.y + (i - 1) * 30f * scale, Color.rgb(255, 120, 220), huge = true)
        }
        addScore(1000)
        addFloat("NAVE-MAE DESTRUIDA! +1000", b.x, b.y, Color.rgb(255, 230, 120))
        // Loot shower
        powerUps.add(PowerUp(b.x - 60f * scale, b.y, PowerType.WEAPON))
        powerUps.add(PowerUp(b.x, b.y - 30f * scale, PowerType.PART))
        powerUps.add(PowerUp(b.x + 60f * scale, b.y, PowerType.LIFE))
        screenPunch = 1.6f
        flashAlpha = 0.5f
        shake = 26f
        hitStop = 0.22f
    }

    // ---------- Missions ----------

    private fun missionProgress(kind: Int, amount: Int) {
        val m = mission ?: return
        if (m.kind != kind || m.progress >= m.target) return
        m.progress += amount
        if (m.progress >= m.target) {
            addScore(300)
            addFloat("MISSAO CUMPRIDA! +300", w / 2f, h * 0.3f, Color.rgb(120, 255, 160))
            powerUps.add(PowerUp(w * 0.35f, h * 0.4f, PowerType.WEAPON))
            powerUps.add(PowerUp(w * 0.65f, h * 0.4f, PowerType.PART))
            mission = null
            missionCooldown = 1
        }
    }

    private fun nearestTargetX(x: Float, y: Float): Float? {
        var best: Float? = null
        var bestD = Float.MAX_VALUE
        for (inv in invaders) {
            if (!inv.alive) continue
            val d = hypot(inv.x - x, inv.y - y)
            if (d < bestD) { bestD = d; best = inv.x }
        }
        val b = boss
        if (b != null) {
            val d = hypot(b.x - x, b.y - y)
            if (d < bestD) best = b.x
        }
        return best
    }

    private fun updateBullets(dt: Float) {
        bullets.removeAll { b ->
            if (b.homing) {
                val tx = nearestTargetX(b.x, b.y)
                if (tx != null) {
                    val desired = ((tx - b.x) * 2.2f).coerceIn(-320f * scale, 320f * scale)
                    b.vx += (desired - b.vx) * min(6f * dt, 1f)
                }
                if (Random.nextFloat() < dt * 28f) {
                    spawnSparks(b.x, b.y + 8f * scale, Color.rgb(200, 200, 200), count = 1, small = true, spreadUp = true)
                }
            }
            b.x += b.vx * dt
            b.y -= b.speed * dt
            b.trail.add(0, b.y)
            if (b.trail.size > 6) b.trail.removeAt(b.trail.size - 1)
            b.y < -60f
        }
        enemyBullets.removeAll { b ->
            b.x += b.vx * dt
            b.y += b.speed * dt
            b.y > h + 50f || b.y < -90f || b.x < -50f || b.x > w + 50f
        }
    }

    private fun updateParticles(dt: Float) {
        particles.removeAll { p ->
            p.life -= dt
            if (p.life <= 0) return@removeAll true
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += p.gravity * dt
            p.vx *= 0.98f
            p.vy *= 0.98f
            false
        }
    }

    /** Applies damage; handles death, combo, drops, splitter spawn, missions. */
    private fun damageInvader(inv: Invader, amount: Int) {
        inv.hp -= amount
        if (inv.hp > 0) {
            addScore(5)
            spawnSparks(inv.x, inv.y, inv.color, count = 6, small = true)
            return
        }
        inv.alive = false
        explode(inv.x, inv.y, inv.color, big = true)
        combo++
        comboTimer = 2f
        val base = when (inv.variant) {
            2 -> 40; 3 -> 35; 4 -> 60; 5 -> 25; 0 -> 30; else -> 20
        }
        val mult = 1 + combo / 5
        addScore(base * mult)
        if (combo >= 3) addFloat("COMBO x$mult", inv.x, inv.y - inv.size * 2f, Color.rgb(255, 230, 120))
        missionProgress(0, 1)
        if (inv.diving) missionProgress(2, 1)
        shake = maxOf(shake, 6f)
        hitStop = maxOf(hitStop, 0.05f)
        // Splitter divides into two mini divers
        if (inv.variant == 5 && !inv.mini) {
            repeat(2) { i ->
                val mini = Invader(
                    homeX = inv.x + (i * 60 - 30) * scale, homeY = inv.y,
                    x = inv.x + (i * 60 - 30) * scale, y = inv.y,
                    size = 22f * scale, color = invaderColor(1), variant = 1, hp = 1, mini = true
                )
                mini.diving = true
                invaders.add(mini)
            }
        }
        if (Random.nextFloat() < 0.14f) {
            powerUps.add(PowerUp(inv.x, inv.y, rollPowerType()))
        }
    }

    private fun checkCollisions() {
        // Player bullets vs invaders / boss / UFO
        bullets.removeAll { b ->
            var consumed = false
            var hits = 0
            for (inv in invaders) {
                if (!inv.alive) continue
                if (hypot(inv.x - b.x, inv.y - b.y) < inv.size * 1.1f) {
                    damageInvader(inv, 1)
                    hits++
                    spawnSparks(b.x, b.y, inv.color, count = 6, small = true)
                    if (b.splash) {
                        for (other in invaders.toList()) {
                            if (other.alive && other !== inv && hypot(other.x - b.x, other.y - b.y) < 85f * scale) {
                                damageInvader(other, 1)
                            }
                        }
                        explode(b.x, b.y, Color.rgb(255, 170, 90), big = false)
                    }
                    if (!b.pierce || hits >= 3) { consumed = true; break }
                }
            }
            // Boss
            if (!consumed) {
                val bs = boss
                if (bs != null && !bs.entering && hypot(bs.x - b.x, bs.y - b.y) < 100f * scale) {
                    bs.hp--
                    consumed = true
                    spawnSparks(b.x, b.y, Color.rgb(255, 120, 220), count = 8, small = true)
                    addScore(5)
                    if (b.splash) explode(b.x, b.y, Color.rgb(255, 170, 90), big = false)
                    if (bs.hp <= 0) bossDeath(bs)
                }
            }
            // UFO
            if (!consumed) {
                val saucer = ufo
                if (saucer != null && hypot(saucer.x - b.x, saucer.y - b.y) < 70 * scale) {
                    ufo = null
                    consumed = true
                    explode(saucer.x, saucer.y, Color.rgb(255, 220, 90), huge = true)
                    addScore(150)
                    missionProgress(4, 1)
                    shake = maxOf(shake, 12f)
                    screenPunch = maxOf(screenPunch, 0.8f)
                }
            }
            consumed
        }

        // Enemy bullets vs player
        enemyBullets.removeAll { b ->
            if (invincible <= 0f && hypot(playerX - b.x, playerY - b.y) < playerW * 0.7f) {
                explode(b.x, b.y, Color.rgb(255, 120, 40), big = false)
                hitPlayer(false)
                true
            } else {
                false
            }
        }
    }

    private fun hitPlayer(instantDeath: Boolean) {
        if (state != State.PLAYING || invincible > 0f) return

        // Armor plates absorb hits before anything else
        if (armor > 0 && !instantDeath) {
            armor--
            invincible = 1f
            explode(playerX, playerY - playerW * 0.3f, Color.rgb(150, 170, 225), big = false)
            particles.add(
                Particle(playerX, playerY - playerW * 0.3f, 0f, 0f, 14f * scale, 0.4f, 0.4f,
                    Color.rgb(150, 170, 225), 0f).apply { isRing = true }
            )
            addFloat("BLINDAGEM ABSORVEU", playerX, playerY - 80 * scale, Color.rgb(150, 170, 225))
            shake = maxOf(shake, 8f)
            damageTakenThisWave = true
            return
        }

        // Shield absorbs one non-lethal hit
        if (shieldUp && !instantDeath) {
            shieldUp = false
            invincible = 1.2f
            explode(playerX, playerY - playerW * 0.4f, Color.rgb(111, 168, 255), big = false)
            particles.add(
                Particle(playerX, playerY - playerW * 0.4f, 0f, 0f, 12f * scale, 0.4f, 0.4f,
                    Color.rgb(111, 168, 255), 0f).apply { isRing = true }
            )
            addFloat("ESCUDO QUEBRADO", playerX, playerY - 80 * scale, Color.rgb(111, 168, 255))
            shake = maxOf(shake, 8f)
            return
        }

        explode(playerX, playerY, Color.rgb(0, 255, 180), big = true)
        shake = 18f
        flashAlpha = 0.55f
        damagePulse = 1f
        screenPunch = 1f
        hitStop = maxOf(hitStop, 0.14f)
        damageTakenThisWave = true
        lives--
        if (lives <= 0 || instantDeath) {
            lives = 0
            state = State.GAME_OVER
            gameOverTimer = 0f
            explode(playerX, playerY, Color.rgb(0, 255, 180), huge = true)
            shake = 28f
        } else {
            invincible = 2f
            playerX = w / 2f
            targetX = playerX
        }
    }

    private fun shoot() {
        val rapid = rapidTimer > 0f
        val spd = 1150f * scale
        val wingExtra = when {
            tripleTimer > 0f -> 2
            wingUp >= 2 -> 2
            wingUp == 1 -> 1
            else -> 0
        }
        when (weapon) {
            Weapon.PLASMA -> {
                fireCooldown = (if (rapid) 0.07f else 0.18f) - cannonUp * 0.03f
                val angles = when (wingExtra) {
                    2 -> floatArrayOf(-0.22f, 0f, 0.22f)
                    1 -> floatArrayOf(-0.13f, 0.13f)
                    else -> floatArrayOf(0f)
                }
                for (a in angles) {
                    bullets.add(Bullet(playerX, playerY - playerW, spd * cos(a).coerceAtLeast(0.7f),
                        Color.rgb(120, 255, 200), sin(a) * spd))
                }
            }
            Weapon.SPREAD -> {
                fireCooldown = (if (rapid) 0.16f else 0.26f) - cannonUp * 0.02f
                for (a in floatArrayOf(-0.5f, -0.25f, 0f, 0.25f, 0.5f)) {
                    bullets.add(Bullet(playerX, playerY - playerW, spd * 0.95f * cos(a).coerceAtLeast(0.55f),
                        Color.rgb(255, 230, 130), sin(a) * spd))
                }
            }
            Weapon.LASER -> {
                fireCooldown = (if (rapid) 0.2f else 0.3f) - cannonUp * 0.02f
                bullets.add(Bullet(playerX, playerY - playerW, 1650f * scale,
                    Color.rgb(200, 240, 255), 0f, pierce = true))
                if (wingExtra >= 1) {
                    bullets.add(Bullet(playerX - playerW * 0.5f, playerY - playerW, spd,
                        Color.rgb(120, 255, 200), -0.15f * spd))
                    bullets.add(Bullet(playerX + playerW * 0.5f, playerY - playerW, spd,
                        Color.rgb(120, 255, 200), 0.15f * spd))
                }
            }
            Weapon.MISSILE -> {
                fireCooldown = (if (rapid) 0.22f else 0.34f) - cannonUp * 0.02f
                val n = 1 + wingExtra
                for (i in 0 until n) {
                    val off = (i - (n - 1) / 2f) * playerW * 0.5f
                    bullets.add(Bullet(playerX + off, playerY - playerW, 720f * scale,
                        Color.rgb(255, 170, 90), off * 1.2f, homing = true, splash = true))
                }
            }
        }
        spawnSparks(playerX, playerY - playerW, Color.rgb(120, 255, 200), count = 4, small = true, spreadUp = true)
    }

    // ---------- Power-ups & cosmic FX ----------

    private fun rollPowerType(): PowerType {
        val r = Random.nextFloat()
        return when {
            r < 0.20f -> PowerType.RAPID
            r < 0.38f -> PowerType.TRIPLE
            r < 0.52f -> PowerType.SHIELD
            r < 0.62f -> PowerType.LIFE
            r < 0.72f -> PowerType.NOVA
            r < 0.82f -> PowerType.PART
            r < 0.91f -> PowerType.WEAPON
            else -> PowerType.ARMOR
        }
    }

    private fun powerColor(t: PowerType): Int = when (t) {
        PowerType.RAPID -> Color.rgb(255, 193, 77)
        PowerType.TRIPLE -> Color.rgb(89, 229, 255)
        PowerType.SHIELD -> Color.rgb(111, 168, 255)
        PowerType.LIFE -> Color.rgb(255, 111, 165)
        PowerType.NOVA -> Color.rgb(199, 125, 255)
        PowerType.PART -> Color.rgb(255, 216, 120)
        PowerType.WEAPON -> Color.rgb(170, 255, 245)
        PowerType.ARMOR -> Color.rgb(150, 170, 225)
    }

    private fun updatePowerUps(dt: Float) {
        if (state != State.PLAYING) return
        powerUps.removeAll { p ->
            p.phase += dt * 3f
            p.y += 130f * scale * dt
            p.x += sin(p.phase) * 42f * scale * dt
            p.x = p.x.coerceIn(30f * scale, w - 30f * scale)
            if (p.y > h + 50f * scale) return@removeAll true
            // Pickup?
            if (hypot(playerX - p.x, playerY - p.y) < playerW * 0.95f) {
                applyPowerUp(p.type)
                missionProgress(1, 1)
                spawnSparks(p.x, p.y, powerColor(p.type), count = 18, small = false)
                return@removeAll true
            }
            false
        }
    }

    private fun applyPowerUp(type: PowerType) {
        when (type) {
            PowerType.RAPID -> {
                rapidTimer = 8f
                addFloat("TIRO RÁPIDO!", playerX, playerY - 70 * scale, powerColor(type))
            }
            PowerType.TRIPLE -> {
                tripleTimer = 8f
                addFloat("TIRO TRIPLO!", playerX, playerY - 70 * scale, powerColor(type))
            }
            PowerType.SHIELD -> {
                shieldUp = true
                addFloat("ESCUDO!", playerX, playerY - 70 * scale, powerColor(type))
            }
            PowerType.LIFE -> {
                lives = (lives + 1).coerceAtMost(5)
                flashAlpha = maxOf(flashAlpha, 0.22f)
                addFloat("+1 VIDA", playerX, playerY - 70 * scale, powerColor(type))
            }
            PowerType.NOVA -> applyNova()
            PowerType.PART -> applyRandomUpgrade()
            PowerType.WEAPON -> {
                weapon = Weapon.entries[(weapon.ordinal + 1) % Weapon.entries.size]
                val wName = when (weapon) {
                    Weapon.PLASMA -> "PLASMA"
                    Weapon.SPREAD -> "SPREAD"
                    Weapon.LASER -> "LASER"
                    Weapon.MISSILE -> "MISSIL"
                }
                addFloat("ARMA: $wName", playerX, playerY - 80 * scale, powerColor(PowerType.WEAPON))
            }
            PowerType.ARMOR -> {
                armor = (armor + 2).coerceAtMost(4)
                addFloat("BLINDAGEM +$armor", playerX, playerY - 80 * scale, powerColor(PowerType.ARMOR))
            }
        }
        shake = maxOf(shake, 5f)
    }

    /** Grants a random permanent (for this run) ship module upgrade. */
    private fun applyRandomUpgrade() {
        val options = mutableListOf<Int>()
        if (engineUp < 2) options.add(0)
        if (cannonUp < 2) options.add(1)
        if (wingUp < 2) options.add(2)
        if (hullUp < 2) options.add(3)
        if (options.isEmpty()) {
            addScore(250)
            addFloat("SISTEMAS NO MÁXIMO +250", playerX, playerY - 80 * scale, powerColor(PowerType.PART))
            return
        }
        when (options.random()) {
            0 -> { engineUp++; addFloat("MOTOR IÔNICO Nv$engineUp", playerX, playerY - 80 * scale, Color.rgb(255, 170, 60)) }
            1 -> { cannonUp++; addFloat("CANHÃO DE PLASMA Nv$cannonUp", playerX, playerY - 80 * scale, Color.rgb(120, 255, 200)) }
            2 -> { wingUp++; addFloat("ASAS DE COMBATE Nv$wingUp", playerX, playerY - 80 * scale, Color.rgb(89, 229, 255)) }
            else -> { hullUp++; shieldUp = true; addFloat("CASCO REFORÇADO Nv$hullUp", playerX, playerY - 80 * scale, Color.rgb(111, 168, 255)) }
        }
        flashAlpha = maxOf(flashAlpha, 0.25f)
        shake = maxOf(shake, 8f)
        hitStop = maxOf(hitStop, 0.08f)
    }

    /** Shockwave that damages every enemy on screen and clears hostile fire. */
    private fun applyNova() {
        for (inv in invaders.toList()) {
            if (inv.alive) damageInvader(inv, 2)
        }
        for (b in enemyBullets) spawnSparks(b.x, b.y, Color.rgb(255, 150, 60), count = 2, small = true)
        enemyBullets.clear()
        val bs = boss
        if (bs != null) {
            bs.hp -= 6
            spawnSparks(bs.x, bs.y, Color.rgb(199, 125, 255), count = 20, small = false)
            if (bs.hp <= 0) bossDeath(bs)
        }
        val cx = w / 2f
        val cy = h / 2f
        particles.add(Particle(cx, cy, 0f, 0f, 30f * scale, 0.6f, 0.6f, Color.rgb(199, 125, 255), 0f).apply { isRing = true })
        particles.add(Particle(cx, cy, 0f, 0f, 10f * scale, 0.45f, 0.45f, Color.WHITE, 0f).apply { isRing = true })
        flashAlpha = 0.35f
        shake = maxOf(shake, 14f)
        hitStop = 0.12f
        screenPunch = 1.4f
        addFloat("NOVA CÓSMICA!", cx, cy - 60 * scale, Color.rgb(199, 125, 255))
    }

    private fun addFloat(text: String, x: Float, y: Float, color: Int) {
        floatTexts.add(FloatText(text, x.coerceIn(140 * scale, w - 140 * scale), y, color))
    }

    private fun updateFloatTexts(dt: Float) {
        floatTexts.removeAll { t ->
            t.life -= dt
            t.y -= 46f * scale * dt
            t.life <= 0f
        }
    }

    private fun updateMeteors(dt: Float) {
        meteorTimer -= dt
        if (meteorTimer <= 0f) {
            val fromLeft = Random.nextBoolean()
            val speed = (650f + Random.nextFloat() * 550f) * scale
            val angle = Math.toRadians((if (fromLeft) 25.0 else 155.0) + Random.nextDouble() * 24.0 - 12.0)
            meteors.add(
                Meteor(
                    x = if (fromLeft) -40f else w + 40f,
                    y = Random.nextFloat() * h * 0.45f,
                    vx = cos(angle.toFloat()) * speed,
                    vy = abs(sin(angle.toFloat())) * speed,
                    len = (90f + Random.nextFloat() * 130f) * scale
                )
            )
            meteorTimer = Random.nextFloat() * 5f + 4f
        }
        meteors.removeAll { m ->
            m.x += m.vx * dt
            m.y += m.vy * dt
            m.x < -250f || m.x > w + 250f || m.y > h + 250f
        }
    }

    private fun updateDust(dt: Float) {
        for (d in dust) {
            d.x += d.drift * dt
            d.y += d.speed * scale * dt
            if (d.y > h + d.radius) {
                d.y = -d.radius
                d.x = Random.nextFloat() * w
            }
            if (d.x < -d.radius) d.x = w + d.radius
            if (d.x > w + d.radius) d.x = -d.radius
        }
    }

    // ---------- Particles ----------

    private fun explode(x: Float, y: Float, color: Int, big: Boolean = false, huge: Boolean = false) {
        val count = when {
            huge -> 140
            big -> 46
            else -> 24
        }
        repeat(count) {
            val angle = Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = (Random.nextFloat() + 0.3f) * (if (huge) 520f else if (big) 340f else 220f) * scale
            particles.add(
                Particle(x, y, cos(angle) * speed, sin(angle) * speed,
                    (Random.nextFloat() * 5f + 2.5f) * scale * (if (big) 1.6f else 1f),
                    Random.nextFloat() * 0.5f + 0.45f, 0f, color, 260f * scale
                ).also { it.maxLife = it.life }
            )
        }
        particles.add(Particle(x, y, 0f, 0f, 8f * scale, 0.35f, 0.35f, color, 0f).apply { isRing = true })
    }

    private fun spawnSparks(x: Float, y: Float, color: Int, count: Int, small: Boolean, spreadUp: Boolean = false) {
        repeat(count) {
            val angle = if (spreadUp) (-Math.PI.toFloat()) + (Random.nextFloat() - 0.5f) * 1.2f
            else Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = (Random.nextFloat() + 0.2f) * 260f * scale
            particles.add(
                Particle(x, y, cos(angle) * speed, sin(angle) * speed,
                    (Random.nextFloat() * 3f + 1.5f) * scale * (if (small) 0.7f else 1f),
                    Random.nextFloat() * 0.25f + 0.2f, 0.45f, color, 150f * scale
                ).apply { maxLife = life }
            )
        }
    }

    private fun addScore(points: Int) {
        score += points
    }

    // ---------- Draw ----------

    private fun draw() {
        val canvas = try {
            holder.lockHardwareCanvas() ?: return
        } catch (_: Exception) {
            return
        }
        try {
            val err = fatal
            if (err != null) {
                // Show the error on screen so it can be reported without adb
                canvas.drawColor(Color.BLACK)
                val ep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(255, 70, 70)
                    textSize = 34f * scale
                }
                var y = 80f * scale
                for (line in err.toString().chunked(40)) {
                    canvas.drawText(line, 30f, y, ep)
                    y += 44f * scale
                    if (y > h - 60f) break
                }
                ep.color = Color.WHITE
                for (el in err.stackTrace.take(6)) {
                    canvas.drawText("${el.className.substringAfterLast('.')}.${el.methodName}:${el.lineNumber}", 30f, y, ep)
                    y += 38f * scale
                    if (y > h - 20f) break
                }
                return
            }

            canvas.save()
            // Screen punch: quick zoom on big impacts
            if (screenPunch > 0.01f) {
                val z = 1f + screenPunch * 0.035f
                canvas.translate(w / 2f, h / 2f)
                canvas.scale(z, z)
                canvas.translate(-w / 2f, -h / 2f)
            }
            if (shake > 0.5f) {
                canvas.translate(
                    (Random.nextFloat() - 0.5f) * shake,
                    (Random.nextFloat() - 0.5f) * shake
                )
            }

            drawBackground(canvas)
            drawStars(canvas)
            drawMeteors(canvas)
            drawDust(canvas)
            drawPlanet(canvas)
            drawGalaxies(canvas)
            drawParticlesBelow(canvas)

            when (state) {
                State.PLAYING -> {
                    drawUfo(canvas)
                    drawBoss(canvas)
                    drawInvaders(canvas)
                    drawPowerUps(canvas)
                    drawPlayer(canvas)
                    drawBullets(canvas)
                }
                State.GAME_OVER -> drawGameOver(canvas)
            }

            drawParticlesAbove(canvas)
            drawDebris(canvas)
            drawFloatTexts(canvas)
            drawHud(canvas)

            canvas.restore()

            // Screen space: reactive ambience tint, then vignette
            fillPaint.style = Paint.Style.FILL
            fillPaint.shader = null
            setShadow(null)
            if (ambR + ambG + ambB > 1f) {
                fillPaint.color = Color.rgb(
                    ambR.toInt().coerceIn(0, 255),
                    ambG.toInt().coerceIn(0, 255),
                    ambB.toInt().coerceIn(0, 255)
                )
                fillPaint.alpha = 26
                canvas.drawRect(0f, 0f, w, h, fillPaint)
                fillPaint.alpha = 255
            }
            canvas.drawRect(0f, 0f, w, h, vignettePaint)
            if (damagePulse > 0.01f) {
                val alpha = (damagePulse * 170).toInt().coerceIn(0, 255)
                val saved = canvas.saveLayerAlpha(0f, 0f, w, h, alpha)
                canvas.drawRect(0f, 0f, w, h, damagePaint)
                canvas.restoreToCount(saved)
            }

            if (flashAlpha > 0.01f) {
                fillPaint.color = Color.argb((flashAlpha * 255).toInt(), 235, 245, 255)
                canvas.drawRect(0f, 0f, w, h, fillPaint)
            }
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
            }
        }
    }

    private fun drawBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        val drift = min(w, h) * 0.04f
        for (i in nebulae.indices) {
            val ox = sin(bgTime * 0.11f + i * 2.1f) * drift + camX * 0.12f
            val oy = cos(bgTime * 0.07f + i * 1.7f) * drift
            canvas.save()
            canvas.translate(ox, oy)
            canvas.drawRect(-drift, -drift, w + drift, h + drift, nebulae[i])
            canvas.restore()
        }
    }

    private fun drawStars(canvas: Canvas) {
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        for (s in stars) {
            val twk = 0.65f + 0.35f * sin(bgTime * 1.7f + s.seed)
            fillPaint.color = Color.WHITE
            fillPaint.alpha = (s.alpha * twk).toInt()
            canvas.drawCircle(s.x + camX * 0.3f * s.z, s.y, s.radius * (0.6f + s.z * 0.4f), fillPaint)
        }
        fillPaint.alpha = 255
    }

    private fun drawShadowRect(canvas: Canvas, x: Float, y: Float, hw: Float, hh: Float) {
        fillPaint.shader = null
        setShadow(null)
        fillPaint.color = Color.argb(90, 0, 0, 0)
        fillPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(
            x - hw + 7 * scale, y - hh + 11 * scale,
            x + hw + 7 * scale, y + hh + 11 * scale,
            hh, hh, fillPaint
        )
    }

    private fun drawShadowCircle(canvas: Canvas, x: Float, y: Float, r: Float) {
        fillPaint.shader = null
        setShadow(null)
        fillPaint.color = Color.argb(90, 0, 0, 0)
        fillPaint.style = Paint.Style.FILL
        canvas.drawCircle(x + 7 * scale, y + 11 * scale, r, fillPaint)
    }

    private fun drawPlayer(canvas: Canvas) {
        val blink = invincible > 0f && ((invincible * 10f).toInt() % 2 == 0)
        if (blink) return

        val x = playerX
        val y = playerY
        val half = playerW

        // Ground shadow
        drawShadowEllipse(canvas, x, y + half * 0.72f, half * 1.25f, half * 0.2f)

        // Aura
        glowPaint.shader = RadialGradient(
            x, y, half * 2.2f,
            Color.argb(90, 0, 255, 190), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        fillPaint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, half * 2.2f, glowPaint)

        // Engine flames (behind hull, unrotated) — ion engine upgrades make them bigger
        setShadow(Color.rgb(255, 170, 40))
        fillPaint.shader = null
        if (engineUp > 0) {
            glowPaint.shader = RadialGradient(
                x, y + half * 0.5f, half * (0.7f + 0.25f * engineUp),
                Color.argb(70 + engineUp * 25, 255, 160, 40), Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y + half * 0.5f, half * (0.7f + 0.25f * engineUp), glowPaint)
        }
        val flameScale = 1f + 0.3f * engineUp
        for (side in floatArrayOf(-0.34f, 0.34f)) {
            fillPaint.color = Color.rgb(255, 190, 60)
            val fl = half * (0.4f + Random.nextFloat() * 0.3f) * flameScale
            val fx = x + side * half
            val flamePath = Path().apply {
                moveTo(fx - half * 0.13f, y + half * 0.42f)
                lineTo(fx, y + half * 0.42f + fl)
                lineTo(fx + half * 0.13f, y + half * 0.42f)
                close()
            }
            canvas.drawPath(flamePath, fillPaint)
        }
        setShadow(null)

        // Shield bubble
        if (shieldUp) {
            fillPaint.style = Paint.Style.STROKE
            val shimmer = 90 + sin(bgTime * 6f) * 40f
            fillPaint.strokeWidth = 3.5f * scale
            fillPaint.color = Color.argb(shimmer.toInt().coerceIn(0, 255), 120, 180, 255)
            canvas.drawCircle(x, y - half * 0.1f, half * 1.55f, fillPaint)
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = Color.argb(26, 110, 170, 255)
            canvas.drawCircle(x, y - half * 0.1f, half * 1.55f, fillPaint)
        }

        // Banking tilt for a pseudo-3D feel
        val bank = ((targetX - playerX) / playerW).coerceIn(-1f, 1f) * 16f
        canvas.save()
        canvas.rotate(bank, x, y)

        // Wings with metallic gradient — combat wings widen and gain tip cannons
        val span = 1f + 0.12f * wingUp
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = LinearGradient(
            x - half * span, y - half, x + half * span, y + half,
            intArrayOf(
                Color.rgb(190, 255, 240),
                Color.rgb(0, 210, 175),
                Color.rgb(0, 90, 110)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        val wings = Path().apply {
            moveTo(x, y - half * 0.55f)
            lineTo(x - half * 1.08f * span, y + half * 0.62f)
            lineTo(x - half * 0.42f, y + half * 0.52f)
            lineTo(x, y + half * 0.18f)
            lineTo(x + half * 0.42f, y + half * 0.52f)
            lineTo(x + half * 1.08f * span, y + half * 0.62f)
            close()
        }
        setShadow(Color.rgb(0, 220, 180))
        canvas.drawPath(wings, fillPaint)

        // Wingtip navigation lights (red left, green right)
        fillPaint.shader = null
        setShadow(null)
        fillPaint.color = Color.rgb(255, 60, 60)
        canvas.drawCircle(x - half * 1.02f * span, y + half * 0.58f, half * 0.09f, fillPaint)
        fillPaint.color = Color.rgb(70, 255, 110)
        canvas.drawCircle(x + half * 1.02f * span, y + half * 0.58f, half * 0.09f, fillPaint)

        // Wing tip cannons
        if (wingUp > 0) {
            setShadow(Color.rgb(120, 255, 200))
            fillPaint.color = Color.rgb(20, 120, 105)
            for (side in floatArrayOf(-1f, 1f)) {
                canvas.drawRoundRect(
                    x + side * half * span - half * 0.06f, y + half * 0.3f,
                    x + side * half * span + half * 0.06f, y + half * 0.62f,
                    half * 0.05f, half * 0.05f, fillPaint
                )
            }
            setShadow(null)
        }

        // Hull side pods — reinforced armor
        if (hullUp > 0) {
            fillPaint.style = Paint.Style.FILL
            fillPaint.shader = LinearGradient(
                x - half, y, x + half, y + half,
                intArrayOf(
                    Color.rgb(150, 200, 190),
                    Color.rgb(40, 110, 120)
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            setShadow(Color.rgb(60, 160, 170))
            for (side in floatArrayOf(-1f, 1f)) {
                canvas.drawRoundRect(
                    x + side * half * 0.92f - half * 0.14f, y - half * 0.05f,
                    x + side * half * 0.92f + half * 0.14f, y + half * 0.55f,
                    half * 0.1f, half * 0.1f, fillPaint
                )
            }
            fillPaint.shader = null
            setShadow(null)
        }

        // Fuselage with vertical metallic gradient
        fillPaint.shader = LinearGradient(
            x, y - half, x, y + half,
            intArrayOf(
                Color.rgb(235, 255, 252),
                Color.rgb(90, 235, 205),
                Color.rgb(0, 110, 130)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        setShadow(Color.rgb(0, 230, 195))
        val fuselage = Path().apply {
            moveTo(x, y - half * 0.95f)
            lineTo(x - half * 0.34f, y + half * 0.48f)
            lineTo(x + half * 0.34f, y + half * 0.48f)
            close()
        }
        canvas.drawPath(fuselage, fillPaint)
        fillPaint.shader = null
        setShadow(null)

        // Plasma cannon barrels on the nose
        if (cannonUp > 0) {
            setShadow(Color.rgb(120, 255, 200))
            fillPaint.color = Color.rgb(15, 95, 85)
            val barrelOffsets = if (cannonUp >= 2) floatArrayOf(-0.12f, 0.12f) else floatArrayOf(0f)
            for (off in barrelOffsets) {
                canvas.drawRoundRect(
                    x + off * half - half * 0.045f, y - half * 1.12f,
                    x + off * half + half * 0.045f, y - half * 0.55f,
                    half * 0.04f, half * 0.04f, fillPaint
                )
            }
            setShadow(null)
        }

        // Spine highlight
        fillPaint.shader = null
        setShadow(null)
        fillPaint.strokeWidth = half * 0.07f
        fillPaint.style = Paint.Style.STROKE
        fillPaint.color = Color.argb(150, 255, 255, 255)
        canvas.drawLine(x, y - half * 0.78f, x, y + half * 0.2f, fillPaint)
        fillPaint.style = Paint.Style.FILL

        // Glass canopy
        fillPaint.shader = RadialGradient(
            x - half * 0.05f, y - half * 0.28f, half * 0.42f,
            intArrayOf(
                Color.WHITE,
                Color.rgb(140, 230, 255),
                Color.argb(230, 10, 60, 110)
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(
            x - half * 0.19f, y - half * 0.46f,
            x + half * 0.19f, y + half * 0.06f, fillPaint
        )

        // Canopy specular glint
        fillPaint.shader = null
        fillPaint.color = Color.argb(220, 255, 255, 255)
        canvas.drawCircle(x - half * 0.07f, y - half * 0.33f, half * 0.045f, fillPaint)

        fillPaint.shader = null
        canvas.restore()
    }

    private fun drawShadowEllipse(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float) {
        fillPaint.shader = null
        setShadow(null)
        fillPaint.color = Color.argb(100, 0, 0, 0)
        fillPaint.style = Paint.Style.FILL
        canvas.drawOval(x - rx, y - ry, x + rx, y + ry, fillPaint)
    }

    private fun drawInvaders(canvas: Canvas) {
        for (inv in invaders) {
            if (!inv.alive) continue
            when (inv.variant) {
                0 -> drawCrab(canvas, inv)
                1 -> drawSquid(canvas, inv)
                2 -> drawArmored(canvas, inv)
                3 -> drawHunter(canvas, inv)
                4 -> drawBomber(canvas, inv)
                else -> drawSquid(canvas, inv)
            }
        }
        setShadow(null)
        fillPaint.shader = null
    }

    private fun drawHunter(canvas: Canvas, inv: Invader) {
        val s = inv.size
        drawShadowCircle(canvas, inv.x, inv.y, s * 0.7f)
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = LinearGradient(
            inv.x, inv.y - s, inv.x, inv.y + s,
            intArrayOf(shade(inv.color, 1.6f), inv.color, shade(inv.color, 0.4f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        setShadow(inv.color)
        // Dart-shaped attack craft pointing down
        val dart = Path().apply {
            moveTo(inv.x, inv.y + s * 0.95f)
            lineTo(inv.x - s * 0.85f, inv.y - s * 0.45f)
            lineTo(inv.x - s * 0.3f, inv.y - s * 0.55f)
            lineTo(inv.x, inv.y - s * 0.2f)
            lineTo(inv.x + s * 0.3f, inv.y - s * 0.55f)
            lineTo(inv.x + s * 0.85f, inv.y - s * 0.45f)
            close()
        }
        canvas.drawPath(dart, fillPaint)
        fillPaint.shader = null
        setShadow(null)
        // Menacing eye slit
        fillPaint.color = Color.BLACK
        canvas.drawRoundRect(
            inv.x - s * 0.4f, inv.y - s * 0.15f,
            inv.x + s * 0.4f, inv.y + s * 0.05f,
            s * 0.1f, s * 0.1f, fillPaint
        )
        fillPaint.color = Color.rgb(255, 240, 150)
        canvas.drawRoundRect(
            inv.x - s * 0.3f, inv.y - s * 0.1f,
            inv.x + s * 0.3f, inv.y - s * 0.02f,
            s * 0.06f, s * 0.06f, fillPaint
        )
    }

    private fun drawBomber(canvas: Canvas, inv: Invader) {
        val s = inv.size
        drawShadowRect(canvas, inv.x, inv.y, s * 0.95f, s * 0.55f)
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = RadialGradient(
            inv.x - s * 0.3f, inv.y - s * 0.3f, s * 0.1f,
            inv.x, inv.y, s * 1.2f,
            intArrayOf(shade(inv.color, 1.6f), inv.color, shade(inv.color, 0.35f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        setShadow(inv.color)
        canvas.drawRoundRect(
            inv.x - s * 0.95f, inv.y - s * 0.55f,
            inv.x + s * 0.95f, inv.y + s * 0.4f,
            s * 0.4f, s * 0.4f, fillPaint
        )
        fillPaint.shader = null
        setShadow(null)
        // Bomb bay with blinking payload
        fillPaint.color = Color.argb(220, 20, 10, 10)
        canvas.drawCircle(inv.x, inv.y + s * 0.05f, s * 0.28f, fillPaint)
        fillPaint.color = if (sin(inv.pulse * 4f) > 0f) Color.rgb(255, 90, 40) else Color.rgb(120, 40, 20)
        canvas.drawCircle(inv.x, inv.y + s * 0.05f, s * 0.14f, fillPaint)
        // HP pips for the tank
        fillPaint.color = Color.WHITE
        for (i in 0 until inv.hp) {
            canvas.drawCircle(inv.x - s * 0.3f + i * s * 0.3f, inv.y - s * 0.35f, s * 0.07f, fillPaint)
        }
    }

    private fun drawCrab(canvas: Canvas, inv: Invader) {
        val s = inv.size
        val pulse = 1f + sin(inv.pulse) * 0.06f
        drawShadowRect(canvas, inv.x, inv.y, s * pulse, s * 0.5f)

        // Shell with spherical shading
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = LinearGradient(
            inv.x - s, inv.y - s, inv.x + s, inv.y + s,
            intArrayOf(shade(inv.color, 1.55f), inv.color, shade(inv.color, 0.4f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        setShadow(inv.color)
        canvas.drawRoundRect(
            inv.x - s * pulse, inv.y - s * 0.55f * pulse,
            inv.x + s * pulse, inv.y + s * 0.30f * pulse,
            s * 0.3f, s * 0.3f, fillPaint
        )
        fillPaint.shader = null

        // Glossy highlight arc
        setShadow(null)
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = s * 0.12f
        fillPaint.color = Color.argb(110, 255, 255, 255)
        canvas.drawArc(
            inv.x - s * 0.72f * pulse, inv.y - s * 0.5f * pulse,
            inv.x + s * 0.72f * pulse, inv.y + s * 0.25f * pulse,
            200f, 110f, false, fillPaint
        )

        // Antennas and legs (dark limbs)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = shade(inv.color, 0.55f)
        val limbPaintStroke = s * 0.14f
        fillPaint.strokeWidth = limbPaintStroke
        val legSwing = sin(inv.pulse * 1.5f) * s * 0.15f
        canvas.drawLine(inv.x - s * 0.45f, inv.y - s * 0.5f, inv.x - s * 0.75f, inv.y - s * 0.95f + sin(inv.pulse) * s * 0.1f, fillPaint)
        canvas.drawLine(inv.x + s * 0.45f, inv.y - s * 0.5f, inv.x + s * 0.75f, inv.y - s * 0.95f - sin(inv.pulse) * s * 0.1f, fillPaint)
        canvas.drawLine(inv.x - s * 0.5f, inv.y + s * 0.2f, inv.x - s * 0.85f, inv.y + s * 0.75f + legSwing, fillPaint)
        canvas.drawLine(inv.x - s * 0.2f, inv.y + s * 0.25f, inv.x - s * 0.35f, inv.y + s * 0.85f - legSwing, fillPaint)
        canvas.drawLine(inv.x + s * 0.2f, inv.y + s * 0.25f, inv.x + s * 0.35f, inv.y + s * 0.85f + legSwing, fillPaint)
        canvas.drawLine(inv.x + s * 0.5f, inv.y + s * 0.2f, inv.x + s * 0.85f, inv.y + s * 0.75f - legSwing, fillPaint)

        // Antenna tips glowing
        setShadow(inv.color)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = shade(inv.color, 1.7f)
        canvas.drawCircle(inv.x - s * 0.75f, inv.y - s * 0.95f + sin(inv.pulse) * s * 0.1f, s * 0.1f, fillPaint)
        canvas.drawCircle(inv.x + s * 0.75f, inv.y - s * 0.95f - sin(inv.pulse) * s * 0.1f, s * 0.1f, fillPaint)

        // Eyes with glint
        setShadow(null)
        fillPaint.color = Color.BLACK
        canvas.drawCircle(inv.x - s * 0.3f, inv.y - s * 0.12f, s * 0.13f, fillPaint)
        canvas.drawCircle(inv.x + s * 0.3f, inv.y - s * 0.12f, s * 0.13f, fillPaint)
        fillPaint.color = Color.argb(200, 255, 255, 255)
        canvas.drawCircle(inv.x - s * 0.34f, inv.y - s * 0.16f, s * 0.035f, fillPaint)
        canvas.drawCircle(inv.x + s * 0.26f, inv.y - s * 0.16f, s * 0.035f, fillPaint)
    }

    private fun drawSquid(canvas: Canvas, inv: Invader) {
        val s = inv.size
        drawShadowCircle(canvas, inv.x, inv.y, s * 0.62f)

        // Translucent outer membrane
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        val memColor = shade(inv.color, 1.6f)
        fillPaint.shader = RadialGradient(
            inv.x, inv.y - s * 0.1f, s * 0.88f,
            intArrayOf(Color.argb(150, memColor shr 16 and 0xFF, memColor shr 8 and 0xFF, memColor and 0xFF), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(inv.x, inv.y - s * 0.1f, s * 0.88f, fillPaint)
        fillPaint.shader = null

        // Tentacles behind head
        fillPaint.color = shade(inv.color, 0.8f)
        setShadow(inv.color)
        for (i in -2..2 step 2) {
            val phase = inv.pulse * 2f + i
            val tipX = inv.x + i * s * 0.32f + sin(phase) * s * 0.14f
            val path = Path().apply {
                moveTo(inv.x + i * s * 0.22f - s * 0.08f, inv.y + s * 0.25f)
                quadTo(inv.x + i * s * 0.3f, inv.y + s * 0.6f, tipX, inv.y + s * 0.85f)
                lineTo(tipX + s * 0.1f, inv.y + s * 0.85f)
                quadTo(inv.x + i * s * 0.3f + s * 0.1f, inv.y + s * 0.6f, inv.x + i * s * 0.22f + s * 0.08f, inv.y + s * 0.25f)
                close()
            }
            canvas.drawPath(path, fillPaint)
            fillPaint.color = shade(inv.color, 1.5f)
            canvas.drawCircle(tipX + s * 0.05f, inv.y + s * 0.85f, s * 0.07f, fillPaint)
            fillPaint.color = shade(inv.color, 0.8f)
        }

        // Head dome with volume shading
        fillPaint.shader = RadialGradient(
            inv.x - s * 0.2f, inv.y - s * 0.3f, s * 0.75f,
            intArrayOf(
                shade(inv.color, 1.7f),
                inv.color,
                shade(inv.color, 0.35f)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(inv.x, inv.y - s * 0.1f, s * 0.62f, fillPaint)
        fillPaint.shader = null

        // Pulsing energy core
        val corePulse = 0.6f + sin(inv.pulse * 2f) * 0.4f
        fillPaint.shader = RadialGradient(
            inv.x, inv.y - s * 0.1f, s * 0.32f,
            intArrayOf(Color.WHITE, shade(inv.color, 1.4f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (120 + corePulse * 100).toInt()
        canvas.drawCircle(inv.x, inv.y - s * 0.1f, s * 0.3f, fillPaint)
        fillPaint.alpha = 255
        fillPaint.shader = null

        // Cyclops eye tracking sideways
        fillPaint.color = Color.BLACK
        canvas.drawCircle(inv.x, inv.y - s * 0.15f, s * 0.22f, fillPaint)
        val look = sin(inv.pulse * 0.8f) * s * 0.09f
        fillPaint.color = Color.WHITE
        canvas.drawCircle(inv.x + look, inv.y - s * 0.15f, s * 0.1f, fillPaint)
        fillPaint.color = Color.BLACK
        canvas.drawCircle(inv.x + look, inv.y - s * 0.15f, s * 0.045f, fillPaint)
    }

    private fun drawArmored(canvas: Canvas, inv: Invader) {
        val s = inv.size
        drawShadowRect(canvas, inv.x, inv.y, s, s * 0.5f)

        // Heavy metallic hull
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = LinearGradient(
            inv.x, inv.y - s * 0.6f, inv.x, inv.y + s * 0.35f,
            intArrayOf(
                shade(inv.color, 1.6f),
                inv.color,
                shade(inv.color, 0.35f)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        setShadow(inv.color)
        canvas.drawRoundRect(
            inv.x - s, inv.y - s * 0.6f,
            inv.x + s, inv.y + s * 0.35f,
            s * 0.18f, s * 0.18f, fillPaint
        )
        fillPaint.shader = null
        setShadow(null)

        // Recessed inner plate with bevel edges
        fillPaint.color = Color.argb(225, 26, 52, 34)
        canvas.drawRoundRect(
            inv.x - s * 0.72f, inv.y - s * 0.38f,
            inv.x + s * 0.72f, inv.y + s * 0.14f,
            s * 0.12f, s * 0.12f, fillPaint
        )
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = s * 0.06f
        fillPaint.color = Color.argb(140, 255, 255, 255)
        canvas.drawLine(inv.x - s * 0.66f, inv.y - s * 0.34f, inv.x + s * 0.66f, inv.y - s * 0.34f, fillPaint)
        fillPaint.color = Color.argb(80, 0, 0, 0)
        canvas.drawLine(inv.x - s * 0.66f, inv.y + s * 0.1f, inv.x + s * 0.66f, inv.y + s * 0.1f, fillPaint)
        fillPaint.style = Paint.Style.FILL

        // Rivets with specular glints
        for (i in -1..1) {
            val bx = inv.x + i * s * 0.5f
            fillPaint.color = Color.argb(255, 200, 230, 195)
            canvas.drawCircle(bx, inv.y - s * 0.12f, s * 0.08f, fillPaint)
            fillPaint.color = Color.argb(220, 255, 255, 255)
            canvas.drawCircle(bx - s * 0.025f, inv.y - s * 0.145f, s * 0.03f, fillPaint)
        }

        // Energy shield when tough
        if (inv.hp > 1) {
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = 3.5f * scale
            fillPaint.color = Color.argb((140 + sin(inv.pulse * 3f) * 60f).toInt(), 160, 255, 140)
            canvas.drawCircle(inv.x, inv.y - s * 0.1f, s * 1.15f, fillPaint)
            fillPaint.style = Paint.Style.FILL
        }
    }

    private fun drawUfo(canvas: Canvas) {
        val saucer = ufo ?: return
        val s = 55f * scale
        val x = saucer.x
        val y = saucer.y

        // Under-glow beam
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        fillPaint.shader = RadialGradient(
            x, y + s * 0.3f, s * 1.4f,
            intArrayOf(Color.argb(90, 120, 255, 240), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y + s * 0.3f, s * 1.4f, fillPaint)
        fillPaint.shader = null

        // Metallic saucer body
        fillPaint.shader = LinearGradient(
            x, y - s * 0.28f, x, y + s * 0.34f,
            intArrayOf(
                Color.rgb(230, 255, 252),
                Color.rgb(130, 245, 235),
                Color.rgb(10, 90, 105)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        setShadow(Color.rgb(120, 255, 240))
        canvas.drawOval(x - s, y - s * 0.28f, x + s, y + s * 0.34f, fillPaint)
        fillPaint.shader = null
        setShadow(null)

        // Glass dome with reflection
        fillPaint.shader = RadialGradient(
            x - s * 0.12f, y - s * 0.45f, s * 0.55f,
            intArrayOf(Color.WHITE, Color.rgb(255, 110, 220), Color.argb(200, 90, 10, 70)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawArc(x - s * 0.42f, y - s * 0.75f, x + s * 0.42f, y + s * 0.1f, 180f, 180f, true, fillPaint)
        fillPaint.shader = null

        // Running lights
        for (i in -2..2) {
            val on = ((saucer.blink + i).toInt() % 3) == 0
            fillPaint.color = if (on) Color.rgb(255, 240, 120) else Color.rgb(120, 90, 40)
            canvas.drawCircle(x + i * s * 0.38f, y + s * 0.05f, s * 0.09f, fillPaint)
            if (on) {
                fillPaint.color = Color.argb(120, 255, 240, 120)
                canvas.drawCircle(x + i * s * 0.38f, y + s * 0.05f, s * 0.16f, fillPaint)
            }
        }
    }

    private fun drawBullets(canvas: Canvas) {
        setShadow(null)
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = 3f * scale
        for (b in bullets) {
            for ((i, ty) in b.trail.withIndex()) {
                fillPaint.color = b.color
                fillPaint.alpha = (70 * (1f - i.toFloat() / b.trail.size)).toInt()
                canvas.drawLine(b.x, ty, b.x, ty + 8f * scale, fillPaint)
            }
            setShadow(b.color)
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = b.color
            fillPaint.alpha = 255
            when {
                b.pierce -> { // Laser beam bolt
                    fillPaint.color = Color.WHITE
                    canvas.drawRoundRect(
                        b.x - 2.5f * scale, b.y - 30f * scale,
                        b.x + 2.5f * scale, b.y + 14f * scale,
                        2.5f * scale, 2.5f * scale, fillPaint
                    )
                    fillPaint.color = b.color
                    fillPaint.alpha = 130
                    canvas.drawRoundRect(
                        b.x - 6f * scale, b.y - 34f * scale,
                        b.x + 6f * scale, b.y + 18f * scale,
                        6f * scale, 6f * scale, fillPaint
                    )
                    fillPaint.alpha = 255
                }
                b.homing -> { // Missile rocket
                    val rocket = Path().apply {
                        moveTo(b.x, b.y - 14f * scale)
                        lineTo(b.x - 5f * scale, b.y + 8f * scale)
                        lineTo(b.x + 5f * scale, b.y + 8f * scale)
                        close()
                    }
                    canvas.drawPath(rocket, fillPaint)
                    fillPaint.color = Color.rgb(255, 220, 130)
                    canvas.drawCircle(b.x, b.y + 10f * scale, (3f + Random.nextFloat() * 3f) * scale, fillPaint)
                }
                else -> canvas.drawRoundRect(
                    b.x - 4f * scale, b.y - 16f * scale,
                    b.x + 4f * scale, b.y + 10f * scale,
                    4f * scale, 4f * scale, fillPaint
                )
            }
            fillPaint.style = Paint.Style.STROKE
            setShadow(null)
        }

        setShadow(Color.rgb(255, 90, 60))
        fillPaint.style = Paint.Style.FILL
        for (b in enemyBullets) {
            fillPaint.color = b.color
            canvas.drawCircle(b.x, b.y, 8f * scale, fillPaint)
        }
        fillPaint.shader = null
        setShadow(null)
    }

    private fun drawParticlesBelow(canvas: Canvas) {
        fillPaint.style = Paint.Style.FILL
        for (p in particles) {
            if (p.isRing) continue
            val t = p.life / p.maxLife
            fillPaint.color = p.color
            fillPaint.alpha = (t * 230).toInt()
            canvas.drawCircle(p.x, p.y, p.radius * (0.4f + t * 0.6f), fillPaint)
        }
        fillPaint.alpha = 255
    }

    private fun drawParticlesAbove(canvas: Canvas) {
        fillPaint.style = Paint.Style.STROKE
        for (p in particles) {
            if (!p.isRing) continue
            val t = 1f - p.life / p.maxLife
            fillPaint.strokeWidth = (6f * (1f - t) + 1f) * scale
            fillPaint.color = p.color
            fillPaint.alpha = ((1f - t) * 200).toInt()
            canvas.drawCircle(p.x, p.y, p.radius + t * 90f * scale, fillPaint)
        }
        fillPaint.style = Paint.Style.FILL
        fillPaint.alpha = 255
    }

    private fun drawGalaxies(canvas: Canvas) {
        val gals = arrayOf(
            floatArrayOf(w * 0.74f + camX * 0.22f, h * 0.28f, minDim * 0.17f, 14f),
            floatArrayOf(w * 0.16f + camX * 0.22f, h * 0.76f, minDim * 0.115f, -22f)
        )
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        for (i in gals.indices) {
            val g = gals[i]
            canvas.save()
            canvas.translate(g[0], g[1])
            canvas.rotate(bgTime * g[3])
            canvas.scale(1f, 0.42f)
            // Spiral arms
            fillPaint.style = Paint.Style.STROKE
            for (arm in 0..2) {
                fillPaint.strokeWidth = (2.5f - arm) * scale + 1f
                fillPaint.color = Color.argb(46 - arm * 12, 200, 180, 255)
                canvas.drawCircle(0f, 0f, g[2] * (0.45f + arm * 0.24f), fillPaint)
            }
            // Glowing core (drawn unrotated)
            fillPaint.style = Paint.Style.FILL
            canvas.restore()
            fillPaint.shader = galaxyPaints[i].shader
            if (fillPaint.shader != null) canvas.drawCircle(g[0], g[1], g[2], fillPaint)
            fillPaint.shader = null
        }
    }

    private fun drawPlanet(canvas: Canvas) {
        val px = w * 1.04f + camX * 0.3f
        val py = -h * 0.24f
        val pr = h * 0.44f
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        fillPaint.shader = planetPaint.shader
        if (fillPaint.shader != null) {
            canvas.drawCircle(px, py, pr, fillPaint)
        }
        fillPaint.shader = null
        // Atmosphere rim
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = 3f * scale
        fillPaint.color = Color.argb(80, 130, 210, 255)
        canvas.drawCircle(px, py, pr + 2f * scale, fillPaint)
        fillPaint.strokeWidth = 9f * scale
        fillPaint.color = Color.argb(26, 90, 170, 255)
        canvas.drawCircle(px, py, pr + 7f * scale, fillPaint)
        fillPaint.style = Paint.Style.FILL
    }

    private fun drawMeteors(canvas: Canvas) {
        fillPaint.style = Paint.Style.STROKE
        setShadow(null)
        for (m in meteors) {
            val nx = m.vx
            val ny = m.vy
            val nLen = hypot(nx, ny)
            val tx = m.x - nx / nLen * m.len
            val ty = m.y - ny / nLen * m.len
            fillPaint.shader = LinearGradient(
                m.x, m.y, tx, ty,
                intArrayOf(Color.WHITE, Color.argb(150, 150, 220, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.25f, 1f),
                Shader.TileMode.CLAMP
            )
            fillPaint.strokeWidth = 2.5f * scale
            canvas.drawLine(m.x, m.y, tx, ty, fillPaint)
            fillPaint.shader = null
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = Color.WHITE
            canvas.drawCircle(m.x, m.y, 3f * scale, fillPaint)
            fillPaint.style = Paint.Style.STROKE
        }
        fillPaint.style = Paint.Style.FILL
    }

    private fun drawDust(canvas: Canvas) {
        setShadow(null)
        for (d in dust) {
            fillPaint.color = Color.argb(34, 190, 210, 255)
            canvas.drawCircle(d.x + camX * 0.8f, d.y, d.radius, fillPaint)
        }
    }

    private fun drawBoss(canvas: Canvas) {
        val b = boss ?: return
        val s = 100f * scale
        drawShadowEllipse(canvas, b.x, b.y + s * 0.2f, s * 1.3f, s * 0.3f)

        // Menacing hull
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = LinearGradient(
            b.x, b.y - s * 0.6f, b.x, b.y + s * 0.6f,
            intArrayOf(
                Color.rgb(90, 45, 130),
                Color.rgb(40, 18, 66),
                Color.rgb(12, 6, 22)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        setShadow(Color.rgb(255, 80, 180))
        canvas.drawOval(b.x - s * 1.3f, b.y - s * 0.5f, b.x + s * 1.3f, b.y + s * 0.55f, fillPaint)
        fillPaint.shader = null
        setShadow(null)

        // Side cannons
        fillPaint.color = Color.rgb(25, 12, 40)
        for (side in floatArrayOf(-1f, 1f)) {
            canvas.drawCircle(b.x + side * s * 1.05f, b.y + s * 0.1f, s * 0.22f, fillPaint)
        }

        // Pulsing weak-point core
        val corePulse = 0.7f + sin(b.pulse * 2f) * 0.3f
        fillPaint.shader = RadialGradient(
            b.x, b.y, s * 0.4f,
            intArrayOf(Color.WHITE, Color.rgb(255, 110, 200), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.alpha = (140 + corePulse * 100).toInt()
        canvas.drawCircle(b.x, b.y, s * 0.4f, fillPaint)
        fillPaint.alpha = 255
        fillPaint.shader = null

        // Spikes
        fillPaint.color = Color.rgb(60, 30, 90)
        for (i in -2..2) {
            if (i == 0) continue
            canvas.drawRect(b.x + i * s * 0.5f - s * 0.05f, b.y - s * 0.75f, b.x + i * s * 0.5f + s * 0.05f, b.y - s * 0.4f, fillPaint)
        }
    }

    /** Foreground layer: fast drifting rocks that sell the sense of depth. */    private fun drawDebris(canvas: Canvas) {
        setShadow(null)
        for (d in debris) {
            canvas.save()
            canvas.translate(d.x + camX * 1.35f, d.y)
            canvas.rotate(d.rot)
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = Color.argb(d.alpha, 150, 172, 205)
            canvas.drawRoundRect(
                -d.size, -d.size * 0.65f, d.size, d.size * 0.65f,
                d.size * 0.4f, d.size * 0.4f, fillPaint
            )
            fillPaint.color = Color.argb(d.alpha / 2, 90, 105, 135)
            canvas.drawCircle(-d.size * 0.2f, d.size * 0.1f, d.size * 0.45f, fillPaint)
            // Motion streak
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = 2f * scale
            fillPaint.color = Color.argb(d.alpha / 2, 190, 210, 240)
            canvas.drawLine(0f, -d.size, 0f, -d.size * 2.4f, fillPaint)
            canvas.restore()
        }
        fillPaint.style = Paint.Style.FILL
    }

    private fun drawPowerUps(canvas: Canvas) {
        val s = 24f * scale
        for (p in powerUps) {
            val color = powerColor(p.type)
            val bobY = p.y + sin(p.phase * 1.4f) * 6f * scale
            drawShadowCircle(canvas, p.x, bobY, s)
            setShadow(color)

            // Rotating diamond capsule
            canvas.save()
            canvas.translate(p.x, bobY)
            canvas.rotate((p.phase * 40f) % 360f)
            fillPaint.style = Paint.Style.FILL
            fillPaint.shader = LinearGradient(
                -s, -s, s, s,
                intArrayOf(shade(color, 1.5f), color, shade(color, 0.5f)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(-s, -s, s, s, s * 0.35f, s * 0.35f, fillPaint)
            fillPaint.shader = null
            canvas.restore()

            // Pulsing halo ring
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = 2.5f * scale
            fillPaint.color = Color.argb((110 + sin(p.phase * 2.4f) * 70f).toInt().coerceIn(0, 255), color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)
            canvas.drawCircle(p.x, bobY, s * 1.55f + sin(p.phase * 2.4f) * 4f * scale, fillPaint)
            fillPaint.style = Paint.Style.FILL

            // Icon glyph
            setShadow(null)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = s * 1.15f
            textPaint.setShadowLayer(8f, 0f, 0f, Color.BLACK)
            textPaint.color = Color.WHITE
            val glyph = when (p.type) {
                PowerType.RAPID -> "R"
                PowerType.TRIPLE -> "T"
                PowerType.SHIELD -> "S"
                PowerType.LIFE -> "\u2665"
                PowerType.NOVA -> "N"
                PowerType.PART -> "P"
                PowerType.WEAPON -> "W"
                PowerType.ARMOR -> "A"
            }
            canvas.drawText(glyph, p.x, bobY + s * 0.42f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
        setShadow(null)
    }

    private fun drawFloatTexts(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        for (t in floatTexts) {
            val a = (t.life / 1.1f).coerceIn(0f, 1f)
            textPaint.textSize = 36f * scale
            textPaint.setShadowLayer(14f, 0f, 0f, t.color)
            textPaint.color = Color.argb((a * 255).toInt(), t.color shr 16 and 0xFF, t.color shr 8 and 0xFF, t.color and 0xFF)
            canvas.drawText(t.text, t.x, t.y, textPaint)
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.setShadowLayer(10f, 0f, 0f, Color.CYAN)
        textPaint.color = Color.WHITE
    }

    private fun drawHud(canvas: Canvas) {
        setShadow(null)
        fillPaint.shader = null
        textPaint.textSize = 40f * scale

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.setShadowLayer(10f, 0f, 0f, Color.CYAN)
        textPaint.color = Color.WHITE
        canvas.drawText("SCORE $score", 30f, 56f * scale, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.setShadowLayer(10f, 0f, 0f, Color.MAGENTA)
        textPaint.color = Color.rgb(255, 150, 240)
        canvas.drawText("WAVE $wave", w / 2f, 56f * scale, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.setShadowLayer(10f, 0f, 0f, Color.GREEN)
        textPaint.color = Color.rgb(120, 255, 160)
        canvas.drawText("\u2665".repeat(lives.coerceAtLeast(0)), w - 30f, 56f * scale, textPaint)
        textPaint.textAlign = Paint.Align.LEFT

        // Armor pips next to lives
        if (armor > 0) {
            fillPaint.color = Color.rgb(150, 170, 225)
            setShadow(Color.rgb(150, 170, 225))
            for (i in 0 until armor) {
                canvas.drawCircle(w - 30f - 26f * scale - i * 22f * scale, 42f * scale, 8f * scale, fillPaint)
            }
            setShadow(null)
        }

        // Boss HP bar
        val bs = boss
        if (bs != null) {
            val barW = w * 0.6f
            val barX = w / 2f - barW / 2f
            val barY = 84f * scale
            fillPaint.color = Color.argb(160, 30, 8, 20)
            canvas.drawRoundRect(barX, barY, barX + barW, barY + 16f * scale, 8f * scale, 8f * scale, fillPaint)
            val frac = (bs.hp.toFloat() / bs.maxHp).coerceIn(0f, 1f)
            fillPaint.shader = LinearGradient(
                barX, barY, barX + barW, barY,
                intArrayOf(Color.rgb(255, 60, 60), Color.rgb(255, 170, 60)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(barX, barY, barX + barW * frac, barY + 16f * scale, 8f * scale, 8f * scale, fillPaint)
            fillPaint.shader = null
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 20f * scale
            textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(255, 80, 160))
            textPaint.color = Color.rgb(255, 150, 210)
            canvas.drawText("NAVE-MAE", w / 2f, barY - 6f * scale, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }

        // Active mission
        val m = mission
        if (m != null) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 22f * scale
            textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(255, 216, 120))
            textPaint.color = Color.rgb(255, 226, 150)
            canvas.drawText("MISSAO: ${m.text} (${m.progress.coerceAtMost(m.target)}/${m.target})",
                w / 2f, h - 18f * scale, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }

        // Weapon label (bottom-right)
        val wName = when (weapon) {
            Weapon.PLASMA -> "PLASMA"
            Weapon.SPREAD -> "SPREAD"
            Weapon.LASER -> "LASER"
            Weapon.MISSILE -> "MISSIL"
        }
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 22f * scale
        textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(170, 255, 245))
        textPaint.color = Color.rgb(170, 255, 245)
        canvas.drawText("ARMA: $wName", w - 30f, h - 18f * scale, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 40f * scale

        // Sector banner
        if (sectorBannerTimer > 0f) {
            val sectorIndex = ((wave - 1) / 3).coerceAtMost(sectors.lastIndex)
            val a = (sectorBannerTimer / 2f).coerceIn(0f, 1f)
            bigTextPaint.alpha = (a * 255).toInt()
            bigTextPaint.textSize = 60f * scale
            bigTextPaint.setShadowLayer(20f, 0f, 0f, sectors[sectorIndex].accent)
            bigTextPaint.color = sectors[sectorIndex].accent
            canvas.drawText(sectors[sectorIndex].name, w / 2f, h * 0.55f, bigTextPaint)
            bigTextPaint.alpha = 255
        }

        if (waveBannerTimer > 0f) {
            val a = (waveBannerTimer / 1.6f).coerceIn(0f, 1f)
            bigTextPaint.alpha = (a * 255).toInt()
            bigTextPaint.textSize = 72f * scale
            bigTextPaint.setShadowLayer(22f, 0f, 0f, Color.CYAN)
            bigTextPaint.color = Color.rgb(140, 240, 255)
            canvas.drawText("WAVE $wave", w / 2f, h * 0.42f, bigTextPaint)
            bigTextPaint.alpha = 255
        }

        // Active power-up bars (top-left, under score)
        var barY = 78f * scale
        val barW = 150f * scale
        val barH = 9f * scale
        setShadow(null)
        if (rapidTimer > 0f) {
            drawPowerBar(canvas, 30f, barY, barW, barH, rapidTimer / 8f, powerColor(PowerType.RAPID), "RAPID")
            barY += barH + 7f * scale
        }
        if (tripleTimer > 0f) {
            drawPowerBar(canvas, 30f, barY, barW, barH, tripleTimer / 8f, powerColor(PowerType.TRIPLE), "TRIPLE")
            barY += barH + 7f * scale
        }
        if (shieldUp) {
            fillPaint.color = Color.argb(200, 111, 168, 255)
            canvas.drawCircle(30f + barH / 2f, barY + barH / 2f, barH * 0.75f, fillPaint)
            textPaint.textSize = 22f * scale
            textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(111, 168, 255))
            textPaint.color = Color.rgb(170, 205, 255)
            canvas.drawText("SHIELD", 30f + barH * 1.4f, barY + barH, textPaint)
            textPaint.color = Color.WHITE
        }

        // Installed modules pips (bottom-left)
        if (engineUp + cannonUp + wingUp + hullUp > 0) {
            textPaint.textSize = 22f * scale
            textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(255, 216, 120))
            textPaint.color = Color.rgb(255, 216, 120)
            canvas.drawText(
                "MOTOR $engineUp  CANHÃO $cannonUp  ASAS $wingUp  CASCO $hullUp",
                30f, h - 18f * scale, textPaint
            )
            textPaint.color = Color.WHITE
        }
    }

    private fun drawPowerBar(canvas: Canvas, x: Float, y: Float, bw: Float, bh: Float, frac: Float, color: Int, label: String) {
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        fillPaint.color = Color.argb(70, 0, 0, 0)
        canvas.drawRoundRect(x, y, x + bw, y + bh, bh / 2f, bh / 2f, fillPaint)
        fillPaint.color = color
        canvas.drawRoundRect(x, y, x + bw * frac.coerceIn(0f, 1f), y + bh, bh / 2f, bh / 2f, fillPaint)
        textPaint.textSize = 20f * scale
        textPaint.setShadowLayer(5f, 0f, 0f, color)
        textPaint.color = Color.WHITE
        canvas.drawText(label, x + bw + 12f * scale, y + bh * 0.95f, textPaint)
    }

    private fun drawGameOver(canvas: Canvas) {
        bigTextPaint.textSize = 96f * scale
        bigTextPaint.setShadowLayer(24f, 0f, 0f, Color.RED)
        bigTextPaint.color = Color.rgb(255, 80, 80)
        canvas.drawText("GAME OVER", w / 2f, h / 2f - 20f * scale, bigTextPaint)

        bigTextPaint.textSize = 44f * scale
        bigTextPaint.setShadowLayer(12f, 0f, 0f, Color.CYAN)
        bigTextPaint.color = Color.WHITE
        canvas.drawText("SCORE: $score", w / 2f, h / 2f + 60f * scale, bigTextPaint)

        val alpha = if (gameOverTimer > 1.2f && (gameOverTimer * 2f).toInt() % 2 == 0) 255 else 90
        bigTextPaint.alpha = alpha
        bigTextPaint.setShadowLayer(10f, 0f, 0f, Color.YELLOW)
        bigTextPaint.color = Color.rgb(255, 230, 120)
        canvas.drawText("TOQUE PARA REINICIAR", w / 2f, h / 2f + 130f * scale, bigTextPaint)
        bigTextPaint.alpha = 255
    }

    private fun setShadow(color: Int?) {
        if (color != null) {
            fillPaint.setShadowLayer(18f * scale, 0f, 0f, color)
        } else {
            fillPaint.clearShadowLayer()
        }
    }

    // ---------- Entities ----------

    private class Bullet(
        var x: Float,
        var y: Float,
        val speed: Float,
        val color: Int,
        var vx: Float = 0f,
        val pierce: Boolean = false,
        val homing: Boolean = false,
        val splash: Boolean = false
    ) {
        val trail = mutableListOf<Float>()
        var pierceHits = 0
    }

    private class Invader(
        val homeX: Float,
        var homeY: Float,
        var x: Float,
        var y: Float,
        val size: Float,
        val color: Int,
        val variant: Int,
        var hp: Int,
        var alive: Boolean = true,
        var pulse: Float = Random.nextFloat() * 6f,
        var diving: Boolean = false,
        var divePhase: Float = 0f,
        var mini: Boolean = false
    )

    private class Ufo(var x: Float, val y: Float, val vx: Float) {
        var blink = 0f
    }

    private class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var radius: Float,
        var life: Float,
        var maxLife: Float,
        val color: Int,
        val gravity: Float,
        var isRing: Boolean = false
    )

    private class Star(var x: Float, var y: Float, val radius: Float, val speed: Float, val alpha: Int, val seed: Float, val z: Float)

    private class Debris(
        var x: Float,
        var y: Float,
        val size: Float,
        val speed: Float,
        var rot: Float,
        val rotSpeed: Float,
        val drift: Float,
        val alpha: Int
    )

    private enum class PowerType { RAPID, TRIPLE, SHIELD, LIFE, NOVA, PART, WEAPON, ARMOR }

    private class PowerUp(var x: Float, var y: Float, val type: PowerType, var phase: Float = Random.nextFloat() * 6f)

    private class Meteor(var x: Float, var y: Float, val vx: Float, val vy: Float, val len: Float)

    private class Dust(var x: Float, var y: Float, val radius: Float, val drift: Float, val speed: Float)

    private class FloatText(val text: String, var x: Float, var y: Float, val color: Int, var life: Float = 1.1f)
}

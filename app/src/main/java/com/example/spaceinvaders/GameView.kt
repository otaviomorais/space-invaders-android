package com.example.spaceinvaders

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private enum class State { MENU, PLAYING, GAME_OVER, SHOP }

    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var surfaceReady = false

    // Screen
    private var w = 0f
    private var h = 0f
    private var scale = 1f

    // Game state
    private var state = State.MENU
    private var score = 0
    private var lives = 3
    private var wave = 1
    private var highScore = 0
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
    private var dragPointerId = -1

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
    private val PARTICLE_CAP = 600
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

    // UI-thread -> game-thread requests (avoid mutating lists off-thread)
    @Volatile private var specialRequested = false
    @Volatile private var resetRequested = false
    @Volatile private var surfaceInitRequested = false
    @Volatile private var uiShopRequested = false
    @Volatile private var uiMenuRequested = false
    @Volatile private var uiShopBackRequested = false
    @Volatile private var uiSkinAction = -1 // -1 none, else skin index buy/select
    @Volatile private var uiDashRequested = false
    @Volatile private var uiMineRequested = false

    // Shader cache: gradients built once, repositioned per draw via local matrix
    private val shaderCache = HashMap<Long, Shader>()
    private val localMatrix = Matrix()
    private val addPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    /** Additively blends a cached radial glow centered at (x, y). */
    private fun drawGlow(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        val s = glowShader(color)
        localMatrix.reset()
        localMatrix.setScale(radius, radius)
        localMatrix.postTranslate(x, y)
        s.setLocalMatrix(localMatrix)
        addPaint.shader = s
        canvas.drawCircle(x, y, radius, addPaint)
        addPaint.shader = null
    }

    /** Radial glow shader in unit space (center 0,0 radius 1) — position via setLocalMatrix. */
    private fun glowShader(color: Int): Shader {
        val key = (1L shl 62) or (color.toLong() and 0xFFFFFFFFL)
        return shaderCache.getOrPut(key) {
            RadialGradient(0f, 0f, 1f, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
    }

    /** Vertical hull gradient in unit space (y -1..1): light top, base mid, dark bottom. */
    private fun hullShader(color: Int): Shader {
        val key = (2L shl 62) or (color.toLong() and 0xFFFFFFFFL)
        return shaderCache.getOrPut(key) {
            LinearGradient(
                0f, -1f, 0f, 1f,
                intArrayOf(shade(color, 1.6f), color, shade(color, 0.4f)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    /** Radial hull gradient in unit space (radius 1): highlight -> base -> shadow. */
    private fun radialHullShader(color: Int): Shader {
        val key = (3L shl 62) or (color.toLong() and 0xFFFFFFFFL)
        return shaderCache.getOrPut(key) {
            RadialGradient(
                0f, 0f, 1f,
                intArrayOf(shade(color, 1.6f), color, shade(color, 0.35f)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    /** Generic unit-space shader cache entry; key packs kind, color and quantized params. */
    private fun cachedUnitShader(kind: Int, a: Int, b: Int, build: () -> Shader): Shader {
        val key = ((kind.toLong() and 0x3FFF) shl 48) or
            ((a.toLong() and 0xFFFFFFFFL) shl 16) or
            (b.toLong() and 0xFFFFL)
        return shaderCache.getOrPut(key, build)
    }

    /** Positions a cached unit-space shader at (x, y) with uniform scale s. */
    private fun placeShader(shader: Shader, x: Float, y: Float, s: Float) {
        localMatrix.reset()
        localMatrix.setScale(s, s)
        localMatrix.postTranslate(x, y)
        shader.setLocalMatrix(localMatrix)
    }

    /** Positions a cached unit-space shader at (x, y) with separate x/y scales. */
    private fun placeShader(shader: Shader, x: Float, y: Float, sx: Float, sy: Float) {
        localMatrix.reset()
        localMatrix.setScale(sx, sy)
        localMatrix.postTranslate(x, y)
        shader.setLocalMatrix(localMatrix)
    }

    private fun clearShaderCache() {
        shaderCache.clear()
    }

    // Ship upgrades (per run)
    private var engineUp = 0
    private var cannonUp = 0
    private var wingUp = 0
    private var hullUp = 0
    private var droneUp = 0
    private var coreUp = 0
    private var shieldGenTimer = 0f
    private var slowTimer = 0f
    private var magnetTimer = 0f
    private var cloneTimer = 0f
    private var droneFireTimer = 0f

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
    private var bestRank = 0 // 0=C 1=B 2=A 3=S
    private var hordeModifier = 0 // 0=none 1=gravidade baixa 2=nevoa 3=enxame

    // Loja e meta-progressao
    private var coins = 0
    private var selectedSkin = 0
    private var ownedSkins = mutableSetOf(0)

    // Armas secundarias
    private var dashCooldown = 0f
    private var mineCount = 1
    private var mineTimer = 0f
    private val mines = mutableListOf<Mine>()

    // Som/vibracao
    private var vibrator: Vibrator? = null
    private var toneGenerator: ToneGenerator? = null

    // Leaderboard top5
    private var leaderboard = mutableListOf<Int>()
    private var boss: Boss? = null
    private var bossWave = false
    private var mission: Mission? = null
    private var missionCooldown = 0

    // Special attack & cinematics
    private var specialCharge = 0f
    private var cineTimer = 0f
    private var cineDuration = 1f
    private var cineStrikeTimer = 0f
    private var specialStrikesLeft = 0
    private var bossVictoryTimer = 0f
    private val beams = mutableListOf<Beam>()

    // Ship evolution: powerups forge the ship into a warship
    private var powerupsCollected = 0
    private var shipLevel = 1

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
        var hp: Int = maxHp,
        var type: Int = 0,
        var phase: Int = 0,
        var timer: Float = 1.6f,
        var cycle: Float = 6.5f,
        var vx: Float = 1f,
        var spiral: Float = 0f,
        var pulse: Float = 0f,
        var entering: Boolean = true,
        var dying: Boolean = false
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

    // Paths reutilizaveis (reset + rebuild) para evitar alocacao a cada frame.
    private val flamePath = Path()
    private val wingsPath = Path()
    private val fuselagePath = Path()
    private val finPath = Path()
    private val rocketPath = Path()

    // Textos localizados (carregados uma vez no init, ver strings.xml)
    private var strPlay = ""
    private var strShop = ""
    private var strHighscoreFmt = ""
    private var strShopCoinsFmt = ""
    private var strControlsHint = ""
    private var strTagline = ""
    private var strGameOver = ""
    private var strScoreFmt = ""
    private var strTapRestart = ""
    private var strMenu = ""
    private var strCoinsFmt = ""
    private var strBuyFmt = ""
    private var strSelect = ""
    private var strSelected = ""
    private var strBack = ""
    private var strWaveFmt = ""
    private var strHordeMode = ""
    private var strBossMothership = ""

    init {
        holder.addCallback(this)
        focusable = FOCUSABLE
        val res = context.resources
        strPlay = res.getString(R.string.ui_play)
        strShop = res.getString(R.string.ui_shop)
        strHighscoreFmt = res.getString(R.string.ui_highscore_format)
        strShopCoinsFmt = res.getString(R.string.ui_shop_coins_format)
        strControlsHint = res.getString(R.string.ui_controls_hint)
        strTagline = res.getString(R.string.ui_tagline)
        strGameOver = res.getString(R.string.ui_game_over)
        strScoreFmt = res.getString(R.string.ui_score_format)
        strTapRestart = res.getString(R.string.ui_tap_to_restart)
        strMenu = res.getString(R.string.ui_menu)
        strCoinsFmt = res.getString(R.string.ui_coins_format)
        strBuyFmt = res.getString(R.string.ui_buy_format)
        strSelect = res.getString(R.string.ui_select)
        strSelected = res.getString(R.string.ui_selected)
        strBack = res.getString(R.string.ui_back)
        strWaveFmt = res.getString(R.string.ui_wave_format)
        strHordeMode = res.getString(R.string.ui_horde_mode)
        strBossMothership = res.getString(R.string.ui_boss_mothership)
        val prefs = context.getSharedPreferences("space_invaders", Context.MODE_PRIVATE)
        highScore = prefs.getInt("highscore", 0)
        bestRank = prefs.getInt("bestRank", 0)
        coins = prefs.getInt("coins", 0)
        selectedSkin = prefs.getInt("selectedSkin", 0)
        val ownedStr = prefs.getString("ownedSkins", "0") ?: "0"
        ownedSkins = ownedStr.split(",").mapNotNull { it.toIntOrNull() }.toMutableSet()
        if (ownedSkins.isEmpty()) ownedSkins.add(0)
        val lbStr = prefs.getString("leaderboard", "") ?: ""
        leaderboard = lbStr.split(",").mapNotNull { it.toIntOrNull() }.toMutableList()
        if (leaderboard.isEmpty() && highScore > 0) leaderboard.add(highScore)
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
        } catch (_: Exception) {}
    }

    private fun saveCoinsAndSkins() {
        context.getSharedPreferences("space_invaders", Context.MODE_PRIVATE).edit()
            .putInt("coins", coins)
            .putInt("selectedSkin", selectedSkin)
            .putString("ownedSkins", ownedSkins.joinToString(","))
            .apply()
    }

    private fun getSkinColors(skin: Int): IntArray = when (skin) {
        1 -> intArrayOf(Color.rgb(255, 90, 90), Color.rgb(180, 30, 60), Color.rgb(90, 10, 30))
        2 -> intArrayOf(Color.rgb(255, 220, 120), Color.rgb(200, 160, 60), Color.rgb(120, 90, 20))
        else -> intArrayOf(Color.rgb(190, 255, 240), Color.rgb(0, 210, 175), Color.rgb(0, 90, 110))
    }

    private fun saveHighScore() {
        if (score > highScore) {
            highScore = score
            context.getSharedPreferences("space_invaders", Context.MODE_PRIVATE)
                .edit().putInt("highscore", highScore).apply()
        }
        saveLeaderboard(withScore = score)
    }

    private fun saveLeaderboard(withScore: Int? = null) {
        if (withScore != null) leaderboard.add(withScore)
        leaderboard = leaderboard.sortedDescending().take(5).toMutableList()
        context.getSharedPreferences("space_invaders", Context.MODE_PRIVATE).edit()
            .putString("leaderboard", leaderboard.joinToString(","))
            .apply()
    }

    private fun triggerVibration(light: Boolean) {
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(if (light) 50 else 120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") v.vibrate(if (light) 50 else 120)
            }
        } catch (_: Exception) {}
    }

    private fun playTone(success: Boolean) {
        try {
            toneGenerator?.startTone(if (success) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 120)
        } catch (_: Exception) {}
    }

    /** Frees hardware resources; call from the Activity in onDestroy. */
    fun release() {
        try {
            toneGenerator?.release()
        } catch (_: Exception) {}
        toneGenerator = null
    }

    private fun comboRank(): String = GameRules.comboRank(combo)

    private fun comboRankColor(): Int = when (comboRank()) {
        "S" -> Color.rgb(255, 215, 0)
        "A" -> Color.rgb(255, 80, 80)
        "B" -> Color.rgb(90, 200, 255)
        else -> Color.rgb(180, 180, 180)
    }

    private fun updateBestRank() {
        val v = GameRules.rankValue(comboRank())
        if (v > bestRank) {
            bestRank = v
            context.getSharedPreferences("space_invaders", Context.MODE_PRIVATE)
                .edit().putInt("bestRank", bestRank).apply()
        }
    }

    private fun bestRankLabel(): String = GameRules.bestRankLabel(bestRank)

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
                // Consume UI-thread requests safely on the game thread
                if (surfaceInitRequested) {
                    surfaceInitRequested = false
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
                if (resetRequested) {
                    resetRequested = false
                    resetGame()
                }
                if (specialRequested) {
                    specialRequested = false
                    if (state == State.PLAYING && specialCharge >= 100f &&
                        cineTimer <= 0f && bossVictoryTimer <= 0f
                    ) {
                        triggerSpecial()
                    }
                }
                if (uiShopRequested) {
                    uiShopRequested = false
                    state = State.SHOP
                }
                if (uiShopBackRequested) {
                    uiShopBackRequested = false
                    state = State.MENU
                }
                if (uiMenuRequested) {
                    uiMenuRequested = false
                    saveHighScore()
                    state = State.MENU
                }
                if (uiSkinAction >= 0) {
                    val skin = uiSkinAction
                    uiSkinAction = -1
                    if (skin in 0..2) {
                        if (ownedSkins.contains(skin)) {
                            selectedSkin = skin
                            saveCoinsAndSkins()
                        } else if (coins >= 500) {
                            coins -= 500
                            ownedSkins.add(skin)
                            selectedSkin = skin
                            saveCoinsAndSkins()
                        }
                    }
                }
                if (uiDashRequested) {
                    uiDashRequested = false
                    if (state == State.PLAYING && dashCooldown <= 0f) {
                        dashCooldown = 2.5f
                        invincible = 0.6f
                        val dir = if (playerX < w / 2f) 1f else -1f
                        playerX = (playerX + dir * 180f * scale).coerceIn(playerW, w - playerW)
                        targetX = playerX
                        shake = maxOf(shake, 8f)
                        triggerVibration(true)
                        spawnSparks(playerX, playerY, Color.rgb(120, 255, 200), 10, true)
                    }
                }
                if (uiMineRequested) {
                    uiMineRequested = false
                    if (state == State.PLAYING && mineCount > 0) {
                        mineCount--
                        mineTimer = 6f
                        mines.add(Mine(playerX, playerY - 20f * scale))
                        triggerVibration(true)
                    }
                }

                try {
                    // Cinematic clock runs on raw time
                    if (cineTimer > 0f) cineTimer -= dt
                    if (bossVictoryTimer > 0f) {
                        bossVictoryTimer -= dt
                        val b = boss
                        if (b != null) {
                            if (Random.nextFloat() < dt * 9f) {
                                explode(
                                    b.x + (Random.nextFloat() - 0.5f) * 200f * scale,
                                    b.y + (Random.nextFloat() - 0.5f) * 80f * scale,
                                    Color.rgb(255, 150 + Random.nextInt(80), 90),
                                    big = false
                                )
                                shake = maxOf(shake, 8f)
                            }
                            if (bossVictoryTimer <= 0f) finishBossDeath(b)
                        }
                    }
                    if (specialStrikesLeft > 0 && state == State.PLAYING) {
                        cineStrikeTimer -= dt
                        if (cineStrikeTimer <= 0f) {
                            strikeBeam()
                            specialStrikesLeft--
                            cineStrikeTimer = 0.14f
                        }
                    }
                    if (beams.isNotEmpty()) {
                        val beamIt = beams.iterator()
                        while (beamIt.hasNext()) {
                            val beam = beamIt.next()
                            beam.life -= dt
                            if (beam.life <= 0f) beamIt.remove()
                        }
                    }

                    var effDt = dt
                    if (hitStop > 0f) {
                        hitStop -= dt
                        effDt = dt * 0.12f
                    } else if (cineTimer > 0f) {
                        effDt = dt * 0.3f
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
        // A inicializacao das listas (stars/dust/debris/backgrounds) e' deferida
        // para a game thread, evitando ConcurrentModificationException com o draw.
        surfaceInitRequested = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        clearShaderCache()
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

    private fun updateMines(isSlow: Boolean, dt: Float) {
        val iter = mines.iterator()
        while (iter.hasNext()) {
            val m = iter.next()
            m.timer -= dt * (if (isSlow) 0.38f else 1f)
            m.pulse += dt * 5f
            // contato com invasor
            var exploded = false
            for (inv in invaders.toList()) {
                if (!inv.alive) continue
                if (hypot(inv.x - m.x, inv.y - m.y) < inv.size + 30f * scale) {
                    exploded = true
                    break
                }
            }
            val b = boss
            if (!exploded && b != null && hypot(b.x - m.x, b.y - m.y) < 120f * scale) exploded = true
            if (exploded || m.timer <= 0f) {
                // explosao
                explode(m.x, m.y, Color.rgb(255, 180, 60), big = true)
                // dano em area
                for (inv in invaders.toList()) {
                    if (inv.alive && hypot(inv.x - m.x, inv.y - m.y) < 140f * scale) {
                        damageInvader(inv, 2)
                    }
                }
                if (b != null && hypot(b.x - m.x, b.y - m.y) < 150f * scale) {
                    b.hp -= 6
                    if (b.hp <= 0) bossDeath(b)
                }
                shake = maxOf(shake, 14f)
                flashAlpha = maxOf(flashAlpha, 0.25f)
                triggerVibration(false)
                iter.remove()
            }
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
        5 -> Color.rgb(170, 255, 90)    // splitter - lime
        6 -> Color.rgb(100, 180, 255)   // shield bearer - ice blue
        7 -> Color.rgb(255, 220, 80)    // sniper - gold
        8 -> Color.rgb(255, 90, 130) // swarmer - hot pink
        9 -> Color.rgb(120, 120, 130) // camuflado - cinza
        10 -> Color.rgb(255, 120, 30) // rastro de fogo - laranja
        else -> Color.rgb(255, 90, 130)
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
            Pair(0.16f, 0.26f), Pair(0.80f, 0.18f), Pair(0.55f, 0.88f),
            Pair(0.32f, 0.62f), Pair(0.90f, 0.68f)
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

        // Horda infinita após wave 12: modificadores aleatórios
        if (wave > 12) {
            hordeModifier = Random.nextInt(1, 4) // 1=gravidade baixa 2=névoa 3=enxame
            waveBannerTimer = 2.2f
            val modName = when (hordeModifier) { 1 -> "GRAVIDADE BAIXA"; 2 -> "NÉVOA"; else -> "ENXAME" }
            addFloat("MODO HORDA: $modName", w / 2f, h * 0.35f, Color.rgb(255, 80, 80))
            // Boss continua a cada 4 waves mesmo na horda, mas com modificador mantido
            if (wave % 4 == 0) {
                bossWave = true
                entering = false
                val bossType = if (sectorIndex == 3) 3 else sectorIndex % 3
                val bossNames = arrayOf("RAINHA NEBULOSA", "LEVIATA GLACIAL", "TITA VULCANICO", "VACUO ETERNO")
                val bossHp = if (bossType == 3) 50 + 20 * sectorIndex + 3 * wave else 42 + sectorIndex * 18 + wave * 2 + bossType * 8
                boss = Boss(
                    x = w / 2f, y = -180f * scale,
                    maxHp = bossHp,
                    type = bossType
                )
                addFloat("ALERTA: ${bossNames[bossType]}!", w / 2f, h * 0.35f, sectors[sectorIndex].accent)
                shake = maxOf(shake, 10f)
                return
            }
            bossWave = false
        } else {
            hordeModifier = 0
            // Every 4th wave: MOTHERSHIP BOSS
            if (wave % 4 == 0) {
                bossWave = true
                entering = false
                val bossType = if (sectorIndex == 3) 3 else sectorIndex % 3
                val bossNames = arrayOf("RAINHA NEBULOSA", "LEVIATA GLACIAL", "TITA VULCANICO", "VACUO ETERNO")
                val bossHp = if (bossType == 3) 50 + 20 * sectorIndex + 3 * wave else 42 + sectorIndex * 18 + wave * 2 + bossType * 8
                boss = Boss(
                    x = w / 2f, y = -180f * scale,
                    maxHp = bossHp,
                    type = bossType
                )
                addFloat("ALERTA: ${bossNames[bossType]}!", w / 2f, h * 0.35f, sectors[sectorIndex].accent)
                shake = maxOf(shake, 10f)
                return
            }
            bossWave = false
        }

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

        var cols = min(5 + wave / 2, 9)
        if (hordeModifier == 3) cols = min(cols + 2, 11) // enxame = cols+2
        val rows = min(3 + (wave - 1) / 2, 5)
        val marginX = w * 0.13f
        val spacingX = if (cols > 1) (w - marginX * 2) / (cols - 1) else 0f

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var variant = r % 3
                val roll = Random.nextFloat()
                if (wave >= 8 && roll < 0.12f) variant = 10
                else if (wave >= 7 && roll < 0.12f) variant = 9
                else if (wave >= 6 && roll < 0.10f) variant = 8
                else if (wave >= 5 && roll < 0.19f) variant = 7
                else if (wave >= 3 && roll < 0.30f) variant = 6
                else if (wave >= 2 && roll < 0.40f) variant = 3
                else if (wave >= 3 && roll < 0.50f) variant = 4
                else if (wave >= 4 && roll < 0.58f) variant = 5
                val size = (when (variant) {
                    0 -> 40f
                    1 -> 36f
                    3 -> 34f
                    4 -> 52f
                    5 -> 34f
                    6 -> 44f
                    7 -> 32f
                    8 -> 22f
                    9 -> 38f
                    10 -> 40f
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
                            2, 6, 9 -> 2
                            4 -> 3
                            10 -> 1
                            else -> if (r == 0 && wave >= 4) 2 else 1
                        }
                    )
                )
            }
        }
        waveBannerTimer = 1.6f
        // Reinforced hull regenerates its shield every wave
        if (hullUp > 0) shieldUp = true
        // Tutorial: garante W e P visíveis na onda 1
        if (wave == 1 && !bossWave) {
            powerUps.add(PowerUp(w * 0.32f, h * 0.28f, PowerType.WEAPON))
            powerUps.add(PowerUp(w * 0.68f, h * 0.32f, PowerType.PART))
        }
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
        droneUp = 0
        coreUp = 0
        shieldGenTimer = 0f
        slowTimer = 0f
        magnetTimer = 0f
        cloneTimer = 0f
        droneFireTimer = 0f
        camX = 0f
        screenPunch = 0f
        ambR = 0f
        ambG = 0f
        ambB = 0f
        weapon = Weapon.PLASMA
        armor = 0
        combo = 0
        comboTimer = 0f
        hordeModifier = 0
        boss = null
        bossWave = false
        mission = null
        missionCooldown = 0
        sectorBannerTimer = 0f
        specialCharge = 0f
        cineTimer = 0f
        bossVictoryTimer = 0f
        specialStrikesLeft = 0
        beams.clear()
        powerupsCollected = 0
        shipLevel = 1
        dashCooldown = 0f
        mineCount = 1
        mineTimer = 0f
        mines.clear()
        initDebris()
        spawnWave()
    }

    // ---------- Input ----------

    // Shared UI geometry: single source of truth for hit-testing AND drawing,
    // so touch regions can never drift away from what is rendered.

    private fun menuPlayRect() = RectF(
        w / 2f - 170f * scale, h * 0.62f,
        w / 2f + 170f * scale, h * 0.62f + 78f * scale
    )

    private fun menuShopRect() = RectF(
        w / 2f - 130f * scale, h * 0.73f,
        w / 2f + 130f * scale, h * 0.73f + 58f * scale
    )

    private fun shopBackRect() = RectF(
        w / 2f - 130f * scale, h * 0.88f,
        w / 2f + 130f * scale, h * 0.88f + 58f * scale
    )

    private val shopCardW = 280f
    private val shopCardH = 260f
    private val shopGap = 30f

    private fun shopCardRect(i: Int): RectF {
        val cardW = shopCardW * scale
        val cardH = shopCardH * scale
        val gap = shopGap * scale
        val totalW = cardW * 3 + gap * 2
        val startX = w / 2f - totalW / 2f
        val startY = h * 0.32f
        val x = startX + i * (cardW + gap)
        return RectF(x, startY, x + cardW, startY + cardH)
    }

    private fun shopBuyRect(i: Int): RectF {
        val card = shopCardRect(i)
        val btnW = 180f * scale
        val btnH = 48f * scale
        val cx = card.centerX()
        val by = card.bottom - 62f * scale
        return RectF(cx - btnW / 2f, by, cx + btnW / 2f, by + btnH)
    }

    private fun gameOverMenuRect() = RectF(30f, h - 74f * scale, 190f * scale, h - 26f * scale)

    private fun dashButtonCenter() = Pair(70f * scale, h - 84f * scale)

    private fun mineButtonCenter() = Pair(170f * scale, h - 84f * scale)

    private fun specialButtonCenter() = Pair(w - 84f * scale, h - 84f * scale)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val px = event.getX(idx)
                val py = event.getY(idx)
                // SHOP state
                if (state == State.SHOP) {
                    if (shopBackRect().contains(px, py)) {
                        uiShopBackRequested = true
                        return true
                    }
                    for (i in 0..2) {
                        if (shopBuyRect(i).contains(px, py)) {
                            uiSkinAction = i
                            return true
                        }
                    }
                    return true
                }
                // Menu: JOGAR button and LOJA button
                if (state == State.MENU) {
                    if (menuPlayRect().contains(px, py)) {
                        resetRequested = true
                        return true
                    }
                    if (menuShopRect().contains(px, py)) {
                        uiShopRequested = true
                        return true
                    }
                    return true
                }
                // Secondary buttons in PLAYING (dash and mine) - bottom left
                if (state == State.PLAYING) {
                    val (dashX, dashY) = dashButtonCenter()
                    if (hypot(px - dashX, py - dashY) < 52f * scale) {
                        uiDashRequested = true
                        return true
                    }
                    val (mineX, mineY) = mineButtonCenter()
                    if (hypot(px - mineX, py - mineY) < 52f * scale) {
                        uiMineRequested = true
                        return true
                    }
                    val (bx, by) = specialButtonCenter()
                    if (hypot(px - bx, py - by) < 62f * scale) {
                        if (specialCharge >= 100f) specialRequested = true
                        return true
                    }
                    // Drag steering: a single tracked pointer drives the ship,
                    // extra fingers (multitouch) never yank it.
                    if (!dragging) {
                        dragging = true
                        dragPointerId = event.getPointerId(idx)
                        lastTouchX = px
                    }
                    return true
                }
                if (state == State.GAME_OVER) {
                    if (gameOverTimer > 0.8f && gameOverMenuRect().contains(px, py)) {
                        uiMenuRequested = true
                        return true
                    }
                    if (gameOverTimer > 1.2f) resetRequested = true
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging && dragPointerId >= 0) {
                    val pIdx = event.findPointerIndex(dragPointerId)
                    if (pIdx >= 0) {
                        val px = event.getX(pIdx)
                        targetX += (px - lastTouchX) * (1.8f + engineUp * 0.2f)
                        lastTouchX = px
                        targetX = targetX.coerceIn(playerW, w - playerW)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upId = event.getPointerId(event.actionIndex)
                if (dragging && upId == dragPointerId) {
                    dragging = false
                    dragPointerId = -1
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                dragPointerId = -1
            }
        }
        return true
    }

    // ---------- Update ----------

    private fun update(dt: Float) {
        // bg reativo: pulsa mais forte com combo
        bgTime += dt * (1f + combo * 0.08f)
        updateStars(dt)

        // Menu/Shop: only the cosmos breathes
        if (state == State.MENU || state == State.SHOP) {
            updateMeteors(dt)
            updateDust(dt)
            updateDebris(dt)
            updateParticles(dt)
            return
        }

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
        slowTimer = (slowTimer - dt).coerceAtLeast(0f)
        magnetTimer = (magnetTimer - dt).coerceAtLeast(0f)
        cloneTimer = (cloneTimer - dt).coerceAtLeast(0f)
        dashCooldown = (dashCooldown - dt).coerceAtLeast(0f)
        mineTimer -= dt
        if (mineTimer <= 0f && mineCount < 1) {
            mineCount = 1
            mineTimer = 0f
        }
        // Minas
        updateMines(slowTimer > 0f, dt)

        // Shield generator core regenerates shield
        if (coreUp == 1) {
            if (shieldUp) shieldGenTimer = 12f
            else {
                shieldGenTimer -= dt
                if (shieldGenTimer <= 0f) {
                    shieldUp = true
                    shieldGenTimer = 12f
                    addFloat("ESCUDO REGENERADO!", playerX, playerY - 70 * scale, Color.rgb(111, 168, 255))
                    flashAlpha = maxOf(flashAlpha, 0.18f)
                }
            }
        }

        // Drone escort fires at nearest invader
        if (droneUp == 1) {
            droneFireTimer -= dt
            if (droneFireTimer <= 0f && invaders.any { it.alive }) {
                fireDrone()
                droneFireTimer = 0.62f
            }
        }

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
        if (slowTimer > 0f) { tb += 34f; tg += 18f; tr += 8f }
        if (magnetTimer > 0f) { tr += 22f; tg += 18f }
        if (cloneTimer > 0f) { tr += 18f; tb += 22f }
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
                val snipers = alive.filter { it.variant == 7 }
                val shooter = when {
                    bombers.isNotEmpty() && Random.nextFloat() < 0.40f -> bombers.random()
                    snipers.isNotEmpty() && Random.nextFloat() < 0.35f -> snipers.random()
                    else -> {
                        // Swarmers rarely shoot - filter them mostly out
                        val nonSwarm = alive.filter { it.variant != 8 }
                        if (nonSwarm.isNotEmpty() && Random.nextFloat() < 0.85f) nonSwarm.random() else alive.random()
                    }
                }
                if (shooter.variant == 4) {
                    // Bomber drops a 3-shot cluster fan
                    val speed = (380f + wave * 14f) * scale
                    for (a in floatArrayOf(-0.3f, 0f, 0.3f)) {
                        enemyBullets.add(
                            Bullet(shooter.x, shooter.y + shooter.size, speed,
                                Color.rgb(255, 140, 60), sin(a) * speed)
                        )
                    }
                } else if (shooter.variant == 7) {
                    // Sniper: single high-velocity precise shot
                    val speed = 720f * scale
                    var vx = 0f
                    // More accurate aim
                    val travel = (shooter.y - playerY) / speed
                    if (travel > 0f) vx = ((playerX - shooter.x) / travel * 0.9f).coerceIn(-260f * scale, 260f * scale)
                    enemyBullets.add(Bullet(shooter.x, shooter.y + shooter.size, speed, Color.rgb(255, 230, 120), vx))
                    // Muzzle flash
                    spawnSparks(shooter.x, shooter.y + shooter.size, Color.rgb(255, 230, 120), 3, true)
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
            val m = mission
            if (m != null && m.kind == 3 && !damageTakenThisWave) missionProgress(3, 1)
            wave++
            addScore(100)
            // recarrega mina a cada onda
            mineCount = 1
            mineTimer = 0f
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
        val sdt = if (slowTimer > 0f) dt * 0.38f else dt
        val speed = (60f + wave * 24f) * scale
        var descendRate = (9f + wave * 2.4f) * scale
        if (hordeModifier == 1) descendRate *= 0.5f // gravidade baixa

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
                formOffX += formDirX * speed * sdt
            }

            for (inv in invaders) {
                if (!inv.alive || inv.diving) continue
                inv.pulse += dt * 6f
                inv.x = inv.homeX + formOffX
                inv.homeY += descendRate * sdt
                inv.y = inv.homeY
                // Hunters strafe inside the formation AND dodge incoming fire
                if (inv.variant == 3) {
                    inv.x += sin(inv.pulse * 1.3f) * 42f * scale * (if (slowTimer > 0f) 0.38f else 1f)
                    for (pb in bullets) {
                        if (abs(pb.x - inv.x) < 46f * scale && pb.y < inv.y && inv.y - pb.y < 240f * scale) {
                            inv.x += (if (inv.x < pb.x) -1f else 1f) * 90f * scale * sdt
                            break
                        }
                    }
                }
                if (inv.variant == 8) {
                    inv.x += sin(inv.pulse * 3.6f) * 30f * scale
                    inv.y += cos(inv.pulse * 3.6f) * 8f * scale
                }
                if (inv.variant == 10) {
                    // Rastro de fogo - particulas a cada frame
                    if (Random.nextFloat() < 0.7f) spawnSparks(inv.x, inv.y + inv.size * 0.4f, Color.rgb(255, 120, 30), count = 1, small = true, spreadUp = false)
                }
                maxY = maxOf(maxY, inv.y)
            }

            if (maxY > playerY - 100 * scale) hitPlayer(instantDeath = true)

            // Divers: S-curve dive towards the player
            for (inv in invaders) {
                if (!inv.alive || !inv.diving) continue
                inv.pulse += dt * 12f
                inv.divePhase += sdt * 5f
                inv.y += (330f + wave * 22f) * scale * sdt
                inv.x += sin(inv.divePhase) * 170f * scale * sdt
                inv.x += kotlin.math.sign(playerX - inv.x) * 70f * scale * sdt
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
                // Left the screen: wraps back to the top to strike again (twice max)
                if (inv.y > h + inv.size * 2f) {
                    if (inv.wraps < 2 && !inv.mini) {
                        inv.wraps++
                        inv.y = -inv.size * 2f
                        inv.x = (Random.nextFloat() * (w - 200f * scale) + 100f * scale)
                    } else {
                        inv.alive = false
                        inv.diving = false
                    }
                }
            }
        }
    }
    private fun updateUfo(dt: Float) {
        val sdt = if (slowTimer > 0f) dt * 0.38f else dt
        val current = ufo
        if (current != null) {
            current.x += current.vx * sdt
            current.blink += dt * 8f
            if (current.x < -150 * scale || current.x > w + 150 * scale) ufo = null
        } else {
            ufoTimer -= sdt
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
        val sdt = if (slowTimer > 0f) dt * 0.38f else dt
        b.pulse += dt * 4f
        if (b.dying) {
            b.y += 14f * scale * sdt
            return
        }
        if (b.entering) {
            b.y += (h * 0.18f - b.y) * min(2f * sdt, 1f)
            if (abs(b.y - h * 0.18f) < 8f * scale) b.entering = false
            return
        }
        // Tipo 3: Vazio - buraco negro puxa jogador
        if (b.type == 3) {
            playerX += (b.x - playerX) * 0.02f * scale * dt * 60f
            targetX = playerX
            playerX = playerX.coerceIn(playerW, w - playerW)
            targetX = targetX.coerceIn(playerW, w - playerW)
            // puxa tambem balas inimigas levemente
            for (eb in enemyBullets) {
                eb.x += (b.x - eb.x) * 0.015f
            }
        }

        val enraged = b.hp <= b.maxHp / 3
        val speedMul = if (enraged) 1.35f else 1f
        b.timer -= sdt * speedMul
        b.cycle -= sdt

        when (b.phase) {
            0 -> { // Sweep + aimed volleys - pattern varies by boss type
                val typeSpeed = when (b.type) { 1 -> 0.8f; 2 -> 1.4f; else -> 0.9f }
                b.x += b.vx * (90f + wave * 5f) * typeSpeed * scale * sdt * speedMul
                if (b.x < 160f * scale) { b.x = 160f * scale; b.vx = 1f }
                if (b.x > w - 160f * scale) { b.x = w - 160f * scale; b.vx = -1f }
                if (b.timer <= 0f) {
                    fireBossVolley(b)
                    b.timer = when (b.type) { 1 -> 1.5f; 2 -> 1.0f; else -> 1.8f }
                }
            }
            1 -> { // Rotating spiral barrage
                val spin = when (b.type) { 1 -> 1.6f; 2 -> 2.9f; else -> 2.1f }
                b.spiral += sdt * spin * speedMul
                if (b.timer <= 0f) {
                    fireBossSpiral(b)
                    b.timer = when (b.type) { 1 -> 0.48f; 2 -> 0.30f; else -> 0.42f }
                }
            }
            else -> { // Summon minions - type determines variant
                if (b.timer <= 0f) {
                    summonMinions(b)
                    b.phase = 0
                    b.timer = 1.8f
                }
            }
        }

        if (b.cycle <= 0f) {
            b.phase = (b.phase + 1) % 3
            b.timer = if (b.phase == 2) 0.6f else 1.3f
            b.cycle = when (b.type) { 2 -> 6.5f; else -> 8f }
        }
    }

    private fun fireBossVolley(b: Boss) {
        val speed = (380f + wave * 10f) * scale
        val col = when (b.type) { 1 -> Color.rgb(120, 230, 255); 2 -> Color.rgb(255, 140, 60); 3 -> Color.rgb(120, 40, 180); else -> Color.rgb(255, 70, 170) }
        for (a in floatArrayOf(-0.28f, 0f, 0.28f)) {
            enemyBullets.add(
                Bullet(b.x + a * 160f * scale, b.y + 80f * scale, speed, col, sin(a) * speed * 0.8f)
            )
        }
    }

    private fun fireBossSpiral(b: Boss) {
        val speed = when (b.type) { 2 -> 300f; 3 -> 280f; else -> 250f } * scale
        val col = when (b.type) { 1 -> Color.rgb(180, 240, 255); 2 -> Color.rgb(255, 180, 90); 3 -> Color.rgb(80, 30, 140); else -> Color.rgb(255, 120, 220) }
        for (i in 0 until 6) {
            val angle = b.spiral + i * (Math.PI.toFloat() / 3f)
            enemyBullets.add(
                Bullet(b.x, b.y + 40f * scale, abs(sin(angle)) * speed + 60f * scale, col, cos(angle) * speed)
            )
        }
    }

    private fun summonMinions(b: Boss) {
        val variants = when (b.type) {
            1 -> intArrayOf(2, 2, 1) // Glacial: armored + squid
            2 -> intArrayOf(4, 3, 4) // Vulcanico: bombers + hunter
            else -> intArrayOf(0, 1, 5) // Nebulosa: crab, squid, splitter
        }
        for (i in variants.indices) {
            val variant = variants[i]
            invaders.add(
                Invader(
                    homeX = b.x + (i - 1f) * 110f * scale, homeY = b.y + 120f * scale,
                    x = b.x + (i - 1f) * 110f * scale, y = b.y,
                    size = if (variant == 4) 36f * scale else 30f * scale,
                    color = invaderColor(variant), variant = variant,
                    hp = if (variant == 4) 3 else 1
                )
            )
        }
        addFloat("REFUERÇOS!", b.x, b.y + 140f * scale, when (b.type) { 1 -> Color.rgb(120, 230, 255); 2 -> Color.rgb(255, 140, 60); else -> Color.rgb(255, 120, 220) })
    }

    /** Boss defeat starts a staged cinematic; loot drops when it ends. */
    private fun bossDeath(b: Boss) {
        if (b.dying) return
        b.dying = true
        bossVictoryTimer = 2.8f
        cineTimer = 2.8f
        cineDuration = 2.8f
        addFloat("NUCLEO CRITICO!", b.x, b.y - 60f * scale, Color.rgb(255, 230, 120))
        shake = maxOf(shake, 12f)
    }

    private fun finishBossDeath(b: Boss) {
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

    // ---------- Special: Orbital Bombardment ----------

    private fun triggerSpecial() {
        specialCharge = 0f
        cineTimer = 2.2f
        cineDuration = 2.2f
        specialStrikesLeft = 12
        cineStrikeTimer = 0.3f
        flashAlpha = 0.3f
        shake = 10f
        addFloat("BOMBARDEIO ORBITAL!", w / 2f, h * 0.4f, Color.rgb(255, 230, 120))
    }

    private fun strikeBeam() {
        val alive = invaders.filter { it.alive && !it.diving }
        val target = when {
            alive.isNotEmpty() -> alive.random().let { it.x to it.y }
            boss != null -> boss!!.x to boss!!.y
            else -> w / 2f to h * 0.3f
        }
        val (tx, ty) = target
        beams.add(Beam(tx, ty))
        explode(tx, ty, Color.rgb(255, 240, 150), big = true)
        shake = maxOf(shake, 9f)
        var hitSomething = false
        for (inv in invaders.toList()) {
            if (inv.alive && hypot(inv.x - tx, inv.y - ty) < 70f * scale) {
                damageInvader(inv, 2)
                hitSomething = true
            }
        }
        if (!hitSomething) {
            val b = boss
            if (b != null && hypot(b.x - tx, b.y - ty) < 130f * scale) {
                b.hp -= 4
                specialCharge = (specialCharge + 1f).coerceAtMost(100f)
                if (b.hp <= 0) bossDeath(b)
            }
        }
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

    private fun fireDrone() {
        val target = invaders.filter { it.alive }.minByOrNull { hypot(it.x - playerX, it.y - playerY) } ?: boss ?: return
        val tx = target.let { when (it) { is Invader -> it.x; is Boss -> it.x; else -> playerX } }
        // Drone hovers slightly behind-left of player
        val dx = playerX - 48f * scale
        val dy = playerY - 20f * scale
        val angle = kotlin.math.atan2(target.let { when (it) { is Invader -> it.y; is Boss -> it.y; else -> playerY } } - dy, tx - dx)
        bullets.add(Bullet(dx, dy, 1050f * scale, Color.rgb(180, 210, 255), cos(angle) * 320f * scale))
        spawnSparks(dx, dy, Color.rgb(180, 210, 255), 2, true)
    }

    private fun updateBullets(dt: Float) {
        val bulletIt = bullets.iterator()
        while (bulletIt.hasNext()) {
            val b = bulletIt.next()
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
            if (b.y < -60f) bulletIt.remove()
        }
        val esdt = if (slowTimer > 0f) dt * 0.38f else dt
        val enemyIt = enemyBullets.iterator()
        while (enemyIt.hasNext()) {
            val b = enemyIt.next()
            b.x += b.vx * esdt
            b.y += b.speed * esdt
            if (b.y > h + 50f || b.y < -90f || b.x < -50f || b.x > w + 50f) enemyIt.remove()
        }
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0) {
                it.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += p.gravity * dt
            p.vx *= p.drag
            p.vy *= p.drag
            p.rot += p.rotSpeed * dt
            if (p.grow != 0f) p.radius += p.grow * dt
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
        updateBestRank()
        specialCharge = (specialCharge + 4f).coerceAtMost(100f)
        val base = GameRules.invaderBaseScore(inv.variant)
        val mult = GameRules.scoreMultiplier(combo)
        addScore(base * mult)
        // Loja: coins
        coins += base * mult
        saveCoinsAndSkins()
        triggerVibration(true)
        playTone(true)
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
        val bulletIt = bullets.iterator()
        while (bulletIt.hasNext()) {
            val b = bulletIt.next()
            if (resolvePlayerBulletHit(b)) bulletIt.remove()
        }

        // Enemy bullets vs player
        val enemyIt = enemyBullets.iterator()
        while (enemyIt.hasNext()) {
            val b = enemyIt.next()
            if (invincible <= 0f && hypot(playerX - b.x, playerY - b.y) < playerW * 0.7f) {
                explode(b.x, b.y, Color.rgb(255, 120, 40), big = false)
                hitPlayer(false)
                enemyIt.remove()
            }
        }
    }

    /** Aplica os efeitos de colisão de um projétil do jogador. Retorna true se ele deve ser consumido. */
    private fun resolvePlayerBulletHit(b: Bullet): Boolean {
        var hits = 0
        // Snapshot: killing a Splitter adds minis to `invaders` mid-iteration
        for (inv in invaders.toList()) {
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
                if (!b.pierce || hits >= 3) return true
            }
        }
        // Boss
        val bs = boss
        if (bs != null && !bs.entering && !bs.dying && hypot(bs.x - b.x, bs.y - b.y) < 100f * scale) {
            bs.hp--
            specialCharge = (specialCharge + 1f).coerceAtMost(100f)
            spawnSparks(b.x, b.y, Color.rgb(255, 120, 220), count = 8, small = true)
            addScore(5)
            if (b.splash) explode(b.x, b.y, Color.rgb(255, 170, 90), big = false)
            if (bs.hp <= 0) bossDeath(bs)
            return true
        }
        // UFO
        val saucer = ufo
        if (saucer != null && hypot(saucer.x - b.x, saucer.y - b.y) < 70 * scale) {
            ufo = null
            explode(saucer.x, saucer.y, Color.rgb(255, 220, 90), huge = true)
            addScore(150)
            missionProgress(4, 1)
            shake = maxOf(shake, 12f)
            screenPunch = maxOf(screenPunch, 0.8f)
            return true
        }
        return false
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
            triggerVibration(true)
            playTone(false)
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
            triggerVibration(true)
            playTone(false)
            return
        }

        explode(playerX, playerY, Color.rgb(0, 255, 180), big = true)
        triggerVibration(false)
        playTone(false)
        shake = 18f
        flashAlpha = 0.55f
        damagePulse = 1f
        screenPunch = 1f
        hitStop = maxOf(hitStop, 0.14f)
        damageTakenThisWave = true
        combo = 0
        lives--
        if (lives <= 0 || instantDeath) {
            lives = 0
            state = State.GAME_OVER
            gameOverTimer = 0f
            saveHighScore()
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
        if (cloneTimer > 0f) {
            bullets.add(Bullet(playerX - 58f * scale, playerY - playerW, 1050f * scale, Color.rgb(180, 160, 255)))
        }
        spawnSparks(playerX, playerY - playerW, Color.rgb(120, 255, 200), count = 4, small = true, spreadUp = true)
    }

    // ---------- Power-ups & cosmic FX ----------

    private fun rollPowerType(): PowerType {
        val r = Random.nextFloat()
        return when {
            r < 0.14f -> PowerType.RAPID
            r < 0.26f -> PowerType.TRIPLE
            r < 0.36f -> PowerType.SHIELD
            r < 0.44f -> PowerType.LIFE
            r < 0.52f -> PowerType.NOVA
            r < 0.62f -> PowerType.PART
            r < 0.72f -> PowerType.WEAPON
            r < 0.80f -> PowerType.ARMOR
            r < 0.86f -> PowerType.SLOW
            r < 0.93f -> PowerType.MAGNET
            else -> PowerType.CLONE
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
        PowerType.SLOW -> Color.rgb(120, 200, 255)
        PowerType.MAGNET -> Color.rgb(255, 220, 80)
        PowerType.CLONE -> Color.rgb(200, 160, 255)
    }

    private fun updatePowerUps(dt: Float) {
        if (state != State.PLAYING) return
        val it = powerUps.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.phase += dt * 3f
            p.y += 130f * scale * dt
            p.x += sin(p.phase) * 42f * scale * dt
            if (magnetTimer > 0f) {
                p.x += (playerX - p.x) * 0.09f
                p.y += (playerY - p.y) * 0.09f
            }
            p.x = p.x.coerceIn(30f * scale, w - 30f * scale)
            if (p.y > h + 50f * scale) {
                it.remove()
                continue
            }
            // Pickup?
            if (hypot(playerX - p.x, playerY - p.y) < playerW * 0.95f) {
                applyPowerUp(p.type)
                missionProgress(1, 1)
                // Every powerup forges the ship further into a warship
                powerupsCollected++
                val newLevel = GameRules.shipLevelFor(powerupsCollected)
                if (newLevel > shipLevel) {
                    shipLevel = newLevel
                    flashAlpha = maxOf(flashAlpha, 0.3f)
                    addFloat(
                        if (shipLevel >= 5) "NAVE DE GUERRA!" else "NAVE EVOLUIDA Nv$shipLevel",
                        playerX, playerY - 100 * scale, Color.rgb(255, 216, 120)
                    )
                }
                spawnSparks(p.x, p.y, powerColor(p.type), count = 18, small = false)
                it.remove()
            }
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
            PowerType.SLOW -> {
                slowTimer = 5f
                addFloat("TEMPO LENTO! 5s", playerX, playerY - 80 * scale, powerColor(PowerType.SLOW))
            }
            PowerType.MAGNET -> {
                magnetTimer = 8f
                addFloat("IMÃ CÓSMICO! 8s", playerX, playerY - 80 * scale, powerColor(PowerType.MAGNET))
            }
            PowerType.CLONE -> {
                cloneTimer = 8f
                addFloat("CLONE ATIVO! 8s", playerX, playerY - 80 * scale, powerColor(PowerType.CLONE))
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
        if (droneUp < 1) options.add(4)
        if (coreUp < 1) options.add(5)
        if (options.isEmpty()) {
            addScore(250)
            addFloat("SISTEMAS NO MÁXIMO +250", playerX, playerY - 80 * scale, powerColor(PowerType.PART))
            return
        }
        when (options.random()) {
            0 -> { engineUp++; addFloat("MOTOR IÔNICO Nv$engineUp", playerX, playerY - 80 * scale, Color.rgb(255, 170, 60)) }
            1 -> { cannonUp++; addFloat("CANHÃO DE PLASMA Nv$cannonUp", playerX, playerY - 80 * scale, Color.rgb(120, 255, 200)) }
            2 -> { wingUp++; addFloat("ASAS DE COMBATE Nv$wingUp", playerX, playerY - 80 * scale, Color.rgb(89, 229, 255)) }
            3 -> { hullUp++; shieldUp = true; addFloat("CASCO REFORÇADO Nv$hullUp", playerX, playerY - 80 * scale, Color.rgb(111, 168, 255)) }
            4 -> { droneUp = 1; addFloat("DRONE ESCOLTA!", playerX, playerY - 80 * scale, Color.rgb(180, 200, 255)) }
            else -> { coreUp = 1; shieldGenTimer = 12f; addFloat("NÚCLEO DE FUSÃO!", playerX, playerY - 80 * scale, Color.rgb(255, 220, 120)) }
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
        val it = floatTexts.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.life -= dt
            t.y -= 46f * scale * dt
            if (t.life <= 0f) it.remove()
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
        val meteorIt = meteors.iterator()
        while (meteorIt.hasNext()) {
            val m = meteorIt.next()
            m.x += m.vx * dt
            m.y += m.vy * dt
            if (m.x < -250f || m.x > w + 250f || m.y > h + 250f) meteorIt.remove()
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

    private fun addParticle(p: Particle) {
        if (particles.size >= PARTICLE_CAP) {
            // Evict the oldest transient particle; keep structured rings/flash intact
            val idx = particles.indexOfFirst {
                it.kind == Particle.KIND_SPARK || it.kind == Particle.KIND_SMOKE || it.kind == Particle.KIND_EMBER
            }
            if (idx >= 0) particles.removeAt(idx) else return
        }
        particles.add(p)
    }

    private fun explode(x: Float, y: Float, color: Int, big: Boolean = false, huge: Boolean = false) {
        if (big || huge) triggerVibration(false) else triggerVibration(true)
        val eScale = when {
            huge -> 2.2f
            big -> 1.4f
            else -> 1f
        }

        // White-hot flash core
        addParticle(Particle(x, y, 0f, 0f, (26f + 26f * eScale) * scale, 0.09f, 0.09f,
            Color.WHITE, 0f).apply { kind = Particle.KIND_FLASH })

        // Expanding fireball puffs
        val puffs = when {
            huge -> 7
            big -> 5
            else -> 3
        }
        repeat(puffs) {
            val a = Random.nextFloat() * 6.2832f
            val sp = (Random.nextFloat() * 60f + 10f) * scale * eScale
            addParticle(Particle(
                x + cos(a) * 6f * scale * eScale, y + sin(a) * 6f * scale * eScale,
                cos(a) * sp, sin(a) * sp - 20f * scale,
                (13f + Random.nextFloat() * 11f) * scale * eScale,
                0.35f + Random.nextFloat() * 0.2f, 0.55f, color, -60f * scale
            ).apply {
                kind = Particle.KIND_FIRE
                drag = 0.92f
                grow = 30f * scale * eScale
                maxLife = life
            })
        }

        // Fast sparks
        val sparks = when {
            huge -> 52
            big -> 24
            else -> 13
        }
        repeat(sparks) {
            val a = Random.nextFloat() * 6.2832f
            val sp = (Random.nextFloat() + 0.3f) * (if (huge) 500f else if (big) 350f else 230f) * scale
            addParticle(Particle(x, y, cos(a) * sp, sin(a) * sp,
                (Random.nextFloat() * 2.2f + 1.1f) * scale,
                Random.nextFloat() * 0.35f + 0.3f, 0.65f, sparkColor(color), 320f * scale
            ).apply {
                kind = Particle.KIND_SPARK
                drag = 0.985f
                maxLife = life
            })
        }

        // Tumbling solid debris
        val chunks = when {
            huge -> 8
            big -> 5
            else -> 3
        }
        repeat(chunks) {
            val a = Random.nextFloat() * 6.2832f
            val sp = (Random.nextFloat() + 0.2f) * 190f * scale * eScale
            addParticle(Particle(x, y, cos(a) * sp, sin(a) * sp - 40f * scale,
                (Random.nextFloat() * 3f + 2f) * scale * eScale,
                Random.nextFloat() * 0.5f + 0.7f, 1.2f, shade(color, 0.55f), 480f * scale
            ).apply {
                kind = Particle.KIND_DEBRIS
                rotSpeed = (Random.nextFloat() - 0.5f) * 720f
                drag = 0.99f
                maxLife = life
            })
        }

        // Smoke plume that lingers after the fireball dies
        val smoke = when {
            huge -> 6
            big -> 4
            else -> 2
        }
        repeat(smoke) {
            val a = Random.nextFloat() * 6.2832f
            val sp = (Random.nextFloat() * 40f + 8f) * scale
            addParticle(Particle(x, y, cos(a) * sp, sin(a) * sp - 30f * scale,
                (9f + Random.nextFloat() * 8f) * scale,
                0.9f + Random.nextFloat() * 0.5f, 1.4f, 0, -25f * scale
            ).apply {
                kind = Particle.KIND_SMOKE
                drag = 0.96f
                grow = 24f * scale
                maxLife = life
            })
        }

        // Shockwave ring
        addParticle(Particle(x, y, 0f, 0f, 8f * scale * eScale, 0.38f, 0.38f, color, 0f).apply {
            kind = Particle.KIND_RING
        })

        // Lingering embers that drift and flicker
        val embers = if (huge) 6 else 3
        repeat(embers) {
            val a = Random.nextFloat() * 6.2832f
            val sp = (Random.nextFloat() * 80f + 20f) * scale
            addParticle(Particle(x, y, cos(a) * sp, sin(a) * sp - 60f * scale,
                2.2f * scale, 0.9f + Random.nextFloat() * 0.5f, 1.4f,
                Color.rgb(255, 170, 70), 140f * scale
            ).apply {
                kind = Particle.KIND_EMBER
                drag = 0.985f
                maxLife = life
            })
        }
    }

    private fun sparkColor(base: Int): Int {
        // Half the sparks keep the victim's hue, half are white-hot molten bits
        return if (Random.nextFloat() < 0.5f) shade(base, 1.5f)
        else Color.rgb(255, 150 + Random.nextInt(80), 50 + Random.nextInt(60))
    }

    private fun fireRampColor(t: Float): Int = when {
        t > 0.8f -> Color.WHITE
        t > 0.55f -> Color.rgb(255, 238, 150)
        t > 0.3f -> Color.rgb(255, 160, 50)
        t > 0.15f -> Color.rgb(215, 70, 25)
        else -> Color.rgb(85, 40, 35)
    }

    private fun spawnSparks(x: Float, y: Float, color: Int, count: Int, small: Boolean, spreadUp: Boolean = false) {
        repeat(count) {
            val angle = if (spreadUp) (-Math.PI.toFloat()) + (Random.nextFloat() - 0.5f) * 1.2f
            else Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = (Random.nextFloat() + 0.2f) * 260f * scale
            addParticle(Particle(x, y, cos(angle) * speed, sin(angle) * speed,
                (Random.nextFloat() * 3f + 1.5f) * scale * (if (small) 0.7f else 1f),
                Random.nextFloat() * 0.25f + 0.2f, 0.45f, color, 150f * scale
            ).apply {
                kind = Particle.KIND_SPARK
                maxLife = life
            })
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
                State.MENU -> drawMenu(canvas)
                State.PLAYING -> {
                    drawUfo(canvas)
                    drawBoss(canvas)
                    drawInvaders(canvas)
                    drawMines(canvas)
                    drawPowerUps(canvas)
                    drawPlayer(canvas)
                    if (droneUp == 1) drawDrone(canvas)
                    if (cloneTimer > 0f) drawClone(canvas)
                    drawBullets(canvas)
                }
                State.SHOP -> drawShop(canvas)
                State.GAME_OVER -> drawGameOver(canvas)
            }

            drawParticlesAbove(canvas)
            drawBeams(canvas)
            drawDebris(canvas)
            if (state != State.MENU) drawFloatTexts(canvas)
            if (state != State.MENU) drawHud(canvas)

            canvas.restore()

            // Cinematic letterbox bars
            if (cineTimer > 0f) {
                val p = when {
                    cineDuration - cineTimer < 0.35f -> (cineDuration - cineTimer) / 0.35f
                    cineTimer < 0.35f -> cineTimer / 0.35f
                    else -> 1f
                }.coerceIn(0f, 1f)
                val barH = 74f * scale * p
                fillPaint.shader = null
                setShadow(null)
                fillPaint.color = Color.BLACK
                canvas.drawRect(0f, 0f, w, barH, fillPaint)
                canvas.drawRect(0f, h - barH, w, h, fillPaint)
            }

            // Special attack button (bottom-right)
            if (state == State.PLAYING) {
                val bx = w - 84f * scale
                val by = h - 84f * scale
                val r = 46f * scale
                val ready = specialCharge >= 100f
                // Base
                fillPaint.style = Paint.Style.FILL
                setShadow(null)
                fillPaint.color = Color.argb(150, 10, 14, 30)
                canvas.drawCircle(bx, by, r, fillPaint)
                fillPaint.color = Color.argb(220, 40, 50, 90)
                canvas.drawCircle(bx, by, r * 0.82f, fillPaint)
                // Charge arc
                fillPaint.style = Paint.Style.STROKE
                fillPaint.strokeWidth = 7f * scale
                fillPaint.color = if (ready) Color.rgb(255, 230, 120) else Color.rgb(90, 200, 255)
                canvas.drawArc(bx - r, by - r, bx + r, by + r, -90f, 360f * (specialCharge / 100f), false, fillPaint)
                // Glyph
                fillPaint.style = Paint.Style.FILL
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = 34f * scale
                textPaint.setShadowLayer(8f, 0f, 0f, if (ready) Color.rgb(255, 230, 120) else Color.rgb(90, 200, 255))
                textPaint.color = if (ready) Color.rgb(255, 240, 160) else Color.rgb(160, 220, 255)
                canvas.drawText("E", bx, by + 12f * scale, textPaint)
                textPaint.textAlign = Paint.Align.LEFT
                if (ready) {
                    val pulse = 0.5f + sin(bgTime * 8f) * 0.5f
                    fillPaint.style = Paint.Style.STROKE
                    fillPaint.strokeWidth = 3f * scale
                    fillPaint.color = Color.argb((120 + pulse * 120).toInt(), 255, 230, 120)
                    canvas.drawCircle(bx, by, r + 6f * scale + pulse * 4f * scale, fillPaint)
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.textSize = 20f * scale
                    textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(255, 230, 120))
                    textPaint.color = Color.rgb(255, 240, 160)
                    canvas.drawText("PRONTO!", bx, by - r - 14f * scale, textPaint)
                    textPaint.textAlign = Paint.Align.LEFT
                }
                fillPaint.style = Paint.Style.FILL
                // Dash button (bottom-left first)
                val dashX = 70f * scale
                val dashY = h - 84f * scale
                val dr = 42f * scale
                fillPaint.style = Paint.Style.FILL
                setShadow(null)
                fillPaint.color = Color.argb(150, 10, 14, 30)
                canvas.drawCircle(dashX, dashY, dr, fillPaint)
                fillPaint.color = if (dashCooldown <= 0f) Color.argb(220, 40, 90, 90) else Color.argb(120, 40, 40, 50)
                canvas.drawCircle(dashX, dashY, dr * 0.82f, fillPaint)
                if (dashCooldown > 0f) {
                    fillPaint.style = Paint.Style.STROKE
                    fillPaint.strokeWidth = 5f * scale
                    fillPaint.color = Color.rgb(120, 255, 200)
                    val sweep = 360f * (1f - dashCooldown / 2.5f)
                    canvas.drawArc(dashX - dr, dashY - dr, dashX + dr, dashY + dr, -90f, sweep, false, fillPaint)
                    fillPaint.style = Paint.Style.FILL
                }
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = 28f * scale
                textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(120, 255, 200))
                textPaint.color = if (dashCooldown <= 0f) Color.rgb(180, 255, 230) else Color.rgb(120, 120, 130)
                canvas.drawText(">>", dashX, dashY + 10f * scale, textPaint)
                textPaint.textAlign = Paint.Align.LEFT
                // Mine button (second)
                val mineX = 170f * scale
                val mineY = h - 84f * scale
                fillPaint.style = Paint.Style.FILL
                setShadow(null)
                fillPaint.color = Color.argb(150, 10, 14, 30)
                canvas.drawCircle(mineX, mineY, dr, fillPaint)
                fillPaint.color = if (mineCount > 0) Color.argb(220, 90, 50, 40) else Color.argb(120, 40, 40, 50)
                canvas.drawCircle(mineX, mineY, dr * 0.82f, fillPaint)
                if (mineCount == 0 && mineTimer > 0f) {
                    fillPaint.style = Paint.Style.STROKE
                    fillPaint.strokeWidth = 5f * scale
                    fillPaint.color = Color.rgb(255, 140, 60)
                    val sweepM = 360f * (1f - mineTimer / 6f)
                    canvas.drawArc(mineX - dr, mineY - dr, mineX + dr, mineY + dr, -90f, sweepM, false, fillPaint)
                    fillPaint.style = Paint.Style.FILL
                }
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.textSize = 30f * scale
                textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(255, 140, 60))
                textPaint.color = if (mineCount > 0) Color.rgb(255, 200, 160) else Color.rgb(120, 120, 130)
                canvas.drawText("@", mineX, mineY + 10f * scale, textPaint)
                textPaint.textAlign = Paint.Align.LEFT
                fillPaint.style = Paint.Style.FILL
            }

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

        // Engine heat haze (additive, cached glow)
        if (engineUp > 0) {
            drawGlow(canvas, x, y + half * 0.55f, half * (0.75f + 0.25f * engineUp), Color.argb(90, 255, 150, 40))
        }
        drawGlow(canvas, x, y + half * 0.45f, half * 0.55f, Color.argb(55, 120, 190, 255))

        // Engine flames (behind hull) — twin jet exhausts: blue plume, white-hot core
        setShadow(null)
        fillPaint.shader = null
        val flameScale = 1f + 0.3f * engineUp
        for (side in floatArrayOf(-0.34f, 0.34f)) {
            val fl = half * (0.4f + Random.nextFloat() * 0.3f) * flameScale
            val fx = x + side * half
            flamePath.reset()
            flamePath.moveTo(fx - half * 0.12f, y + half * 0.42f)
            flamePath.lineTo(fx, y + half * 0.42f + fl)
            flamePath.lineTo(fx + half * 0.12f, y + half * 0.42f)
            flamePath.close()
            fillPaint.color = Color.argb(200, 90, 170, 255)
            canvas.drawPath(flamePath, fillPaint)
            flamePath.reset()
            flamePath.moveTo(fx - half * 0.055f, y + half * 0.42f)
            flamePath.lineTo(fx, y + half * 0.42f + fl * 0.6f)
            flamePath.lineTo(fx + half * 0.055f, y + half * 0.42f)
            flamePath.close()
            fillPaint.color = Color.rgb(235, 245, 255)
            canvas.drawPath(flamePath, fillPaint)
        }

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

        // Delta wings — gunmetal alloy with a machined sheen
        val span = 1f + 0.12f * wingUp
        fillPaint.style = Paint.Style.FILL
        val wingShader = hullShader(Color.rgb(112, 122, 138))
        placeShader(wingShader, x, y + half * 0.1f, half * 1.1f)
        fillPaint.shader = wingShader
        wingsPath.reset()
        wingsPath.moveTo(x, y - half * 0.55f)
        wingsPath.lineTo(x - half * 1.08f * span, y + half * 0.62f)
        wingsPath.lineTo(x - half * 0.42f, y + half * 0.52f)
        wingsPath.lineTo(x, y + half * 0.18f)
        wingsPath.lineTo(x + half * 0.42f, y + half * 0.52f)
        wingsPath.lineTo(x + half * 1.08f * span, y + half * 0.62f)
        wingsPath.close()
        setShadow(null)
        canvas.drawPath(wingsPath, fillPaint)
        // Wing panel lines
        fillPaint.shader = null
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = max(1f, half * 0.03f)
        fillPaint.color = Color.argb(120, 20, 26, 34)
        canvas.drawLine(x - half * 0.5f * span, y - half * 0.16f, x - half * 1.0f * span, y + half * 0.55f, fillPaint)
        canvas.drawLine(x + half * 0.5f * span, y - half * 0.16f, x + half * 1.0f * span, y + half * 0.55f, fillPaint)
        fillPaint.style = Paint.Style.FILL

        // Skin accent stripes along the wing leading edges
        val skinCols = getSkinColors(selectedSkin)
        fillPaint.color = skinCols[1]
        canvas.drawLine(x, y - half * 0.5f, x - half * 1.02f * span, y + half * 0.58f, fillPaint)
        canvas.drawLine(x, y - half * 0.5f, x + half * 1.02f * span, y + half * 0.58f, fillPaint)

        // Wingtip navigation lights (red left, green right)
        setShadow(null)
        fillPaint.color = Color.rgb(255, 60, 60)
        canvas.drawCircle(x - half * 1.02f * span, y + half * 0.58f, half * 0.09f, fillPaint)
        fillPaint.color = Color.rgb(70, 255, 110)
        canvas.drawCircle(x + half * 1.02f * span, y + half * 0.58f, half * 0.09f, fillPaint)

        // Wing tip cannons
        if (wingUp > 0) {
            setShadow(Color.rgb(120, 255, 200))
            val podShader = hullShader(Color.rgb(30, 90, 82))
            placeShader(podShader, 0f, 0f, 0.001f)
            placeShader(podShader, x, y + half * 0.46f, 1f, half * 0.16f)
            fillPaint.shader = podShader
            for (side in floatArrayOf(-1f, 1f)) {
                canvas.drawRoundRect(
                    x + side * half * span - half * 0.06f, y + half * 0.3f,
                    x + side * half * span + half * 0.06f, y + half * 0.62f,
                    half * 0.05f, half * 0.05f, fillPaint
                )
            }
            fillPaint.shader = null
            setShadow(null)
        }

        // Hull side pods — reinforced armor
        if (hullUp > 0) {
            fillPaint.style = Paint.Style.FILL
            val podShader = hullShader(Color.rgb(96, 128, 140))
            placeShader(podShader, 0f, 0f, 0.001f)
            placeShader(podShader, 0f, y + half * 0.25f, 1f, half * 0.3f)
            fillPaint.shader = podShader
            setShadow(null)
            for (side in floatArrayOf(-1f, 1f)) {
                canvas.drawRoundRect(
                    x + side * half * 0.92f - half * 0.14f, y - half * 0.05f,
                    x + side * half * 0.92f + half * 0.14f, y + half * 0.55f,
                    half * 0.1f, half * 0.1f, fillPaint
                )
            }
            fillPaint.shader = null
        }

        // Fuselage — gunmetal plate with vertical light falloff
        val fusShader = hullShader(Color.rgb(128, 138, 152))
        placeShader(fusShader, x, y - half * 0.2f, 1f, half * 0.72f)
        fillPaint.shader = fusShader
        setShadow(null)
        fuselagePath.reset()
        fuselagePath.moveTo(x, y - half * 0.95f)
        fuselagePath.lineTo(x - half * 0.34f, y + half * 0.48f)
        fuselagePath.lineTo(x + half * 0.34f, y + half * 0.48f)
        fuselagePath.close()
        canvas.drawPath(fuselagePath, fillPaint)
        // Skin accent stripe down the spine
        fillPaint.shader = null
        fillPaint.color = skinCols[1]
        flamePath.reset()
        flamePath.moveTo(x, y - half * 0.9f)
        flamePath.lineTo(x - half * 0.07f, y + half * 0.42f)
        flamePath.lineTo(x + half * 0.07f, y + half * 0.42f)
        flamePath.close()
        canvas.drawPath(flamePath, fillPaint)

        // Plasma cannon barrels on the nose
        if (cannonUp > 0) {
            setShadow(Color.rgb(120, 255, 200))
            fillPaint.color = Color.rgb(25, 32, 42)
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

        // Warship evolution tiers (forges with every powerup collected)
        if (shipLevel >= 2) {
            // Side thrusters
            setShadow(Color.rgb(120, 255, 200))
            fillPaint.color = Color.rgb(20, 130, 110)
            for (side in floatArrayOf(-1f, 1f)) {
                canvas.drawCircle(x + side * half * 0.55f, y + half * 0.42f, half * 0.11f, fillPaint)
            }
            fillPaint.color = Color.argb(160, 140, 255, 230)
            for (side in floatArrayOf(-1f, 1f)) {
                canvas.drawCircle(
                    x + side * half * 0.55f, y + half * (0.5f + Random.nextFloat() * 0.1f),
                    half * 0.06f, fillPaint
                )
            }
            setShadow(null)
        }
        if (shipLevel >= 3) {
            // Armor plates flanking the fuselage
            fillPaint.style = Paint.Style.FILL
            val plateShader = hullShader(Color.rgb(150, 162, 178))
            placeShader(plateShader, x, y + half * 0.17f, 1f, half * 0.28f)
            fillPaint.shader = plateShader
            for (side in floatArrayOf(-1f, 1f)) {
                canvas.drawRoundRect(
                    x + side * half * 0.3f - half * 0.07f, y - half * 0.1f,
                    x + side * half * 0.3f + half * 0.07f, y + half * 0.45f,
                    half * 0.05f, half * 0.05f, fillPaint
                )
            }
            fillPaint.shader = null
        }
        if (shipLevel >= 4) {
            // Tail fins + energy spine core
            setShadow(Color.rgb(0, 220, 180))
            fillPaint.color = Color.rgb(0, 150, 130)
            for (side in floatArrayOf(-1f, 1f)) {
                finPath.reset()
                finPath.moveTo(x + side * half * 0.25f, y + half * 0.2f)
                finPath.lineTo(x + side * half * 0.75f, y + half * 0.75f)
                finPath.lineTo(x + side * half * 0.2f, y + half * 0.5f)
                finPath.close()
                canvas.drawPath(finPath, fillPaint)
            }
            fillPaint.color = Color.argb((150 + sin(bgTime * 7f) * 90).toInt().coerceIn(0, 255), 120, 255, 220)
            canvas.drawCircle(x, y + half * 0.05f, half * 0.09f, fillPaint)
            setShadow(null)
        }
        if (shipLevel >= 5) {
            // Gold trim + shoulder cannons: full warship
            setShadow(Color.rgb(255, 216, 120))
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = 2.5f * scale
            fillPaint.color = Color.rgb(255, 216, 120)
            canvas.drawPath(fuselagePath, fillPaint)
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = Color.rgb(255, 216, 120)
            for (side in floatArrayOf(-1f, 1f)) {
                canvas.drawRoundRect(
                    x + side * half * 0.55f - half * 0.04f, y - half * 0.95f,
                    x + side * half * 0.55f + half * 0.04f, y - half * 0.5f,
                    half * 0.035f, half * 0.035f, fillPaint
                )
            }
            setShadow(null)
        }
        fillPaint.style = Paint.Style.FILL

        // Riveted spine highlight
        fillPaint.shader = null
        setShadow(null)
        fillPaint.strokeWidth = half * 0.07f
        fillPaint.style = Paint.Style.STROKE
        fillPaint.color = Color.argb(120, 255, 255, 255)
        canvas.drawLine(x, y - half * 0.78f, x, y + half * 0.2f, fillPaint)
        fillPaint.style = Paint.Style.FILL

        // Glass canopy — dark cockpit glass with a specular glint
        val glassShader = hullShader(Color.rgb(84, 140, 190))
        placeShader(glassShader, x - half * 0.05f, y - half * 0.2f, half * 0.42f)
        fillPaint.shader = glassShader
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

    private fun drawDrone(canvas: Canvas) {
        val x = playerX - 54f * scale + sin(bgTime * 4f) * 8f * scale
        val y = playerY - 18f * scale + cos(bgTime * 5f) * 6f * scale
        setShadow(Color.rgb(180, 210, 255))
        fillPaint.color = Color.rgb(180, 210, 255)
        val dHalf = 18f * scale
        val path = Path().apply {
            moveTo(x, y - dHalf)
            lineTo(x - dHalf * 0.7f, y + dHalf * 0.6f)
            lineTo(x, y + dHalf * 0.2f)
            lineTo(x + dHalf * 0.7f, y + dHalf * 0.6f)
            close()
        }
        canvas.drawPath(path, fillPaint)
        setShadow(null)
        fillPaint.color = Color.WHITE
        canvas.drawCircle(x, y - 2f * scale, 3f * scale, fillPaint)
    }

    private fun drawClone(canvas: Canvas) {
        val x = playerX - 62f * scale
        val y = playerY - 4f * scale
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb((90 + sin(bgTime * 6f) * 40).toInt().coerceIn(0, 255), 200, 160, 255)
        setShadow(Color.rgb(200, 160, 255))
        val half = playerW * 0.7f
        val path = Path().apply {
            moveTo(x, y - half * 0.9f)
            lineTo(x - half * 0.7f, y + half * 0.6f)
            lineTo(x, y + half * 0.25f)
            lineTo(x + half * 0.7f, y + half * 0.6f)
            close()
        }
        canvas.drawPath(path, fillPaint)
        setShadow(null)
    }

    private fun drawShadowEllipse(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float) {
        fillPaint.shader = null
        setShadow(null)
        fillPaint.color = Color.argb(100, 0, 0, 0)
        fillPaint.style = Paint.Style.FILL
        canvas.drawOval(x - rx, y - ry, x + rx, y + ry, fillPaint)
    }

    private fun drawInvaders(canvas: Canvas) {
        // névoa = alpha reduzido nos inimigos (hordeModifier==2) via layer alpha
        val fog = hordeModifier == 2
        var fogLayer = -1
        if (fog) fogLayer = canvas.saveLayerAlpha(0f, 0f, w, h, 110)
        for (inv in invaders) {
            if (!inv.alive) continue
            when (inv.variant) {
                0 -> drawCrab(canvas, inv)
                1 -> drawSquid(canvas, inv)
                2 -> drawArmored(canvas, inv)
                3 -> drawHunter(canvas, inv)
                4 -> drawBomber(canvas, inv)
                5 -> drawSquid(canvas, inv)
                6 -> drawShieldBearer(canvas, inv)
                7 -> drawSniper(canvas, inv)
                8 -> drawSwarmer(canvas, inv)
                9 -> drawCamuflado(canvas, inv)
                10 -> drawFireTrail(canvas, inv)
                else -> drawSwarmer(canvas, inv)
            }
        }
        if (fog) canvas.restoreToCount(fogLayer)
        setShadow(null)
        fillPaint.shader = null
        fillPaint.alpha = 255
    }

    private fun drawHunter(canvas: Canvas, inv: Invader) {
        val s = inv.size
        drawShadowCircle(canvas, inv.x, inv.y, s * 0.7f)
        fillPaint.style = Paint.Style.FILL
        val hs = hullShader(inv.color)
        placeShader(hs, inv.x, inv.y, 1f, s)
        fillPaint.shader = hs
        setShadow(null)
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
        val rs = radialHullShader(inv.color)
        placeShader(rs, inv.x - s * 0.3f, inv.y - s * 0.3f, s * 1.2f)
        fillPaint.shader = rs
        setShadow(null)
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

    private fun drawShieldBearer(canvas: Canvas, inv: Invader) {
        val s = inv.size
        drawShadowRect(canvas, inv.x, inv.y, s * 0.9f, s * 0.5f)
        // Armored hull - angular plates
        fillPaint.style = Paint.Style.FILL
        val rs = radialHullShader(inv.color)
        placeShader(rs, inv.x - s * 0.25f, inv.y - s * 0.2f, s * 1.1f)
        fillPaint.shader = rs
        setShadow(null)
        canvas.drawRoundRect(inv.x - s * 0.85f, inv.y - s * 0.5f, inv.x + s * 0.85f, inv.y + s * 0.35f, s * 0.35f, s * 0.35f, fillPaint)
        fillPaint.shader = null
        setShadow(null)
        // Frontal energy shield - pulsating arc
        val pulse = 0.6f + sin(inv.pulse * 2f) * 0.4f
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = 4f * scale
        fillPaint.color = Color.argb((110 + pulse * 90).toInt(), 120, 210, 255)
        canvas.drawArc(inv.x - s * 1.15f, inv.y - s * 0.85f, inv.x + s * 1.15f, inv.y + s * 1.05f, 200f, 140f, false, fillPaint)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb((28 + pulse * 22).toInt(), 120, 210, 255)
        canvas.drawArc(inv.x - s * 1.15f, inv.y - s * 0.85f, inv.x + s * 1.15f, inv.y + s * 1.05f, 200f, 140f, true, fillPaint)
        // HP pips
        fillPaint.color = Color.WHITE
        for (i in 0 until inv.hp) canvas.drawCircle(inv.x - s * 0.22f + i * s * 0.22f, inv.y - s * 0.32f, s * 0.06f, fillPaint)
    }

    private fun drawSniper(canvas: Canvas, inv: Invader) {
        val s = inv.size
        drawShadowRect(canvas, inv.x, inv.y, s * 0.5f, s * 0.6f)
        setShadow(inv.color)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = inv.color
        // Slender body
        canvas.drawRoundRect(inv.x - s * 0.32f, inv.y - s * 0.6f, inv.x + s * 0.32f, inv.y + s * 0.15f, s * 0.18f, s * 0.18f, fillPaint)
        // Long barrel
        fillPaint.color = shade(inv.color, 0.55f)
        canvas.drawRect(inv.x - s * 0.08f, inv.y + s * 0.15f, inv.x + s * 0.08f, inv.y + s * 0.95f, fillPaint)
        // Scope lens
        fillPaint.color = Color.BLACK
        canvas.drawCircle(inv.x, inv.y - s * 0.38f, s * 0.14f, fillPaint)
        // Charging glow at tip - pulses with phase
        val charge = (sin(inv.pulse * 1.8f) * 0.5f + 0.5f)
        if (charge > 0.6f) {
            setShadow(Color.rgb(255, 240, 120))
            fillPaint.color = Color.argb((charge * 200).toInt(), 255, 240, 120)
            canvas.drawCircle(inv.x, inv.y + s * 0.95f, s * (0.12f + charge * 0.08f), fillPaint)
            setShadow(inv.color)
        } else setShadow(null)
    }

    private fun drawSwarmer(canvas: Canvas, inv: Invader) {
        val s = inv.size
        drawShadowCircle(canvas, inv.x, inv.y, s * 0.55f)
        setShadow(inv.color)
        fillPaint.style = Paint.Style.FILL
        // Fluttering wings - alpha pulse
        val flap = sin(inv.pulse * 4f) * s * 0.25f
        fillPaint.color = shade(inv.color, 1.3f)
        fillPaint.alpha = 140
        canvas.drawOval(inv.x - s * 0.95f, inv.y - s * 0.15f + flap, inv.x - s * 0.15f, inv.y + s * 0.35f - flap, fillPaint)
        canvas.drawOval(inv.x + s * 0.15f, inv.y - s * 0.15f - flap, inv.x + s * 0.95f, inv.y + s * 0.35f + flap, fillPaint)
        fillPaint.alpha = 255
        fillPaint.color = inv.color
        canvas.drawCircle(inv.x, inv.y, s * 0.5f, fillPaint)
        setShadow(null)
        // Two beady eyes
        fillPaint.color = Color.BLACK
        canvas.drawCircle(inv.x - s * 0.14f, inv.y - s * 0.08f, s * 0.08f, fillPaint)
        canvas.drawCircle(inv.x + s * 0.14f, inv.y - s * 0.08f, s * 0.08f, fillPaint)
        fillPaint.color = Color.WHITE
        canvas.drawCircle(inv.x - s * 0.12f, inv.y - s * 0.11f, s * 0.03f, fillPaint)
        canvas.drawCircle(inv.x + s * 0.16f, inv.y - s * 0.11f, s * 0.03f, fillPaint)
    }

    private fun drawCamuflado(canvas: Canvas, inv: Invader) {
        val s = inv.size
        val dist = hypot(playerX - inv.x, playerY - inv.y)
        val revealed = dist < 200f * scale
        val alpha = if (revealed) 255 else 40
        drawShadowCircle(canvas, inv.x, inv.y, s * 0.55f)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = inv.color
        fillPaint.alpha = alpha
        setShadow(if (revealed) inv.color else Color.TRANSPARENT)
        // Corpo camuflado - forma similar ao armored mas fantasma
        canvas.drawRoundRect(inv.x - s * 0.85f, inv.y - s * 0.45f, inv.x + s * 0.85f, inv.y + s * 0.35f, s * 0.25f, s * 0.25f, fillPaint)
        if (revealed) {
            fillPaint.alpha = 255
            fillPaint.color = Color.WHITE
            canvas.drawCircle(inv.x - s * 0.2f, inv.y - s * 0.1f, s * 0.08f, fillPaint)
            canvas.drawCircle(inv.x + s * 0.2f, inv.y - s * 0.1f, s * 0.08f, fillPaint)
            fillPaint.color = Color.BLACK
            canvas.drawCircle(inv.x - s * 0.2f, inv.y - s * 0.1f, s * 0.04f, fillPaint)
            canvas.drawCircle(inv.x + s * 0.2f, inv.y - s * 0.1f, s * 0.04f, fillPaint)
        } else {
            // silhueta tracejada
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = 2f * scale
            fillPaint.color = Color.argb(alpha, 180, 180, 190)
            canvas.drawRoundRect(inv.x - s * 0.85f, inv.y - s * 0.45f, inv.x + s * 0.85f, inv.y + s * 0.35f, s * 0.25f, s * 0.25f, fillPaint)
            fillPaint.style = Paint.Style.FILL
        }
        fillPaint.alpha = 255
        setShadow(null)
    }

    private fun drawFireTrail(canvas: Canvas, inv: Invader) {
        val s = inv.size
        drawShadowCircle(canvas, inv.x, inv.y, s * 0.6f)
        // Rastro de fogo atrás - cached glow
        fillPaint.style = Paint.Style.FILL
        drawGlow(canvas, inv.x, inv.y + s * 0.3f, s * 0.9f, Color.argb(120, 255, 120, 30))
        fillPaint.shader = null
        setShadow(null)
        fillPaint.color = inv.color
        canvas.drawCircle(inv.x, inv.y, s * 0.55f, fillPaint)
        // Chamas internas pulsantes
        val flicker = sin(inv.pulse * 6f) * 0.15f + 0.85f
        fillPaint.color = Color.argb((200 * flicker).toInt(), 255, 200, 60)
        canvas.drawCircle(inv.x, inv.y + s * 0.15f, s * 0.25f * flicker, fillPaint)
        fillPaint.color = Color.WHITE
        canvas.drawCircle(inv.x, inv.y - s * 0.05f, s * 0.08f, fillPaint)
        setShadow(null)
    }

    private fun drawCrab(canvas: Canvas, inv: Invader) {
        val s = inv.size
        val pulse = 1f + sin(inv.pulse) * 0.06f
        drawShadowRect(canvas, inv.x, inv.y, s * pulse, s * 0.5f)

        // Angular warship hull - faceted armor plates
        fillPaint.style = Paint.Style.FILL
        val cs = hullShader(inv.color)
        placeShader(cs, inv.x, inv.y - s * 0.12f, 1f, s)
        fillPaint.shader = cs
        setShadow(null)
        // Main hull - beveled octagon
        val hullPath = Path().apply {
            moveTo(inv.x - s * 0.85f * pulse, inv.y - s * 0.4f * pulse)
            lineTo(inv.x - s * 0.55f * pulse, inv.y - s * 0.55f * pulse)
            lineTo(inv.x + s * 0.55f * pulse, inv.y - s * 0.55f * pulse)
            lineTo(inv.x + s * 0.85f * pulse, inv.y - s * 0.4f * pulse)
            lineTo(inv.x + s * pulse, inv.y + s * 0.15f * pulse)
            lineTo(inv.x + s * 0.7f * pulse, inv.y + s * 0.30f * pulse)
            lineTo(inv.x - s * 0.7f * pulse, inv.y + s * 0.30f * pulse)
            lineTo(inv.x - s * pulse, inv.y + s * 0.15f * pulse)
            close()
        }
        canvas.drawPath(hullPath, fillPaint)
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

        // Angular drone chassis
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        drawGlow(canvas, inv.x, inv.y - s * 0.1f, s * 0.88f, Color.argb(55, inv.color shr 16 and 0xFF, inv.color shr 8 and 0xFF, inv.color and 0xFF))
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

        // Faceted drone head - angular instead of sphere
        val headS = radialHullShader(inv.color)
        placeShader(headS, inv.x - s * 0.15f, inv.y - s * 0.2f, s * 0.75f)
        fillPaint.shader = headS
        val headPath = Path().apply {
            moveTo(inv.x, inv.y - s * 0.72f)
            lineTo(inv.x + s * 0.52f, inv.y - s * 0.35f)
            lineTo(inv.x + s * 0.42f, inv.y + s * 0.22f)
            lineTo(inv.x, inv.y + s * 0.38f)
            lineTo(inv.x - s * 0.42f, inv.y + s * 0.22f)
            lineTo(inv.x - s * 0.52f, inv.y - s * 0.35f)
            close()
        }
        canvas.drawPath(headPath, fillPaint)
        fillPaint.shader = null

        // Pulsing core - cached glow
        val corePulse = 0.6f + sin(inv.pulse * 2f) * 0.4f
        drawGlow(canvas, inv.x, inv.y - s * 0.1f, s * 0.32f, Color.argb((120 + corePulse * 100).toInt(), 255, 255, 255))
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

        // Heavy angular armor - faceted bunker hull
        fillPaint.style = Paint.Style.FILL
        val armorHs = hullShader(inv.color)
        placeShader(armorHs, inv.x, inv.y - s * 0.12f, 1f, s * 0.5f)
        fillPaint.shader = armorHs
        setShadow(null)
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

        // Under-glow beam - cached
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        drawGlow(canvas, x, y + s * 0.3f, s * 1.4f, Color.argb(90, 120, 255, 240))
        fillPaint.shader = null

        // Angular recon craft body - faceted
        val ufoHs = hullShader(Color.rgb(130, 145, 160))
        placeShader(ufoHs, x, y + s * 0.03f, 1f, s * 0.32f)
        fillPaint.shader = ufoHs
        setShadow(null)
        val hullPath = Path().apply {
            moveTo(x - s * 0.95f, y - s * 0.05f)
            lineTo(x - s * 0.6f, y - s * 0.28f)
            lineTo(x + s * 0.6f, y - s * 0.28f)
            lineTo(x + s * 0.95f, y - s * 0.05f)
            lineTo(x + s * 0.85f, y + s * 0.28f)
            lineTo(x - s * 0.85f, y + s * 0.28f)
            close()
        }
        canvas.drawPath(hullPath, fillPaint)
        fillPaint.shader = null
        setShadow(null)

        // Cockpit canopy - flat glass
        val domeShader = hullShader(Color.rgb(180, 200, 215))
        placeShader(domeShader, x, y - s * 0.32f, s * 0.45f)
        fillPaint.shader = domeShader
        val domePath = Path().apply {
            moveTo(x - s * 0.42f, y - s * 0.08f)
            lineTo(x - s * 0.28f, y - s * 0.62f)
            lineTo(x + s * 0.28f, y - s * 0.62f)
            lineTo(x + s * 0.42f, y - s * 0.08f)
            close()
        }
        canvas.drawPath(domePath, fillPaint)
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
                    rocketPath.reset()
                    rocketPath.moveTo(b.x, b.y - 14f * scale)
                    rocketPath.lineTo(b.x - 5f * scale, b.y + 8f * scale)
                    rocketPath.lineTo(b.x + 5f * scale, b.y + 8f * scale)
                    rocketPath.close()
                    canvas.drawPath(rocketPath, fillPaint)
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
        // Dense battle smoke sits UNDER the entities
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = null
        setShadow(null)
        for (p in particles) {
            if (p.kind != Particle.KIND_SMOKE) continue
            val t = p.life / p.maxLife
            fillPaint.color = Color.argb((t * 84).toInt().coerceIn(0, 255), 92, 92, 102)
            canvas.drawCircle(p.x, p.y, p.radius, fillPaint)
        }
        fillPaint.alpha = 255
    }

    private fun drawParticlesAbove(canvas: Canvas) {
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = null
        setShadow(null)
        // Tumbling debris (normal blend, keeps its victim's hue)
        for (p in particles) {
            if (p.kind != Particle.KIND_DEBRIS) continue
            val t = p.life / p.maxLife
            fillPaint.color = p.color
            fillPaint.alpha = (t * 255).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rot)
            val s = p.radius
            canvas.drawRect(-s, -s * 0.6f, s, s * 0.6f, fillPaint)
            canvas.restore()
        }
        fillPaint.alpha = 255

        // Energy: sparks, fire, flash, embers, rings (additive)
        for (p in particles) {
            when (p.kind) {
                Particle.KIND_SPARK, Particle.KIND_EMBER -> {
                    val t = p.life / p.maxLife
                    addPaint.color = p.color
                    if (p.kind == Particle.KIND_EMBER) {
                        val flick = 0.6f + 0.4f * sin(p.x * 7f + bgTime * 18f)
                        addPaint.alpha = (t * flick * 255f).toInt().coerceIn(0, 255)
                    } else {
                        addPaint.alpha = (t * 255).toInt().coerceIn(0, 255)
                    }
                    canvas.drawCircle(p.x, p.y, p.radius * (0.4f + t * 0.6f), addPaint)
                }
                Particle.KIND_FIRE -> {
                    val t = p.life / p.maxLife
                    addPaint.color = fireRampColor(t)
                    addPaint.alpha = (t * 235).toInt().coerceIn(0, 255)
                    canvas.drawCircle(p.x, p.y, p.radius, addPaint)
                }
                Particle.KIND_FLASH -> {
                    val t = p.life / p.maxLife
                    addPaint.color = Color.WHITE
                    addPaint.alpha = (t * 230).toInt().coerceIn(0, 255)
                    canvas.drawCircle(p.x, p.y, p.radius * (1.6f - t * 0.6f), addPaint)
                }
                Particle.KIND_RING -> {
                    val t = 1f - p.life / p.maxLife
                    addPaint.style = Paint.Style.STROKE
                    addPaint.strokeWidth = (6f * (1f - t) + 1f) * scale
                    addPaint.color = p.color
                    addPaint.alpha = ((1f - t) * 210).toInt().coerceIn(0, 255)
                    canvas.drawCircle(p.x, p.y, p.radius + t * 110f * scale, addPaint)
                    addPaint.style = Paint.Style.FILL
                }
            }
        }
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

        // Menacing hull - palette per sector
        val hullCols = when (b.type) {
            1 -> intArrayOf(Color.rgb(45, 90, 130), Color.rgb(18, 48, 90), Color.rgb(6, 18, 40)) // Glacial
            2 -> intArrayOf(Color.rgb(130, 45, 20), Color.rgb(90, 22, 12), Color.rgb(40, 10, 6)) // Vulcanico
            3 -> intArrayOf(Color.rgb(20, 10, 30), Color.rgb(10, 5, 20), Color.rgb(2, 2, 8)) // Vazio - preto/roxo
            else -> intArrayOf(Color.rgb(90, 45, 130), Color.rgb(40, 18, 66), Color.rgb(12, 6, 22))
        }
        val glowCol = when (b.type) { 1 -> Color.rgb(120, 230, 255); 2 -> Color.rgb(255, 140, 60); 3 -> Color.rgb(120, 40, 180); else -> Color.rgb(255, 80, 180) }
        fillPaint.style = Paint.Style.FILL
        // Dreadnought hull - faceted angular plates, base color by sector
        val hullBase = hullCols[1]
        val bossHs = hullShader(hullBase)
        placeShader(bossHs, b.x, b.y + s * 0.02f, 1f, s * 0.55f)
        fillPaint.shader = bossHs
        setShadow(null)
        val dreadPath = Path().apply {
            moveTo(b.x - s * 1.2f, b.y - s * 0.15f)
            lineTo(b.x - s * 0.9f, b.y - s * 0.48f)
            lineTo(b.x + s * 0.9f, b.y - s * 0.48f)
            lineTo(b.x + s * 1.2f, b.y - s * 0.15f)
            lineTo(b.x + s * 1.05f, b.y + s * 0.38f)
            lineTo(b.x - s * 1.05f, b.y + s * 0.38f)
            close()
        }
        canvas.drawPath(dreadPath, fillPaint)
        fillPaint.shader = null
        // Hull plate lines
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = 2f * scale
        fillPaint.color = shade(hullBase, 1.4f)
        canvas.drawPath(dreadPath, fillPaint)
        fillPaint.style = Paint.Style.FILL

        // Side cannons
        fillPaint.color = Color.rgb(25, 12, 40)
        for (side in floatArrayOf(-1f, 1f)) {
            canvas.drawCircle(b.x + side * s * 1.05f, b.y + s * 0.1f, s * 0.22f, fillPaint)
        }

        // Pulsing weak-point core - cached glow
        val coreCol = when (b.type) { 1 -> Color.rgb(120, 230, 255); 2 -> Color.rgb(255, 170, 60); else -> Color.rgb(255, 110, 200) }
        val corePulse = 0.7f + sin(b.pulse * 2f) * 0.3f
        fillPaint.alpha = (140 + corePulse * 100).toInt()
        drawGlow(canvas, b.x, b.y, s * 0.4f, coreCol)
        // White hot center
        fillPaint.alpha = (200).coerceAtMost(255)
        canvas.drawCircle(b.x, b.y, s * 0.14f, fillPaint.apply { color = Color.WHITE; shader = null })
        fillPaint.alpha = 255
        fillPaint.shader = null

        // Spikes
        fillPaint.color = Color.rgb(60, 30, 90)
        for (i in -2..2) {
            if (i == 0) continue
            canvas.drawRect(b.x + i * s * 0.5f - s * 0.05f, b.y - s * 0.75f, b.x + i * s * 0.5f + s * 0.05f, b.y - s * 0.4f, fillPaint)
        }
        // Tipo 3: anel de buraco negro
        if (b.type == 3) {
            val pulse = 0.6f + sin(b.pulse * 3f) * 0.4f
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = 6f * scale
            fillPaint.color = Color.argb((120 + pulse * 80).toInt(), 80, 30, 140)
            canvas.drawCircle(b.x, b.y, s * 0.55f + pulse * 8f * scale, fillPaint)
            fillPaint.color = Color.argb((60 + pulse * 40).toInt(), 0, 0, 0)
            fillPaint.style = Paint.Style.FILL
            canvas.drawCircle(b.x, b.y, s * 0.32f, fillPaint)
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = 3f * scale
            fillPaint.color = Color.argb((180 + pulse * 60).toInt(), 140, 80, 220)
            canvas.drawCircle(b.x, b.y, s * 0.42f, fillPaint)
            fillPaint.style = Paint.Style.FILL
        }
    }

    /** Orbital strike beams raining from above during the special cinematic. */
    private fun drawBeams(canvas: Canvas) {
        fillPaint.style = Paint.Style.FILL
        for (beam in beams) {
            val t = (beam.life / 0.5f).coerceIn(0f, 1f)
            fillPaint.shader = LinearGradient(
                0f, 0f, 0f, beam.y,
                intArrayOf(Color.TRANSPARENT, Color.argb((t * 210).toInt(), 255, 240, 150)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            val bw = 7f * scale * t
            canvas.drawRect(beam.x - bw, 0f, beam.x + bw, beam.y, fillPaint)
            fillPaint.shader = null
            fillPaint.color = Color.argb((t * 220).toInt(), 255, 250, 200)
            canvas.drawCircle(beam.x, beam.y, 16f * scale * t, fillPaint)
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

            // Rotating diamond capsule - cached hull shader at origin
            canvas.save()
            canvas.translate(p.x, bobY)
            canvas.rotate((p.phase * 40f) % 360f)
            fillPaint.style = Paint.Style.FILL
            val puHs = hullShader(color)
            placeShader(puHs, 0f, 0f, s)
            fillPaint.shader = puHs
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
                PowerType.SLOW -> "Z"
                PowerType.MAGNET -> "M"
                PowerType.CLONE -> "C"
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
        textPaint.setShadowLayer(10f, 0f, 0f, Color.rgb(45, 55, 70))
        textPaint.color = Color.rgb(225, 232, 240)
        canvas.drawText("SCORE $score", 30f, 56f * scale, textPaint)
        // Combo rank S/A/B/C próximo ao score com cor distinta
        val rank = comboRank()
        val rankCol = comboRankColor()
        textPaint.setShadowLayer(10f, 0f, 0f, rankCol)
        textPaint.color = rankCol
        val scoreW = textPaint.measureText("SCORE $score")
        canvas.drawText("RANK $rank", 30f + scoreW + 18f * scale, 56f * scale, textPaint)
        textPaint.textSize = 22f * scale
        textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(180, 180, 180))
        textPaint.color = Color.rgb(200, 200, 200)
        canvas.drawText("BEST ${bestRankLabel()}", 30f + scoreW + 18f * scale + textPaint.measureText("RANK $rank ") + 8f * scale, 56f * scale, textPaint)
        textPaint.textSize = 40f * scale

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.setShadowLayer(10f, 0f, 0f, Color.rgb(60, 50, 30))
        textPaint.color = Color.rgb(232, 206, 142)
        canvas.drawText(strWaveFmt.format(wave), w / 2f, 56f * scale, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.setShadowLayer(10f, 0f, 0f, Color.rgb(40, 35, 35))
        textPaint.color = Color.rgb(210, 70, 70)
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
        // Coins display top-right below lives
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 24f * scale
        textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(255, 220, 120))
        textPaint.color = Color.rgb(255, 220, 120)
        canvas.drawText("$" + coins, w - 30f, 86f * scale, textPaint)
        textPaint.textAlign = Paint.Align.LEFT

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
            canvas.drawText(strBossMothership, w / 2f, barY - 6f * scale, textPaint)
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
            val a = (waveBannerTimer / 2.2f).coerceIn(0f, 1f)
            bigTextPaint.alpha = (a * 255).toInt()
            bigTextPaint.textSize = 72f * scale
            if (wave > 12) {
                bigTextPaint.setShadowLayer(22f, 0f, 0f, Color.rgb(255, 60, 60))
                bigTextPaint.color = Color.rgb(255, 80, 80)
                canvas.drawText(strHordeMode, w / 2f, h * 0.42f, bigTextPaint)
                bigTextPaint.textSize = 44f * scale
                bigTextPaint.color = Color.rgb(255, 180, 180)
                canvas.drawText(strWaveFmt.format(wave), w / 2f, h * 0.48f, bigTextPaint)
            } else {
                bigTextPaint.setShadowLayer(22f, 0f, 0f, Color.CYAN)
                bigTextPaint.color = Color.rgb(140, 240, 255)
                canvas.drawText(strWaveFmt.format(wave), w / 2f, h * 0.42f, bigTextPaint)
            }
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
            barY += barH + 7f * scale
        }
        if (slowTimer > 0f) {
            drawPowerBar(canvas, 30f, barY, barW, barH, slowTimer / 5f, powerColor(PowerType.SLOW), "LENTO")
            barY += barH + 7f * scale
        }
        if (magnetTimer > 0f) {
            drawPowerBar(canvas, 30f, barY, barW, barH, magnetTimer / 8f, powerColor(PowerType.MAGNET), "IMA")
            barY += barH + 7f * scale
        }
        if (cloneTimer > 0f) {
            drawPowerBar(canvas, 30f, barY, barW, barH, cloneTimer / 8f, powerColor(PowerType.CLONE), "CLONE")
            barY += barH + 7f * scale
        }

        // Installed modules pips (bottom-left)
        if (engineUp + cannonUp + wingUp + hullUp + droneUp + coreUp > 0) {
            textPaint.textSize = 20f * scale
            textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(255, 216, 120))
            textPaint.color = Color.rgb(255, 216, 120)
            canvas.drawText(
                "M $engineUp C $cannonUp W $wingUp H $hullUp D $droneUp N $coreUp",
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

    private fun drawMines(canvas: Canvas) {
        for (m in mines) {
            val pulse = 0.6f + sin(m.pulse * 3f) * 0.4f
            fillPaint.style = Paint.Style.FILL
            setShadow(Color.rgb(255, 140, 60))
            fillPaint.color = Color.rgb(255, 180, 60)
            canvas.drawCircle(m.x, m.y, 18f * scale + pulse * 4f * scale, fillPaint)
            fillPaint.color = Color.rgb(255, 90, 40)
            canvas.drawCircle(m.x, m.y, 10f * scale, fillPaint)
            fillPaint.color = Color.WHITE
            canvas.drawCircle(m.x, m.y, 4f * scale, fillPaint)
            // timer ring
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = 3f * scale
            fillPaint.color = Color.argb(180, 255, 220, 120)
            val sweep = 360f * (m.timer / 1.8f)
            canvas.drawArc(m.x - 22f*scale, m.y - 22f*scale, m.x + 22f*scale, m.y + 22f*scale, -90f, sweep, false, fillPaint)
            fillPaint.style = Paint.Style.FILL
            setShadow(null)
        }
    }

    private fun drawShop(canvas: Canvas) {
        // Fundo escuro
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        fillPaint.color = Color.argb(200, 8, 10, 30)
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        // Titulo
        bigTextPaint.textSize = 64f * scale
        bigTextPaint.setShadowLayer(18f, 0f, 0f, Color.rgb(255, 220, 120))
        bigTextPaint.color = Color.WHITE
        canvas.drawText(strShop, w/2f, h * 0.18f, bigTextPaint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 26f * scale
        textPaint.setShadowLayer(8f, 0f, 0f, Color.rgb(255, 220, 120))
        textPaint.color = Color.rgb(255, 220, 120)
        canvas.drawText(strCoinsFmt.format(coins), w/2f, h * 0.24f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        // Grade 3 skins
        val cardW = 280f * scale
        val cardH = 260f * scale
        val gap = 30f * scale
        val totalW = cardW * 3 + gap * 2
        val startX = w / 2f - totalW / 2f
        val startY = h * 0.32f
        val names = arrayOf("PADRAO", "RUBI", "OURO")
        val prices = arrayOf(0, 500, 500)
        val skinCols = arrayOf(
            intArrayOf(Color.rgb(0, 210, 175), Color.rgb(0, 90, 110)),
            intArrayOf(Color.rgb(255, 90, 90), Color.rgb(90, 10, 30)),
            intArrayOf(Color.rgb(255, 220, 120), Color.rgb(120, 90, 20))
        )
        for (i in 0..2) {
            val x = startX + i * (cardW + gap)
            val y = startY
            // card bg
            fillPaint.color = if (selectedSkin == i) Color.argb(220, 30, 60, 50) else Color.argb(160, 20, 20, 40)
            setShadow(if (selectedSkin == i) Color.rgb(255, 220, 120) else Color.TRANSPARENT)
            canvas.drawRoundRect(x, y, x + cardW, y + cardH, 18f*scale, 18f*scale, fillPaint)
            setShadow(null)
            // preview nave
            val px = x + cardW/2f
            val py = y + 90f * scale
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = skinCols[i][0]
            val path = android.graphics.Path().apply {
                moveTo(px, py - 30f*scale)
                lineTo(px - 40f*scale, py + 20f*scale)
                lineTo(px, py + 8f*scale)
                lineTo(px + 40f*scale, py + 20f*scale)
                close()
            }
            canvas.drawPath(path, fillPaint)
            // nome
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 22f * scale
            textPaint.setShadowLayer(6f, 0f, 0f, skinCols[i][0])
            textPaint.color = Color.WHITE
            canvas.drawText(names[i], px, y + 150f*scale, textPaint)
            // botao
            val btnW = 180f * scale
            val btnH = 48f * scale
            val bx = px - btnW/2f
            val by = y + cardH - 62f * scale
            val owned = ownedSkins.contains(i)
            val canBuy = !owned && coins >= prices[i]
            fillPaint.color = when {
                selectedSkin == i -> Color.rgb(120, 255, 160)
                owned -> Color.rgb(90, 200, 255)
                canBuy -> Color.rgb(255, 220, 120)
                else -> Color.rgb(80, 80, 90)
            }
            canvas.drawRoundRect(bx, by, bx+btnW, by+btnH, btnH/2f, btnH/2f, fillPaint)
            textPaint.textSize = 18f * scale
            textPaint.setShadowLayer(4f, 0f, 0f, Color.BLACK)
            textPaint.color = Color.BLACK
            val label = when {
                selectedSkin == i -> strSelected
                owned -> strSelect
                else -> strBuyFmt.format(prices[i])
            }
            canvas.drawText(label, px, by + btnH*0.68f, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
        // botao VOLTAR
        val bw = 260f * scale
        val bh = 58f * scale
        val left = w/2f - bw/2f
        val top = h * 0.88f
        fillPaint.color = Color.argb(200, 40, 40, 60)
        canvas.drawRoundRect(left, top, left+bw, top+bh, bh/2f, bh/2f, fillPaint)
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = 3f * scale
        fillPaint.color = Color.WHITE
        canvas.drawRoundRect(left, top, left+bw, top+bh, bh/2f, bh/2f, fillPaint)
        fillPaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 28f * scale
        textPaint.setShadowLayer(8f, 0f, 0f, Color.CYAN)
        textPaint.color = Color.WHITE
        canvas.drawText(strBack, w/2f, top + bh*0.68f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawMenu(canvas: Canvas) {
        val pulse = 0.5f + sin(bgTime * 3f) * 0.5f

        // Decorative ship hovering above the title
        val sx = w / 2f
        val sy = h * 0.16f + sin(bgTime * 2f) * 8f * scale
        drawGlow(canvas, sx, sy, 90f * scale, Color.argb(90, 180, 190, 200))
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        fillPaint.color = Color.rgb(160, 170, 185)
        val ship = Path().apply {
            moveTo(sx, sy - 34f * scale)
            lineTo(sx - 26f * scale, sy + 22f * scale)
            lineTo(sx, sy + 10f * scale)
            lineTo(sx + 26f * scale, sy + 22f * scale)
            close()
        }
        canvas.drawPath(ship, fillPaint)
        setShadow(null)
        fillPaint.color = Color.WHITE
        canvas.drawCircle(sx, sy - 6f * scale, 5f * scale, fillPaint)

        // Title - tactical steel/amber
        bigTextPaint.textSize = 92f * scale
        bigTextPaint.setShadowLayer(26f, 0f, 0f, Color.rgb(45, 55, 70))
        bigTextPaint.color = Color.rgb(225, 232, 240)
        canvas.drawText("SPACE", w / 2f, h * 0.36f, bigTextPaint)
        bigTextPaint.setShadowLayer(26f, 0f, 0f, Color.rgb(60, 50, 30))
        bigTextPaint.color = Color.rgb(232, 206, 142)
        canvas.drawText("INVADERS", w / 2f, h * 0.47f, bigTextPaint)

        // High score
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 30f * scale
        textPaint.setShadowLayer(10f, 0f, 0f, Color.rgb(255, 216, 120))
        textPaint.color = Color.rgb(255, 226, 150)
        canvas.drawText(strHighscoreFmt.format(highScore), w / 2f, h * 0.55f, textPaint)
        // Leaderboard top3
        if (leaderboard.isNotEmpty()) {
            textPaint.textSize = 22f * scale
            textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(45, 55, 70))
            textPaint.color = Color.argb(200, 180, 200, 210)
            for (i in 0 until minOf(3, leaderboard.size)) {
                canvas.drawText("${i+1}. ${leaderboard[i]}", w / 2f, h * 0.55f + 28f * scale + i * 24f * scale, textPaint)
            }
        }

        // JOGAR button - uses shared hitbox
        run {
            val r = menuPlayRect()
            val left = r.left; val top = r.top; val bw = r.width(); val bh = r.height()
            setShadow(Color.rgb(45, 55, 70))
            fillPaint.color = Color.argb(230, 38, 45, 58)
            canvas.drawRoundRect(left, top, left + bw, top + bh, bh / 2f, bh / 2f, fillPaint)
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = 3.5f * scale
            fillPaint.color = Color.rgb(180, 190, 205)
            canvas.drawRoundRect(left, top, left + bw, top + bh, bh / 2f, bh / 2f, fillPaint)
            fillPaint.style = Paint.Style.FILL
            setShadow(null)
            textPaint.textSize = 44f * scale
            textPaint.setShadowLayer(14f, 0f, 0f, Color.rgb(45, 55, 70))
            textPaint.color = Color.rgb(232, 206, 142)
            canvas.drawText(strPlay, w / 2f, top + bh * 0.68f, textPaint)
        }
        // LOJA button - uses shared hitbox
        run {
            val r2 = menuShopRect()
            val left2 = r2.left; val top2 = r2.top; val bw2 = r2.width(); val bh2 = r2.height()
        setShadow(Color.rgb(255, 220, 120))
        fillPaint.color = Color.argb(200, 60, 50, 20)
        canvas.drawRoundRect(left2, top2, left2 + bw2, top2 + bh2, bh2/2f, bh2/2f, fillPaint)
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = 3f * scale
        fillPaint.color = Color.rgb(255, 220, 120)
        canvas.drawRoundRect(left2, top2, left2 + bw2, top2 + bh2, bh2/2f, bh2/2f, fillPaint)
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        textPaint.textSize = 28f * scale
        textPaint.setShadowLayer(8f, 0f, 0f, Color.rgb(255, 220, 120))
        textPaint.color = Color.WHITE
        canvas.drawText(strShopCoinsFmt.format(coins), w / 2f, top2 + bh2 * 0.68f, textPaint)
        }

        // Controls hint - tactical
        textPaint.textSize = 20f * scale
        textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(45, 55, 70))
        textPaint.color = Color.argb(210, 200, 210, 220)
        canvas.drawText(
            strControlsHint,
            w / 2f, h * 0.87f, textPaint
        )
        textPaint.color = Color.argb(170, 180, 190, 205)
        textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(45, 55, 70))
        canvas.drawText(strTagline, w / 2f, h * 0.92f, textPaint)
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawGameOver(canvas: Canvas) {
        bigTextPaint.textSize = 96f * scale
        bigTextPaint.setShadowLayer(24f, 0f, 0f, Color.RED)
        bigTextPaint.color = Color.rgb(255, 80, 80)
        canvas.drawText(strGameOver, w / 2f, h / 2f - 20f * scale, bigTextPaint)

        bigTextPaint.textSize = 44f * scale
        bigTextPaint.setShadowLayer(12f, 0f, 0f, Color.CYAN)
        bigTextPaint.color = Color.WHITE
        canvas.drawText(strScoreFmt.format(score), w / 2f, h / 2f + 60f * scale, bigTextPaint)
        // leaderboard top3 in game over
        if (leaderboard.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 20f * scale
            textPaint.setShadowLayer(6f, 0f, 0f, Color.rgb(255, 216, 120))
            textPaint.color = Color.rgb(255, 226, 150)
            for (i in 0 until minOf(3, leaderboard.size)) {
                canvas.drawText("TOP ${i+1}: ${leaderboard[i]}", w/2f, h/2f + 90f*scale + i*22f*scale, textPaint)
            }
            textPaint.textAlign = Paint.Align.LEFT
        }

        val alpha = if (gameOverTimer > 1.2f && (gameOverTimer * 2f).toInt() % 2 == 0) 255 else 90
        bigTextPaint.alpha = alpha
        bigTextPaint.setShadowLayer(10f, 0f, 0f, Color.YELLOW)
        bigTextPaint.color = Color.rgb(255, 230, 120)
        canvas.drawText(strTapRestart, w / 2f, h / 2f + 130f * scale, bigTextPaint)
        bigTextPaint.alpha = 255

        // MENU button (bottom-left)
        if (gameOverTimer > 0.8f) {
            fillPaint.style = Paint.Style.FILL
            setShadow(null)
            fillPaint.color = Color.argb(190, 30, 30, 60)
            canvas.drawRoundRect(30f, h - 74f * scale, 190f * scale, h - 26f * scale, 20f * scale, 20f * scale, fillPaint)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 24f * scale
            textPaint.setShadowLayer(6f, 0f, 0f, Color.CYAN)
            textPaint.color = Color.WHITE
            canvas.drawText(strMenu, 110f * scale, h - 42f * scale, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun setShadow(color: Int?) {
        if (color != null) {
            fillPaint.setShadowLayer(18f * scale, 0f, 0f, color)
        } else {
            fillPaint.clearShadowLayer()
        }
    }

    // ---------- Entities ----------

    private class Mine(var x: Float, var y: Float, var timer: Float = 1.8f, var pulse: Float = 0f)

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
        var mini: Boolean = false,
        var wraps: Int = 0
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
    ) {
        companion object {
            const val KIND_SPARK = 0
            const val KIND_FIRE = 1
            const val KIND_SMOKE = 2
            const val KIND_DEBRIS = 3
            const val KIND_RING = 4
            const val KIND_FLASH = 5
            const val KIND_EMBER = 6
        }

        var kind: Int = if (isRing) KIND_RING else KIND_SPARK
        var rot: Float = Random.nextFloat() * 360f
        var rotSpeed: Float = 0f
        var drag: Float = 0.98f
        var grow: Float = 0f
    }

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

    private enum class PowerType { RAPID, TRIPLE, SHIELD, LIFE, NOVA, PART, WEAPON, ARMOR, SLOW, MAGNET, CLONE }

    private class PowerUp(var x: Float, var y: Float, val type: PowerType, var phase: Float = Random.nextFloat() * 6f)

    private class Meteor(var x: Float, var y: Float, val vx: Float, val vy: Float, val len: Float)

    private class Dust(var x: Float, var y: Float, val radius: Float, val drift: Float, val speed: Float)

    private class FloatText(val text: String, var x: Float, var y: Float, val color: Int, var life: Float = 1.1f)

    private class Beam(val x: Float, val y: Float, var life: Float = 0.5f)
}

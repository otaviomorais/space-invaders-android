package com.example.spaceinvaders

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
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

    // Paints
    private val bgPaint = Paint()
    private val nebulae = arrayOf(
        Paint(Paint.ANTI_ALIAS_FLAG), Paint(Paint.ANTI_ALIAS_FLAG), Paint(Paint.ANTI_ALIAS_FLAG)
    )
    private var bgW = 0f
    private var bgH = 0f
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
                update(dt)
                draw()
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
        initStars()
        initBackgrounds()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    // ---------- Setup ----------

    private fun initBackgrounds() {
        if (bgW == w && bgH == h) return
        bgW = w
        bgH = h
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.rgb(6, 4, 24),
            Color.rgb(28, 8, 48),
            Shader.TileMode.CLAMP
        )
        val defs = arrayOf(
            Triple(Color.argb(70, 110, 40, 190), 0.22f, 0.30f),
            Triple(Color.argb(60, 20, 160, 170), 0.75f, 0.22f),
            Triple(Color.argb(55, 200, 40, 120), 0.50f, 0.85f)
        )
        for (i in nebulae.indices) {
            val (col, fx, fy) = defs[i]
            val r = min(w, h) * 0.55f
            nebulae[i].shader = RadialGradient(
                w * fx, h * fy, r,
                col, Color.TRANSPARENT,
                Shader.TileMode.CLAMP
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
                    Color.argb((Random.nextFloat() * 130 + 80).toInt(), 255, 255, 255)
                )
            )
        }
    }

    private fun invaderColor(variant: Int): Int = when (variant) {
        0 -> Color.rgb(235, 70, 160)   // crab - magenta
        1 -> Color.rgb(70, 215, 250)   // squid - cyan
        else -> Color.rgb(120, 230, 95) // armored - green
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

        val cols = min(5 + wave / 2, 9)
        val rows = min(3 + (wave - 1) / 2, 5)
        val marginX = w * 0.13f
        val spacingX = if (cols > 1) (w - marginX * 2) / (cols - 1) else 0f

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val variant = r % 3
                val size = (when (variant) {
                    0 -> 40f
                    1 -> 36f
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
                        hp = if (variant == 2 || (r == 0 && wave >= 4)) 2 else 1
                    )
                )
            }
        }
        waveBannerTimer = 1.6f
    }

    private fun resetGame() {
        score = 0
        lives = 3
        wave = 1
        bullets.clear()
        enemyBullets.clear()
        particles.clear()
        ufo = null
        ufoTimer = 9f
        state = State.PLAYING
        invincible = 0f
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
                    targetX += (event.x - lastTouchX) * 1.8f
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

        playerX += (targetX - playerX) * min(16f * dt, 1f)

        fireCooldown -= dt
        invincible -= dt
        shake *= 0.88f
        flashAlpha *= 0.9f
        waveBannerTimer -= dt

        if (dragging && fireCooldown <= 0f) shoot()

        updateBullets(dt)
        updateInvaders(dt)
        updateUfo(dt)
        updateParticles(dt)
        checkCollisions()

        // ---- Enemy fire (harder AI) ----
        invaderFireTimer -= dt
        if (invaderFireTimer <= 0f && invaders.isNotEmpty()) {
            val alive = invaders.filter { it.alive && !it.diving && it.y > 0f }
            if (alive.isNotEmpty()) {
                val shooter = alive.random()
                fireAimed(shooter)
                var interval = (Random.nextFloat() * 0.7f + 1.5f / wave).coerceAtLeast(0.22f)
                val aliveCount = invaders.count { it.alive }
                if (aliveCount <= 3) interval *= 0.55f // desperate survivors shoot much faster
                invaderFireTimer = interval
            } else {
                invaderFireTimer = 0.4f
            }
        }

        // ---- Divers ----
        if (!entering) {
            diveTimer -= dt
            if (diveTimer <= 0f) {
                val candidates = invaders.filter { it.alive && !it.diving }
                if (candidates.size > 2) {
                    candidates.random().diving = true
                }
                diveTimer = (7f - wave * 0.5f).coerceAtLeast(2.2f) + Random.nextFloat() * 2.5f
            }
        }

        // Wave cleared
        if (!entering && invaders.none { it.alive }) {
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
            s.y += s.speed * scale * dt * (if (state == State.GAME_OVER) 0.2f else 1f)
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

    private fun updateUfo(dt: Float) {
        val current = ufo
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

    private fun updateBullets(dt: Float) {
        bullets.removeAll { b ->
            b.y -= b.speed * dt
            b.trail.add(0, b.y)
            if (b.trail.size > 6) b.trail.removeAt(b.trail.size - 1)
            b.y < -50f
        }
        enemyBullets.removeAll { b ->
            b.x += b.vx * dt
            b.y += b.speed * dt
            b.y > h + 50f || b.x < -50f || b.x > w + 50f
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

    private fun checkCollisions() {
        // Player bullets vs invaders / UFO
        bullets.removeAll { b ->
            var consumed = false
            for (inv in invaders) {
                if (!inv.alive) continue
                if (hypot(inv.x - b.x, inv.y - b.y) < inv.size * 1.1f) {
                    inv.hp--
                    consumed = true
                    spawnSparks(b.x, b.y, inv.color, count = 8, small = true)
                    if (inv.hp <= 0) {
                        inv.alive = false
                        explode(inv.x, inv.y, inv.color, big = true)
                        addScore(if (inv.variant == 2) 40 else if (inv.variant == 0) 30 else 20)
                        shake = maxOf(shake, 6f)
                    } else {
                        addScore(5)
                    }
                    break
                }
            }
            if (!consumed) {
                val saucer = ufo
                if (saucer != null && hypot(saucer.x - b.x, saucer.y - b.y) < 70 * scale) {
                    ufo = null
                    consumed = true
                    explode(saucer.x, saucer.y, Color.rgb(255, 220, 90), huge = true)
                    addScore(150)
                    shake = maxOf(shake, 12f)
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
        explode(playerX, playerY, Color.rgb(0, 255, 180), big = true)
        shake = 18f
        flashAlpha = 0.55f
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
        fireCooldown = 0.18f
        bullets.add(Bullet(playerX, playerY - playerW, 1150f * scale, Color.rgb(120, 255, 200)))
        spawnSparks(playerX, playerY - playerW, Color.rgb(120, 255, 200), count = 4, small = true, spreadUp = true)
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
            canvas.save()
            if (shake > 0.5f) {
                canvas.translate(
                    (Random.nextFloat() - 0.5f) * shake,
                    (Random.nextFloat() - 0.5f) * shake
                )
            }

            drawBackground(canvas)
            drawStars(canvas)
            drawParticlesBelow(canvas)

            when (state) {
                State.PLAYING -> {
                    drawUfo(canvas)
                    drawInvaders(canvas)
                    drawPlayer(canvas)
                    drawBullets(canvas)
                }
                State.GAME_OVER -> drawGameOver(canvas)
            }

            drawParticlesAbove(canvas)
            drawHud(canvas)

            canvas.restore()

            if (flashAlpha > 0.01f) {
                fillPaint.style = Paint.Style.FILL
                fillPaint.shader = null
                fillPaint.color = Color.argb((flashAlpha * 255).toInt(), 255, 255, 255)
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
            val ox = sin(bgTime * 0.11f + i * 2.1f) * drift
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
            fillPaint.color = s.color
            canvas.drawCircle(s.x, s.y, s.radius, fillPaint)
        }
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

        // Engine flames (behind hull, unrotated)
        setShadow(Color.rgb(255, 170, 40))
        fillPaint.shader = null
        for (side in floatArrayOf(-0.34f, 0.34f)) {
            fillPaint.color = Color.rgb(255, 190, 60)
            val fl = half * (0.4f + Random.nextFloat() * 0.3f)
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

        // Banking tilt for a pseudo-3D feel
        val bank = ((targetX - playerX) / playerW).coerceIn(-1f, 1f) * 16f
        canvas.save()
        canvas.rotate(bank, x, y)

        // Wings with metallic gradient
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = LinearGradient(
            x - half, y - half, x + half, y + half,
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
            lineTo(x - half * 1.08f, y + half * 0.62f)
            lineTo(x - half * 0.42f, y + half * 0.52f)
            lineTo(x, y + half * 0.18f)
            lineTo(x + half * 0.42f, y + half * 0.52f)
            lineTo(x + half * 1.08f, y + half * 0.62f)
            close()
        }
        setShadow(Color.rgb(0, 220, 180))
        canvas.drawPath(wings, fillPaint)

        // Wingtip navigation lights (red left, green right)
        fillPaint.shader = null
        setShadow(null)
        fillPaint.color = Color.rgb(255, 60, 60)
        canvas.drawCircle(x - half * 1.02f, y + half * 0.58f, half * 0.09f, fillPaint)
        fillPaint.color = Color.rgb(70, 255, 110)
        canvas.drawCircle(x + half * 1.02f, y + half * 0.58f, half * 0.09f, fillPaint)

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
                else -> drawArmored(canvas, inv)
        }
        }
        setShadow(null)
        fillPaint.shader = null
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
            inv.x - s * 0.25f, inv.y - s * 0.35f, s * 0.05f,
            inv.x, inv.y - s * 0.05f, s * 0.75f,
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
            canvas.drawRoundRect(
                b.x - 4f * scale, b.y - 16f * scale,
                b.x + 4f * scale, b.y + 10f * scale,
                4f * scale, 4f * scale, fillPaint
            )
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

        if (waveBannerTimer > 0f) {
            val a = (waveBannerTimer / 1.6f).coerceIn(0f, 1f)
            bigTextPaint.alpha = (a * 255).toInt()
            bigTextPaint.textSize = 72f * scale
            bigTextPaint.setShadowLayer(22f, 0f, 0f, Color.CYAN)
            bigTextPaint.color = Color.rgb(140, 240, 255)
            canvas.drawText("WAVE $wave", w / 2f, h * 0.42f, bigTextPaint)
            bigTextPaint.alpha = 255
        }
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
        var vx: Float = 0f
    ) {
        val trail = mutableListOf<Float>()
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
        var divePhase: Float = 0f
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

    private class Star(var x: Float, var y: Float, val radius: Float, val speed: Float, val color: Int)
}

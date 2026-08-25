package com.example.spaceinvaders

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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

    // Player
    private var playerX = 0f
    private val playerY get() = h - 120 * scale
    private var playerW = 90f
    private var targetX = 0f
    private var fireCooldown = 0f
    private var invincible = 0f

    // Entities
    private val bullets = mutableListOf<Bullet>()
    private val enemyBullets = mutableListOf<Bullet>()
    private val invaders = mutableListOf<Invader>()
    private val particles = mutableListOf<Particle>()
    private val stars = mutableListOf<Star>()

    private var invaderDirX = 1f
    private var invaderFireTimer = 0f

    // Paints (reused to avoid GC churn)
    private val bgPaint = Paint().apply { shader = null; color = Color.BLACK }
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
        scale = min(w / 1280f, h / 720f).coerceAtLeast(0.5f)
        playerW = 90f * scale
        if (playerX == 0f) playerX = w / 2f
        targetX = playerX
        initStars()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    // ---------- Setup ----------

    private fun initStars() {
        stars.clear()
        repeat(120) {
            stars.add(
                Star(
                    Random.nextFloat() * w,
                    Random.nextFloat() * h,
                    Random.nextFloat() * 2.2f + 0.6f,
                    Random.nextFloat() * 40f + 15f,
                    Color.argb((Random.nextFloat() * 130 + 80).toInt(), 255, 255, 255)
                )
            )
        }
    }

    private fun spawnWave() {
        invaders.clear()
        invaderDirX = 1f
        val cols = min(4 + wave, 10)
        val rows = min(3 + wave / 2, 5)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                invaders.add(
                    Invader(
                        x = (c + 1) * (w / (cols + 1f)),
                        y = 140 * scale + r * 110 * scale,
                        size = (if (r == 0) 46f else 38f) * scale,
                        color = if (r == 0) Color.rgb(255, 70, 160) else Color.rgb(80, 220, 255),
                        alive = true
                    )
                )
            }
        }
    }

    private fun resetGame() {
        score = 0
        lives = 3
        wave = 1
        bullets.clear()
        enemyBullets.clear()
        particles.clear()
        state = State.PLAYING
        invincible = 0f
        spawnWave()
    }

    // ---------- Input ----------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                targetX = event.x
                if (state == State.GAME_OVER && gameOverTimer > 1.2f) {
                    resetGame()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (state == State.PLAYING) shoot()
            }
        }
        return true
    }

    // ---------- Update ----------

    private fun update(dt: Float) {
        updateStars(dt)

        if (state == State.GAME_OVER) {
            gameOverTimer += dt
            updateParticles(dt)
            shake *= 0.9f
            flashAlpha *= 0.92f
            return
        }

        // Player movement with smoothing
        playerX += (targetX - playerX) * min(12f * dt, 1f)
        playerX = playerX.coerceIn(playerW, w - playerW)

        fireCooldown -= dt
        invincible -= dt
        shake *= 0.88f
        flashAlpha *= 0.9f

        // Auto-fire assist when holding
        if (fireCooldown <= 0f && abs(targetX - playerX) < 2f && invaders.isNotEmpty()) {
            // tap fires instead; keep cooldown only via taps
        }

        updateBullets(dt)
        updateInvaders(dt)
        updateParticles(dt)
        checkCollisions()

        // Enemy fire
        invaderFireTimer -= dt
        if (invaderFireTimer <= 0f && invaders.isNotEmpty()) {
            val shooter = invaders.filter { it.alive }.random()
            enemyBullets.add(Bullet(shooter.x, shooter.y + shooter.size, 420f * scale, Color.rgb(255, 90, 60)))
            invaderFireTimer = (Random.nextFloat() * 0.8f + 1.6f / wave).coerceAtLeast(0.25f)
        }

        // Wave cleared
        if (invaders.none { it.alive }) {
            wave++
            addScore(100)
            spawnWave()
        }
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
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = 0f
        for (inv in invaders) {
            if (!inv.alive) continue
            inv.pulse += dt * 6f
            minX = min(minX, inv.x)
            maxX = maxOf(maxX, inv.x)
            maxY = maxOf(maxY, inv.y)
        }
        if (minX > maxX) return

        val speed = (60f + wave * 22f) * scale
        invaderDirX = when {
            invaderDirX > 0 && maxX + speed * dt > w - 60 * scale -> -1f
            invaderDirX < 0 && minX - speed * dt < 60 * scale -> 1f
            else -> invaderDirX
        }

        for (inv in invaders) {
            if (!inv.alive) continue
            inv.x += invaderDirX * speed * dt
            inv.y += 12f * scale * dt
        }

        // Invaders reached the bottom?
        if (maxY > playerY - 60 * scale) {
            hitPlayer(instantDeath = true)
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
            b.y += b.speed * dt
            b.trail.clear()
            b.y > h + 50f
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
        // Player bullets vs invaders
        bullets.removeAll { b ->
            var hit = false
            for (inv in invaders) {
                if (!inv.alive) continue
                if (hypot(inv.x - b.x, inv.y - b.y) < inv.size * 1.1f) {
                    inv.hp--
                    hit = true
                    spawnSparks(b.x, b.y, inv.color, count = 8, small = true)
                    if (inv.hp <= 0) {
                        inv.alive = false
                        explode(inv.x, inv.y, inv.color, big = true)
                        addScore(if (inv.size > 42 * scale) 30 else 20)
                        shake = maxShake(shake, 6f)
                    } else {
                        addScore(5)
                    }
                    break
                }
            }
            hit
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

    private fun maxShake(a: Float, b: Float) = if (a > b) a else b

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
        if (fireCooldown > 0f || state != State.PLAYING) return
        bullets.add(Bullet(playerX, playerY - playerW, 1100f * scale, Color.rgb(120, 255, 200)))
        fireCooldown = 0.22f
        // Muzzle flash particles
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
                Particle(
                    x = x,
                    y = y,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    radius = (Random.nextFloat() * 5f + 2.5f) * scale * (if (big) 1.6f else 1f),
                    life = Random.nextFloat() * 0.5f + 0.45f,
                    maxLife = 0f,
                    color = color,
                    gravity = 260f * scale
                ).also { it.maxLife = it.life }
            )
        }
        // Ring shockwave
        particles.add(Particle(x, y, 0f, 0f, 8f * scale, 0.35f, 0.35f, color, 0f).apply { isRing = true })
    }

    private fun spawnSparks(x: Float, y: Float, color: Int, count: Int, small: Boolean, spreadUp: Boolean = false) {
        repeat(count) {
            val angle = if (spreadUp) (-Math.PI.toFloat()) + (Random.nextFloat() - 0.5f) * 1.2f
            else Random.nextFloat() * 2f * Math.PI.toFloat()
            val speed = (Random.nextFloat() + 0.2f) * 260f * scale
            particles.add(
                Particle(
                    x, y,
                    cos(angle) * speed, sin(angle) * speed,
                    (Random.nextFloat() * 3f + 1.5f) * scale * (if (small) 0.7f else 1f),
                    Random.nextFloat() * 0.25f + 0.2f, 0.45f,
                    color, 150f * scale
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
        if (bgPaint.shader == null) {
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                Color.rgb(8, 6, 30),
                Color.rgb(30, 8, 52),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)
    }

    private fun drawStars(canvas: Canvas) {
        fillPaint.style = Paint.Style.FILL
        setShadow(null)
        for (s in stars) {
            fillPaint.color = s.color
            canvas.drawCircle(s.x, s.y, s.radius, fillPaint)
        }
    }

    private fun drawPlayer(canvas: Canvas) {
        val blink = invincible > 0f && ((invincible * 10f).toInt() % 2 == 0)
        if (blink) return

        val y = playerY
        val half = playerW

        // Glow aura under ship
        glowPaint.shader = RadialGradient(
            playerX, y, half * 2.2f,
            Color.argb(90, 0, 255, 190), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        fillPaint.style = Paint.Style.FILL
        canvas.drawCircle(playerX, y, half * 2.2f, glowPaint)

        // Ship body (neon triangle)
        setShadow(Color.rgb(0, 255, 190))
        fillPaint.color = Color.rgb(0, 255, 190)
        val path = android.graphics.Path().apply {
            moveTo(playerX, y - half * 0.9f)
            lineTo(playerX - half * 0.7f, y + half * 0.6f)
            lineTo(playerX, y + half * 0.25f)
            lineTo(playerX + half * 0.7f, y + half * 0.6f)
            close()
        }
        canvas.drawPath(path, fillPaint)

        // Cockpit
        setShadow(null)
        fillPaint.color = Color.WHITE
        canvas.drawCircle(playerX, y - half * 0.15f, half * 0.16f, fillPaint)

        // Engine flame flicker
        setShadow(Color.rgb(255, 170, 40))
        fillPaint.color = Color.rgb(255, 190, 60)
        val flameLen = half * (0.35f + Random.nextFloat() * 0.25f)
        val flamePath = android.graphics.Path().apply {
            moveTo(playerX - half * 0.18f, y + half * 0.35f)
            lineTo(playerX, y + half * 0.35f + flameLen)
            lineTo(playerX + half * 0.18f, y + half * 0.35f)
            close()
        }
        canvas.drawPath(flamePath, fillPaint)
        setShadow(null)
    }

    private fun drawInvaders(canvas: Canvas) {
        for (inv in invaders) {
            if (!inv.alive) continue
            val s = inv.size
            val pulse = 1f + sin(inv.pulse) * 0.06f
            setShadow(inv.color)
            fillPaint.color = inv.color

            // Alien body: rounded block with eyes and legs
            canvas.drawRoundRect(
                inv.x - s * pulse, inv.y - s * 0.62f * pulse,
                inv.x + s * pulse, inv.y + s * 0.32f * pulse,
                s * 0.3f, s * 0.3f, fillPaint
            )

            // Legs
            canvas.drawRect(inv.x - s * 0.65f, inv.y + s * 0.2f, inv.x - s * 0.4f, inv.y + s * 0.75f, fillPaint)
            canvas.drawRect(inv.x + s * 0.4f, inv.y + s * 0.2f, inv.x + s * 0.65f, inv.y + s * 0.75f, fillPaint)

            // Eyes
            setShadow(null)
            fillPaint.color = Color.BLACK
            val blinkEyes = sin(inv.pulse * 0.7f) > 0.95f
            if (!blinkEyes) {
                canvas.drawCircle(inv.x - s * 0.32f, inv.y - s * 0.12f, s * 0.14f, fillPaint)
                canvas.drawCircle(inv.x + s * 0.32f, inv.y - s * 0.12f, s * 0.14f, fillPaint)
            }

            // HP indicator for tougher ones
            if (inv.hp > 1) {
                fillPaint.color = Color.WHITE
                fillPaint.alpha = 200
                canvas.drawCircle(inv.x, inv.y + s * 0.05f, s * 0.08f * inv.hp, fillPaint)
                fillPaint.alpha = 255
            }
        }
        setShadow(null)
    }

    private fun drawBullets(canvas: Canvas) {
        setShadow(null)
        // Player bullets: glowing capsules with trail
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = 3f * scale
        for (b in bullets) {
            fillPaint.color = Color.argb(70, b.color shr 16 and 0xFF, b.color shr 8 and 0xFF, b.color and 0xFF)
            for ((i, ty) in b.trail.withIndex()) {
                fillPaint.alpha = (70 * (1f - i.toFloat() / b.trail.size)).toInt()
                canvas.drawLine(b.x, ty, b.x, ty + 8f * scale, fillPaint)
            }
            setShadow(b.color)
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = b.color
            canvas.drawRoundRect(
                b.x - 4f * scale, b.y - 16f * scale,
                b.x + 4f * scale, b.y + 10f * scale,
                4f * scale, 4f * scale, fillPaint
            )
            fillPaint.style = Paint.Style.STROKE
            setShadow(null)
        }

        // Enemy bullets
        setShadow(Color.rgb(255, 90, 60))
        fillPaint.style = Paint.Style.FILL
        for (b in enemyBullets) {
            fillPaint.color = b.color
            canvas.drawCircle(b.x, b.y, 8f * scale, fillPaint)
        }
        fillPaint.style = Paint.Style.FILL
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
        textPaint.textSize = 40f * scale
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
        val hearts = "♥".repeat(lives.coerceAtLeast(0))
        canvas.drawText(hearts, w - 30f, 56f * scale, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
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

    // ---------- Data classes ----------

    private data class Bullet(var x: Float, var y: Float, val speed: Float, val color: Int) {
        val trail = mutableListOf<Float>()
    }

    private data class Invader(
        var x: Float,
        var y: Float,
        val size: Float,
        val color: Int,
        var alive: Boolean,
        var hp: Int = if (size > 42f) 2 else 1,
        var pulse: Float = Random.nextFloat() * 6f
    )

    private data class Particle(
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

    private data class Star(var x: Float, var y: Float, val radius: Float, val speed: Float, val color: Int)
}

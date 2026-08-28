package com.example.spaceinvaders

import kotlin.random.Random

internal enum class GameState { MENU, PLAYING, GAME_OVER, SHOP }

internal enum class Weapon { PLASMA, SPREAD, LASER, MISSILE }

internal class SectorDef(
    val name: String,
    val top: Int,
    val mid: Int,
    val bot: Int,
    val neb: IntArray,
    val accent: Int
)

internal class Boss(
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

internal class Mission(val text: String, val target: Int, val kind: Int, var progress: Int = 0)

internal class Mine(var x: Float, var y: Float, var timer: Float = 1.8f, var pulse: Float = 0f)

internal class Bullet(
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

internal class Invader(
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

internal class Ufo(var x: Float, val y: Float, val vx: Float) {
    var blink = 0f
}

internal class Particle(
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var radius: Float = 0f,
    var life: Float = 0f,
    var maxLife: Float = 1f,
    var color: Int = 0,
    var gravity: Float = 0f,
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

    fun reset(
        nx: Float, ny: Float, nvx: Float, nvy: Float,
        nradius: Float, nlife: Float, nmaxLife: Float,
        ncolor: Int, ngravity: Float, nisRing: Boolean = false,
        nkind: Int = if (nisRing) KIND_RING else KIND_SPARK
    ) {
        x = nx; y = ny; vx = nvx; vy = nvy
        radius = nradius; life = nlife; maxLife = nmaxLife
        color = ncolor; gravity = ngravity; isRing = nisRing
        kind = nkind
        rot = Random.nextFloat() * 360f
        rotSpeed = 0f; drag = 0.98f; grow = 0f
    }
}

internal class ParticlePool(private val capacity: Int = 600) {
    private val pool = ArrayDeque<Particle>(capacity)

    fun obtain(
        x: Float, y: Float, vx: Float, vy: Float,
        radius: Float, life: Float, maxLife: Float,
        color: Int, gravity: Float, isRing: Boolean = false,
        kind: Int = if (isRing) Particle.KIND_RING else Particle.KIND_SPARK
    ): Particle {
        val p = pool.removeLastOrNull() ?: Particle()
        p.reset(x, y, vx, vy, radius, life, maxLife, color, gravity, isRing, kind)
        return p
    }

    fun recycle(p: Particle) {
        if (pool.size < capacity) {
            pool.addLast(p)
        }
    }
}

internal class Star(
    var x: Float,
    var y: Float,
    val radius: Float,
    val speed: Float,
    val alpha: Int,
    val seed: Float,
    val z: Float
)

internal class Debris(
    var x: Float,
    var y: Float,
    val size: Float,
    val speed: Float,
    var rot: Float,
    val rotSpeed: Float,
    val drift: Float,
    val alpha: Int
)

internal enum class PowerType { RAPID, TRIPLE, SHIELD, LIFE, NOVA, PART, WEAPON, ARMOR, SLOW, MAGNET, CLONE }

internal class PowerUp(var x: Float, var y: Float, val type: PowerType, var phase: Float = Random.nextFloat() * 6f)

internal class Meteor(var x: Float, var y: Float, val vx: Float, val vy: Float, val len: Float)

internal class Dust(var x: Float, var y: Float, val radius: Float, val drift: Float, val speed: Float)

internal class FloatText(val text: String, var x: Float, var y: Float, val color: Int, var life: Float = 1.1f)

internal class Beam(val x: Float, val y: Float, var life: Float = 0.5f)

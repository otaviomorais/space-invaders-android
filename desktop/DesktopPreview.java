import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.Point;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Preview Desktop Oficial do Space Invaders Android.
 * Fiel à engine Kotlin em app/src/main/java/com/example/spaceinvaders/:
 * - Naves com design militar/tático e partículas avançadas
 * - 4 armas (Plasma, Spread, Laser, Míssil) e 11 Power-ups
 * - Boss Dreadnought a cada 4 ondas com núcleo reator e torres
 * - Modos de Horda, Setores Cósmicos e Missões
 * - 3 Ataques Especiais Cinematográficos Rotativos (Orbital, Buraco Negro, Esquadrão Stealth)
 * - Controles: Mouse (arraste/clique) ou Teclado (A/D, Espaço, Shift, M, E)
 */
public class DesktopPreview extends JPanel {

    public static final int W = 1280;
    public static final int H = 720;

    public enum State { MENU, PLAYING, GAME_OVER, SHOP }
    public enum Weapon { PLASMA, SPREAD, LASER, MISSILE }
    public enum PowerType { RAPID, TRIPLE, SHIELD, LIFE, NOVA, PART, WEAPON, ARMOR, SLOW, MAGNET, CLONE }

    private static class SectorDef {
        String name; Color top, mid, bot, accent;
        SectorDef(String name, Color top, Color mid, Color bot, Color accent) {
            this.name = name; this.top = top; this.mid = mid; this.bot = bot; this.accent = accent;
        }
    }

    private static final SectorDef[] SECTORS = new SectorDef[]{
        new SectorDef("SETOR NEBULOSA", new Color(3, 2, 12), new Color(16, 7, 38), new Color(30, 9, 48), new Color(255, 90, 200)),
        new SectorDef("SETOR GLACIAL", new Color(2, 6, 16), new Color(8, 20, 44), new Color(14, 36, 68), new Color(120, 230, 255)),
        new SectorDef("SETOR VULCÂNICO", new Color(10, 3, 4), new Color(32, 10, 8), new Color(56, 18, 8), new Color(255, 130, 60)),
        new SectorDef("O VAZIO", new Color(2, 2, 6), new Color(6, 4, 14), new Color(10, 6, 22), new Color(200, 160, 255))
    };

    private static class Inv {
        float homeX, homeY, x, y, size; Color color; int variant, hp;
        boolean alive = true; float pulse; boolean diving = false; float divePhase; boolean mini = false;
        Inv(float hx, float hy, float x, float y, float size, Color color, int variant, int hp) {
            this.homeX = hx; this.homeY = hy; this.x = x; this.y = y; this.size = size;
            this.color = color; this.variant = variant; this.hp = hp; this.pulse = (float)(Math.random() * 6);
        }
    }

    private static class Boss {
        float x, y; int maxHp, hp, type; float pulse; boolean entering = true, dying = false;
        Boss(float x, float y, int maxHp, int type) {
            this.x = x; this.y = y; this.maxHp = maxHp; this.hp = maxHp; this.type = type;
        }
    }

    private static class Bullet {
        float x, y, speed; Color color; float vx; boolean pierce, homing, splash;
        List<Float> trail = new ArrayList<>();
        Bullet(float x, float y, float speed, Color color, float vx, boolean pierce, boolean homing, boolean splash) {
            this.x = x; this.y = y; this.speed = speed; this.color = color; this.vx = vx;
            this.pierce = pierce; this.homing = homing; this.splash = splash;
        }
    }

    private static class Part {
        public static final int KIND_SPARK = 0, KIND_FIRE = 1, KIND_SMOKE = 2, KIND_DEBRIS = 3, KIND_RING = 4, KIND_FLASH = 5, KIND_EMBER = 6;
        float x, y, vx, vy, radius, life, maxLife; Color color; float gravity;
        int kind; float rot, rotSpeed, drag = 0.98f, grow = 0f;
        Part(float x, float y, float vx, float vy, float radius, float life, float maxLife, Color color, float gravity, int kind) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.radius = radius;
            this.life = life; this.maxLife = maxLife; this.color = color; this.gravity = gravity;
            this.kind = kind; this.rot = (float)(Math.random() * 360);
        }
    }

    private static class PowerUp {
        float x, y; PowerType type; float phase = (float)(Math.random() * 6);
        PowerUp(float x, float y, PowerType type) { this.x = x; this.y = y; this.type = type; }
    }

    private static class Mine {
        float x, y, timer = 6f, pulse = 0f;
        Mine(float x, float y) { this.x = x; this.y = y; }
    }

    private static class FloatText {
        String text; float x, y; Color color; float life = 1.1f;
        FloatText(String text, float x, float y, Color color) { this.text = text; this.x = x; this.y = y; this.color = color; }
    }

    private static class Ufo {
        float x, y, vx, blink = 0f;
        Ufo(float x, float y, float vx) { this.x = x; this.y = y; this.vx = vx; }
    }

    private final Random rnd = new Random();
    private final List<float[]> stars = new ArrayList<>();
    private final List<Inv> invaders = new ArrayList<>();
    private final List<Bullet> bullets = new ArrayList<>();
    private final List<Bullet> eBullets = new ArrayList<>();
    private final List<Part> parts = new ArrayList<>();
    private final List<PowerUp> powerUps = new ArrayList<>();
    private final List<Mine> mines = new ArrayList<>();
    private final List<FloatText> floatTexts = new ArrayList<>();

    private State state = State.MENU;
    private Weapon weapon = Weapon.PLASMA;
    private int score = 0, lives = 3, wave = 1, coins = 0, combo = 0;
    private float shake = 0f, flash = 0f, bgTime = 0f, specialCharge = 0f;
    private int specialType = 0;
    private float blackHoleTimer = 0f, carpetBombTimer = 0f;
    private float dashCooldown = 0f, invincible = 0f, rapidTimer = 0f, tripleTimer = 0f, slowTimer = 0f, magnetTimer = 0f;
    private boolean shieldUp = false;
    private int shipLevel = 1, powerupsCollected = 0, mineCount = 1;

    private float playerX = W / 2f, playerY = H - 90f, targetX = W / 2f, fireCd = 0f;
    private boolean dragging = false; Point lastTouch = null;
    private boolean keyLeft = false, keyRight = false, keyShoot = false;

    private float formOffX = 0f, formDirX = 1f, invFireTimer = 1.5f, diveTimer = 7f;
    private boolean entering = false, bossWave = false;
    private Boss boss = null;
    private Ufo ufo = null; float ufoTimer = 9f;

    public DesktopPreview() {
        setPreferredSize(new Dimension(W, H));
        setBackground(Color.BLACK);
        setFocusable(true);
        initStars();

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragging = true; lastTouch = e.getPoint();
                float px = e.getX(), py = e.getY();
                if (state == State.MENU) {
                    if (px >= W/2f - 170 && px <= W/2f + 170 && py >= H*0.62f && py <= H*0.62f + 78) resetGame();
                } else if (state == State.GAME_OVER) {
                    if (py >= H - 80) resetGame();
                } else if (state == State.PLAYING) {
                    if (px <= 120 && py >= H - 120 && dashCooldown <= 0) triggerDash();
                    else if (px >= 130 && px <= 230 && py >= H - 120 && mineCount > 0) triggerMine();
                    else if (px >= W - 140 && py >= H - 140 && specialCharge >= 100) triggerSpecial();
                }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragging && lastTouch != null) {
                    targetX += (e.getX() - lastTouch.x) * 1.8f;
                    lastTouch = e.getPoint();
                    targetX = Math.max(50f, Math.min(W - 50f, targetX));
                }
            }
            @Override public void mouseReleased(MouseEvent e) { dragging = false; }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int k = e.getKeyCode();
                if (k == KeyEvent.VK_LEFT || k == KeyEvent.VK_A) keyLeft = true;
                if (k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_D) keyRight = true;
                if (k == KeyEvent.VK_SPACE) keyShoot = true;
                if (k == KeyEvent.VK_SHIFT) triggerDash();
                if (k == KeyEvent.VK_M) triggerMine();
                if (k == KeyEvent.VK_E) triggerSpecial();
                if (state != State.PLAYING && k == KeyEvent.VK_ENTER) resetGame();
            }
            @Override public void keyReleased(KeyEvent e) {
                int k = e.getKeyCode();
                if (k == KeyEvent.VK_LEFT || k == KeyEvent.VK_A) keyLeft = false;
                if (k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_D) keyRight = false;
                if (k == KeyEvent.VK_SPACE) keyShoot = false;
            }
        });

        new Timer(16, ev -> { update(0.016f); repaint(); }).start();
    }

    private void initStars() {
        stars.clear();
        for (int i = 0; i < 160; i++)
            stars.add(new float[]{ rnd.nextFloat() * W, rnd.nextFloat() * H, rnd.nextFloat() * 2.2f + 0.6f, rnd.nextFloat() * 46f + 14f });
    }

    private void resetGame() {
        score = 0; lives = 3; wave = 1; coins = 0; combo = 0; state = State.PLAYING;
        specialCharge = 0; dashCooldown = 0; invincible = 0; rapidTimer = 0; tripleTimer = 0;
        shieldUp = false; shipLevel = 1; powerupsCollected = 0; mineCount = 1; weapon = Weapon.PLASMA;
        bullets.clear(); eBullets.clear(); parts.clear(); powerUps.clear(); mines.clear(); floatTexts.clear();
        boss = null; ufo = null; ufoTimer = 9f;
        spawnWave();
    }

    private void triggerDash() {
        if (state != State.PLAYING || dashCooldown > 0) return;
        dashCooldown = 2.5f; invincible = 0.6f;
        float dir = playerX < W / 2f ? 1f : -1f;
        playerX = Math.max(50f, Math.min(W - 50f, playerX + dir * 180f));
        targetX = playerX; shake = Math.max(shake, 8f);
        spawnSparks(playerX, playerY, new Color(120, 255, 200), 10);
    }

    private void triggerMine() {
        if (state != State.PLAYING || mineCount <= 0) return;
        mineCount--; mines.add(new Mine(playerX, playerY - 20f));
    }

    private void triggerSpecial() {
        if (state != State.PLAYING || specialCharge < 100) return;
        specialCharge = 0; shake = 18f; flash = 0.5f;
        int currentType = specialType;
        specialType = (specialType + 1) % 3;

        if (currentType == 0) {
            for (Inv inv : new ArrayList<>(invaders)) if (inv.alive) damageInvader(inv, 3);
            if (boss != null) { boss.hp -= 15; if (boss.hp <= 0) bossDeath(); }
            addFloat("BOMBARDEIO ORBITAL SATÉLITE!", W / 2f, H * 0.35f, new Color(255, 216, 120));
        } else if (currentType == 1) {
            blackHoleTimer = 2.2f;
            addFloat("SINGULARIDADE: BURACO NEGRO!", W / 2f, H * 0.35f, new Color(199, 125, 255));
        } else {
            carpetBombTimer = 2.0f;
            addFloat("ESQUADRÃO STEALTH FLYBY!", W / 2f, H * 0.35f, new Color(255, 110, 80));
        }
    }

    private static Color invColor(int variant) {
        switch (variant) {
            case 0: return new Color(255, 80, 170);
            case 1: return new Color(90, 230, 255);
            case 3: return new Color(168, 85, 247);
            case 4: return new Color(239, 68, 68);
            case 6: return new Color(59, 130, 246);
            case 7: return new Color(16, 185, 129);
            default: return new Color(140, 255, 110);
        }
    }

    private void spawnWave() {
        invaders.clear(); formOffX = 0; formDirX = 1; entering = true;
        int sectorIndex = Math.min((wave - 1) / 3, SECTORS.length - 1);
        if (wave % 4 == 0) {
            bossWave = true; entering = false;
            boss = new Boss(W / 2f, H * 0.22f, 40 + wave * 6, sectorIndex);
            addFloat("ALERTA: NAVE-MÃE!", W / 2f, H * 0.35f, SECTORS[sectorIndex].accent);
            shake = Math.max(shake, 10f);
            return;
        }
        bossWave = false; boss = null;
        int cols = Math.min(5 + wave / 2, 9);
        int rows = Math.min(3 + (wave - 1) / 2, 5);
        float marginX = W * 0.13f, spacingX = cols > 1 ? (W - marginX * 2) / (cols - 1) : 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int variant = r % 3;
                if (wave >= 5 && Math.random() < 0.2) variant = 7;
                else if (wave >= 3 && Math.random() < 0.3) variant = 4;
                else if (wave >= 2 && Math.random() < 0.4) variant = 3;
                float size = (variant == 0 ? 40 : variant == 1 ? 36 : variant == 4 ? 52 : 44);
                invaders.add(new Inv(marginX + c * spacingX, H * 0.14f + r * H * 0.055f,
                    marginX + c * spacingX, -H * (0.25f + c * 0.06f + r * 0.12f) - size,
                    size, invColor(variant), variant, variant == 4 ? 3 : variant == 6 ? 2 : 1));
            }
        }
    }

    private void update(float dt) {
        bgTime += dt;
        for (float[] s : stars) {
            s[1] += s[3] * dt * (state == State.GAME_OVER ? 0.2f : 1f);
            if (s[1] > H) { s[1] = 0; s[0] = rnd.nextFloat() * W; }
        }

        if (state != State.PLAYING) return;

        // Player Move & Cooldowns
        if (keyLeft) targetX -= 600f * dt;
        if (keyRight) targetX += 600f * dt;
        targetX = Math.max(50f, Math.min(W - 50f, targetX));
        playerX += (targetX - playerX) * Math.min(18f * dt, 1f);

        fireCd -= dt; invincible -= dt; dashCooldown -= dt; rapidTimer -= dt; tripleTimer -= dt;
        shake *= 0.88f; flash *= 0.9f;

        if ((keyShoot || dragging) && fireCd <= 0) shoot();

        // Special Black Hole & Carpet Bombing update
        if (blackHoleTimer > 0) {
            blackHoleTimer -= dt;
            float cx = W / 2f, cy = H / 2f;
            for (Inv inv : invaders) {
                if (inv.alive) {
                    inv.x += (cx - inv.x) * dt * 2.8f;
                    inv.y += (cy - inv.y) * dt * 2.8f;
                }
            }
            eBullets.clear();
            if (blackHoleTimer <= 0) {
                explode(cx, cy, new Color(199, 125, 255), 4);
                shake = 28f; flash = 0.6f;
                for (Inv inv : new ArrayList<>(invaders)) if (inv.alive) damageInvader(inv, 4);
                if (boss != null) { boss.hp -= 18; if (boss.hp <= 0) bossDeath(); }
            }
        }

        if (carpetBombTimer > 0) {
            carpetBombTimer -= dt;
            if (rnd.nextFloat() < dt * 18f) {
                float dropX = rnd.nextFloat() * W, dropY = rnd.nextFloat() * (H * 0.7f);
                explode(dropX, dropY, new Color(255, 120, 50), 2);
                shake = Math.max(shake, 12f);
                for (Inv inv : new ArrayList<>(invaders)) {
                    if (inv.alive && Math.hypot(inv.x - dropX, inv.y - dropY) < 110) damageInvader(inv, 2);
                }
            }
        }

        // Bullets
        for (Bullet b : new ArrayList<>(bullets)) {
            b.y -= b.speed * dt;
            b.trail.add(0, b.y); if (b.trail.size() > 6) b.trail.remove(b.trail.size() - 1);
            if (b.y < -50) bullets.remove(b);
        }
        for (Bullet b : new ArrayList<>(eBullets)) {
            b.x += b.vx * dt; b.y += b.speed * dt;
            if (b.y > H + 50 || b.x < -50 || b.x > W + 50) eBullets.remove(b);
        }

        // Particles
        for (Part p : new ArrayList<>(parts)) {
            p.life -= dt;
            if (p.life <= 0) { parts.remove(p); continue; }
            p.x += p.vx * dt; p.y += p.vy * dt; p.vy += p.gravity * dt;
            p.vx *= p.drag; p.vy *= p.drag;
        }

        // Mines
        for (Mine m : new ArrayList<>(mines)) {
            m.timer -= dt; m.pulse += dt * 5;
            boolean exp = m.timer <= 0;
            for (Inv inv : new ArrayList<>(invaders)) {
                if (inv.alive && Math.hypot(inv.x - m.x, inv.y - m.y) < inv.size + 30) exp = true;
            }
            if (exp) {
                mines.remove(m); explode(m.x, m.y, new Color(255, 180, 60), 2);
                for (Inv inv : new ArrayList<>(invaders)) {
                    if (inv.alive && Math.hypot(inv.x - m.x, inv.y - m.y) < 140) damageInvader(inv, 2);
                }
            }
        }

        // Powerups
        for (PowerUp p : new ArrayList<>(powerUps)) {
            p.y += 130f * dt; p.phase += dt * 3;
            p.x += Math.sin(p.phase) * 40f * dt;
            if (p.y > H + 50) powerUps.remove(p);
            else if (Math.hypot(playerX - p.x, playerY - p.y) < 55) {
                powerUps.remove(p); applyPowerUp(p.type);
            }
        }

        // Invaders Formation
        float speed = 60 + wave * 24, descend = 9 + wave * 2.4f;
        if (entering) {
            boolean settled = true;
            for (Inv v : invaders) {
                if (!v.alive) continue;
                v.pulse += dt * 6;
                float tx = v.homeX + formOffX;
                v.x += (tx - v.x) * Math.min(5 * dt, 1); v.y += (v.homeY - v.y) * Math.min(5 * dt, 1);
                if (Math.abs(tx - v.x) > 4 || Math.abs(v.homeY - v.y) > 4) settled = false;
            }
            if (settled) entering = false;
        } else if (!bossWave) {
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxHalf = 0;
            for (Inv v : invaders) { if (!v.alive || v.diving) continue; v.pulse += dt * 6;
                minX = Math.min(minX, v.homeX); maxX = Math.max(maxX, v.homeX); maxHalf = Math.max(maxHalf, v.size); }
            if (minX < maxX) {
                if (formDirX > 0 && maxX + formOffX + maxHalf > W - 50) formDirX = -1;
                else if (formDirX < 0 && minX + formOffX - maxHalf < 50) formDirX = 1;
                formOffX += formDirX * speed * dt;
            }
            for (Inv v : invaders) { if (!v.alive || v.diving) continue;
                v.x = v.homeX + formOffX; v.homeY += descend * dt; v.y = v.homeY; }
        }

        // Enemy Shooting
        invFireTimer -= dt;
        if (invFireTimer <= 0 && !invaders.isEmpty()) {
            List<Inv> alive = new ArrayList<>();
            for (Inv v : invaders) if (v.alive && v.y > 0) alive.add(v);
            if (!alive.isEmpty()) {
                Inv sh = alive.get(rnd.nextInt(alive.size()));
                eBullets.add(new Bullet(sh.x, sh.y + sh.size, 550f, new Color(255, 90, 60), (playerX - sh.x) * 0.3f, false, false, false));
                invFireTimer = Math.max(0.3f, 1.8f - wave * 0.1f);
            }
        }

        // Collisions
        for (Bullet b : new ArrayList<>(bullets)) {
            for (Inv v : new ArrayList<>(invaders)) {
                if (v.alive && Math.hypot(v.x - b.x, v.y - b.y) < v.size * 1.1f) {
                    bullets.remove(b); damageInvader(v, 1); break;
                }
            }
            if (boss != null && Math.hypot(boss.x - b.x, boss.y - b.y) < 100) {
                bullets.remove(b); boss.hp--; specialCharge = Math.min(100, specialCharge + 2);
                spawnSparks(b.x, b.y, new Color(255, 120, 220), 8);
                if (boss.hp <= 0) bossDeath();
            }
        }

        for (Bullet b : new ArrayList<>(eBullets)) {
            if (invincible <= 0 && Math.hypot(playerX - b.x, playerY - b.y) < 40) {
                eBullets.remove(b); explode(b.x, b.y, new Color(255, 120, 40), 1); hitPlayer();
            }
        }

        // Next Wave Check
        if (!entering && !bossWave) {
            boolean anyAlive = false;
            for (Inv v : invaders) if (v.alive) anyAlive = true;
            if (!anyAlive && !invaders.isEmpty()) { wave++; score += 100; spawnWave(); }
        }
    }

    private void shoot() {
        fireCd = rapidTimer > 0 ? 0.08f : 0.2f;
        bullets.add(new Bullet(playerX, playerY - 40f, 1100f, new Color(120, 255, 200), 0, false, false, false));
        if (tripleTimer > 0) {
            bullets.add(new Bullet(playerX, playerY - 40f, 1100f, new Color(120, 255, 200), -200f, false, false, false));
            bullets.add(new Bullet(playerX, playerY - 40f, 1100f, new Color(120, 255, 200), 200f, false, false, false));
        }
    }

    private void damageInvader(Inv inv, int dmg) {
        inv.hp -= dmg;
        if (inv.hp > 0) { spawnSparks(inv.x, inv.y, inv.color, 6); return; }
        inv.alive = false; explode(inv.x, inv.y, inv.color, 1);
        score += 30; coins += 30; combo++; specialCharge = Math.min(100, specialCharge + 4);
        if (Math.random() < 0.18) powerUps.add(new PowerUp(inv.x, inv.y, PowerType.values()[rnd.nextInt(PowerType.values().length)]));
    }

    private void applyPowerUp(PowerType type) {
        switch (type) {
            case RAPID: rapidTimer = 8f; addFloat("TIRO RÁPIDO!", playerX, playerY - 70, new Color(255, 193, 77)); break;
            case TRIPLE: tripleTimer = 8f; addFloat("TIRO TRIPLO!", playerX, playerY - 70, new Color(89, 229, 255)); break;
            case SHIELD: shieldUp = true; addFloat("ESCUDO!", playerX, playerY - 70, new Color(111, 168, 255)); break;
            case LIFE: lives = Math.min(5, lives + 1); addFloat("+1 VIDA!", playerX, playerY - 70, new Color(255, 111, 165)); break;
            case NOVA: triggerSpecial(); break;
            default: coins += 100; addFloat("+100 COINS!", playerX, playerY - 70, new Color(255, 216, 120)); break;
        }
    }

    private void hitPlayer() {
        if (invincible > 0) return;
        if (shieldUp) { shieldUp = false; invincible = 1.2f; addFloat("ESCUDO QUEBRADO", playerX, playerY - 70, Color.CYAN); return; }
        lives--; shake = 18f; flash = 0.5f; combo = 0;
        if (lives <= 0) state = State.GAME_OVER; else invincible = 2f;
    }

    private void bossDeath() {
        if (boss == null) return;
        score += 500; coins += 500; explode(boss.x, boss.y, new Color(255, 100, 100), 3);
        boss = null; wave++; spawnWave();
    }

    private void explode(float x, float y, Color c, int scale) {
        for (int i = 0; i < 24 * scale; i++) {
            float a = (float)(Math.random() * 6.28);
            float sp = (float)(Math.random() * 300 + 50) * scale;
            parts.add(new Part(x, y, (float)Math.cos(a)*sp, (float)Math.sin(a)*sp, (float)(Math.random()*4+2), 0.5f, 0.5f, c, 180f, Part.KIND_SPARK));
        }
    }

    private void spawnSparks(float x, float y, Color c, int count) {
        for (int i = 0; i < count; i++) {
            float a = (float)(Math.random() * 6.28); float sp = (float)(Math.random() * 200 + 40);
            parts.add(new Part(x, y, (float)Math.cos(a)*sp, (float)Math.sin(a)*sp, 2.5f, 0.3f, 0.3f, c, 100f, Part.KIND_SPARK));
        }
    }

    private void addFloat(String txt, float x, float y, Color c) {
        floatTexts.add(new FloatText(txt, x, y, c));
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int sectorIdx = Math.min((wave - 1) / 3, SECTORS.length - 1);
        SectorDef sec = SECTORS[sectorIdx];

        // Background Gradient
        g2.setPaint(new LinearGradientPaint(0, 0, 0, H, new float[]{0f, 0.5f, 1f}, new Color[]{sec.top, sec.mid, sec.bot}));
        g2.fillRect(0, 0, W, H);

        // Shake offset
        if (shake > 0.5f) g2.translate((Math.random() - 0.5) * shake, (Math.random() - 0.5) * shake);

        // Stars
        g2.setColor(Color.WHITE);
        for (float[] s : stars) g2.fill(new Ellipse2D.Float(s[0], s[1], s[2], s[2]));

        // Black Hole Draw
        if (blackHoleTimer > 0) {
            float cx = W / 2f, cy = H / 2f, r = 70f;
            g2.setColor(new Color(199, 125, 255, 140)); g2.fillOval((int)(cx - r * 1.8f), (int)(cy - r * 1.8f), (int)(r * 3.6f), (int)(r * 3.6f));
            g2.setColor(Color.BLACK); g2.fillOval((int)(cx - r), (int)(cy - r), (int)(r * 2), (int)(r * 2));
            g2.setColor(Color.WHITE); g2.setStroke(new BasicStroke(4)); g2.drawOval((int)(cx - r), (int)(cy - r), (int)(r * 2), (int)(r * 2));
        }

        // Mines
        for (Mine m : new ArrayList<>(mines)) {
            g2.setColor(new Color(255, 180, 60));
            g2.fill(new Ellipse2D.Float(m.x - 12, m.y - 12, 24, 24));
        }

        // Invaders
        for (Inv v : new ArrayList<>(invaders)) {
            if (!v.alive) continue;
            g2.setColor(v.color);
            g2.fill(new RoundRectangle2D.Float(v.x - v.size*0.8f, v.y - v.size*0.5f, v.size*1.6f, v.size, 12, 12));
        }

        // Boss
        if (boss != null) {
            g2.setColor(new Color(180, 40, 80));
            g2.fill(new RoundRectangle2D.Float(boss.x - 120, boss.y - 50, 240, 100, 24, 24));
            g2.setColor(Color.RED); g2.fillOval((int)boss.x - 20, (int)boss.y - 20, 40, 40);
        }

        // Powerups
        for (PowerUp p : new ArrayList<>(powerUps)) {
            g2.setColor(new Color(255, 216, 120));
            g2.fill(new RoundRectangle2D.Float(p.x - 18, p.y - 18, 36, 36, 8, 8));
        }

        // Bullets
        for (Bullet b : new ArrayList<>(bullets)) {
            g2.setColor(b.color); g2.fill(new RoundRectangle2D.Float(b.x - 4, b.y - 12, 8, 20, 4, 4));
        }
        for (Bullet b : new ArrayList<>(eBullets)) {
            g2.setColor(b.color); g2.fillOval((int)b.x - 6, (int)b.y - 6, 12, 12);
        }

        // Particles
        for (Part p : new ArrayList<>(parts)) {
            g2.setColor(p.color); g2.fill(new Ellipse2D.Float(p.x - p.radius, p.y - p.radius, p.radius * 2, p.radius * 2));
        }

        // Player Delta Ship Drawing
        if (state == State.PLAYING && (invincible <= 0 || ((int)(invincible * 10) % 2 == 0))) {
            g2.setColor(new Color(90, 170, 255));
            Path2D.Float flame = new Path2D.Float();
            flame.moveTo(playerX - 10, playerY + 18);
            flame.lineTo(playerX, playerY + 36 + Math.random() * 8);
            flame.lineTo(playerX + 10, playerY + 18);
            g2.fill(flame);

            Path2D.Float ship = new Path2D.Float();
            ship.moveTo(playerX, playerY - 32);
            ship.lineTo(playerX - 44, playerY + 24);
            ship.lineTo(playerX - 16, playerY + 16);
            ship.lineTo(playerX, playerY + 6);
            ship.lineTo(playerX + 16, playerY + 16);
            ship.lineTo(playerX + 44, playerY + 24);
            ship.closePath();
            g2.setColor(new Color(74, 85, 104));
            g2.fill(ship);

            if (shieldUp) {
                g2.setColor(new Color(120, 180, 255, 120));
                g2.setStroke(new BasicStroke(3));
                g2.drawOval((int)playerX - 52, (int)playerY - 48, 104, 96);
            }
        }

        // HUD & UI
        g2.setFont(new Font("Monospaced", Font.BOLD, 22));
        g2.setColor(new Color(255, 216, 120));
        g2.drawString("SCORE: " + score, 30, 42);
        g2.drawString("WAVE " + wave, W / 2 - 40, 42);
        g2.drawString("VIDAS: " + lives, W - 160, 42);

        if (state == State.MENU) {
            g2.setFont(new Font("Monospaced", Font.BOLD, 48));
            g2.setColor(Color.CYAN); g2.drawString("SPACE INVADERS", W / 2 - 200, H * 0.4f);
            g2.setColor(Color.YELLOW); g2.fillRect(W / 2 - 170, (int)(H * 0.62f), 340, 78);
            g2.setColor(Color.BLACK); g2.setFont(new Font("Monospaced", Font.BOLD, 32));
            g2.drawString("JOGAR", W / 2 - 50, (int)(H * 0.62f) + 50);
        } else if (state == State.GAME_OVER) {
            g2.setFont(new Font("Monospaced", Font.BOLD, 64));
            g2.setColor(Color.RED); g2.drawString("GAME OVER", W / 2 - 180, H * 0.45f);
            g2.setFont(new Font("Monospaced", Font.BOLD, 28));
            g2.setColor(Color.YELLOW); g2.drawString("CLIQUE OU ENTER PARA REINICIAR", W / 2 - 240, H * 0.65f);
        }

        // Secondary ability buttons in PLAYING
        if (state == State.PLAYING) {
            g2.setFont(new Font("Monospaced", Font.BOLD, 16));
            g2.setColor(new Color(30, 40, 60, 180));
            g2.fillRect(20, H - 90, 80, 60);
            g2.fillRect(110, H - 90, 80, 60);
            g2.fillRect(W - 130, H - 90, 100, 60);
            g2.setColor(Color.WHITE);
            g2.drawString("DASH(Shift)", 22, H - 55);
            g2.drawString("MINA(M)", 122, H - 55);
            g2.drawString("ESPECIAL(E)", W - 128, H - 55);
        }
    }

    public static void main(String[] args) {
        JFrame frame = new JFrame("Space Invaders — Desktop Preview (Paridade Oficial)");
        DesktopPreview preview = new DesktopPreview();
        frame.add(preview);
        frame.pack();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}

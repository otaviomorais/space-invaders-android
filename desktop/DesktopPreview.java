import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Point;
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
 * Preview desktop do Space Invaders (mesmo visual/jogabilidade do APK).
 * Compilado pelo GitHub Actions como JAR executavel (Java 8+).
 * Controles: arraste o mouse para mover, segure para atirar.
 */
public class DesktopPreview extends JPanel {

    private static final int W = 1280;
    private static final int H = 720;

    private final Random rnd = new Random();
    private final List<float[]> stars = new ArrayList<>();
    private final List<Inv> invaders = new ArrayList<>();
    private final List<Bullet> bullets = new ArrayList<>();
    private final List<Bullet> eBullets = new ArrayList<>();
    private final List<Part> parts = new ArrayList<>();

    private float scale = 1f;
    private boolean gameOver = false;
    private float goTimer = 0f;
    private float waveBanner = 0f;
    private float bgTime = 0f;

    private int score = 0;
    private int lives = 3;
    private int wave = 1;
    private float shake = 0f, flash = 0f;

    private float playerX = W / 2f;
    private float targetX = W / 2f;
    private float fireCd = 0f;
    private float invinc = 0f;
    private boolean dragging = false;
    private Point lastTouch = null;

    private float formOffX = 0f;
    private float formDirX = 1f;
    private boolean entering = false;
    private float invFireTimer = 1.5f;
    private float diveTimer = 7f;

    private Ufo ufo = null;
    private float ufoTimer = 9f;

    public DesktopPreview() {
        setPreferredSize(new Dimension(W, H));
        setBackground(Color.BLACK);
        initStars();
        spawnWave();
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                dragging = true; lastTouch = e.getPoint();
                if (gameOver && goTimer > 1.2f) resetGame();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragging && lastTouch != null) {
                    targetX += (e.getX() - lastTouch.x) * 1.8f;
                    lastTouch = e.getPoint();
                    targetX = Math.max(60f, Math.min(W - 60f, targetX));
                }
            }
            @Override public void mouseReleased(MouseEvent e) { dragging = false; }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        new Timer(16, ev -> { update(0.016f); repaint(); }).start();
    }

    private void initStars() {
        for (int i = 0; i < 140; i++)
            stars.add(new float[]{ rnd.nextFloat() * W, rnd.nextFloat() * H,
                rnd.nextFloat() * 2.2f + 0.6f, rnd.nextFloat() * 46f + 14f });
    }

    private static Color invColor(int variant) {
        switch (variant) {
            case 0: return new Color(255, 80, 170);
            case 1: return new Color(90, 230, 255);
            default: return new Color(140, 255, 110);
        }
    }

    private void spawnWave() {
        invaders.clear(); formOffX = 0; formDirX = 1; entering = true;
        int cols = Math.min(5 + wave / 2, 9);
        int rows = Math.min(3 + (wave - 1) / 2, 5);
        float marginX = W * 0.13f, spacingX = cols > 1 ? (W - marginX * 2) / (cols - 1) : 0;
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                int variant = r % 3;
                float size = (variant == 0 ? 40 : variant == 1 ? 36 : 46);
                invaders.add(new Inv(marginX + c * spacingX, H * 0.14f + r * H * 0.055f,
                    marginX + c * spacingX, -H * (0.25f + c * 0.06f + r * 0.12f) - size,
                    size, invColor(variant), variant,
                    variant == 2 || (r == 0 && wave >= 4) ? 2 : 1));
            }
        waveBanner = 1.6f;
    }

    private void resetGame() {
        score = 0; lives = 3; wave = 1; gameOver = false;
        bullets.clear(); eBullets.clear(); parts.clear(); ufo = null; ufoTimer = 9f; invinc = 0;
        spawnWave();
    }

    private void update(float dt) {
        bgTime += dt;
        for (float[] s : stars) {
            s[1] += s[3] * dt * (gameOver ? 0.2f : 1f);
            if (s[1] > H) { s[1] = 0; s[0] = rnd.nextFloat() * W; }
        }
        if (gameOver) {
            goTimer += dt; stepParts(dt); shake *= 0.9f; flash *= 0.92f; return;
        }
        playerX += (targetX - playerX) * Math.min(16f * dt, 1f);
        fireCd -= dt; invinc -= dt; shake *= 0.88f; flash *= 0.9f; waveBanner -= dt;
        if (dragging && fireCd <= 0) shoot();

        Iterator<Bullet> bi = bullets.iterator();
        while (bi.hasNext()) { Bullet b = bi.next(); b.y -= b.speed * dt; b.trail.add(0, b.y); if (b.trail.size() > 6) b.trail.remove(b.trail.size() - 1); if (b.y < -50) bi.remove(); }
        Iterator<Bullet> ei = eBullets.iterator();
        while (ei.hasNext()) { Bullet b = ei.next(); b.x += b.vx * dt; b.y += b.speed * dt; if (b.y > H + 50 || b.x < -50 || b.x > W + 50) ei.remove(); }

        // Invaders
        float speed = (60 + wave * 24), descend = (9 + wave * 2.4f);
        if (entering) {
            boolean settled = true;
            for (Inv v : invaders) {
                if (!v.alive) continue;
                v.pulse += dt * 6;
                float tx = v.homeX + formOffX;
                v.x += (tx - v.x) * Math.min(5 * dt, 1);
                v.y += (v.homeY - v.y) * Math.min(5 * dt, 1);
                if (Math.abs(tx - v.x) > 4 || Math.abs(v.homeY - v.y) > 4) settled = false;
            }
            if (settled) entering = false;
        } else {
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxHalf = 0;
            for (Inv v : invaders) { if (!v.alive || v.diving) continue; v.pulse += dt * 6;
                minX = Math.min(minX, v.homeX); maxX = Math.max(maxX, v.homeX); maxHalf = Math.max(maxHalf, v.size); }
            if (minX < maxX) {
                if (formDirX > 0 && maxX + formOffX + maxHalf > W - 50) formDirX = -1;
                else if (formDirX < 0 && minX + formOffX - maxHalf < 50) formDirX = 1;
                formOffX += formDirX * speed * dt;
            }
            float maxY = 0;
            for (Inv v : invaders) { if (!v.alive || v.diving) continue;
                v.x = v.homeX + formOffX; v.homeY += descend * dt; v.y = v.homeY; maxY = Math.max(maxY, v.y); }
            if (maxY > H - 110 - 100) hitPlayer(true);
        }

        // UFO
        if (ufo != null) {
            ufo.x += ufo.vx * dt; ufo.blink += dt * 8;
            if (ufo.x < -150 || ufo.x > W + 150) ufo = null;
        } else {
            ufoTimer -= dt;
            if (ufoTimer <= 0) {
                boolean left = rnd.nextBoolean();
                ufo = new Ufo(left ? -120 : W + 120, H * 0.07f, (left ? 1 : -1) * (230 + wave * 14));
                ufoTimer = rnd.nextFloat() * 8 + 10;
            }
        }

        invFireTimer -= dt;
        if (invFireTimer <= 0 && !invaders.isEmpty()) {
            List<Inv> alive = new ArrayList<>();
            for (Inv v : invaders) if (v.alive && !v.diving && v.y > 0) alive.add(v);
            if (!alive.isEmpty()) {
                Inv sh = alive.get(rnd.nextInt(alive.size()));
                float bSpeed = Math.min(500 + wave * 18, 900);
                float travel = (sh.y - (H - 110)) / bSpeed;
                float vx = travel > 0 ? (playerX - sh.x) / travel * 0.65f : 0;
                vx = Math.max(-240f, Math.min(240f, vx));
                eBullets.add(new Bullet(sh.x, sh.y + sh.size, bSpeed, new Color(255, 90, 60), vx));
                float interval = Math.max(rnd.nextFloat() * 0.7f + 1.5f / wave, 0.22f);
                int aliveCount = 0;
                for (Inv v : invaders) if (v.alive) aliveCount++;
                if (aliveCount <= 3) interval *= 0.55f;
                invFireTimer = interval;
            } else invFireTimer = 0.4f;
        }

        // Divers
        if (!entering) {
            diveTimer -= dt;
            if (diveTimer <= 0) {
                List<Inv> cands = new ArrayList<>();
                for (Inv v : invaders) if (v.alive && !v.diving) cands.add(v);
                if (cands.size() > 2) cands.get(rnd.nextInt(cands.size())).diving = true;
                diveTimer = Math.max(7 - wave * 0.5f, 2.2f) + rnd.nextFloat() * 2.5f;
            }
            float py = H - 110;
            for (Inv v : invaders) {
                if (!v.alive || !v.diving) continue;
                v.pulse += dt * 12;
                v.divePhase += dt * 5;
                v.y += (330 + wave * 22) * dt;
                v.x += (float) Math.sin(v.divePhase) * 170 * dt + Math.signum(playerX - v.x) * 70 * dt;
                if (dist(v.x, v.y, playerX, py) < v.size + 30) {
                    explode(v.x, v.y, v.color, 1); v.alive = false; v.diving = false; hitPlayer(false);
                } else if (v.y > H + v.size * 2) { v.alive = false; v.diving = false; }
            }
        }

        if (!entering) {
            boolean anyAlive = false;
            for (Inv v : invaders) if (v.alive) anyAlive = true;
            if (!anyAlive && !invaders.isEmpty()) { wave++; score += 100; spawnWave(); }
        }

        // Collisions
        Iterator<Bullet> it = bullets.iterator();
        outer:
        while (it.hasNext()) {
            Bullet b = it.next();
            for (Inv v : invaders) {
                if (!v.alive) continue;
                if (dist(v.x, v.y, b.x, b.y) < v.size * 1.1f) {
                    v.hp--; score += 5;
                    sparks(b.x, b.y, v.color, 8);
                    if (v.hp <= 0) { v.alive = false; explode(v.x, v.y, v.color, 1);
                        score += v.variant == 2 ? 35 : v.variant == 0 ? 25 : 15; shake = Math.max(shake, 6); }
                    it.remove(); continue outer;
                }
            }
            if (ufo != null && dist(ufo.x, ufo.y, b.x, b.y) < 70) {
                explode(ufo.x, ufo.y, new Color(255, 220, 90), 2);
                score += 150; shake = Math.max(shake, 12); ufo = null; it.remove();
            }
        }
        Iterator<Bullet> ebi = eBullets.iterator();
        while (ebi.hasNext()) {
            Bullet b = ebi.next();
            if (invinc <= 0 && dist(playerX, H - 110, b.x, b.y) < 42) {
                explode(b.x, b.y, new Color(255, 120, 40), 0); hitPlayer(false); ebi.remove();
            }
        }
        stepParts(dt);
    }

    private float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private void shoot() {
        fireCd = 0.18f;
        bullets.add(new Bullet(playerX, H - 110 - 60, 1150, new Color(120, 255, 200)));
    }

    private void hitPlayer(boolean instantDeath) {
        if (gameOver || invinc > 0) return;
        explode(playerX, H - 110, new Color(0, 255, 180), 1);
        shake = 18; flash = 0.55f; lives--;
        if (lives <= 0 || instantDeath) {
            lives = 0; gameOver = true; goTimer = 0;
            explode(playerX, H - 110, new Color(0, 255, 180), 2); shake = 28;
        } else { invinc = 2; playerX = W / 2f; targetX = playerX; }
    }

    private void explode(float x, float y, Color color, int tier) {
        int count = tier == 2 ? 140 : tier == 1 ? 46 : 24;
        float spdBase = tier == 2 ? 520 : tier == 1 ? 340 : 220;
        for (int i = 0; i < count; i++) {
            double a = rnd.nextFloat() * Math.PI * 2;
            float sp = (rnd.nextFloat() + 0.3f) * spdBase;
            parts.add(new Part(x, y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp,
                (rnd.nextFloat() * 5 + 2.5f) * (tier >= 1 ? 1.5f : 1),
                rnd.nextFloat() * 0.5f + 0.45f, color, 260, false));
        }
        Part ring = new Part(x, y, 0, 0, 8, 0.35f, color, 0, true);
        ring.maxLife = ring.life;
        parts.add(ring);
    }

    private void sparks(float x, float y, Color color, int count) {
        for (int i = 0; i < count; i++) {
            double a = rnd.nextFloat() * Math.PI * 2;
            float sp = (rnd.nextFloat() + 0.2f) * 260;
            parts.add(new Part(x, y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp,
                rnd.nextFloat() * 3 + 1.5f, rnd.nextFloat() * 0.25f + 0.2f, color, 150, false));
        }
    }

    private void stepParts(float dt) {
        Iterator<Part> it = parts.iterator();
        while (it.hasNext()) {
            Part p = it.next();
            p.life -= dt;
            if (p.life <= 0) { it.remove(); continue; }
            p.x += p.vx * dt; p.y += p.vy * dt;
            p.vy += p.gravity * dt; p.vx *= 0.98f; p.vy *= 0.98f;
        }
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // Background gradient
        LinearGradientPaint bg = new LinearGradientPaint(0, 0, 0, H,
            new float[]{0f, 1f}, new Color[]{new Color(6, 4, 24), new Color(28, 8, 48)});
        g.setPaint(bg); g.fillRect(0, 0, W, H);

        // Nebulae
        float[][] neb = {{0.22f, 0.30f}, {0.75f, 0.22f}, {0.50f, 0.85f}};
        Color[] ncol = {new Color(110, 40, 190), new Color(20, 160, 170), new Color(200, 40, 120)};
        for (int i = 0; i < 3; i++) {
            float cx = W * neb[i][0] + (float) Math.sin(bgTime * 0.11 + i * 2.1) * 30;
            float cy = H * neb[i][1] + (float) Math.cos(bgTime * 0.07 + i * 1.7) * 30;
            g.setPaint(new RadialGradientPaint(new Point((int) cx, (int) cy), 380,
                new float[]{0f, 1f}, new Color[]{new Color(ncol[i].getRed(), ncol[i].getGreen(), ncol[i].getBlue(), 60), new Color(0, 0, 0, 0)}));
            g.fillRect(0, 0, W, H);
        }

        g.translate((rnd.nextFloat() - 0.5f) * shake, (rnd.nextFloat() - 0.5f) * shake);

        // Stars
        for (float[] s : stars) {
            g.setColor(new Color(255, 255, 255, (int) (60 + s[2] * 40)));
            g.fillOval((int) s[0], (int) s[1], (int) (s[2] * 2), (int) (s[2] * 2));
        }

        if (gameOver) drawGameOver(g); else {
            drawUfo(g);
            for (Inv v : invaders) if (v.alive) {
                if (v.variant == 0) drawCrab(g, v);
                else if (v.variant == 1) drawSquid(g, v);
                else drawArmored(g, v);
            }
            drawPlayer(g);
            // Bullets
            g.setStroke(new BasicStroke(3));
            for (Bullet b : bullets) {
                g.setStroke(new BasicStroke(3));
                for (int i = 0; i < b.trail.size(); i++) {
                    g.setColor(new Color(120, 255, 200, (int) (70 * (1f - i / (float) b.trail.size()))));
                    g.drawLine((int) b.x, (int) (float) b.trail.get(i), (int) b.x, (int) (b.trail.get(i) + 8));
                }
                g.setColor(b.color);
                g.setComposite(java.awt.AlphaComposite.SrcOver);
                g.fillRoundRect((int) b.x - 4, (int) b.y - 16, 8, 26, 8, 8);
            }
            g.setComposite(java.awt.AlphaComposite.SrcOver);
            for (Bullet b : eBullets) { g.setColor(b.color); glowOval(g, b.x, b.y, 8, b.color); }
        }

        // Particles
        for (Part p : parts) {
            float t = p.isRing ? 1 - p.life / p.maxLife : p.life / p.maxLife;
            if (p.isRing) {
                g.setStroke(new BasicStroke(Math.max(6 * (1 - t), 1)));
                g.setColor(new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(), (int) ((1 - t) * 200)));
                g.drawOval((int) (p.x - (p.radius + t * 90)), (int) (p.y - (p.radius + t * 90)),
                    (int) ((p.radius + t * 90) * 2), (int) ((p.radius + t * 90) * 2));
            } else {
                g.setColor(new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(), (int) (t * 230)));
                float r = p.radius * (0.4f + t * 0.6f);
                glowOval(g, p.x, p.y, r, p.color);
            }
        }

        drawHud(g);

        if (flash > 0.01f) {
            g.setColor(new Color(255, 255, 255, (int) (flash * 255)));
            g.fillRect(0, 0, W, H);
        }
    }

    private void glowOval(Graphics2D g, float x, float y, float r, Color c) {
        g.setColor(c);
        g.fill(new Ellipse2D.Float(x - r, y - r, r * 2, r * 2));
        g.setPaint(new RadialGradientPaint(new Point((int) x, (int) y), r * 3,
            new float[]{0f, 1f}, new Color[]{new Color(c.getRed(), c.getGreen(), c.getBlue(), 70), new Color(0, 0, 0, 0)}));
        g.fill(new Ellipse2D.Float(x - r * 3, y - r * 3, r * 6, r * 6));
    }

    private void drawPlayer(Graphics2D g) {
        if (invinc > 0 && ((int) (invinc * 10)) % 2 == 0) return;
        float x = playerX, y = H - 110, half = 60;
        g.setPaint(new RadialGradientPaint(new Point((int) x, (int) y), half * 2.2f,
            new float[]{0f, 1f}, new Color[]{new Color(0, 255, 190, 90), new Color(0, 0, 0, 0)}));
        g.fill(new Ellipse2D.Float(x - half * 2.2f, y - half * 2.2f, half * 4.4f, half * 4.4f));

        Path2D ship = new Path2D.Float();
        ship.moveTo(x, y - half * 0.9f); ship.lineTo(x - half * 0.7f, y + half * 0.6f);
        ship.lineTo(x, y + half * 0.25f); ship.lineTo(x + half * 0.7f, y + half * 0.6f); ship.closePath();
        glowFill(g, ship, new Color(0, 255, 190));
        g.setColor(Color.WHITE);
        g.fillOval((int) (x - half * 0.16f), (int) (y - half * 0.31f), (int) (half * 0.32f), (int) (half * 0.32f));
        float fl = half * (0.35f + rnd.nextFloat() * 0.25f);
        Path2D flame = new Path2D.Float();
        flame.moveTo(x - half * 0.18f, y + half * 0.35f); flame.lineTo(x, y + half * 0.35f + fl);
        flame.lineTo(x + half * 0.18f, y + half * 0.35f); flame.closePath();
        glowFill(g, flame, new Color(255, 190, 60));
    }

    private void glowFill(Graphics2D g, java.awt.Shape s, Color c) {
        g.setPaint(c);
        g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 80));
        g.draw(s);
        g.setPaint(c);
        g.fill(s);
    }

    private void drawCrab(Graphics2D g, Inv v) {
        float s = v.size, pulse = 1 + (float) Math.sin(v.pulse) * 0.06f;
        glowFill(g, new RoundRectangle2D.Float(v.x - s * pulse, v.y - s * 0.55f * pulse, s * 2 * pulse, s * 0.85f * pulse, s * 0.6f, s * 0.6f), v.color);
        g.setStroke(new BasicStroke(s * 0.14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float legSwing = (float) Math.sin(v.pulse * 1.5) * s * 0.15f;
        g.setColor(v.color);
        g.drawLine((int) (v.x - s * 0.45), (int) (v.y - s * 0.5), (int) (v.x - s * 0.75), (int) (v.y - s * 0.95 + Math.sin(v.pulse) * s * 0.1));
        g.drawLine((int) (v.x + s * 0.45), (int) (v.y - s * 0.5), (int) (v.x + s * 0.75), (int) (v.y - s * 0.95 - Math.sin(v.pulse) * s * 0.1));
        g.drawLine((int) (v.x - s * 0.5), (int) (v.y + s * 0.2), (int) (v.x - s * 0.85), (int) (v.y + s * 0.75 + legSwing));
        g.drawLine((int) (v.x + s * 0.5), (int) (v.y + s * 0.2), (int) (v.x + s * 0.85), (int) (v.y + s * 0.75 - legSwing));
        g.drawLine((int) (v.x - s * 0.2), (int) (v.y + s * 0.25), (int) (v.x - s * 0.35), (int) (v.y + s * 0.85 - legSwing));
        g.drawLine((int) (v.x + s * 0.2), (int) (v.y + s * 0.25), (int) (v.x + s * 0.35), (int) (v.y + s * 0.85 + legSwing));
        g.setColor(Color.BLACK);
        g.fillOval((int) (v.x - s * 0.43), (int) (v.y - s * 0.25), (int) (s * 0.26), (int) (s * 0.26));
        g.fillOval((int) (v.x + s * 0.17), (int) (v.y - s * 0.25), (int) (s * 0.26), (int) (s * 0.26));
    }

    private void drawSquid(Graphics2D g, Inv v) {
        float s = v.size;
        glowFill(g, new Ellipse2D.Float(v.x - s * 0.62f, v.y - s * 0.72f, s * 1.24f, s * 1.24f), v.color);
        for (int i = -2; i <= 2; i += 2) {
            float phase = v.pulse * 2 + i;
            float tipX = v.x + i * s * 0.32f + (float) Math.sin(phase) * s * 0.14f;
            Path2D tent = new Path2D.Float();
            tent.moveTo(v.x + i * s * 0.22f - s * 0.08f, v.y + s * 0.25f);
            tent.quadTo(v.x + i * s * 0.3f, v.y + s * 0.6f, tipX, v.y + s * 0.85f);
            tent.lineTo(tipX + s * 0.1f, v.y + s * 0.85f);
            tent.quadTo(v.x + i * s * 0.3f + s * 0.1f, v.y + s * 0.6f, v.x + i * s * 0.22f + s * 0.08f, v.y + s * 0.25f);
            tent.closePath();
            glowFill(g, tent, v.color);
        }
        g.setColor(Color.BLACK);
        g.fillOval((int) (v.x - s * 0.22f), (int) (v.y - s * 0.37f), (int) (s * 0.44f), (int) (s * 0.44f));
        g.setColor(Color.WHITE);
        float look = (float) Math.sin(v.pulse * 0.8) * s * 0.09f;
        g.fillOval((int) (v.x + look - s * 0.1f), (int) (v.y - s * 0.25f), (int) (s * 0.2f), (int) (s * 0.2f));
    }

    private void drawArmored(Graphics2D g, Inv v) {
        float s = v.size;
        glowFill(g, new RoundRectangle2D.Float(v.x - s, v.y - s * 0.6f, s * 2, s * 0.95f, s * 0.36f, s * 0.36f), v.color);
        g.setColor(new Color(30, 60, 40, 220));
        g.fill(new RoundRectangle2D.Float(v.x - s * 0.72f, v.y - s * 0.38f, s * 1.44f, s * 0.52f, s * 0.24f, s * 0.24f));
        g.setColor(new Color(210, 255, 200));
        for (int i = -1; i <= 1; i++)
            g.fillOval((int) (v.x + i * s * 0.5f - s * 0.07f), (int) (v.y - s * 0.19f), (int) (s * 0.14f), (int) (s * 0.14f));
        if (v.hp > 1) {
            g.setStroke(new BasicStroke(3));
            int a = (int) (140 + Math.sin(v.pulse * 3) * 60);
            g.setColor(new Color(160, 255, 140, Math.max(0, a)));
            g.drawOval((int) (v.x - s * 1.15f), (int) (v.y - s * 1.25f), (int) (s * 2.3f), (int) (s * 2.3f));
        }
    }

    private void drawUfo(Graphics2D g) {
        if (ufo == null) return;
        float s = 55, x = ufo.x, y = ufo.y;
        glowFill(g, new Ellipse2D.Float(x - s, y - s * 0.28f, s * 2, s * 0.62f), new Color(130, 245, 235));
        g.setColor(new Color(255, 110, 220));
        g.fillArc((int) (x - s * 0.42f), (int) (y - s * 0.75f), (int) (s * 0.84f), (int) (s * 0.85f), 0, 180);
        for (int i = -2; i <= 2; i++) {
            boolean on = ((int) (ufo.blink + i)) % 3 == 0;
            g.setColor(on ? new Color(255, 240, 120) : new Color(120, 90, 40));
            g.fillOval((int) (x + i * s * 0.38f - s * 0.09f), (int) (y - s * 0.04f), (int) (s * 0.18f), (int) (s * 0.18f));
        }
    }

    private void drawHud(Graphics2D g) {
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 34));
        g.setColor(Color.WHITE); g.drawString("SCORE " + score, 30, 56);
        g.setColor(new Color(255, 150, 240)); g.drawString("WAVE " + wave, W / 2 - 80, 56);
        g.setColor(new Color(120, 255, 160));
        StringBuilder hb = new StringBuilder();
        for (int i = 0; i < lives; i++) hb.append('\u2665');
        g.drawString(hb.toString(), W - 130, 56);

        if (waveBanner > 0) {
            int a = (int) (Math.min(waveBanner / 1.6f, 1f) * 255);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 64));
            g.setColor(new Color(140, 240, 255, a));
            g.drawString("WAVE " + wave, W / 2 - 170, (int) (H * 0.42f));
        }
    }

    private void drawGameOver(Graphics2D g) {
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 84));
        g.setColor(new Color(255, 80, 80));
        g.drawString("GAME OVER", W / 2 - 300, H / 2 - 20);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 40));
        g.setColor(Color.WHITE);
        g.drawString("SCORE: " + score, W / 2 - 120, H / 2 + 60);
        if (((int) (goTimer * 2)) % 2 == 0) {
            g.setColor(new Color(255, 230, 120));
            g.drawString("CLIQUE PARA REINICIAR", W / 2 - 250, H / 2 + 130);
        }
    }

    static class Bullet { float x, y, speed, vx; Color color; List<Float> trail = new ArrayList<>();
        Bullet(float x, float y, float speed, Color color) { this(x, y, speed, color, 0); }
        Bullet(float x, float y, float speed, Color color, float vx) { this.x = x; this.y = y; this.speed = speed; this.color = color; this.vx = vx; } }

    static class Inv { float homeX, homeY, x, y, size; Color color; int variant, hp; boolean alive = true, diving = false; float pulse = (float)(Math.random() * 6), divePhase = 0;
        Inv(float hx, float hy, float x, float y, float size, Color color, int variant, int hp)
        { this.homeX = hx; this.homeY = hy; this.x = x; this.y = y; this.size = size; this.color = color; this.variant = variant; this.hp = hp; } }

    static class Ufo { float x, y, vx, blink = 0;
        Ufo(float x, float y, float vx) { this.x = x; this.y = y; this.vx = vx; } }

    static class Part { float x, y, vx, vy, radius, life, maxLife, gravity; Color color; boolean isRing;
        Part(float x, float y, float vx, float vy, float radius, float life, Color color, float gravity, boolean ring)
        { this.x = x; this.y = y; this.vx = vx; this.vy = vy; this.radius = radius; this.life = life; this.maxLife = life;
          this.color = color; this.gravity = gravity; this.isRing = ring; } }

    public static void main(String[] args) {
        JFrame f = new JFrame("Space Invaders - Preview Desktop");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setResizable(false);
        f.add(new DesktopPreview());
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }
}

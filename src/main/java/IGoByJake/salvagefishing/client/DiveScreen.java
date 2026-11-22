package IGoByJake.salvagefishing.client;

import IGoByJake.salvagefishing.DiveCourse;
import IGoByJake.salvagefishing.net.DiveNetwork;
import IGoByJake.salvagefishing.net.DiveResultPacket;
import IGoByJake.salvagefishing.net.StartDivePacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Random;

/**
 * The dive. The hook sinks at a steady pace down a shaft between rock walls; steer it left and right.
 * <ul>
 *   <li>Hold A/D (or the strafe or arrow keys): a slow drift that way.</li>
 *   <li>Jig: a sideways jerk on the line, like a flappy-bird hop. It adds to the hook's speed if it's already going
 *       that way, and replaces it if it's going the other way. Double-tap A/D to jig; while the jig is live (within
 *       {@link #JIG_CHAIN} s of the last one) each single tap jigs again. Jigs are at least {@link #JIG_COOLDOWN} s
 *       apart (an early tap is held until then).</li>
 *   <li>Hold the left mouse button: the hook drifts toward the cursor, at full speed from {@link #DRAG_RANGE} px
 *       away; past {@link #JIG_RANGE} px it jigs toward it instead.</li>
 *   <li>Touching anything snags it: the catch races up on a wave and the screen closes. Hitting rock, or running
 *       out of line at the bottom, ends with nothing. Right-click (or Esc) reels in straight away.</li>
 * </ul>
 * It gets darker the deeper you go, with a little light round the hook. When the floor comes into view the view
 * stops and the hook sinks on to it. Rock is drawn as dark silhouettes over the pool's colours, so any liquid can
 * have its own look with no textures.
 */
public class DiveScreen extends Screen {
    /** Drift: top speed and how fast it's reached; water drag on anything faster. */
    private static final float DRIFT_V = 38F, DRIFT_ACCEL = 110F, DRAG = 2.4F, GLIDE = 22F;
    /** A jig's push (sideways speed), the most sideways speed jigs can build, the gap between jigs, and how long
     *  a jig stays live so single taps keep jigging. */
    private static final float JIG = 100F, JIG_MAX = 210F;
    private static final float JIG_COOLDOWN = 0.5F, JIG_CHAIN = 0.75F, DOUBLE_TAP = 0.3F;
    /** Sink speed in slow mode (30.4/s, the same as before 1.5.0); there is no fast mode, the normal speed is it. */
    private static final float SLOW = 0.64F;
    /** Mouse: drag this far above the hook for slow mode (short, since the hook sits high in the view). */
    private static final float SLOW_REACH = 10F;
    /** Mouse: full drift speed at this distance from the hook (sideways; up/down for slow/fast); a jig past this
     *  plus 40% (a buffer of full drift). */
    private static final float DRAG_RANGE = 25F, JIG_RANGE = DRAG_RANGE * 1.4F;
    private static final float HOOK_R = 2.5F, ITEM_R = 11F;
    private static final ResourceLocation HOOK = new ResourceLocation("salvagefishing", "textures/gui/hook.png");
    private static final float START_DEPTH = -40F;

    private enum State { PLAY, CATCH, FAIL, DONE }

    private final int id;
    private final DiveCourse course;
    private final Theme theme;
    private final List<ItemStack> stacks;
    private final List<float[]> pos;
    private final long seed;

    private State state = State.PLAY;
    private float hookX, vx, depth = START_DEPTH, time;
    private long lastNanos = -1;
    private float animTime;
    private int caught = -1;
    private boolean left, right, up, down, dragging;
    /** Sink speed: 1, or slow (W / drag up). */
    private float sink = 1F;
    private double mouseYNow;
    private double mouseXNow;
    private float lastJig = -10F, lastTap = -10F;
    private int chain, lastTapDir, pendingJig, lastJigDir;
    // The line: its top end follows the hook loosely; a jig yanks it sideways and makes it bow.
    private float lineTop, lineTopV, bend, bendV, hookAngle;

    public DiveScreen(StartDivePacket p) {
        super(Component.translatable("salvagefishing.title"));
        this.id = p.id();
        this.seed = p.seed();
        this.course = new DiveCourse(p.seed());
        this.theme = Theme.of(p.theme(), p.colors());
        this.stacks = p.stacks();
        this.pos = p.pos();
    }

    public int id() {
        return this.id;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- input ---------------------------------------------------------------------------------------------

    private boolean isUp(int key, int scan) {
        return key == GLFW.GLFW_KEY_UP || Minecraft.getInstance().options.keyUp.matches(key, scan);
    }

    private boolean isDown(int key, int scan) {
        return key == GLFW.GLFW_KEY_DOWN || Minecraft.getInstance().options.keyDown.matches(key, scan);
    }

    private boolean isLeft(int key, int scan) {
        return key == GLFW.GLFW_KEY_LEFT || Minecraft.getInstance().options.keyLeft.matches(key, scan);
    }

    private boolean isRight(int key, int scan) {
        return key == GLFW.GLFW_KEY_RIGHT || Minecraft.getInstance().options.keyRight.matches(key, scan);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (isUp(key, scan)) { up = true; return true; }
        if (isDown(key, scan)) { down = true; return true; }
        if (isLeft(key, scan)) {
            if (!left) tap(-1);
            left = true;
            return true;
        }
        if (isRight(key, scan)) {
            if (!right) tap(1);
            right = true;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (isUp(key, scan)) { up = false; return true; }
        if (isDown(key, scan)) { down = false; return true; }
        if (isLeft(key, scan)) { left = false; return true; }
        if (isRight(key, scan)) { right = false; return true; }
        return super.keyReleased(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) {
            if (state == State.PLAY) send(DiveResultPacket.ABORT, -1);
            state = State.DONE;
            Minecraft.getInstance().setScreen(null);
            return true;
        }
        if (button == InputConstants.MOUSE_BUTTON_LEFT) {
            dragging = true;
            mouseXNow = x;
            mouseYNow = y;
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (dragging) {
            mouseXNow = x;
            mouseYNow = y;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT) dragging = false;
        return true;
    }

    @Override
    public void onClose() {
        if (state == State.PLAY) send(DiveResultPacket.ABORT, -1);
        state = State.DONE;
        super.onClose();
    }

    /** The server cut the dive short. */
    void serverEnded() {
        if (state == State.PLAY) {
            state = State.FAIL;
            animTime = 0;
        }
    }

    private void send(int kind, int index) {
        DiveNetwork.CHANNEL.sendToServer(new DiveResultPacket(this.id, kind, index));
    }

    private static void sound(SoundEvent e, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(e, pitch));
    }

    // ---- simulation ----------------------------------------------------------------------------------------

    /** Where entry i is at time t: swimmers go back and forth across their stretch at a steady speed. */
    private float itemX(int i, float t) {
        float[] p = pos.get(i);
        float range = p[2], speed = p[3];
        if (p[5] == 0 || range <= 0 || speed <= 0) return p[0];
        float lap = 4 * range;
        float u = ((t * speed + p[4] * lap) % lap + lap) % lap;
        return p[0] + (u < 2 * range ? u - range : 3 * range - u);
    }

    /** True while entry i swims right. Item sprites (like vanilla fish) face left, so it's drawn turned round then. */
    private boolean swimsRight(int i, float t) {
        float[] p = pos.get(i);
        if (p[5] == 0 || p[2] <= 0 || p[3] <= 0) return false;
        float lap = 4 * p[2];
        float u = ((t * p[3] + p[4] * lap) % lap + lap) % lap;
        return u < 2 * p[2];
    }

    /** A key tap toward dir: the first jig needs a double tap; while a jig is live, every tap the same way jigs
     *  again. A tap the other way doesn't: it just steers against the jig (a double tap jigs back). */
    private void tap(int dir) {
        if (state != State.PLAY) return;
        boolean live = chain > 0 && time - lastJig <= JIG_CHAIN && dir == lastJigDir;
        boolean doubleTap = dir == lastTapDir && time - lastTap <= DOUBLE_TAP;
        lastTap = time;
        lastTapDir = dir;
        if (live || doubleTap) {
            if (time - lastJig >= JIG_COOLDOWN) jig(dir);
            else pendingJig = dir; // held until the cooldown ends
            lastTapDir = 0;
        }
    }

    private boolean canJig() {
        return time - lastJig >= JIG_COOLDOWN;
    }

    private void jig(int dir) {
        chain = time - lastJig <= JIG_CHAIN ? chain + 1 : 1;
        float size = JIG;
        // Keeps the hook's momentum going the same way; going the other way, the jig replaces it.
        if (dir != lastJigDir) chain = 1;
        lastJigDir = dir;
        vx = vx * dir > 0 ? dir * Math.min(JIG_MAX, Math.abs(vx) + JIG) : dir * JIG;
        lastJig = time;
        pendingJig = 0;
        lineTopV += dir * size * 1.4F;  // the line is yanked first...
        bendV += dir * size * 2.2F;     // ...and bows with it
        sound(SoundEvents.FISHING_BOBBER_THROW, 1.5F);
    }

    private void step(float dt) {
        time += dt;
        if (chain > 0 && time - lastJig > JIG_CHAIN && pendingJig == 0) chain = 0;
        if (pendingJig != 0 && canJig()) {
            if (time - lastJig <= JIG_CHAIN + 0.05F) jig(pendingJig);
            else pendingJig = 0;
        }

        // Drift toward the held key, or toward the mouse while it's in reach; jig toward a far-off mouse.
        int dir = (right ? 1 : 0) - (left ? 1 : 0);
        float target = Float.NaN;
        if (dir != 0) target = dir * DRIFT_V;
        else if (dragging) {
            float dx = (float) (mouseXNow - sx(hookX));
            if (Math.abs(dx) > JIG_RANGE) {
                if (canJig()) jig((int) Math.signum(dx));
                target = Math.signum(dx) * DRIFT_V; // keep drifting toward it between jigs
            } else {
                target = Mth.clamp(dx / DRAG_RANGE, -1F, 1F) * DRIFT_V;
            }
        }
        boolean fast = Math.abs(vx) > DRIFT_V;
        if (!Float.isNaN(target) && !(fast && Math.signum(vx) == Math.signum(target))) {
            float d = target - vx, max = DRIFT_ACCEL * dt;
            vx += Mth.clamp(d, -max, max);
        }
        if (fast) {
            // Water drag after a jig, but only down to drift speed: the hook carries on rather than stopping dead.
            float slowed = vx * (float) Math.exp(-DRAG * dt);
            vx = Math.abs(slowed) < DRIFT_V ? Math.signum(vx) * DRIFT_V : slowed;
        } else if (Float.isNaN(target)) {
            vx -= Mth.clamp(vx, -GLIDE * dt, GLIDE * dt);             // no input: a slow glide to a stop
        }
        float nx = hookX + vx * dt, edge = DiveCourse.WIDTH / 2F - HOOK_R;
        if (Math.abs(nx) > edge) vx = 0;
        hookX = Mth.clamp(nx, -edge, edge);

        // The line's top end trails the hook on a spring; the bow swings and settles.
        lineTopV += (8F * (hookX - lineTop) - 4F * lineTopV) * dt;
        lineTop += lineTopV * dt;
        bendV += (-55F * bend - 5F * bendV) * dt;
        bend += bendV * dt;
        // Slow: W, or the mouse dragged more than SLOW_REACH above the hook. (No fast mode.)
        boolean slow = up || dragging && mouseYNow - hookScreenY < -SLOW_REACH;
        sink = slow ? SLOW : 1F;
        depth += DiveCourse.SPEED * sink * dt;

        if (depth + HOOK_R >= DiveCourse.DEPTH) { fail(); return; }
        if (depth > 0) {
            float l = course.leftMax(depth - HOOK_R, depth + HOOK_R), r = course.rightMin(depth - HOOK_R, depth + HOOK_R);
            if (hookX - HOOK_R < l || hookX + HOOK_R > r) { fail(); return; }
        }
        for (int i = 0; i < stacks.size(); i++) {
            float[] p = pos.get(i);
            if (Math.abs(p[1] - depth) > ITEM_R + HOOK_R) continue;
            if (Math.abs(itemX(i, time) - hookX) <= ITEM_R + HOOK_R) {
                caught = i;
                state = State.CATCH;
                animTime = 0;
                send(DiveResultPacket.CATCH, i);
                sound(theme.lava ? SoundEvents.LAVA_POP : SoundEvents.FISHING_BOBBER_SPLASH, 1.0F);
                return;
            }
        }
    }

    private void fail() {
        state = State.FAIL;
        animTime = 0;
        send(DiveResultPacket.FAIL, -1);
        sound(SoundEvents.STONE_HIT, 0.8F);
        sound(theme.lava ? SoundEvents.LAVA_POP : SoundEvents.GENERIC_SPLASH, 0.9F);
    }

    // ---- drawing -------------------------------------------------------------------------------------------

    private int px0, py0, pw, ph, hookScreenY;
    /** The world depth at the top of the view: follows the hook, but stops once the floor is 80% down the view. */
    private float camTop;

    private int sx(float x) {
        return px0 + pw / 2 + Math.round(x);
    }

    private int sy(float y) {
        return py0 + Math.round(y - camTop);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        long now = System.nanoTime();
        float dt = lastNanos < 0 ? 0 : Math.min(0.05F, (now - lastNanos) / 1.0E9F);
        lastNanos = now;
        if (state == State.PLAY) step(dt);
        else if (state != State.DONE) animTime += dt;

        pw = DiveCourse.WIDTH;
        ph = Math.max(140, this.height - 44);
        px0 = (this.width - pw) / 2;
        py0 = (this.height - ph) / 2 - 4;
        camTop = Math.min(depth - ph * 0.3F, DiveCourse.DEPTH - ph * 0.8F);
        hookScreenY = sy(depth);

        g.enableScissor(px0, py0, px0 + pw, py0 + ph);
        drawWater(g);
        drawWalls(g);
        drawItems(g);
        drawDark(g);
        drawHook(g);
        if (state == State.CATCH || state == State.FAIL) drawEnd(g);
        g.disableScissor();
        drawFrame(g);

        if (state == State.CATCH && animTime > 1.45F || state == State.FAIL && animTime > 1.0F) {
            state = State.DONE;
            Minecraft.getInstance().setScreen(null);
        }
    }

    private void drawWater(GuiGraphics g) {
        int top = py0, bottom = py0 + ph;
        int surf = sy(0);
        // Sky down to the surface, then water in 4-deep bands fixed to the world (not the screen), so bands and
        // the sky/water edge move with the scroll instead of flickering between colours.
        if (surf > top) g.fill(px0, top, px0 + pw, Math.min(bottom, surf), theme.sky);
        int firstBand = Math.max(0, (int) Math.floor(camTop / 4F));
        for (int b = firstBand; ; b++) {
            int y0 = sy(b * 4F), y1 = sy(b * 4F + 4F);
            if (y0 >= bottom) break;
            float f = (float) Math.pow(Mth.clamp(b * 4F / DiveCourse.DEPTH, 0, 1), 0.8);
            int c = f < 0.5F ? lerp(theme.top, theme.mid, f * 2) : lerp(theme.mid, theme.bottom, (f - 0.5F) * 2);
            g.fill(px0, Math.max(top, y0), px0 + pw, Math.min(bottom, y1), c);
        }
        // The surface: gentle drifting waves, each 4-wide column filled solidly from sky to water so no gaps open.
        if (surf > top - 8 && surf < bottom + 8) {
            for (int x = 0; x < pw; x += 4) {
                int h = Math.round(Mth.sin((x + time * 10F) * 0.07F) * 2F);
                int xs = px0 + x;
                if (h < 0) g.fill(xs, surf + h, xs + 4, surf, theme.top);   // crest above the line: water
                if (h > 0) g.fill(xs, surf, xs + 4, surf + h, theme.sky);   // trough below the line: sky
                g.fill(xs, surf + h - 2, xs + 4, surf + h, theme.foam);
                g.fill(xs, surf + h, xs + 4, surf + h + 2, theme.light);
            }
        }
        // Bubbles (or embers) drifting up.
        Random r = new Random(seed * 31);
        for (int k = 0; k < 40; k++) {
            float bx = r.nextFloat() * pw;
            float base = r.nextFloat() * (DiveCourse.DEPTH + 200);
            float wy = base - (time * (12 + r.nextFloat() * 10)) % 200F;
            int y = sy(wy), x = px0 + Math.round(bx + Mth.sin(time * 2 + k) * 2);
            if (y < top || y > bottom || wy < 0) continue;
            g.fill(x, y, x + 2, y + 2, theme.bubble);
        }
    }

    /** Rock as a dark silhouette, block by block, with a darker rim; and the floor. */
    private void drawWalls(GuiGraphics g) {
        int top = py0, bottom = py0 + ph;
        float first = camTop, last = camTop + ph;
        int r0 = (int) Math.floor(Math.max(0, first) / DiveCourse.ROW), r1 = (int) Math.ceil(last / DiveCourse.ROW);
        int body = 0xD8060810, rim = 0xF0000000;
        for (int row = r0; row <= r1; row++) {
            float wy = row * DiveCourse.ROW;
            if (wy >= DiveCourse.DEPTH) break;
            int y = sy(wy), y2 = y + DiveCourse.ROW;
            int l = sx(course.left(wy)), r = sx(course.right(wy));
            int lp = sx(course.left(wy - DiveCourse.ROW)), rp = sx(course.right(wy - DiveCourse.ROW));
            if (l > px0) {
                g.fill(px0, y, l, y2, body);
                g.fill(l - 2, y, l, y2, rim);
                if (row > 0 && lp < l) g.fill(lp, y, l, y + 2, rim); // ledge top
                if (course.isShelfTop(wy) && lp < l) g.fill(lp, y, l, y + 2, 0x55FFFFFF);   // shelf: a pale top
            }
            if (r < px0 + pw) {
                g.fill(r, y, px0 + pw, y2, body);
                g.fill(r, y, r + 2, y2, rim);
                if (row > 0 && rp > r) g.fill(r, y, rp, y + 2, rim);
                if (course.isShelfTop(wy) && rp > r) g.fill(r, y, rp, y + 2, 0x55FFFFFF);
            }
        }
        int floor = sy(DiveCourse.DEPTH);
        if (floor < bottom) {
            g.fill(px0, floor, px0 + pw, bottom, body);
            g.fill(px0, floor, px0 + pw, floor + 2, rim);
            // a few blocky bumps on the floor
            java.util.Random r = new java.util.Random(seed * 7);
            for (int x = 0; x < pw; x += DiveCourse.STEP) {
                int h = r.nextInt(3) * 4;
                if (h > 0) g.fill(px0 + x, floor - h, px0 + x + DiveCourse.STEP, floor, body);
            }
        }
    }

    private void drawItems(GuiGraphics g) {
        for (int i = 0; i < stacks.size(); i++) {
            if (i == caught) continue;
            float[] p = pos.get(i);
            int y = sy(p[1]);
            if (y < py0 - 20 || y > py0 + ph + 20) continue;
            int x = sx(itemX(i, time));
            g.pose().pushPose();
            if (swimsRight(i, time)) {
                // Turned round (a half turn about the vertical, so it stays lit and drawn the right way round).
                g.pose().translate(x, y, 300);
                g.pose().scale(-1.5F, 1.5F, -1F);
            } else {
                g.pose().translate(x, y, 0);
                g.pose().scale(1.5F, 1.5F, 1F);
            }
            g.renderItem(stacks.get(i), -8, -8);
            g.pose().popPose();
        }
    }

    /** Darkness by depth, lighter in two steps round the hook. */
    private void drawDark(GuiGraphics g) {
        float f = Mth.clamp(depth / DiveCourse.DEPTH, 0, 1);
        int a = (int) (Math.pow(f, 1.1) * 190);
        if (a <= 2) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        int hx = sx(hookX), hy = hookScreenY;
        int x0 = px0, y0 = py0, x1 = px0 + pw, y1 = py0 + ph;
        fillExcept(g, x0, y0, x1, y1, hx - 44, hy - 44, hx + 44, hy + 44, a << 24);
        fillExcept(g, hx - 44, hy - 44, hx + 44, hy + 44, hx - 24, hy - 24, hx + 24, hy + 24, (a * 6 / 10) << 24);
        g.fill(hx - 24, hy - 24, hx + 24, hy + 24, (a / 4) << 24);
        g.pose().popPose();
    }

    private static void fillExcept(GuiGraphics g, int x0, int y0, int x1, int y1, int bx0, int by0, int bx1, int by1, int c) {
        g.fill(x0, y0, x1, by0, c);
        g.fill(x0, by1, x1, y1, c);
        g.fill(x0, Math.max(y0, by0), bx0, Math.min(y1, by1), c);
        g.fill(bx1, Math.max(y0, by0), x1, Math.min(y1, by1), c);
    }

    private void drawHook(GuiGraphics g) {
        if (state == State.CATCH) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 250);
        int hx = sx(hookX), hy = hookScreenY;
        // The line: a loose curve from the top of the view to the hook's eye, drawn in single pixels.
        float x0 = sx(lineTop), y0 = py0, x2 = hx, y2 = hy - 7;
        float midX = (x0 + x2) / 2F + bend - vx * 0.25F, midY = (y0 + y2) / 2F;
        int steps = Math.max(24, (int) ((y2 - y0) / 2));
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps, a = (1 - t) * (1 - t), b = 2 * (1 - t) * t, c = t * t;
            int lx = Math.round(a * x0 + b * midX + c * x2), ly = Math.round(a * y0 + b * midY + c * y2);
            g.fill(lx, ly, lx + 1, ly + 1, 0xCCDDDDDD);
        }
        // The hook hangs along the line's last stretch, swinging smoothly.
        float tx = x2 - midX, ty = y2 - midY;
        float want = Mth.clamp((float) -Math.atan2(tx, Math.max(4F, ty)), -1.0F, 1.0F);
        hookAngle += (want - hookAngle) * 0.25F;
        g.pose().pushPose();
        g.pose().translate(x2, y2, 0);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotation(hookAngle));
        g.pose().translate(-x2, -y2, 0);
        // The hook sprite (16x16), its eye at the line's end.
        g.pose().translate(x2 - 7.5F, y2 - 1.5F, 0);
        g.blit(HOOK, 0, 0, 0F, 0F, 16, 16, 16, 16);
        g.pose().popPose();
        if (state == State.PLAY) {
            // Slow: two chevrons above the hook pointing up.
            if (sink < 1F) {
                for (int k = 0; k < 2; k++) chevron(g, hx, hy - 22 - k * 6, 0, -1, 0xCC7CC8FF);
            }
            // Sideways: three chevrons pointing the way the hook is pulled, spreading apart and warming in colour
            // the harder the pull (red when the mouse is far enough to jig). They flash darker while a jig carries
            // the hook faster than drift speed, then settle back.
            int dirX = 0;
            float k = 0;
            boolean far = false;
            if (dragging) {
                float dx = (float) (mouseXNow - hx);
                if (Math.abs(dx) > 2) {
                    dirX = dx > 0 ? 1 : -1;
                    k = Math.min(1F, Math.abs(dx) / DRAG_RANGE);
                    far = Math.abs(dx) > JIG_RANGE;
                }
            } else if (left != right) {
                dirX = right ? 1 : -1;
                k = 1F;
            }
            boolean fast = Math.abs(vx) > DRIFT_V + 1;
            if (dirX == 0 && fast) { // a tap-jig with nothing held: show its direction while it lasts
                dirX = vx > 0 ? 1 : -1;
                k = 1F;
            }
            if (dirX != 0) {
                int c = far ? 0xFFFF5A4A : k < 0.5F ? lerp(0xFF7CFC6A, 0xFFFFE25A, k * 2) : lerp(0xFFFFE25A, 0xFFFF8A4A, (k - 0.5F) * 2);
                if (fast) {
                    float dark = Mth.clamp((Math.abs(vx) - DRIFT_V) / (JIG - DRIFT_V), 0F, 1F) * 0.65F;
                    c = lerp(c, 0xFF1A1A1E, dark);
                }
                float gap = 5F + k * 7F + (far ? 3F : 0F);
                for (int n = 0; n < 3; n++) chevron(g, hx + dirX * Math.round(9 + n * gap), hy + 2, dirX, 0, c);
            }
        }
        g.pose().popPose();
    }

    /** A small 2-pixel-thick chevron centred on (x, y), pointing along (dx, dy) (one of them 0). */
    private static void chevron(GuiGraphics g, int x, int y, int dx, int dy, int c) {
        for (int i = 0; i < 3; i++) {
            if (dx != 0) { // > or <
                int px = x + dx * (i - 1);
                g.fill(px - 1, y - 3 + i, px + 1, y - 2 + i, c);
                g.fill(px - 1, y + 2 - i, px + 1, y + 3 - i, c);
            } else {       // v or ^
                int py = y + dy * (i - 1);
                g.fill(x - 3 + i, py - 1, x - 2 + i, py + 1, c);
                g.fill(x + 2 - i, py - 1, x + 3 - i, py + 1, c);
            }
        }
    }

    /** The catch (or nothing) riding a wave up, then everything sliding away down. */
    private void drawEnd(GuiGraphics g) {
        boolean caughtSomething = state == State.CATCH;
        float t = animTime;
        float rise = caughtSomething ? 0.35F : 0.3F, hold = caughtSomething ? 0.85F : 0.5F, out = caughtSomething ? 0.55F : 0.45F;
        float p = easeOut(Mth.clamp(t / rise, 0, 1));
        float drop = t > hold ? easeIn(Mth.clamp((t - hold) / out, 0, 1)) * (ph + 90) : 0;
        int waveTop = Math.round(py0 + ph - p * ph * 0.62F + drop);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        drawWave(g, waveTop, t);
        if (caughtSomething && caught >= 0) {
            float ip = easeOutBack(Mth.clamp((t - 0.1F) / 0.45F, 0, 1));
            int targetY = py0 + Math.round(ph * 0.4F);
            int startY = py0 + ph + 40;
            int iy = Math.round(startY + (targetY - startY) * ip + drop + Mth.sin(t * 6) * 2);
            int ix = px0 + pw / 2;
            // splashes as it breaks the surface
            if (t > 0.4F) {
                Random r = new Random(seed);
                float st = t - 0.4F;
                for (int k = 0; k < 16; k++) {
                    float vxs = (r.nextFloat() - 0.5F) * 140, vys = 90 + r.nextFloat() * 90;
                    int x = Math.round(ix + vxs * st), y = Math.round(targetY + 10 - vys * st + 260 * st * st + drop);
                    if (y > py0 + ph) continue;
                    g.fill(x, y, x + 3, y + 3, k % 3 == 0 ? theme.light : theme.foam);
                }
            }
            g.pose().pushPose();
            g.pose().translate(ix - 24, iy - 24, 0);
            g.pose().scale(3F, 3F, 1F);
            g.renderItem(stacks.get(caught), 0, 0);
            g.pose().popPose();
        }
        g.pose().popPose();
    }

    private void drawWave(GuiGraphics g, int top, float t) {
        int bottom = py0 + ph;
        if (top >= bottom) return;
        for (int x = 0; x < pw; x += 4) {
            int back = Math.round((Mth.sin(x * 0.07F + t * 4F) * 4F + Mth.sin(x * 0.13F - t * 3F) * 2F) / 2F) * 2;
            int front = Math.round((Mth.sin(x * 0.05F - t * 5F) * 4F + Mth.sin(x * 0.11F + t * 2F) * 2F) / 2F) * 2;
            int xs = px0 + x;
            g.fill(xs, top - 10 + back, xs + 4, top + 4, theme.deep);
            g.fill(xs, top + front, xs + 4, bottom, theme.mid);
            g.fill(xs, top + front, xs + 4, top + front + 4, theme.light);
            if (((x / 4) + (int) (t * 8)) % 7 == 0) g.fill(xs, top + front + 10, xs + 4, top + front + 12, theme.light);
            g.fill(xs, top + front - 2, xs + 4, top + front, theme.foam);
        }
    }

    private void drawFrame(GuiGraphics g) {
        int x0 = px0, y0 = py0, x1 = px0 + pw, y1 = py0 + ph;
        g.fill(x0 - 4, y0 - 4, x1 + 4, y0, 0xFF1E1E24);
        g.fill(x0 - 4, y1, x1 + 4, y1 + 4, 0xFF1E1E24);
        g.fill(x0 - 4, y0, x0, y1, 0xFF1E1E24);
        g.fill(x1, y0, x1 + 4, y1, 0xFF1E1E24);
        g.fill(x0 - 2, y0 - 2, x1 + 2, y0 - 1, 0xFF5A5A66);
        g.fill(x0 - 2, y1 + 1, x1 + 2, y1 + 2, 0xFF5A5A66);
        g.fill(x0 - 2, y0 - 1, x0 - 1, y1 + 1, 0xFF5A5A66);
        g.fill(x1 + 1, y0 - 1, x1 + 2, y1 + 1, 0xFF5A5A66);
        // Depth gauge on the right.
        int gx = x1 + 8;
        g.fill(gx, y0, gx + 4, y1, 0xFF1E1E24);
        float f = Mth.clamp(depth / DiveCourse.DEPTH, 0, 1);
        g.fill(gx, y0, gx + 4, y0 + Math.round(f * ph), theme.light);
        int my = y0 + Math.round(f * ph);
        g.fill(gx - 2, my - 1, gx + 6, my + 1, 0xFFFFFFFF);
        int metres = Math.max(0, Math.round(depth / 16F));
        g.drawCenteredString(this.font, Component.translatable("salvagefishing.depth", metres), px0 + pw / 2, y0 - 14, 0xFFFFFF);
        g.drawCenteredString(this.font, Component.translatable("salvagefishing.hint"), px0 + pw / 2, y1 + 7, 0xAAAAAA);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static float easeOut(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    private static float easeIn(float t) {
        return t * t * t;
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158F, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
    }

    static int lerp(int a, int b, float t) {
        t = Mth.clamp(t, 0, 1);
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    /** Colours for each kind of liquid; a pool can set any of them (see DivePools). */
    private record Theme(boolean lava, int sky, int top, int mid, int bottom, int foam, int light, int deep, int bubble) {
        static Theme of(String name, int[] custom) {
            boolean lava = "lava".equals(name);
            int[] c = lava
                    ? new int[]{0xFF2A0E08, 0xFFF07A1E, 0xFFA8360C, 0xFF2A0A04, 0xFFFFF0A0, 0xFFFFB238, 0xFFB8400C, 0xCCFFC850}
                    : new int[]{0xFF9CC8F5, 0xFF3F76E4, 0xFF173A8A, 0xFF040A1A, 0xFFEAF6FF, 0xFF6FA8F0, 0xFF2A55B8, 0x99D8ECFF};
            for (int i = 0; i < c.length && custom != null && i < custom.length; i++) if (custom[i] != 0) c[i] = custom[i];
            return new Theme(lava, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
        }
    }
}

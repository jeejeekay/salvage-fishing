package IGoByJake.salvagefishing;

import java.util.Random;

/**
 * The shape of one dive, the same on server and client from the same seed: the rock walls on either side, row by
 * row. Units are GUI pixels of the shaft; x = 0 is the middle, y grows downward from the surface.
 */
public final class DiveCourse {
    /** Shaft width. */
    public static final int WIDTH = 160;
    /** How deep the line goes. */
    public static final int DEPTH = 1400;
    /** How fast the hook sinks, per second. */
    public static final float SPEED = 47.5F;
    /** Wall rows are this tall: blocky, like the game. */
    public static final int ROW = 16;
    /** Walls step sideways in blocks this wide. */
    public static final int STEP = 8;
    /** Narrowest gap the walls ever leave. */
    public static final int MIN_GAP = 56;

    private final float[] left, right;
    private final boolean[] shelfTopRow;
    private final java.util.List<Shelf> shelves = new java.util.ArrayList<>();

    /**
     * A flat shelf of rock jutting out from one wall, for treasure to rest on. side -1 = left wall, 1 = right wall.
     * {@code base} is where the wall is just above the shelf, {@code tip} where the shelf ends; the shelf's flat
     * top is at depth {@code top}, between the two.
     */
    public record Shelf(float top, int side, float base, float tip) {
        /** Room on the shelf for something 24 wide to sit. */
        public boolean roomy() {
            return Math.abs(tip - base) >= 28;
        }

        /** How many things fit on it side by side (each 24 wide). */
        public int room() {
            float w = Math.abs(tip - base);
            return w >= 60 ? 2 : w >= 28 ? 1 : 0;
        }
    }

    public DiveCourse(long seed) {
        Random r = new Random(seed);
        int rows = DEPTH / ROW + 80;
        left = new float[rows];
        right = new float[rows];
        float[] centre = noise(r, rows), width = noise(r, rows);
        float[] ledgeL = new float[rows], ledgeR = new float[rows];
        // Shelves first (rows, side, how far out, how thick), so the pointed ledges keep clear of them.
        java.util.List<int[]> plan = new java.util.ArrayList<>();
        int sy = 240 + r.nextInt(80);
        while (sy < DEPTH - 60) {
            plan.add(new int[]{sy / ROW, r.nextBoolean() ? -1 : 1, (4 + r.nextInt(4)) * STEP, 1 + r.nextInt(2)});
            sy += 110 + r.nextInt(90);
        }
        // Ledges that jut in from one side.
        int y = 150 + r.nextInt(60);
        while (y < DEPTH + 200) {
            float frac = Math.min(1F, y / (float) DEPTH);
            int len = (2 + r.nextInt(4)) * ROW;
            float depth = 24 + r.nextInt(24) + frac * 24;
            float[] side = r.nextBoolean() ? ledgeL : ledgeR;
            for (int i = 0; i * ROW < len; i++) {
                int row = y / ROW + i;
                if (row >= rows) break;
                float t = (i + 0.5F) * ROW / len;
                float shape = 1F - (float) Math.pow(Math.abs(2F * t - 1F), 1.6F);
                side[row] = Math.max(side[row], Math.round(depth * shape / STEP) * STEP);
            }
            y += 90 + r.nextInt(110) - (int) (frac * 30);
        }
        for (int[] sh : plan) { // keep pointed ledges away from shelves
            for (int k = Math.max(0, sh[0] - 4); k <= Math.min(rows - 1, sh[0] + sh[3] + 1); k++) {
                ledgeL[k] = 0;
                ledgeR[k] = 0;
            }
        }
        float edge = WIDTH / 2F - 4F;
        for (int i = 0; i < rows; i++) {
            float wy = i * ROW;
            float frac = Math.min(1F, wy / DEPTH);
            float open = wy < 80 ? (80 - wy) / 80F * 30F : 0F; // wide open at the top
            float half = 62F - 14F * frac + width[i] * 14F + open;
            float c = centre[i] * 24F * Math.min(1F, wy / 120F);
            float l = Math.max(-edge, c - half), rr = Math.min(edge, c + half);
            l += ledgeL[i];
            rr -= ledgeR[i];
            if (rr - l < MIN_GAP) { // never shut: pull the ledges back
                float mid = (l + rr) / 2F;
                l = mid - MIN_GAP / 2F;
                rr = mid + MIN_GAP / 2F;
                if (l < -edge) { rr += -edge - l; l = -edge; }
                if (rr > edge) { l -= rr - edge; rr = edge; }
            }
            left[i] = (float) Math.ceil(l / STEP) * STEP;
            right[i] = (float) Math.floor(rr / STEP) * STEP;
        }
        // Shelves: a flat block of rock out from the wall, flat on top.
        shelfTopRow = new boolean[rows];
        for (int[] sh : plan) {
            int r0 = sh[0], side = sh[1], out = sh[2], h = sh[3];
            if (r0 < 2 || r0 + h >= rows) continue;
            float base = side < 0 ? Math.max(left[r0 - 1], left[r0 - 2]) : Math.min(right[r0 - 1], right[r0 - 2]);
            float tip = base - side * out;
            for (int k = r0; k < r0 + h; k++) {
                if (side < 0) left[k] = Math.max(left[k], Math.min(tip, right[k] - MIN_GAP));
                else right[k] = Math.min(right[k], Math.max(tip, left[k] + MIN_GAP));
                left[k] = (float) Math.ceil(left[k] / STEP) * STEP;
                right[k] = (float) Math.floor(right[k] / STEP) * STEP;
            }
            float realTip = side < 0 ? left[r0] : right[r0];
            shelfTopRow[r0] = true;
            shelves.add(new Shelf(r0 * ROW, side, base, realTip));
        }
    }

    /** Where the floor treasure rests: the middle of the gap just above the floor. */
    public float floorX() {
        float y = DEPTH - ROW / 2F;
        return Math.round((left(y) + right(y)) / 2F / STEP) * STEP;
    }

    public java.util.List<Shelf> shelves() {
        return shelves;
    }

    /** True for the top row of a shelf (drawn with a lighter top). */
    public boolean isShelfTop(float y) {
        return shelfTopRow[row(y)];
    }

    /** Smooth values in -1..1, one per row: random points every 8 rows, eased between. */
    private static float[] noise(Random r, int rows) {
        int step = 5;
        float[] pts = new float[rows / step + 2];
        for (int i = 0; i < pts.length; i++) pts[i] = r.nextFloat() * 2F - 1F;
        float[] out = new float[rows];
        for (int i = 0; i < rows; i++) {
            int a = i / step;
            float t = (i % step) / (float) step;
            t = t * t * (3 - 2 * t);
            out[i] = pts[a] + (pts[a + 1] - pts[a]) * t;
        }
        return out;
    }

    private int row(float y) {
        int i = (int) Math.floor(y / ROW);
        return Math.max(0, Math.min(left.length - 1, i));
    }

    public float left(float y) {
        return left[row(y)];
    }

    public float right(float y) {
        return right[row(y)];
    }

    /** The narrowest the shaft gets between two depths. */
    public float leftMax(float y0, float y1) {
        float m = -WIDTH;
        for (int i = row(y0); i <= row(y1); i++) m = Math.max(m, left[i]);
        return m;
    }

    public float rightMin(float y0, float y1) {
        float m = WIDTH;
        for (int i = row(y0); i <= row(y1); i++) m = Math.min(m, right[i]);
        return m;
    }
}

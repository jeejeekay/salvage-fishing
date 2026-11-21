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
    public static final float SPEED = 38F;
    /** Wall rows are this tall: blocky, like the game. */
    public static final int ROW = 8;
    /** Narrowest gap the walls ever leave. */
    public static final int MIN_GAP = 44;

    private final float[] left, right;

    public DiveCourse(long seed) {
        Random r = new Random(seed);
        int rows = DEPTH / ROW + 80;
        left = new float[rows];
        right = new float[rows];
        float[] centre = noise(r, rows), width = noise(r, rows);
        float[] ledgeL = new float[rows], ledgeR = new float[rows];
        // Ledges that jut in from one side.
        int y = 150 + r.nextInt(60);
        while (y < DEPTH + 200) {
            float frac = Math.min(1F, y / (float) DEPTH);
            int len = 24 + r.nextInt(36);
            float depth = 18 + r.nextInt(16) + frac * 22;
            float[] side = r.nextBoolean() ? ledgeL : ledgeR;
            for (int i = 0; i * ROW < len; i++) {
                int row = y / ROW + i;
                if (row >= rows) break;
                float t = (i + 0.5F) * ROW / len;
                float shape = 1F - (float) Math.pow(Math.abs(2F * t - 1F), 1.6F);
                side[row] = Math.max(side[row], Math.round(depth * shape / 4F) * 4F);
            }
            y += 100 + r.nextInt(90) - (int) (frac * 30);
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
            left[i] = Math.round(l / 2F) * 2F;
            right[i] = Math.round(rr / 2F) * 2F;
        }
    }

    /** Smooth values in -1..1, one per row: random points every 8 rows, eased between. */
    private static float[] noise(Random r, int rows) {
        int step = 8;
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

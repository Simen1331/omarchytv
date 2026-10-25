package com.omarchytv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * Native re-creation of Omarchy's screensaver (bin/omarchy-screensaver), which loops
 * `ttfx -i screensaver.txt --random-effect` over the ASCII logo in a black fullscreen terminal.
 * Here: the pixel "OMARCHY TV" logo (logo.txt + matching TV; or plain logo.txt / the original wordmark,
 * see config.xml "logo") on a
 * character grid, a random terminal-text-effect intro each cycle (decrypt, rain, beams, print, slide,
 * expand, matrix, wipe, burn; never the same twice in a row), a hold with a slowly cycling Tokyo Night gradient, then a dissolve.
 *
 * Burn-in protection: every cycle the art moves to a new random spot, it drifts by a few pixels during
 * the hold, the gradient keeps shifting, and the dream runs with the screen dimmed.
 * Cost: one View, rects and a few drawText calls; ~30 fps only while an effect runs (~2.5 s per
 * cycle), 4 fps during the hold.
 */
public class AsciiSaverView extends View {

    private static final int FX_DECRYPT = 0, FX_RAIN = 1, FX_BEAMS = 2, FX_PRINT = 3, FX_SLIDE = 4, FX_EXPAND = 5,
            FX_MATRIX = 6, FX_WIPE = 7, FX_BURN = 8;
    static final String[] FX_NAMES = {"decrypt", "rain", "beams", "print", "slide", "expand", "matrix", "wipe", "burn"};
    private static final long IN_MS = 1800, HOLD_MS = 5000, OUT_MS = 700;   // v2.10: faster rotation (TV test)
    /** Gradient stops from the synced Omarchy theme, cycled across columns: accent, accent2, cyan (Tokyo Night defaults). */
    private final int[] stops;
    private static final int LUT = 96;
    private final int[] gradient = new int[LUT];

    private final Random random = new Random();
    private final Paint block = new Paint();
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DateFormat timeFormat;

    // Art as cells.
    private int cols, rows;
    private int[] cellRow, cellCol;
    private char[] cellChar;
    private float[] seed;          // per-cell random in [0,1), re-rolled each cycle
    private float[] seed2;

    private float cellW, cellH, originX, originY, driftX, driftY;
    private int effect = FX_DECRYPT;
    private long cycleStart = -1;
    private boolean running;
    private boolean showClock = true;
    private float widthFraction = 0.68f;
    /** Fixed time (ms into the cycle) for deterministic preview rendering; -1 = live. */
    private long frozenAt = -1;

    public AsciiSaverView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LauncherConfig theme = LauncherConfig.get(context);
        stops = new int[]{theme.accent, theme.accent2, theme.theme.cyan};
        for (int i = 0; i < LUT; i++) {
            float t = i / (float) LUT * stops.length;
            int a = stops[(int) t % stops.length], b = stops[((int) t + 1) % stops.length];
            gradient[i] = lerpColor(a, b, t - (int) t);
        }
        Typeface tf;
        try {
            tf = androidx.core.content.res.ResourcesCompat.getFont(getContext(), R.font.caskaydia);
        } catch (Exception e) {
            tf = Typeface.MONOSPACE;
        }
        glyph.setTypeface(tf);
        glyph.setTextAlign(Paint.Align.CENTER);
        clock.setTypeface(tf);
        clock.setTextAlign(Paint.Align.CENTER);
        clock.setLetterSpacing(0.06f);
        clock.setColor(theme.theme.fg);   // theme foreground
        timeFormat = android.text.format.DateFormat.getTimeFormat(context);
        loadArt(Art.load(context, LauncherConfig.get(context).screensaverArt));
    }

    void setShowClock(boolean show) {
        showClock = show;
    }

    /**
     * Clock text size as a share of the art height: digits about 30% of the wordmark's height
     * (cap height ~0.73 of the text size). Tall custom art is capped at the size an 8-row logo would get.
     */
    private float clockK() {
        return 0.41f * Math.min(1f, 8f / Math.max(1, rows));
    }

    /** Rows of cells reserved under the art for the clock (gap + one line of clock text). */
    private float clockRows() {
        return showClock ? 1.6f + 1.15f * clockK() * rows : 0f;
    }

    /** Logo width as a share of the screen width (0.45 small, 0.56 medium, 0.68 large). */
    void setWidthFraction(float f) {
        widthFraction = Math.max(0.2f, Math.min(0.85f, f));
        if (getWidth() > 0) onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
    }

    /** For previews: render one effect at a fixed time into the cycle instead of animating. */
    void freeze(int fx, long atMs, long seedValue) {
        random.setSeed(seedValue);
        effect = fx;
        frozenAt = atMs;
        newCycle(false);
        invalidate();
    }

    void start() {
        running = true;
        frozenAt = -1;
        newCycle(true);
        invalidate();
    }

    void stop() {
        running = false;
    }

    // ---- art ------------------------------------------------------------------------------------

    private void loadArt(String text) {
        String[] lines = Art.lines(text);
        int first = 0, last = lines.length - 1;
        List<int[]> cells = new ArrayList<>();
        StringBuilder chars = new StringBuilder();
        cols = 0;
        rows = Math.max(0, last - first + 1);
        for (int r = first; r <= last; r++) {
            String l = lines[r];
            cols = Math.max(cols, l.length());
            for (int c = 0; c < l.length(); c++) {
                char ch = l.charAt(c);
                if (ch == ' ' || ch == '\t') continue;
                cells.add(new int[]{r - first, c});
                chars.append(ch);
            }
        }
        int n = cells.size();
        cellRow = new int[n];
        cellCol = new int[n];
        cellChar = chars.toString().toCharArray();
        seed = new float[n];
        seed2 = new float[n];
        for (int i = 0; i < n; i++) {
            cellRow[i] = cells.get(i)[0];
            cellCol[i] = cells.get(i)[1];
        }
    }

    // ---- cycle ----------------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Sized from the real surface: the art spans widthFraction of the width (68% by default, about
        // 83 cm on a 55" set), cells are 1:2 like a terminal; shrink if the height would not fit.
        cellW = Math.max(4f, w * widthFraction / Math.max(1, cols));
        float maxH = h * 0.8f / (rows + clockRows()) / 2f;
        cellW = Math.min(cellW, maxH);
        cellH = cellW * 2f;
        glyph.setTextSize(cellH * 0.8f);
        clock.setTextSize(Math.max(clockK() * rows * cellH, 14f));
        placeArt();
    }

    private float salt;

    private void newCycle(boolean pickEffect) {
        if (pickEffect) {
            int next = random.nextInt(FX_NAMES.length - 1);
            effect = next >= effect ? next + 1 : next;   // rotate: never repeat the previous effect
        }
        salt = random.nextFloat() * 100f;
        for (int i = 0; i < seed.length; i++) {
            seed[i] = random.nextFloat();
            seed2[i] = random.nextFloat();
        }
        cycleStart = SystemClock.uptimeMillis();
        placeArt();
    }

    /** A random spot that keeps the art (and clock line) fully on screen, away from the edges. */
    private void placeArt() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        // Keep a margin of one cell plus the maximum drift (0.6 cell) on every side so it never clips.
        float margin = cellW * 1.6f;
        float artW = cols * cellW, artH = (rows + clockRows()) * cellH;
        float freeX = Math.max(0, w - artW - 2 * margin), freeY = Math.max(0, h - artH - 2 * margin);
        originX = margin + freeX * random.nextFloat();
        originY = margin + freeY * random.nextFloat();
        float ang = (float) (random.nextFloat() * Math.PI * 2);
        driftX = (float) Math.cos(ang);
        driftY = (float) Math.sin(ang);
    }

    // ---- drawing --------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        if (cellRow == null || cellRow.length == 0 || getWidth() == 0) return;
        long now = SystemClock.uptimeMillis();
        String spd = LauncherConfig.get(getContext()).screensaverSpeed;
        float mult = "slow".equals(spd) ? 0.6f : "fast".equals(spd) ? 1.75f : 1.0f;
        long t = frozenAt >= 0 ? frozenAt : Math.round((now - cycleStart) * mult);
        long total = IN_MS + HOLD_MS + OUT_MS;
        if (frozenAt < 0 && t >= total) {
            newCycle(true);
            t = 0;
        }
        float phase = (now % 24000L) / 24000f;               // gradient cycles every 24 s
        if (frozenAt >= 0) phase = 0.1f;
        // Slow drift during the hold: up to ~0.6 cell over the hold.
        float hold = Math.max(0f, Math.min(1f, (t - IN_MS) / (float) HOLD_MS));
        float ox = originX + driftX * hold * cellW * 0.6f;
        float oy = originY + driftY * hold * cellW * 0.6f;

        float pIn = Math.min(1f, t / (float) IN_MS);
        float pOut = t > IN_MS + HOLD_MS ? Math.min(1f, (t - IN_MS - HOLD_MS) / (float) OUT_MS) : 0f;

        int n = cellRow.length;
        for (int i = 0; i < n; i++) {
            int r = cellRow[i], c = cellCol[i];
            float x = ox + c * cellW, y = oy + r * cellH;
            int finalColor = gradient[(int) (((c / (float) cols) * 0.8f + phase) * LUT) % LUT];
            if (pOut > 0f) {
                // Dissolve: each cell flickers to a glyph then vanishes at its own moment.
                float die = seed[i];
                if (pOut > die) continue;
                if (pOut > die - 0.12f) {
                    drawGlyph(canvas, x, y, Theme.m(0xFF565F89));
                    continue;
                }
                drawCell(canvas, cellChar[i], x, y, finalColor);
                continue;
            }
            if (pIn >= 1f) {
                drawCell(canvas, cellChar[i], x, y, finalColor);
                continue;
            }
            drawIntro(canvas, i, r, c, x, y, pIn, finalColor);
        }

        if (showClock) {
            String time = timeFormat.format(new Date());
            float cy = oy + (rows + 1.6f) * cellH + clock.getTextSize() * 0.80f;
            int a = (int) (255 * Math.min(1f, pIn * 1.5f) * (1f - pOut));
            clock.setAlpha(a);
            canvas.drawText(time, ox + cols * cellW / 2f, cy, clock);
        }

        if (frozenAt >= 0 || !running) return;
        boolean animating = t < IN_MS || t > IN_MS + HOLD_MS;
        postInvalidateDelayed(animating ? 33 : 250);
    }

    private void drawIntro(Canvas canvas, int i, int r, int c, float x, float y, float p, int finalColor) {
        float s = seed[i];
        switch (effect) {
            case FX_DECRYPT: {
                // Cells appear as cycling glyphs, then lock into the logo in random order.
                float appear = s * 0.35f, lock = 0.45f + 0.5f * seed2[i];
                if (p < appear) return;
                if (p < lock) {
                    drawGlyph(canvas, x, y, (seed2[i] > 0.5f ? Theme.m(0xFF565F89) : Theme.m(0xFF414868)));
                } else {
                    drawCell(canvas, cellChar[i], x, y, lerpColor(Theme.m(0xFFC0CAF5), finalColor, ease((p - lock) * 4f)));
                }
                return;
            }
            case FX_RAIN: {
                // Each cell falls from above the screen and lands with a short cyan flash.
                float start = s * 0.65f, dur = 0.3f;
                if (p < start) return;
                float q = Math.min(1f, (p - start) / dur);
                float fromY = -cellH * (2 + seed2[i] * 6) - (y - getTop());
                float yy = y + fromY * (1f - easeIn(q));
                int col = q < 1f ? Theme.m(0xFF7DCFFF) : lerpColor(0xFFFFFFFF, finalColor, Math.min(1f, (p - start - dur) * 6f));
                drawCell(canvas, cellChar[i], x, yy, col);
                return;
            }
            case FX_BEAMS: {
                // A beam sweeps each row (alternating direction) and leaves the cells lit behind it.
                float rowDelay = r / (float) Math.max(1, rows) * 0.35f;
                float pos = (p - rowDelay) / 0.5f;
                float cellPos = (r % 2 == 0) ? c / (float) cols : 1f - c / (float) cols;
                float since = pos - cellPos;
                if (since < 0f) return;
                int col = since < 0.06f ? 0xFFFFFFFF : lerpColor(Theme.m(0xFF7DCFFF), finalColor, Math.min(1f, since * 3f));
                drawCell(canvas, cellChar[i], x, y, col);
                return;
            }
            case FX_PRINT: {
                // Typewriter: row by row, a block cursor prints the logo.
                float order = (r * cols + c) / (float) (rows * cols);
                if (p * 1.1f < order) {
                    int cursorRow = Math.min(rows - 1, (int) (p * 1.1f * rows));
                    if (r == cursorRow && Math.abs(p * 1.1f - order) < 1.5f / (rows * cols)) {
                        drawCell(canvas, '\u2588', x, y, Theme.m(0xFFC0CAF5));
                    }
                    return;
                }
                drawCell(canvas, cellChar[i], x, y, lerpColor(Theme.m(0xFFC0CAF5), finalColor, Math.min(1f, (p * 1.1f - order) * 5f)));
                return;
            }
            case FX_SLIDE: {
                // Rows slide in from alternating sides.
                float start = r / (float) Math.max(1, rows) * 0.4f;
                float q = Math.min(1f, Math.max(0f, (p - start) / 0.55f));
                if (q <= 0f) return;
                float dir = r % 2 == 0 ? -1f : 1f;
                float xx = x + dir * getWidth() * (1f - ease(q));
                drawCell(canvas, cellChar[i], xx, y, lerpColor(Theme.m(0xFF414868), finalColor, q));
                return;
            }
            case FX_MATRIX: {
                // Glyph streams fall down each column; the logo is left behind where they pass.
                float delay = hash(c * 12.9898f + salt) * 0.45f;
                float head = (p - delay) / 0.5f * (rows + 4);
                float since = head - r;
                if (since < 0f) return;
                if (since < 1f) drawGlyph(canvas, x, y, Theme.m(0xFFC0CAF5));
                else if (since < 4f) drawGlyph(canvas, x, y, lerpColor(Theme.m(0xFF9ECE6A), Theme.m(0xFF414868), (since - 1f) / 3f));
                else drawCell(canvas, cellChar[i], x, y, lerpColor(Theme.m(0xFF9ECE6A), finalColor, Math.min(1f, (since - 4f) / 3f)));
                return;
            }
            case FX_WIPE: {
                // Diagonal wipe with a bright cyan edge.
                float order = (c / (float) cols + r / (float) Math.max(1, rows)) / 2f;
                float since = p * 1.25f - order;
                if (since < 0f) return;
                int col = since < 0.04f ? Theme.m(0xFF7DCFFF) : lerpColor(Theme.m(0xFFC0CAF5), finalColor, Math.min(1f, since * 5f));
                drawCell(canvas, cellChar[i], x, y, col);
                return;
            }
            case FX_BURN: {
                // The logo smoulders in dim blue, then burns through red, orange and yellow into its colours,
                // starting from the bottom rows.
                float ignite = (1f - r / (float) Math.max(1, rows)) * 0.45f + s * 0.3f;
                float since = p - ignite;
                if (since < 0f) {
                    drawCell(canvas, cellChar[i], x, y, Theme.m(0xFF292E42));
                } else if (since < 0.08f) {
                    drawGlyph(canvas, x, y, lerpColor(Theme.m(0xFFF7768E), Theme.m(0xFFFF9E64), since / 0.08f));
                } else if (since < 0.16f) {
                    drawCell(canvas, cellChar[i], x, y, lerpColor(Theme.m(0xFFE0AF68), finalColor, (since - 0.08f) / 0.08f));
                } else {
                    drawCell(canvas, cellChar[i], x, y, finalColor);
                }
                return;
            }
            case FX_EXPAND:
            default: {
                // Cells burst out of the art's centre to their places.
                float cx = (getWidth() - cols * cellW) / 2f + cols * cellW / 2f, cy = getHeight() / 2f;
                float q = ease(Math.min(1f, Math.max(0f, (p - s * 0.25f) / 0.7f)));
                if (q <= 0f) return;
                float xx = cx + (x - cx) * q, yy = cy + (y - cy) * q;
                if (q < 1f) drawGlyph(canvas, xx, yy, lerpColor(Theme.m(0xFFBB9AF7), finalColor, q));
                else drawCell(canvas, cellChar[i], xx, yy, finalColor);
            }
        }
    }

    /** Block characters are drawn as exact rects so they tile seamlessly; anything else as text. */
    private void drawCell(Canvas canvas, char ch, float x, float y, int color) {
        block.setColor(color);
        switch (ch) {
            case '\u2588': canvas.drawRect(x, y, x + cellW + 0.5f, y + cellH + 0.5f, block); break;   // █
            case '\u2584': canvas.drawRect(x, y + cellH / 2f, x + cellW + 0.5f, y + cellH + 0.5f, block); break;   // ▄
            case '\u2580': canvas.drawRect(x, y, x + cellW + 0.5f, y + cellH / 2f + 0.5f, block); break;   // ▀
            case '\u258C': canvas.drawRect(x, y, x + cellW / 2f + 0.5f, y + cellH + 0.5f, block); break;   // ▌
            case '\u2590': canvas.drawRect(x + cellW / 2f, y, x + cellW + 0.5f, y + cellH + 0.5f, block); break;   // ▐
            default:
                glyph.setColor(color);
                canvas.drawText(String.valueOf(ch), x + cellW / 2f, y + cellH * 0.78f, glyph);
        }
    }

    private void drawGlyph(Canvas canvas, float x, float y, int color) {
        glyph.setColor(color);
        char g = TextFx.GLYPHS.charAt(random.nextInt(TextFx.GLYPHS.length()));
        canvas.drawText(String.valueOf(g), x + cellW / 2f, y + cellH * 0.78f, glyph);
    }

    private static float hash(float v) {
        double h = Math.sin(v) * 43758.5453;
        return (float) (h - Math.floor(h));
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    private static float easeIn(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t;
    }

    static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }
}

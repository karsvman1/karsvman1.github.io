package nl.japandi.dashboard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-drawn 5x7 proportional pixel font (original design, no third-party glyphs), pure Java
 * so measuring and wrapping can be unit-tested on a normal JVM.
 *
 * Coordinates are in font pixels; the view multiplies them by a scale. Row 0 is the top of a
 * capital, row 6 sits on the baseline, rows 7-8 are descenders, rows -2..-1 hold accents on
 * capitals. Accented letters are composed from the base glyph plus a mark (via Unicode NFD),
 * so é, ë, ï, ü, ç and similar need no separate glyph. Characters the font does not know are
 * reported by {@link #has} so the caller can fall back to a system font for them.
 */
final class PixelFont {
    /** Rows from the top of a capital to the baseline (exclusive of descenders). */
    static final int CAP = 7;
    /** Recommended line height in font pixels: accents (2) + cap (7) + descender (2) + gap (1). */
    static final int LINE = 12;
    static final int SPACING = 1;

    /** A glyph as horizontal runs per row: runs[r] = {x0, len, x1, len, ...}, row offset by top. */
    static final class Glyph {
        final int width, top;
        final int[][] runs;

        Glyph(int width, int top, int[][] runs) {
            this.width = width;
            this.top = top;
            this.runs = runs;
        }
    }

    /** Receives one filled horizontal run of font pixels: x, y (row, may be negative), length. */
    interface RunSink {
        void run(int x, int y, int len);
    }

    private static final Map<Character, Glyph> GLYPHS = new HashMap<Character, Glyph>();
    private static final Map<Character, Glyph> MARKS = new HashMap<Character, Glyph>();

    private PixelFont() {
    }

    private static Glyph parse(String rows, int top) {
        String[] r = rows.trim().split("\\s+");
        int w = r[0].length();
        int[][] runs = new int[r.length][];
        for (int y = 0; y < r.length; y++) {
            if (r[y].length() != w) throw new IllegalStateException("ragged glyph: " + rows);
            List<Integer> l = new ArrayList<Integer>();
            int x = 0;
            while (x < w) {  // bounded by the row width
                if (r[y].charAt(x) == '#') {
                    int s = x;
                    while (x < w && r[y].charAt(x) == '#') x++;
                    l.add(s);
                    l.add(x - s);
                } else {
                    x++;
                }
            }
            runs[y] = new int[l.size()];
            for (int i = 0; i < l.size(); i++) runs[y][i] = l.get(i);
        }
        return new Glyph(w, top, runs);
    }

    private static void g(char c, String rows) {
        GLYPHS.put(c, parse(rows, 0));
    }

    private static void mark(char combining, String rows, int top) {
        MARKS.put(combining, parse(rows, top));
    }

    static {
        g(' ', "... ... ... ... ... ... ...");
        g('0', ".###. #...# #..## #.#.# ##..# #...# .###.");
        g('1', "..#.. .##.. ..#.. ..#.. ..#.. ..#.. .###.");
        g('2', ".###. #...# ....# ...#. ..#.. .#... #####");
        g('3', "##### ...#. ..#.. ...#. ....# #...# .###.");
        g('4', "...#. ..##. .#.#. #..#. ##### ...#. ...#.");
        g('5', "##### #.... ####. ....# ....# #...# .###.");
        g('6', "..##. .#... #.... ####. #...# #...# .###.");
        g('7', "##### ....# ...#. ..#.. .#... .#... .#...");
        g('8', ".###. #...# #...# .###. #...# #...# .###.");
        g('9', ".###. #...# #...# .#### ....# ...#. .##..");

        g('A', ".###. #...# #...# ##### #...# #...# #...#");
        g('B', "####. #...# #...# ####. #...# #...# ####.");
        g('C', ".###. #...# #.... #.... #.... #...# .###.");
        g('D', "###.. #..#. #...# #...# #...# #..#. ###..");
        g('E', "##### #.... #.... ####. #.... #.... #####");
        g('F', "##### #.... #.... ####. #.... #.... #....");
        g('G', ".###. #...# #.... #.### #...# #...# .####");
        g('H', "#...# #...# #...# ##### #...# #...# #...#");
        g('I', "### .#. .#. .#. .#. .#. ###");
        g('J', "..### ...#. ...#. ...#. ...#. #..#. .##..");
        g('K', "#...# #..#. #.#.. ##... #.#.. #..#. #...#");
        g('L', "#.... #.... #.... #.... #.... #.... #####");
        g('M', "#...# ##.## #.#.# #.#.# #...# #...# #...#");
        g('N', "#...# #...# ##..# #.#.# #..## #...# #...#");
        g('O', ".###. #...# #...# #...# #...# #...# .###.");
        g('P', "####. #...# #...# ####. #.... #.... #....");
        g('Q', ".###. #...# #...# #...# #.#.# #..#. .##.#");
        g('R', "####. #...# #...# ####. #.#.. #..#. #...#");
        g('S', ".#### #.... #.... .###. ....# ....# ####.");
        g('T', "##### ..#.. ..#.. ..#.. ..#.. ..#.. ..#..");
        g('U', "#...# #...# #...# #...# #...# #...# .###.");
        g('V', "#...# #...# #...# #...# #...# .#.#. ..#..");
        g('W', "#...# #...# #...# #.#.# #.#.# #.#.# .#.#.");
        g('X', "#...# #...# .#.#. ..#.. .#.#. #...# #...#");
        g('Y', "#...# #...# .#.#. ..#.. ..#.. ..#.. ..#..");
        g('Z', "##### ....# ...#. ..#.. .#... #.... #####");

        g('a', "..... ..... .###. ....# .#### #...# .####");
        g('b', "#.... #.... ####. #...# #...# #...# ####.");
        g('c', ".... .... .### #... #... #... .###");
        g('d', "....# ....# .#### #...# #...# #...# .####");
        g('e', "..... ..... .###. #...# ##### #.... .###.");
        g('f', "..## .#.. #### .#.. .#.. .#.. .#..");
        g('g', "..... ..... .#### #...# #...# #...# .#### ....# .###.");
        g('h', "#.... #.... ####. #...# #...# #...# #...#");
        g('i', "# . # # # # #");
        g('ı', ". . # # # # #");
        g('j', "..# ... ..# ..# ..# ..# ..# #.# .#.");
        g('k', "#... #... #..# #.#. ##.. #.#. #..#");
        g('l', "#. #. #. #. #. #. .#");
        g('m', "..... ..... ##.#. #.#.# #.#.# #.#.# #.#.#");
        g('n', "..... ..... ####. #...# #...# #...# #...#");
        g('o', "..... ..... .###. #...# #...# #...# .###.");
        g('p', "..... ..... ####. #...# #...# #...# ####. #.... #....");
        g('q', "..... ..... .#### #...# #...# #...# .#### ....# ....#");
        g('r', ".... .... #.## ##.. #... #... #...");
        g('s', "..... ..... .#### #.... .###. ....# ####.");
        g('t', ".#.. .#.. #### .#.. .#.. .#.. ..##");
        g('u', "..... ..... #...# #...# #...# #...# .####");
        g('v', "..... ..... #...# #...# #...# .#.#. ..#..");
        g('w', "..... ..... #...# #...# #.#.# #.#.# .#.#.");
        g('x', "..... ..... #...# .#.#. ..#.. .#.#. #...#");
        g('y', "..... ..... #...# #...# #...# #...# .#### ....# .###.");
        g('z', "..... ..... ##### ...#. ..#.. .#... #####");
        g('ß', ".##.. #..#. #..#. #.#.. #..#. #...# #.##.");

        g('.', ". . . . . . #");
        g(',', ".. .. .. .. .. .# .# #.");
        g(':', ". # . . . # .");
        g(';', ".. .# .. .. .. .# #.");
        g('!', "# # # # # . #");
        g('?', ".###. #...# ....# ...#. ..#.. ..... ..#..");
        g('-', ".... .... .... #### .... .... ....");
        g('+', "..... ..#.. ..#.. ##### ..#.. ..#.. .....");
        g('=', ".... .... #### .... #### .... ....");
        g('_', "..... ..... ..... ..... ..... ..... #####");
        g('/', "....# ....# ...#. ..#.. .#... #.... #....");
        g('\\', "#.... #.... .#... ..#.. ...#. ....# ....#");
        g('(', "..# .#. #.. #.. #.. .#. ..#");
        g(')', "#.. .#. ..# ..# ..# .#. #..");
        g('[', "### #.. #.. #.. #.. #.. ###");
        g(']', "### ..# ..# ..# ..# ..# ###");
        g('<', "...# ..#. .#.. #... .#.. ..#. ...#");
        g('>', "#... .#.. ..#. ...# ..#. .#.. #...");
        g('\'', "# # . . . . .");
        g('"', "#.# #.# ... ... ... ... ...");
        g('&', ".##.. #..#. #.#.. .#... #.#.# #..#. .##.#");
        g('%', "##..# ##..# ...#. ..#.. .#... #..## #..##");
        g('#', ".#.#. .#.#. ##### .#.#. ##### .#.#. .#.#.");
        g('@', ".###. #...# #.### #.#.# #.### #.... .###.");
        g('*', "..... #.#.# .###. ##### .###. #.#.# .....");
        g('°', ".#. #.# .#. ... ... ... ...");
        g('€', "..### .#... ####. .#... ####. .#... ..###");
        g('·', ". . . # . . .");
        g('|', "# # # # # # #");
        g('~', "..... ..... .##.# #..#. ..... ..... .....");

        // Combining marks, positioned for lowercase (top row 0); capitals shift them up by 2.
        mark('́', "..# .#.", 0);   // acute
        mark('̀', "#.. .#.", 0);   // grave
        mark('̈', "#.# ...", 0);   // diaeresis
        mark('̂', ".#. #.#", 0);   // circumflex
        mark('̃', ".#.# #.#.", 0); // tilde
        mark('̊', ".#. #.# .#.", -1); // ring
        mark('̧', ".#. #..", 7);   // cedilla (below the baseline)
    }

    /** Upper bound on characters handled per string (bounded loops). */
    private static final int MAX_CHARS = 2000;
    /** Width cache for characters outside the plain glyph table (accented letters, fallbacks). */
    private static final Map<Character, Integer> WIDTHS = new HashMap<Character, Integer>();
    private static final int MAX_CACHED_WIDTHS = 512;

    /** Typographic characters that map onto glyphs the font has. */
    private static String substitute(char c) {
        switch (c) {
            case '–': case '—': case '−': return "-";
            case '‘': case '’': case '´': case '`': return "'";
            case '“': case '”': case '„': return "\"";
            case '…': return "...";
            case ' ': return " ";
            case '•': return "·";
            default: return null;
        }
    }

    /** Zero-width characters (emoji variation selectors, joiners): never drawn, no width. */
    private static boolean zeroWidth(char c) {
        return c == '︎' || c == '️' || c == '‍' || c == '​';
    }

    /** Index after the drawable character starting at i (a surrogate pair counts as one). */
    private static int next(String t, int i) {
        return Character.isHighSurrogate(t.charAt(i)) && i + 1 < t.length()
                && Character.isLowSurrogate(t.charAt(i + 1)) ? i + 2 : i + 1;
    }

    /**
     * Expands text into drawable units: a glyph (plus optional mark), or a fallback character
     * (BMP char or whole surrogate pair, e.g. an emoji) the font cannot draw.
     */
    static List<Object> units(String text) {
        List<Object> out = new ArrayList<Object>();
        if (text == null) return out;
        int n = Math.min(text.length(), MAX_CHARS);
        for (int i = 0; i < n; i = next(text, i)) {
            char c = text.charAt(i);
            if (zeroWidth(c)) continue;
            if (next(text, i) == i + 2) {
                out.add(new Unit(null, null, 0, text.substring(i, i + 2)));
                continue;
            }
            String sub = substitute(c);
            if (sub != null) {
                for (int k = 0; k < sub.length(); k++) out.add(new Unit(GLYPHS.get(sub.charAt(k)), null, 0, null));
                continue;
            }
            out.add(unit(c));
        }
        return out;
    }

    private static Unit unit(char c) {
        Glyph base = GLYPHS.get(c);
        if (base != null) return new Unit(base, null, 0, null);
        String d = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
        if (d.length() == 2 && MARKS.containsKey(d.charAt(1))) {
            char b = d.charAt(0) == 'i' ? 'ı' : d.charAt(0);  // dotless i under accents
            Glyph bg = GLYPHS.get(b);
            if (bg != null) {
                Glyph m = MARKS.get(d.charAt(1));
                boolean upper = Character.isUpperCase(b) && m.top < CAP;
                return new Unit(bg, m, upper ? m.top - 2 : m.top, null);
            }
        }
        return new Unit(null, null, 0, String.valueOf(c));  // not in the font: system glyph
    }

    /** One drawable character. glyph == null means "draw fallback with a system font". */
    static final class Unit {
        final Glyph glyph, mark;
        final int markTop;
        final String fallback;

        Unit(Glyph glyph, Glyph mark, int markTop, String fallback) {
            this.glyph = glyph;
            this.mark = mark;
            this.markTop = markTop;
            this.fallback = fallback;
        }

        /** Advance in font pixels, without the inter-letter spacing. */
        int width() {
            if (glyph == null) return 5;  // fallback glyphs are drawn into a 5-pixel cell
            return mark == null ? glyph.width : Math.max(glyph.width, mark.width);
        }
    }

    static boolean has(char c) {
        return GLYPHS.containsKey(c);
    }

    /**
     * Width in font pixels of the character at i, including the inner spacing of substitutions
     * ("…" is three dots), excluding the spacing to the next character. 0 for zero-width.
     */
    private static int cellWidth(String t, int i) {
        char c = t.charAt(i);
        if (zeroWidth(c)) return 0;
        if (next(t, i) == i + 2) return 5;
        Glyph g = GLYPHS.get(c);
        if (g != null) return g.width;
        String sub = substitute(c);
        if (sub != null) {
            int w = 0;
            for (int k = 0; k < sub.length(); k++) w += GLYPHS.get(sub.charAt(k)).width + (k > 0 ? SPACING : 0);
            return w;
        }
        Integer cached = WIDTHS.get(c);
        if (cached != null) return cached;
        int w = unit(c).width();
        if (WIDTHS.size() < MAX_CACHED_WIDTHS) WIDTHS.put(c, w);
        return w;
    }

    /** Width of text in font pixels. No per-character allocation for known characters. */
    static int measure(String text) {
        return text == null ? 0 : widthUpTo(text, Math.min(text.length(), MAX_CHARS));
    }

    private static int widthUpTo(String t, int end) {
        int w = 0;
        boolean first = true;
        for (int i = 0; i < end; i = next(t, i)) {
            int cw = cellWidth(t, i);
            if (cw == 0) continue;
            w += cw + (first ? 0 : SPACING);
            first = false;
        }
        return w;
    }

    /**
     * Largest end index such that text.substring(0, end) is at most maxWidth wide. One linear
     * pass; never splits a surrogate pair.
     */
    static int fit(String text, int maxWidth) {
        int n = Math.min(text.length(), MAX_CHARS);
        int w = 0, end = 0;
        boolean first = true;
        for (int i = 0; i < n; i = next(text, i)) {
            int cw = cellWidth(text, i);
            int add = cw == 0 ? 0 : cw + (first ? 0 : SPACING);
            if (w + add > maxWidth) break;
            w += add;
            if (cw != 0) first = false;
            end = next(text, i);
        }
        return end;
    }

    /**
     * Emits the pixel runs of one unit at x (font pixels). Marks are centred over the glyph.
     */
    static void emit(Unit u, int x, RunSink sink) {
        int w = u.width();
        if (u.glyph != null) emitGlyph(u.glyph, x + (w - u.glyph.width) / 2, u.glyph.top, sink);
        if (u.mark != null) emitGlyph(u.mark, x + (w - u.mark.width) / 2, u.markTop, sink);
    }

    private static void emitGlyph(Glyph g, int x, int top, RunSink sink) {
        for (int r = 0; r < g.runs.length; r++) {
            int[] rr = g.runs[r];
            for (int i = 0; i + 1 < rr.length; i += 2) sink.run(x + rr[i], top + r, rr[i + 1]);
        }
    }

    /** Longest prefix of text that fits maxWidth, with "..." appended when shortened. */
    static String ellipsize(String text, int maxWidth) {
        if (text == null) return "";
        if (measure(text) <= maxWidth && text.length() <= MAX_CHARS) return text;
        int dots = measure("...") + SPACING;
        if (maxWidth < dots) return "";
        String head = text.substring(0, fit(text, maxWidth - dots)).trim();
        return head.isEmpty() ? "" : head + "...";
    }

    /**
     * Word-wraps text into at most maxLines lines of maxWidth font pixels. Words longer than a
     * line are cut; the last line is ellipsized when text remains. Each line costs one linear
     * pass, so the whole wrap is linear in the text length.
     */
    static List<String> wrap(String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<String>();
        if (text == null || maxLines < 1) return lines;
        String rest = text.trim().replaceAll("\\s+", " ");
        while (!rest.isEmpty() && lines.size() < maxLines) {  // bounded by maxLines
            if (lines.size() == maxLines - 1 || measure(rest) <= maxWidth) {
                lines.add(ellipsize(rest, maxWidth));
                break;
            }
            int end = fit(rest, maxWidth);
            // A space at index end means rest.substring(0, end) fits exactly up to that word.
            int space = end < rest.length() ? rest.lastIndexOf(' ', end) : -1;
            int cut = space > 0 ? space : Math.max(end, next(rest, 0));  // no word fits: cut mid-word
            lines.add(rest.substring(0, cut));
            rest = rest.substring(cut).trim();
        }
        return lines;
    }
}

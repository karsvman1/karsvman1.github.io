package nl.japandi.dashboard;

/**
 * Splits a list of rows with known heights into pages that fit a panel, so no row is ever
 * dropped because the screen is full. Pure Java, unit-tested in android/test.
 */
final class Paging {
    private Paging() {
    }

    /**
     * Greedy split: each page takes rows until the next one would not fit. A row marked in
     * keepWithNext (a section header) only starts on a page that also has room for the row
     * after it, so a header never ends a page. A row taller than the panel gets a page of its
     * own (it is clipped there rather than lost).
     *
     * @param keepWithNext may be null
     * @return start index of every page; always at least one page (start 0), even when empty
     */
    static int[] pageStarts(float[] heights, float available, boolean[] keepWithNext) {
        int n = heights == null ? 0 : heights.length;
        int[] tmp = new int[Math.max(n, 1)];
        int pages = 1;
        float used = 0;
        for (int i = 0; i < n; i++) {  // bounded by the number of rows
            boolean keep = keepWithNext != null && keepWithNext[i] && i + 1 < n;
            float need = heights[i] + (keep ? heights[i + 1] : 0);
            if (used > 0 && used + need > available) {
                tmp[pages++] = i;
                used = 0;
            }
            used += heights[i];
        }
        int[] out = new int[pages];
        System.arraycopy(tmp, 0, out, 0, pages);
        return out;
    }

    /** End index (exclusive) of page p. */
    static int pageEnd(int[] starts, int p, int count) {
        return p + 1 < starts.length ? starts[p + 1] : count;
    }
}

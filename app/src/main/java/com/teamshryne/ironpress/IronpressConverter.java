package com.teamshryne.ironpress;

/** Reusable, thread-safe Android facade for the complete Ironpress converter API. */
public final class IronpressConverter implements AutoCloseable {
    public static final int PAGE_A4 = 1, PAGE_LETTER = 2, PAGE_LEGAL = 3;
    public static final int FONT_CJK_JAPANESE = 1, FONT_CJK_KOREAN = 2, FONT_CJK_SIMPLIFIED_CHINESE = 3,
            FONT_CJK_TRADITIONAL_CHINESE = 4, FONT_EMOJI = 5;
    private long handle;

    static { if (IronpressNative.nativeAbiVersion() != 1) throw new LinkageError("Unsupported Ironpress ABI"); }
    public static String version() { return IronpressNative.nativeVersion(); }
    public IronpressConverter() { handle = IronpressNative.nativeCreate(); }
    private synchronized long live() { if (handle == 0) throw new IllegalStateException("Converter is closed"); return handle; }
    private synchronized void set(String key, String text, byte[] bytes, float[] values, int integer, boolean bool) {
        IronpressNative.nativeSetOption(live(), key, text == null ? "" : text, bytes == null ? new byte[0] : bytes,
                values == null ? new float[0] : values, integer, bool);
    }
    public synchronized IronpressConverter pageSize(int size) { set("pageSize", null, null, null, size, false); return this; }
    public synchronized IronpressConverter customPageSize(float widthPoints, float heightPoints) { set("customPageSize", null, null, new float[]{widthPoints,heightPoints}, 0, false); return this; }
    public synchronized IronpressConverter margin(float points) { set("margin", null, null, new float[]{points}, 0, false); return this; }
    public synchronized IronpressConverter margins(float top, float right, float bottom, float left) { set("margins", null, null, new float[]{top,right,bottom,left}, 0, false); return this; }
    public synchronized IronpressConverter header(String value) { set("header", value, null, null, 0, false); return this; }
    public synchronized IronpressConverter headerHtml(String value) { set("headerHtml", value, null, null, 0, false); return this; }
    public synchronized IronpressConverter footer(String value) { set("footer", value, null, null, 0, false); return this; }
    public synchronized IronpressConverter footerHtml(String value) { set("footerHtml", value, null, null, 0, false); return this; }
    public synchronized IronpressConverter compress(boolean value) { set("compress", null, null, null, 0, value); return this; }
    public synchronized IronpressConverter jpegQuality(int value) { set("jpegQuality", null, null, null, value, false); return this; }
    public synchronized IronpressConverter autoResizeImages(boolean value) { set("autoResizeImages", null, null, null, 0, value); return this; }
    public synchronized IronpressConverter imageDpi(float value) { set("imageDpi", null, null, new float[]{value}, 0, false); return this; }
    public synchronized IronpressConverter filterDpi(float value) { set("filterDpi", null, null, new float[]{value}, 0, false); return this; }
    public synchronized IronpressConverter maskDpi(float value) { set("maskDpi", null, null, new float[]{value}, 0, false); return this; }
    public synchronized IronpressConverter backgroundRasterDpi(float value) { set("backgroundRasterDpi", null, null, new float[]{value}, 0, false); return this; }
    public synchronized IronpressConverter occlusionCull(boolean value) { set("occlusionCull", null, null, null, 0, value); return this; }
    public synchronized IronpressConverter sanitize(boolean value) { set("sanitize", null, null, null, 0, value); return this; }
    public synchronized IronpressConverter addFont(String family, byte[] ttf) { set("font", family, ttf, null, 0, false); return this; }
    public synchronized IronpressConverter addFontPack(int pack, byte[] fontData) { set("fontPack", null, fontData, null, pack, false); return this; }
    public synchronized byte[] convertHtml(String html) { return IronpressNative.nativeConvert(live(), html.getBytes(java.nio.charset.StandardCharsets.UTF_8), false); }
    public synchronized byte[] convertMarkdown(String markdown) { return IronpressNative.nativeConvert(live(), markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8), true); }
    @Override public synchronized void close() { if (handle != 0) { long current = handle; handle = 0; IronpressNative.nativeFree(current); } }
}

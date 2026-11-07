package com.teamshryne.ironpress;

final class IronpressNative {

    static native int nativeAbiVersion();
    static native String nativeVersion();
    static native long nativeCreate();
    static native void nativeFree(long handle);
    static native void nativeSetOption(long handle, String option, String text, byte[] bytes, float[] floats, int integer, boolean bool);
    static native byte[] nativeConvert(long handle, byte[] input, boolean markdown);
    private IronpressNative() {}
}

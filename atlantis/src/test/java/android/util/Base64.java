package android.util;

/** JVM test implementation of the Android methods used by the library. */
public final class Base64 {
    public static final int NO_WRAP = 2;

    private Base64() {}

    public static String encodeToString(byte[] input, int flags) {
        requireNoWrap(flags);
        return java.util.Base64.getEncoder().encodeToString(input);
    }

    public static byte[] decode(String input, int flags) {
        requireNoWrap(flags);
        return java.util.Base64.getDecoder().decode(input);
    }

    private static void requireNoWrap(int flags) {
        if (flags != NO_WRAP) {
            throw new IllegalArgumentException("Expected Base64.NO_WRAP, got " + flags);
        }
    }
}

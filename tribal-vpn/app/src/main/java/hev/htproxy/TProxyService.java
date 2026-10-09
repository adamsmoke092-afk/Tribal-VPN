package hev.htproxy;

/**
 * Binding class for hev-socks5-tunnel's JNI interface. Must exist exactly at
 * this package+name: the native library's JNI_OnLoad does
 * FindClass("hev/htproxy/TProxyService") and RegisterNatives on it - if this
 * class is missing (or renamed by minification), JNI_OnLoad returns JNI_ERR
 * and ART aborts the process at System.loadLibrary. This mirrors the class
 * upstream bundles inside its own AAR.
 *
 * Method names/signatures come verbatim from src/hev-jni.c at the pinned
 * submodule commit (07bea57d): all four return Z (boolean) / [J (long[]).
 */
public class TProxyService {

    public static native boolean TProxyStartService(String config_path, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    public static native long[] TProxyGetStats();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }
}

# hev-socks5-tunnel's JNI_OnLoad does FindClass("hev/htproxy/TProxyService")
# and RegisterNatives on it. Minification would rename or remove that class,
# and the process would abort with SIGABRT at System.loadLibrary (JNI_OnLoad
# returns JNI_ERR). Debug builds don't minify; this protects release builds.
-keep class hev.htproxy.** { *; }

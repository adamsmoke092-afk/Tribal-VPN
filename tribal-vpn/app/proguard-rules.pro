# hev-socks5-tunnel's JNI_OnLoad does FindClass("hev/htproxy/TProxyService")
# and RegisterNatives on it. Minification would rename or remove that class,
# and the process would abort with SIGABRT at System.loadLibrary (JNI_OnLoad
# returns JNI_ERR). Debug builds don't minify; this protects release builds.
-keep class hev.htproxy.** { *; }

# Xray engine (libv2ray.aar): gomobile-generated Java classes plus the go.Seq
# runtime are resolved from the Go side (JNI looks classes up by name), so
# minification would break the core in the same way. Same reasoning as above.
-keep class libv2ray.** { *; }
-keep class go.** { *; }

package dev.flexaccess.ezvpn

import android.content.Context

/**
 * The JNI surface of libezvpn.so (ezvpn `src/ffi_android.rs`). The symbol
 * names in the Rust side are bound to exactly this class, so it must stay
 * `dev.flexaccess.ezvpn.EzvpnNative` whatever the applicationId is.
 *
 * Lifecycle, one session at a time:
 *  1. [connect] — connect + handshake (blocks; call off the main thread).
 *     Returns a handle and stores the network-config JSON in `out[0]`, or
 *     returns 0 with the error message in `out[0]`.
 *  2. [run] — hand the `VpnService.Builder.establish()` fd to the data loop.
 *  3. [stop] — exactly once per successful connect; the handle is dead after.
 *
 * When the data loop ends on its own (server closed, idle timeout, I/O error)
 * the library calls [onTunnelExit] on a background thread; a [stop] never
 * triggers it. All JSON shapes are documented in ezvpn's `ios/ezvpn.h`.
 */
object EzvpnNative {
    init {
        System.loadLibrary("ezvpn")
    }

    /**
     * One-time process setup: logcat logging (tag `ezvpn`) and the JVM/context
     * registration iroh's Android DNS and interface discovery need. Call from
     * `Application.onCreate` before any other entry point. Idempotent.
     */
    @JvmStatic
    external fun init(context: Context)

    /**
     * A fresh ed25519 client keypair as
     * `{"created":…,"public_key":"ed25519-pub:…","secret_key":"ed25519-sec:…"}`.
     * Throws [RuntimeException] when the system RNG is unavailable.
     */
    @JvmStatic
    external fun generateClientKey(): String

    /**
     * The `ed25519-pub:…` half of a secret key, or null when the secret does
     * not parse — which also makes this the validator for pasted keys.
     */
    @JvmStatic
    external fun clientPublicKey(secret: String): String?

    @JvmStatic
    external fun connect(configJson: String, out: Array<String?>): Long

    /** 0 on success, -1 on error (bad handle, no pending session, dup failure). */
    @JvmStatic
    external fun run(handle: Long, tunFd: Int): Int

    /** The live iroh path / custom-relay snapshot as JSON, or null for a dead handle. */
    @JvmStatic
    external fun connPath(handle: Long): String?

    @JvmStatic
    external fun stop(handle: Long)

    fun interface ExitListener {
        /** Called on a library thread; `error` is null for a clean end. */
        fun onTunnelExit(handle: Long, error: String?)
    }

    /** The service installs itself here while it owns a session. */
    @Volatile
    var exitListener: ExitListener? = null

    /** Entry point the library calls (static, signature `(JLjava/lang/String;)V`). */
    @JvmStatic
    fun onTunnelExit(handle: Long, error: String?) {
        exitListener?.onTunnelExit(handle, error)
    }
}

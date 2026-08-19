package dev.gounthar.xcpng.toolbox.xo

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * An [SSLContext] that accepts any certificate.
 *
 * Only reachable when the caller passes `allowUnauthorized = true`, which exists because XOA
 * ships a self-signed certificate. It is a real downgrade and not a convenience: on a hostile
 * network it removes the guarantee that you are talking to your own pool. Keep it opt-in, keep it
 * per-connection, and never make it the default.
 */
internal object TrustEverythingSslContext {
    fun create(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(AcceptAll), SecureRandom())
    }

    private object AcceptAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

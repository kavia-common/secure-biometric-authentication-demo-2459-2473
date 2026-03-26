package org.example.app.network.mock

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.example.app.network.api.LoginRequest
import org.example.app.network.api.RefreshRequest
import org.example.app.network.api.TokenResponse
import java.util.UUID

/**
 * A fake backend implemented as an OkHttp interceptor.
 *
 * Endpoints:
 * - POST /auth/login -> returns new access+refresh
 * - POST /auth/refresh -> returns new access (and possibly new refresh)
 * - GET /protected -> requires Authorization Bearer token that matches current active token
 *
 * This makes the demo runnable without any server.
 */
class MockBackendInterceptor(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Interceptor {

    // Simple in-memory "server" state
    @Volatile private var activeAccessToken: String? = null
    @Volatile private var activeRefreshToken: String? = null
    @Volatile private var accessExpiryMs: Long = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val method = request.method.uppercase()

        return when {
            method == "POST" && path == "/auth/login" -> {
                // For demo, any username/password accepted.
                val tokens = issueTokens()
                jsonResponse(chain, 200, tokenResponseJson(tokens))
            }

            method == "POST" && path == "/auth/refresh" -> {
                val body = readUtf8Body(request)
                val refresh = parseJsonField(body, "refreshToken")
                if (refresh == null || refresh != activeRefreshToken) {
                    jsonResponse(chain, 401, """{"error":"invalid_refresh"}""")
                } else {
                    // Refresh issues a new access token (and rotates refresh token too for realism)
                    val tokens = issueTokens()
                    jsonResponse(chain, 200, tokenResponseJson(tokens))
                }
            }

            method == "GET" && path == "/protected" -> {
                val auth = request.header("Authorization")
                val bearer = auth?.removePrefix("Bearer ")?.trim()

                val ok = bearer != null &&
                    bearer == activeAccessToken &&
                    clock() < accessExpiryMs

                if (!ok) {
                    jsonResponse(chain, 401, """{"error":"unauthorized"}""")
                } else {
                    jsonResponse(chain, 200, """{"message":"You have accessed a protected resource."}""")
                }
            }

            else -> jsonResponse(chain, 404, """{"error":"not_found","path":"$path"}""")
        }
    }

    private fun issueTokens(): TokenResponse {
        val access = "access_${UUID.randomUUID()}"
        val refresh = "refresh_${UUID.randomUUID()}"
        // Keep short for demo refresh behavior.
        val expiresAt = clock() + 30_000L

        activeAccessToken = access
        activeRefreshToken = refresh
        accessExpiryMs = expiresAt

        return TokenResponse(
            accessToken = access,
            refreshToken = refresh,
            accessTokenExpiresAtEpochMs = expiresAt,
        )
    }

    private fun readUtf8Body(request: okhttp3.Request): String {
        val copy = request.newBuilder().build()
        val body = copy.body ?: return ""
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    /**
     * Extremely small JSON helper good enough for the demo payloads.
     * Looks for "field":"value" and returns value.
     */
    private fun parseJsonField(json: String, field: String): String? {
        val quoted = """"$field""""
        val idx = json.indexOf(quoted)
        if (idx < 0) return null
        val colon = json.indexOf(':', idx + quoted.length)
        if (colon < 0) return null
        val firstQuote = json.indexOf('"', colon + 1)
        if (firstQuote < 0) return null
        val secondQuote = json.indexOf('"', firstQuote + 1)
        if (secondQuote < 0) return null
        return json.substring(firstQuote + 1, secondQuote)
    }

    private fun tokenResponseJson(tr: TokenResponse): String {
        return """
            {
              "accessToken": "${tr.accessToken}",
              "refreshToken": "${tr.refreshToken}",
              "accessTokenExpiresAtEpochMs": ${tr.accessTokenExpiresAtEpochMs}
            }
        """.trimIndent()
    }

    private fun jsonResponse(chain: Interceptor.Chain, code: Int, body: String): Response {
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("mock")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

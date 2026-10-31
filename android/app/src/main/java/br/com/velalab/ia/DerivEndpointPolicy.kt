package br.com.velalab.ia

import java.net.URI

enum class DerivAccountType(val apiName: String, val displayName: String) {
    DEMO("demo", "DEMO"),
    REAL("real", "REAL"),
}

/**
 * Validação restrita do WebSocket OTP da API oficial Deriv.
 * É aplicada separadamente à conta solicitada, nunca por redirecionamento implícito.
 * Permitir um endpoint REAL aqui não permite ordens: o cliente é estritamente somente leitura.
 */
internal object DerivEndpointPolicy {
    fun isOfficialAccountUrl(url: String, type: DerivAccountType): Boolean = try {
        val uri = URI(url)
        uri.scheme == "wss" && uri.host == "api.derivws.com" && uri.port == -1 &&
            uri.userInfo == null && uri.fragment == null &&
            uri.path == "/trading/v1/options/ws/${type.apiName}" &&
            uri.rawQuery?.matches(Regex("otp=[A-Za-z0-9_%.-]+")) == true
    } catch (_: Exception) { false }

    fun isOfficialDemoUrl(url: String) = isOfficialAccountUrl(url, DerivAccountType.DEMO)
}

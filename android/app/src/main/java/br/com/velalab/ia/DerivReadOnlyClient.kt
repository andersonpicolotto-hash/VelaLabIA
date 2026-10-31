package br.com.velalab.ia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * API OFICIAL Deriv Options (2026). Somente consulta, inclusive saldo REAL mediante confirmação explícita; NÃO contém buy, sell ou ordens.
 * PAT jamais é salvo, registrado em log, incluído no APK ou enviado para servidor próprio.
 */
internal class DerivReadOnlyClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private var accountSocket: WebSocket? = null
    private var publicSocket: WebSocket? = null

    /** GET de contas e POST de OTP; escolhe a conta pelo tipo explicitamente solicitado. */
    suspend fun getAccountWebSocketUrl(appId: String, pat: String, type: DerivAccountType): String = withContext(Dispatchers.IO) {
        require(appId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "App ID inválido" }
        require(pat.isNotBlank() && !pat.contains('\n') && !pat.contains('\r')) { "Token inválido" }
        val root = "https://api.derivws.com/trading/v1/options/accounts"
        val accountsReq = Request.Builder().url(root)
            .header("Authorization", "Bearer $pat")
            .header("Deriv-App-ID", appId).get().build()
        val accounts = apiJson(accountsReq)
        val data = accounts.opt("data")
        val list: JSONArray = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("accounts") ?: JSONArray().put(data)
            else -> throw IllegalStateException("Resposta de contas inesperada")
        }
        val target = (0 until list.length())
            .mapNotNull { list.optJSONObject(it) }
            .firstOrNull { it.optString("account_type").equals(type.apiName, ignoreCase = true) }
            ?: throw IllegalStateException("Nenhuma conta ${type.displayName} encontrada na Deriv")
        val accountId = target.optString("account_id")
        require(accountId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "ID da conta inválido" }
        val otpReq = Request.Builder().url("$root/$accountId/otp")
            .header("Authorization", "Bearer $pat")
            .header("Deriv-App-ID", appId)
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        val url = apiJson(otpReq).optJSONObject("data")?.optString("url").orEmpty()
        require(DerivEndpointPolicy.isOfficialAccountUrl(url, type)) { "URL da conta ${type.displayName} inválida ou incompatível" }
        url
    }

    private fun apiJson(request: Request): JSONObject = http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("Deriv HTTP ${response.code}")
        val json = response.body?.string() ?: throw IllegalStateException("Resposta vazia da Deriv")
        JSONObject(json)
    }

    /** Sem credenciais; apenas confirma se o feed público de símbolos responde. */
    fun checkPublic(onSuccess: (Int) -> Unit, onError: () -> Unit) {
        closePublic()
        var answered = false
        val request = Request.Builder()
            .url("wss://api.derivws.com/trading/v1/options/ws/public").build()
        publicSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"active_symbols":"brief","req_id":1}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    if (msg.has("error")) {
                        answered = true
                        onError()
                        webSocket.close(1000, "Encerrado")
                    } else if (msg.optString("msg_type") == "active_symbols") {
                        answered = true
                        val count = msg.optJSONArray("active_symbols")?.length() ?: 0
                        onSuccess(count)
                        webSocket.close(1000, "Consulta concluída")
                    }
                } catch (_: Exception) { onError(); webSocket.close(1000, "Resposta inválida") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!answered) onError()
            }
        })
    }

    /** Abre WS autenticado na conta solicitada e envia apenas consulta de saldo. */
    fun connectAccount(url: String, type: DerivAccountType, onBalance: (Double, String) -> Unit, onError: () -> Unit) {
        require(DerivEndpointPolicy.isOfficialAccountUrl(url, type)) { "URL incompatível com conta ${type.displayName}" }
        closeAccount()
        accountSocket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"balance":1,"subscribe":1,"req_id":1}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    if (msg.has("error")) { onError(); webSocket.close(1000, "Erro"); return }
                    if (msg.optString("msg_type") == "balance") {
                        val item = msg.getJSONObject("balance")
                        val value = item.getDouble("balance")
                        val currency = item.optString("currency", "USD")
                        if (!value.isFinite()) throw IllegalStateException("Saldo inválido")
                        onBalance(value, currency)
                    }
                } catch (_: Exception) { onError(); webSocket.close(1000, "Erro de leitura") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { onError() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onError() }
        })
    }

    fun closeAccount() { accountSocket?.close(1000, "Desconectar"); accountSocket = null }
    fun closePublic() { publicSocket?.close(1000, "Desconectar"); publicSocket = null }
    fun close() {
        closeAccount()
        closePublic()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}

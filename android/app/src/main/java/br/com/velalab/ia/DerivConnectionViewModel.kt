package br.com.velalab.ia

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Status da conta da corretora, INDEPENDENTE do saldo e operações do simulador. */
data class DerivConnectionUiState(
    val status: String = "DESCONECTADO",
    val accountType: DerivAccountType = DerivAccountType.DEMO,
    val balance: Double? = null,
    val currency: String = "",
    val marketStatus: String = "NÃO VERIFICADO",
    val publicSymbolCount: Int = 0,
    val message: String = "Conexão DEMO ou REAL para consulta de saldo; nenhuma ordem pode ser enviada.",
)

class DerivConnectionViewModel : ViewModel() {
    private val client = DerivReadOnlyClient()
    private val _ui = MutableStateFlow(DerivConnectionUiState())
    val ui = _ui.asStateFlow()
    private var session = 0
    private var publicSession = 0

    fun checkPublic() {
        val id = ++publicSession
        _ui.update { it.copy(marketStatus = "VERIFICANDO") }
        client.checkPublic(onSuccess = { count ->
            viewModelScope.launch {
                if (id == publicSession) _ui.update { it.copy(marketStatus = "FEED PÚBLICO DISPONÍVEL", publicSymbolCount = count) }
            }
        }, onError = {
            viewModelScope.launch {
                if (id == publicSession) _ui.update { it.copy(marketStatus = "ERRO DE CONEXÃO", publicSymbolCount = 0) }
            }
        })
        viewModelScope.launch {
            delay(20_000)
            if (id == publicSession && _ui.value.marketStatus == "VERIFICANDO") {
                ++publicSession
                client.closePublic()
                _ui.update { it.copy(marketStatus = "TEMPO ESGOTADO") }
            }
        }
    }

    fun connect(appId: String, pat: String, type: DerivAccountType) {
        if (appId.isBlank() || pat.isBlank()) {
            _ui.update { it.copy(message = "Informe seu App ID e seu PAT da Deriv.") }
            return
        }
        disconnect()
        val id = ++session
        _ui.update { it.copy(status = "CONECTANDO", accountType = type, balance = null,
            message = "Autenticando conta ${type.displayName} para consulta de saldo; nenhuma ordem será enviada.") }
        viewModelScope.launch {
            try {
                val url = client.getAccountWebSocketUrl(appId.trim(), pat.trim(), type)
                if (id != session) return@launch
                client.connectAccount(url, type, onBalance = { balance, currency ->
                    viewModelScope.launch {
                        if (id == session) _ui.update {
                            it.copy(status = "CONECTADO - ${type.displayName}", balance = balance, currency = currency,
                                message = "Saldo ${type.displayName} confirmado. Somente leitura, sem ordens.")
                        }
                    }
                }, onError = {
                    viewModelScope.launch {
                        if (id == session) {
                            ++session
                            client.closeAccount()
                            _ui.update { it.copy(status = "ERRO DE CONEXÃO", balance = null,
                                message = "Conexão ${type.displayName} encerrada ou inválida. Tente novamente.") }
                        }
                    }
                })
            } catch (_: Exception) {
                if (id == session) _ui.update { it.copy(status = "ERRO DE CONEXÃO", balance = null,
                    message = "Não foi possível autenticar a conta ${type.displayName}. Confira PAT, App ID, rede e permissões.") }
            }
        }
        viewModelScope.launch {
            delay(25_000)
            if (id == session && _ui.value.status == "CONECTANDO") {
                ++session
                client.closeAccount()
                _ui.update { it.copy(status = "ERRO DE CONEXÃO", balance = null,
                    message = "Tempo esgotado para confirmar saldo ${type.displayName}.") }
            }
        }
    }

    fun disconnect() {
        ++session
        client.closeAccount()
        _ui.update { it.copy(status = "DESCONECTADO", balance = null, currency = "",
            message = "Sessão encerrada. Simulação continua independente.") }
    }

    override fun onCleared() {
        ++session
        ++publicSession
        client.close()
        super.onCleared()
    }
}

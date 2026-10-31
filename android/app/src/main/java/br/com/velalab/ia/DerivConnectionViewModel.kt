package br.com.velalab.ia

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Corretora real e simulador ficticio permanecem separados. Compras apenas em DEMO. */
data class DerivConnectionUiState(
    val status: String = "DESCONECTADO",
    val accountType: DerivAccountType = DerivAccountType.DEMO,
    val balance: Double? = null,
    val currency: String = "",
    val marketStatus: String = "NÃO VERIFICADO",
    val publicSymbolCount: Int = 0,
    val message: String = "Conecte DEMO para executar testes com contratos demonstrativos reais.",
)

class DerivConnectionViewModel(app: Application) : AndroidViewModel(app) {
    private val client = DerivReadOnlyClient()
    private val _ui = MutableStateFlow(DerivConnectionUiState())
    val ui = _ui.asStateFlow()

    private val _demo = MutableStateFlow(DemoBotState())
    val demo = _demo.asStateFlow()
    private val engine = DerivDemoTradingEngine(app.applicationContext, { client.sendDemo(it) }) {
        _demo.value = it
    }
    private val limitStorage = BrokerDailyLossStorage(app.applicationContext)
    private var session = 0
    private var publicSession = 0
    private var polling: Job? = null

    init {
        client.demoMessageListener = { json: JSONObject ->
            viewModelScope.launch { engine.onMessage(json) }
        }
    }

    fun checkPublic() {
        val id = ++publicSession
        _ui.update { it.copy(marketStatus = "VERIFICANDO") }
        client.checkPublic(onSuccess = { count ->
            viewModelScope.launch {
                if (id == publicSession) _ui.update {
                    it.copy(marketStatus = "FEED PÚBLICO DISPONÍVEL", publicSymbolCount = count)
                }
            }
        }, onError = {
            viewModelScope.launch {
                if (id == publicSession) _ui.update {
                    it.copy(marketStatus = "ERRO DE CONEXÃO", publicSymbolCount = 0)
                }
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
            _ui.update { it.copy(message = "Informe o App ID e o PAT da Deriv.") }
            return
        }
        disconnect()
        val id = ++session
        _ui.update { it.copy(status = "CONECTANDO", accountType = type, balance = null,
            message = "Autenticando conta " + type.displayName + ".") }
        viewModelScope.launch {
            try {
                val url = client.getAccountWebSocketUrl(appId.trim(), pat.trim(), type)
                if (id != session) return@launch
                client.connectAccount(url, type, onBalance = { balance, currency ->
                    viewModelScope.launch {
                        if (id == session) {
                            val firstBalance = !_ui.value.status.startsWith("CONECTADO")
                            _ui.update {
                                it.copy(status = "CONECTADO - " + type.displayName, balance = balance,
                                    currency = currency, message = if (type == DerivAccountType.DEMO)
                                        "Conectado a DEMO: o robo pode operar apos voce apertar Iniciar DEMO."
                                    else "Consulta REAL somente leitura. Ordens REAL bloqueadas.")
                            }
                            if (firstBalance && type == DerivAccountType.DEMO) {
                                engine.setConnection(true, balance, currency)
                            }
                        }
                    }
                }, onError = {
                    viewModelScope.launch {
                        if (id == session) {
                            ++session
                            stopPolling()
                            engine.setConnection(false)
                            client.closeAccount()
                            _ui.update { it.copy(status = "ERRO DE CONEXÃO", balance = null,
                                message = "Conexao encerrada ou invalida. Verifique a internet e reconecte.") }
                        }
                    }
                })
            } catch (_: Exception) {
                if (id == session) _ui.update { it.copy(status = "ERRO DE CONEXÃO", balance = null,
                    message = "Nao foi possivel autenticar a conta. Confira PAT, App ID, rede e permissoes.") }
            }
        }
        viewModelScope.launch {
            delay(25_000)
            if (id == session && _ui.value.status == "CONECTANDO") {
                ++session
                client.closeAccount()
                engine.setConnection(false)
                _ui.update { it.copy(status = "ERRO DE CONEXÃO", balance = null,
                    message = "Tempo esgotado para confirmar saldo.") }
            }
        }
    }

    fun startDemo(config: RiskSettings, symbol: String) {
        val s = _ui.value
        if (s.accountType != DerivAccountType.DEMO || !s.status.startsWith("CONECTADO")) {
            _demo.update { it.copy(notice = "Primeiro conecte sua conta DEMO na Deriv.") }
            return
        }
        val error = engine.start(s.balance ?: 0.0, s.currency, config,
            limitStorage.load(DerivAccountType.DEMO), symbol.trim())
        if (error != null) {
            _demo.update { it.copy(notice = error) }
            return
        }
        stopPolling()
        polling = viewModelScope.launch {
            while (true) {
                delay(10_000)
                engine.poll()
                if (!engine.isRunning()) break
            }
        }
    }

    fun pauseDemo() {
        stopPolling()
        engine.pause()
    }

    fun stopDemo() {
        stopPolling()
        engine.stop()
    }

    private fun stopPolling() {
        polling?.cancel()
        polling = null
    }

    fun disconnect() {
        ++session
        stopPolling()
        engine.setConnection(false)
        client.closeAccount()
        _ui.update { it.copy(status = "DESCONECTADO", balance = null, currency = "",
            message = "Sessao encerrada. Operacoes ficticias continuam independentes.") }
    }

    override fun onCleared() {
        ++session
        ++publicSession
        stopPolling()
        engine.setConnection(false)
        client.close()
        super.onCleared()
    }
}

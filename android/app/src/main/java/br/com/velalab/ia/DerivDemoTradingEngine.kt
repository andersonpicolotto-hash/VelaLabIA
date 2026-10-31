package br.com.velalab.ia

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Execucao experimental na conta DEMO da Deriv. Nunca utiliza endpoint REAL.
 * Funciona somente enquanto o app/conexao permanecem ativos; nao e servico 24h.
 * Ordens de compra em estado incerto NAO sao reenviadas.
 */
data class DemoTrade(
    val contractId: String,
    val symbol: String,
    val direction: String,
    val stake: Double,
    val profit: Double,
    val timestamp: Long
)

data class DemoBotState(
    val status: String = "PARADO",
    val notice: String = "Conecte a conta DEMO e inicie o robo.",
    val contractId: String? = null,
    val trades: List<DemoTrade> = emptyList(),
    val todayProfit: Double = 0.0,
    val lossStreak: Int = 0,
    val symbol: String = "R_100",
    val duration: Int = 5,
    val waiting: Boolean = false
)

internal class DerivDemoTradingEngine(
    context: Context,
    private val sendDemo: (JSONObject) -> Boolean,
    private val update: (DemoBotState) -> Unit
) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("velalab_demo_broker_journal_v1", Context.MODE_PRIVATE)
    private var state = DemoBotState()
    private var running = false
    private var connected = false
    private var balance = 0.0
    private var currency = "USD"
    private var settings = RiskSettings()
    private var limit = BrokerDailyLimit()
    private var openingBalance = 0.0
    private var day = LocalDate.now().toString()
    private var inFlight = false
    private var lastSignalCandle = 0L
    private var waitingFor = ""
    private var reqCounter = 4000
    private var requestId = -1
    private var proposedStake = 0.0
    private var proposedDirection = ""
    private var proposedPayout = 0.0
    private var contractId: String? = null
    private var pendingSymbol = ""
    private var pendingDirection = ""
    private var pendingStake = 0.0
    private var pendingTimestamp = 0L
    private var lastRequestAt = 0L

    init {
        load()
        publish("PARADO", if (inFlight) "Existe uma ordem DEMO pendente. Reconecte para reconciliar; nenhuma nova compra sera feita." else "Conecte a conta DEMO e inicie o robo.")
    }

    private fun load() {
        day = prefs.getString("day", LocalDate.now().toString()) ?: LocalDate.now().toString()
        openingBalance = Double.fromBits(prefs.getLong("openingBalance", 0.0.toBits()))
        lastSignalCandle = prefs.getLong("lastSignalCandle", 0L)
        inFlight = prefs.getBoolean("inFlight", false)
        contractId = prefs.getString("contractId", null)
        pendingSymbol = prefs.getString("pendingSymbol", "") ?: ""
        pendingDirection = prefs.getString("pendingDirection", "") ?: ""
        pendingStake = Double.fromBits(prefs.getLong("pendingStake", 0.0.toBits()))
        pendingTimestamp = prefs.getLong("pendingTimestamp", 0L)
        val records = mutableListOf<DemoTrade>()
        try {
            val arr = JSONArray(prefs.getString("records", "[]"))
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                records.add(DemoTrade(obj.getString("id"), obj.getString("symbol"), obj.getString("dir"),
                    obj.getDouble("stake"), obj.getDouble("profit"), obj.getLong("ts")))
            }
        } catch (_: Exception) {
            // Dados corrompidos => nao autorizar ordens com contabilidade desconhecida.
            inFlight = true
            contractId = null
        }
        state = state.copy(trades = records, todayProfit = todayProfit(), lossStreak = streak(), contractId = contractId)
    }

    private fun persist(): Boolean = try {
        val records = JSONArray()
        state.trades.take(200).forEach { trade ->
            records.put(JSONObject().put("id", trade.contractId).put("symbol", trade.symbol)
                .put("dir", trade.direction).put("stake", trade.stake)
                .put("profit", trade.profit).put("ts", trade.timestamp))
        }
        prefs.edit().putString("records", records.toString()).putString("day", day)
            .putLong("openingBalance", openingBalance.toBits())
            .putLong("lastSignalCandle", lastSignalCandle)
            .putBoolean("inFlight", inFlight).putString("contractId", contractId)
            .putString("pendingSymbol", pendingSymbol).putString("pendingDirection", pendingDirection)
            .putLong("pendingStake", pendingStake.toBits()).putLong("pendingTimestamp", pendingTimestamp)
            .commit()
    } catch (_: Exception) { false }

    private fun todayProfit(): Double = state.trades.filter {
        java.time.Instant.ofEpochMilli(it.timestamp).atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().toString() == day
    }.sumOf { it.profit }

    private fun streak(): Int {
        var count = 0
        for (trade in state.trades) {
            if (trade.profit > 0) break
            if (trade.profit < 0) count++
        }
        return count
    }

    private fun publish(status: String = state.status, notice: String = state.notice) {
        state = state.copy(status = status, notice = notice, contractId = contractId,
            todayProfit = todayProfit(), lossStreak = streak(), waiting = waitingFor.isNotEmpty())
        update(state)
    }

    fun snapshot() = state

    fun setConnection(isConnected: Boolean, receivedBalance: Double = 0.0, accountCurrency: String = "USD") {
        connected = isConnected
        if (!isConnected) {
            running = false
            waitingFor = ""
            publish("DESCONECTADO", if (inFlight) "Conexao perdida durante ordem DEMO; novas compras bloqueadas ate reconciliacao." else "Conexao DEMO encerrada. Robo parado.")
            return
        }
        if (receivedBalance.isFinite() && receivedBalance > 0) balance = receivedBalance
        if (accountCurrency.matches(Regex("[A-Z]{3}"))) currency = accountCurrency
        if (inFlight && contractId != null) {
            running = false
            subscribeContract()
            publish("RECUPERANDO", "Consultando contrato DEMO aberto anteriormente; nenhuma nova compra.")
        } else if (inFlight) {
            running = false
            publish("BLOQUEADO", "Compra DEMO com resultado desconhecido. Verifique o extrato na Deriv; nao reinicie o robo automaticamente.")
        } else if (!running) {
            publish("PARADO", "Conta DEMO conectada. Iniciar executa ordens DEMO de verdade quando houver sinal.")
        }
    }

    fun start(accountBalance: Double, accountCurrency: String, config: RiskSettings, dailyLimit: BrokerDailyLimit, symbol: String): String? {
        if (!connected) return "Conecte sua conta DEMO primeiro."
        if (inFlight || waitingFor.isNotEmpty()) return "Existe operacao pendente ou incerta. Aguarde reconciliacao."
        if (!accountBalance.isFinite() || accountBalance <= 0) return "Saldo DEMO indisponivel."
        if (!symbol.matches(Regex("[A-Za-z0-9_]{1,30}"))) return "Codigo de ativo invalido."
        config.error()?.let { return it }
        dailyLimit.validationError()?.let { return it }
        balance = accountBalance
        currency = accountCurrency
        settings = config
        limit = dailyLimit
        val newDay = LocalDate.now().toString()
        if (newDay != day) {
            day = newDay
            openingBalance = accountBalance
        }
        if (openingBalance <= 0) openingBalance = accountBalance
        if (!persist()) return "Falha ao salvar controles locais: operacoes bloqueadas."
        val maxLoss = min(openingBalance * limit.percent / 100.0, limit.amount ?: Double.POSITIVE_INFINITY)
        if (max(0.0, -todayProfit()) >= maxLoss || streak() >= settings.consecutiveLossLimit) {
            return "Limite diario ou sequencia de perdas atingido. Nao iniciar."
        }
        running = true
        state = state.copy(symbol = symbol, duration = if (settings.durationMinutes == 1) 1 else 5)
        publish("ATIVO", "Analisando candles DEMO reais (EMA9/EMA21 + RSI). Pode passar horas sem sinal.")
        requestCandles()
        return null
    }

    fun pause() {
        running = false
        publish("PAUSADO", if (inFlight) "Novas compras pausadas; monitoramento do contrato DEMO continua." else "Novas compras DEMO pausadas.")
    }

    fun stop() {
        running = false
        publish("PARADO", if (inFlight) "Robo parado para novas compras; aguarde liquidacao do contrato DEMO aberto." else "Robo DEMO parado.")
    }

    fun poll() {
        if (!connected || !running || inFlight || waitingFor.isNotEmpty()) return
        if (System.currentTimeMillis() - lastRequestAt < 50_000) return
        requestCandles()
    }

    private fun sendRequest(payload: JSONObject, type: String): Boolean {
        if (!connected) return false
        val id = ++reqCounter
        payload.put("req_id", id)
        if (!sendDemo(payload)) return false
        waitingFor = type
        requestId = id
        lastRequestAt = System.currentTimeMillis()
        publish()
        return true
    }

    private fun requestCandles() {
        if (!running || inFlight || waitingFor.isNotEmpty()) return
        val payload = JSONObject().put("ticks_history", state.symbol).put("end", "latest")
            .put("count", 180).put("style", "candles").put("granularity", 60)
        if (!sendRequest(payload, "candles")) fail("Falha de rede ao pedir candles DEMO.")
    }

    /** Chamado na thread principal, em sequencia, pelo ViewModel. */
    fun onMessage(msg: JSONObject) {
        if (msg.optString("msg_type") == "proposal_open_contract") {
            readContract(msg)
            return
        }
        if (msg.optInt("req_id", -1) != requestId || waitingFor.isEmpty()) return
        val phase = waitingFor
        waitingFor = ""
        if (msg.has("error")) {
            val code = msg.optJSONObject("error")?.optString("code") ?: "rejeitado"
            if (phase == "buy") {
                // Rejeicao explicita e diferente de timeout: nenhuma compra foi feita.
                clearPending()
                publish("PAUSADO", "Deriv rejeitou compra DEMO (" + code + ").")
            } else {
                fail("Deriv rejeitou " + phase + " (" + code + ").")
            }
            return
        }
        when (phase) {
            "candles" -> onCandles(msg)
            "proposal" -> onProposal(msg)
            "buy" -> onBuy(msg)
        }
    }

    private fun onCandles(msg: JSONObject) {
        if (!running || inFlight) return
        val arr = msg.optJSONArray("candles")
        if (msg.optString("msg_type") != "candles" || arr == null || arr.length() < 60) {
            fail("Candles da Deriv indisponiveis ou incompletos.")
            return
        }
        val history = (0 until arr.length()).mapNotNull { idx ->
            arr.optJSONObject(idx)?.let { row ->
                val epoch = row.optLong("epoch", 0L)
                val close = row.optDouble("close", Double.NaN)
                if (epoch > 0 && close.isFinite() && close > 0) epoch to close else null
            }
        }.sortedBy { it.first }.distinctBy { it.first }
        if (history.size < 60) { fail("Dados insuficientes para EMA e RSI."); return }
        // Ultimo candle ainda pode estar se formando. Nunca utiliza candle incompleto.
        val closed = history.dropLast(1)
        val last = closed.last().first
        if (last <= lastSignalCandle) { publish("ATIVO", "Aguardando novo candle fechado."); return }
        val prices = closed.map { it.second }
        val e9 = ema(prices, 9)
        val e21 = ema(prices, 21)
        val rsi = rsi(prices)
        val direction = when {
            e9[e9.lastIndex - 1] <= e21[e21.lastIndex - 1] && e9.last() > e21.last() && rsi in 52.0..68.0 -> "CALL"
            e9[e9.lastIndex - 1] >= e21[e21.lastIndex - 1] && e9.last() < e21.last() && rsi in 32.0..48.0 -> "PUT"
            else -> null
        }
        if (direction == null) {
            publish("ATIVO", "Sem cruzamento EMA/RSI confirmado. Aguardando; nenhuma entrada forçada.")
            return
        }
        val maxLoss = min(openingBalance * limit.percent / 100.0, limit.amount ?: Double.POSITIVE_INFINITY)
        val stake = (balance * min(settings.stakePercent, 1.0) / 100.0 * 100).roundToInt() / 100.0
        val lossExposure = max(0.0, -todayProfit())
        if (stake <= 0 || stake > balance * min(settings.maxStakePercent, 1.0) / 100.0 + 0.001 ||
            lossExposure + stake > maxLoss || streak() >= settings.consecutiveLossLimit) {
            fail("Limite diario, tamanho maximo ou perdas consecutivas bloqueou entrada.")
            return
        }
        lastSignalCandle = last
        if (!persist()) { fail("Nao foi possivel persistir sinal; compra cancelada."); return }
        proposedStake = stake
        proposedDirection = direction
        if (!sendRequest(JSONObject().put("proposal", 1).put("amount", stake)
                .put("basis", "stake").put("contract_type", direction)
                .put("currency", currency).put("duration", state.duration)
                .put("duration_unit", "m").put("underlying_symbol", state.symbol), "proposal")) {
            fail("Falha ao obter proposta DEMO.")
        } else {
            publish("ATIVO", "Sinal " + direction + " confirmado; consultando payout na Deriv.")
        }
    }

    private fun onProposal(msg: JSONObject) {
        if (!running || inFlight || msg.optString("msg_type") != "proposal") {
            fail("Proposta DEMO inesperada; nenhuma compra."); return
        }
        val proposal = msg.optJSONObject("proposal")
        val id = proposal?.optString("id").orEmpty()
        val ask = proposal?.optDouble("ask_price", Double.NaN) ?: Double.NaN
        val payout = proposal?.optDouble("payout", Double.NaN) ?: Double.NaN
        val netPercent = if (ask > 0 && payout.isFinite()) (payout / ask - 1.0) * 100.0 else Double.NaN
        if (id.isBlank() || !ask.isFinite() || ask <= 0 || ask > proposedStake + 0.01 ||
            !netPercent.isFinite() || netPercent < settings.minPayoutPercent) {
            publish("ATIVO", "Proposta recusada: payout abaixo do minimo, preco ou ID invalido.")
            return
        }
        val maxLoss = min(openingBalance * limit.percent / 100.0, limit.amount ?: Double.POSITIVE_INFINITY)
        if (max(0.0, -todayProfit()) + ask > maxLoss || ask > balance ||
            streak() >= settings.consecutiveLossLimit) {
            fail("Risco alterado antes de comprar; operacao bloqueada.")
            return
        }
        // Persistir ANTES do envio. Se a resposta buy se perder, bloquear novas compras.
        inFlight = true
        pendingSymbol = state.symbol
        pendingDirection = proposedDirection
        pendingStake = ask
        proposedPayout = netPercent
        pendingTimestamp = System.currentTimeMillis()
        contractId = null
        if (!persist()) { inFlight = false; fail("Falha ao gravar ordem; compra nao enviada."); return }
        if (!sendRequest(JSONObject().put("buy", id).put("price", ask), "buy")) {
            fail("Compra DEMO de estado incerto. Verifique o extrato na Deriv antes de tentar novamente.")
        } else {
            publish("COMPRANDO", "Enviando ordem DEMO real para a Deriv; valor " + ask + " " + currency + ".")
        }
    }

    private fun onBuy(msg: JSONObject) {
        if (!inFlight || msg.optString("msg_type") != "buy") {
            fail("Resposta buy incerta. Nao repetir compra."); return
        }
        val contract = msg.optJSONObject("buy")?.optString("contract_id").orEmpty()
        if (!contract.matches(Regex("[0-9]{1,30}"))) {
            fail("Confirmacao de contrato invalida; reconciliacao necessaria."); return
        }
        contractId = contract
        if (!persist()) { fail("Contrato recebido mas nao persistido. Acompanhe na Deriv."); return }
        subscribeContract()
        publish("EM CONTRATO", "Ordem DEMO confirmada: contrato #" + contract + " (payout " + proposedPayout.roundToInt() + "%).")
    }

    private fun subscribeContract() {
        if (!connected || contractId == null) return
        val id = contractId!!.toLongOrNull() ?: return
        val payload = JSONObject().put("proposal_open_contract", 1).put("contract_id", id).put("subscribe", 1)
            .put("req_id", ++reqCounter)
        if (!sendDemo(payload)) publish("BLOQUEADO", "Falha ao consultar contrato aberto. Reconecte DEMO.")
    }

    private fun readContract(msg: JSONObject) {
        if (!inFlight || contractId == null || msg.has("error")) return
        val item = msg.optJSONObject("proposal_open_contract") ?: return
        if (item.optString("contract_id") != contractId) return
        val ended = item.optInt("is_sold") == 1 || item.optInt("is_expired") == 1
        if (!ended) return
        val profit = item.optDouble("profit", Double.NaN)
        if (!profit.isFinite()) {
            fail("Contrato encerrado sem lucro confirmado. Verifique o extrato DEMO.")
            return
        }
        val closed = DemoTrade(contractId!!, pendingSymbol, pendingDirection, pendingStake,
            profit, System.currentTimeMillis())
        if (state.trades.none { it.contractId == closed.contractId }) {
            state = state.copy(trades = listOf(closed) + state.trades)
        }
        clearPending()
        balance += profit
        val maxLoss = min(openingBalance * limit.percent / 100.0, limit.amount ?: Double.POSITIVE_INFINITY)
        if (max(0.0, -todayProfit()) >= maxLoss || streak() >= settings.consecutiveLossLimit) {
            running = false
            publish("PAUSADO", "Protecao DEMO: limite de perda diaria ou " + settings.consecutiveLossLimit + " derrotas seguidas.")
        } else {
            publish(if (running) "ATIVO" else "PARADO",
                "Contrato DEMO encerrado: " + (if (profit >= 0) "+" else "") + profit + " " + currency + ".")
        }
    }

    private fun clearPending() {
        inFlight = false
        contractId = null
        waitingFor = ""
        pendingSymbol = ""
        pendingDirection = ""
        pendingStake = 0.0
        pendingTimestamp = 0L
        if (!persist()) {
            inFlight = true
            running = false
            publish("BLOQUEADO", "Falha ao salvar resultado; nao operar ate corrigir armazenamento.")
        }
    }

    private fun fail(reason: String) {
        running = false
        waitingFor = ""
        publish(if (inFlight) "BLOQUEADO" else "PAUSADO", reason)
    }

    private fun ema(prices: List<Double>, period: Int): List<Double> {
        val factor = 2.0 / (period + 1)
        val result = mutableListOf(prices.first())
        prices.drop(1).forEach { result.add(it * factor + result.last() * (1 - factor)) }
        return result
    }

    private fun rsi(prices: List<Double>): Double {
        val changes = prices.takeLast(15).zipWithNext { a, b -> b - a }
        val gains = changes.sumOf { max(0.0, it) } / 14.0
        val losses = changes.sumOf { max(0.0, -it) } / 14.0
        return when {
            losses == 0.0 && gains == 0.0 -> 50.0
            losses == 0.0 -> 100.0
            else -> 100.0 - 100.0 / (1 + gains / losses)
        }
    }
}

package br.com.velalab.ia

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.random.Random

/** Em Fase 1, a simulação NÃO lê candles ou cotações reais. */
data class PendingTrade(
    val id: Long,
    val asset: String,
    val direction: String,
    val stake: Double,
    val payout: Double,
    val duration: Int,
)

data class SimulatorUiState(
    val status: String = "PARADO",
    val settings: RiskSettings = RiskSettings(),
    val trades: List<SimTrade> = emptyList(),
    val pending: List<PendingTrade> = emptyList(),
    val notice: String = "Somente simulação interna com dados fictícios.",
) {
    val initialBalance get() = 10_000.0
    val balance get() = initialBalance + trades.sumOf { it.result }
    val todayResult get(): Double = trades.filter { isToday(it.timestamp) }.sumOf { it.result }
    val todayOpeningBalance get() = balance - todayResult
    val riskCommitted get() = pending.sumOf { it.stake }
    val dailyLossWithOpenRisk get() = max(0.0, -todayResult) + riskCommitted
    val winCount get() = trades.count { it.won }
    val lossCount get() = trades.size - winCount
    val winRate get() = if (trades.isEmpty()) 0.0 else winCount * 100.0 / trades.size

    companion object {
        fun isToday(timestamp: Long): Boolean = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
    }
}

class SimViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SimStorage(app.applicationContext)
    private val random = Random.Default
    private var loop: Job? = null
    private var tradeCounter = System.currentTimeMillis()
    private val _ui = MutableStateFlow(SimulatorUiState(settings = store.readSettings(), trades = store.readTrades()))
    val ui = _ui.asStateFlow()

    fun saveSettings(settings: RiskSettings): String? {
        settings.error()?.let { return it }
        // Mudança somente por ação explícita do usuário; UI confirma aumento de risco.
        store.saveSettings(settings)
        _ui.update { it.copy(settings = settings, notice = "Configurações salvas neste celular.") }
        enforceRisk()
        return null
    }

    fun start() {
        if (_ui.value.status == "ATIVO") return
        if (!canOpen(_ui.value, 0.0)) {
            _ui.update { it.copy(status = "PAUSADO", notice = "Limite de risco atingido. Não é possível iniciar.") }
            return
        }
        _ui.update { it.copy(status = "ATIVO", notice = "Simulação acelerada, sem ordens na Deriv.") }
        loop?.cancel()
        loop = viewModelScope.launch {
            while (isActive && _ui.value.status == "ATIVO") {
                generateVirtualOpportunity()
                delay(4_500)
            }
        }
    }

    fun pause() {
        loop?.cancel()
        _ui.update { it.copy(status = "PAUSADO", notice = "Novas operações fictícias pausadas; pendentes serão concluídas.") }
    }

    fun stop() {
        loop?.cancel()
        // Em simulador acelerado, cancelar posições fictícias abertas sem contabilizar resultado.
        _ui.update { it.copy(status = "PARADO", pending = emptyList(), notice = "Simulação parada. Posições virtuais pendentes canceladas.") }
    }

    private fun canOpen(s: SimulatorUiState, additionalStake: Double): Boolean {
        val maxLoss = s.todayOpeningBalance * s.settings.dailyLossPercent / 100.0
        val riskInUse = s.dailyLossWithOpenRisk + additionalStake
        if (s.balance <= 0 || riskInUse >= maxLoss) return false
        if (recentLossStreak(s.trades) >= s.settings.consecutiveLossLimit) return false
        return true
    }

    private fun recentLossStreak(trades: List<SimTrade>): Int {
        var count = 0
        for (trade in trades) {
            if (trade.won) break
            count++
        }
        return count
    }

    private fun generateVirtualOpportunity() {
        val s = _ui.value
        if (s.status != "ATIVO" || s.pending.size >= s.settings.maxConcurrent) return
        val hypotheticalPayout = listOf(0.78, 0.82, 0.85).random(random)
        if (hypotheticalPayout * 100 < s.settings.minPayoutPercent) {
            _ui.update { it.copy(notice = "Sinal fictício descartado: payout abaixo do mínimo.") }
            return
        }
        val stake = s.balance * s.settings.stakePercent / 100.0
        val maxStake = s.balance * s.settings.maxStakePercent / 100.0
        if (stake > maxStake || !canOpen(s, stake)) {
            pauseForRisk("Risco agregado ou limite diário impede nova entrada.")
            return
        }
        val duration = if (s.settings.durationMinutes == 0) listOf(1, 5).random(random) else s.settings.durationMinutes
        val trade = PendingTrade(
            id = ++tradeCounter,
            asset = listOf("EUR/USD • FICTÍCIO", "GBP/USD • FICTÍCIO", "USD/JPY • FICTÍCIO").random(random),
            direction = if (random.nextBoolean()) "CALL" else "PUT",
            stake = stake,
            payout = hypotheticalPayout,
            duration = duration,
        )
        _ui.update { it.copy(pending = it.pending + trade, notice = "Entrada sintética; não utiliza o mercado da corretora.") }
        viewModelScope.launch {
            // Seis/doze segundos para uma expiração VIRTUAL de 1/5 min.
            delay(if (duration == 1) 6_000 else 12_000)
            if (_ui.value.pending.none { it.id == trade.id }) return@launch
            val won = random.nextDouble() < 0.48 // apenas um cenário arbitrário de demonstração.
            val net = if (won) trade.stake * trade.payout else -trade.stake
            val closed = SimTrade(
                id = trade.id, timestamp = System.currentTimeMillis(), asset = trade.asset,
                direction = trade.direction, stake = trade.stake,
                payout = trade.payout, duration = duration, won = won, result = net,
            )
            // Concluir e persistir mesmo se pausado, mas não se posição foi cancelada por "Parar".
            store.insertTrade(closed)
            _ui.update { it.copy(pending = it.pending.filterNot { p -> p.id == trade.id }, trades = listOf(closed) + it.trades) }
            enforceRisk()
        }
    }

    private fun pauseForRisk(reason: String) {
        loop?.cancel()
        _ui.update { it.copy(status = "PAUSADO", notice = reason) }
    }

    private fun enforceRisk() {
        val s = _ui.value
        val lossLimit = s.todayOpeningBalance * s.settings.dailyLossPercent / 100.0
        if (s.dailyLossWithOpenRisk >= lossLimit) pauseForRisk("Proteção: limite diário (incluindo risco em aberto) atingido.")
        else if (recentLossStreak(s.trades) >= s.settings.consecutiveLossLimit) {
            pauseForRisk("Proteção: ${s.settings.consecutiveLossLimit} perdas consecutivas. Robô pausado.")
        }
    }

    override fun onCleared() {
        loop?.cancel()
        store.close()
        super.onCleared()
    }
}

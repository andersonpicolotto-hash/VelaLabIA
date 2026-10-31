package br.com.velalab.ia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private val Background = Color(0xFF0A101A)
private val CardDark = Color(0xFF131F2D)
private val Accent = Color(0xFF4297F3)
private val Green = Color(0xFF37CF92)
private val Red = Color(0xFFF16878)
private val Light = Color(0xFFE6EFF8)
private val Muted = Color(0xFF93A5B8)

private val VelaColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF071522),
    background = Background,
    surface = CardDark,
    onBackground = Light,
    onSurface = Light,
    error = Red,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = VelaColors) { VelaApp() }
        }
    }
}

private enum class Tab(val label: String) {
    HOME("Painel"), TRADES("Operações"), RESEARCH("Pesquisa"), CONNECTION("Conexão"), SETTINGS("Ajustes")
}

@Composable
private fun VelaApp(vm: SimViewModel = viewModel(), derivVm: DerivConnectionViewModel = viewModel()) {
    val state by vm.ui.collectAsState()
    val deriv by derivVm.ui.collectAsState()
    var tab by remember { mutableStateOf(Tab.HOME) }
    Scaffold(
        containerColor = Background,
        topBar = {
            Column(Modifier.fillMaxWidth().background(Background).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp)) {
                Text("VELALAB IA", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Light)
                Spacer(Modifier.height(3.dp))
                Text("SIMULAÇÃO INTERNA  •  PESQUISA EXPERIMENTAL", style = MaterialTheme.typography.labelSmall, color = Accent)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = CardDark) {
                Tab.entries.forEach { option ->
                    NavigationBarItem(
                        selected = tab == option,
                        onClick = { tab = option },
                        label = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = {
                            Text(when (option) {
                                Tab.HOME -> "▤"
                                Tab.TRADES -> "◷"
                                Tab.RESEARCH -> "⌁"
                                Tab.CONNECTION -> "◉"
                                Tab.SETTINGS -> "⚙"
                            })
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.HOME -> Dashboard(state, vm, deriv)
                Tab.TRADES -> TradeHistory(state)
                Tab.RESEARCH -> ResearchPage()
                Tab.CONNECTION -> ConnectionPage(deriv, derivVm)
                Tab.SETTINGS -> RiskSettingsPage(state.settings, vm)
            }
        }
    }
}

private fun money(v: Double): String = NumberFormat.getNumberInstance(Locale("pt", "BR")).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}.format(v)

@Composable
private fun InfoCard(title: String, value: String, color: Color = Light, modifier: Modifier = Modifier) {
    Column(modifier.background(CardDark, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text(title, color = Muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(7.dp))
        Text(value, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = Light, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun Dashboard(state: SimulatorUiState, vm: SimViewModel, deriv: DerivConnectionUiState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Surface(color = Color(0xFF1B2F43), shape = RoundedCornerShape(12.dp)) {
            Text("● SIMULAÇÃO FICTÍCIA  |  DERIV: ${deriv.status}", color = Accent, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(12.dp))
        }
        Spacer(Modifier.height(15.dp))
        Text("Saldo fictício", color = Muted)
        Text("${money(state.balance)} CV", color = Light, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("CV = créditos virtuais. Não há dinheiro ou preços reais.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            InfoCard("Resultado hoje", "${if (state.todayResult >= 0) "+" else ""}${money(state.todayResult)} CV",
                if (state.todayResult >= 0) Green else Red, Modifier.weight(1f))
            InfoCard("Taxa de acerto", "${"%.1f".format(Locale.US, state.winRate)}%", Light, Modifier.weight(1f))
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            InfoCard("Operações concluídas", state.trades.size.toString(), Light, Modifier.weight(1f))
            InfoCard("Operações em aberto", "${state.pending.size} / ${state.settings.maxConcurrent}", Accent, Modifier.weight(1f))
        }
        Spacer(Modifier.height(22.dp))
        SectionTitle("Simulador • ${state.status}")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::start, enabled = state.status != "ATIVO", modifier = Modifier.weight(1f)) { Text("Iniciar") }
            OutlinedButton(onClick = vm::pause, enabled = state.status == "ATIVO", modifier = Modifier.weight(1f)) { Text("Pausar") }
            OutlinedButton(onClick = vm::stop, enabled = state.status != "PARADO", modifier = Modifier.weight(1f)) { Text("Parar") }
        }
        Spacer(Modifier.height(10.dp))
        Text(state.notice, color = if (state.notice.contains("Proteção") || state.notice.contains("limite")) Red else Muted,
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
        SectionTitle("Evolução do saldo fictício")
        Spacer(Modifier.height(8.dp))
        VirtualEquityChart(state.trades)
        Spacer(Modifier.height(18.dp))
        SectionTitle("Gerenciamento de risco")
        Spacer(Modifier.height(10.dp))
        InfoCard("Exposição pendente / limite diário",
            "${money(state.riskCommitted)} / ${money(state.todayOpeningBalance * state.settings.dailyLossPercent / 100.0)} CV",
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("Resultado aleatório; sem análise de gráficos, estratégias ou conexão com corretora. Vencimento virtual acelerado para teste.",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun VirtualEquityChart(trades: List<SimTrade>) {
    val chronological = trades.reversed()
    val points = mutableListOf(10_000.0)
    chronological.forEach { points.add(points.last() + it.result) }
    val minValue = points.minOrNull() ?: 0.0
    val maxValue = points.maxOrNull() ?: 1.0
    val range = max(0.01, maxValue - minValue)
    val lineColor = if (points.last() >= points.first()) Green else Red
    Surface(color = CardDark, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Canvas(Modifier.fillMaxWidth().height(145.dp)) {
                val chart = Path()
                points.forEachIndexed { idx, value ->
                    val x = (idx.toFloat() / max(1, points.size - 1)) * size.width
                    val y = ((maxValue - value) / range).toFloat() * (size.height - 18f) + 9f
                    if (idx == 0) chart.moveTo(x, y) else chart.lineTo(x, y)
                }
                drawLine(color = Muted.copy(alpha = .25f),
                    start = Offset(0f, size.height / 2), end = Offset(size.width, size.height / 2))
                drawPath(chart, color = lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
            Text("Gráfico do saldo do simulador, não é gráfico de preços.", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TradeHistory(state: SimulatorUiState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SectionTitle("Operações fictícias")
        Spacer(Modifier.height(7.dp))
        Text("${state.winCount} vitórias  •  ${state.lossCount} derrotas  •  ${state.pending.size} pendentes", color = Muted)
        Spacer(Modifier.height(15.dp))
        if (state.trades.isEmpty() && state.pending.isEmpty()) {
            InfoCard("Histórico", "Nenhuma operação ainda", modifier = Modifier.fillMaxWidth())
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(state.pending, key = { "p${it.id}" }) { p ->
                    Surface(color = CardDark, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(13.dp)) {
                            Text("${p.asset}  •  ${p.direction}  •  ABERTA", fontWeight = FontWeight.Bold)
                            Text("${money(p.stake)} CV • ${p.duration} min virtuais", color = Muted)
                        }
                    }
                }
                items(state.trades, key = { "t${it.id}" }) { trade ->
                    Surface(color = CardDark, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(13.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("${trade.asset}  •  ${trade.direction}", fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(3.dp))
                                Text("${SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(trade.timestamp))}  •  ${trade.duration} min virtuais  •  ${money(trade.stake)} CV", color = Muted,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text("${if (trade.result >= 0) "+" else ""}${money(trade.result)}", color = if (trade.won) Green else Red,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResearchPage() {
    val names = listOf(
        "Price Action", "Suporte e resistência", "EMA 9 / 21 / 200",
        "RSI + MACD", "Bandas de Bollinger", "Rompimento e reteste"
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Laboratório de estratégias")
        Spacer(Modifier.height(8.dp))
        Text("Os cálculos de indicadores e backtests estão no backend Python. A conexão do aplicativo ao motor remoto ainda depende da implantação do servidor e de testes de integração.",
            color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        names.forEach { name ->
            Surface(color = CardDark, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, color = Light, modifier = Modifier.weight(1f))
                    Text("EXPERIMENTAL", color = Accent, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(7.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("Nenhum sinal acima autoriza uma entrada. Não há IA treinada nem execução automática de ordens reais.",
            color = Red, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ConnectionPage(state: DerivConnectionUiState, vm: DerivConnectionViewModel) {
    var appId by remember { mutableStateOf("") }
    var pat by remember { mutableStateOf("") }
    var accountType by remember { mutableStateOf(DerivAccountType.DEMO) }
    var realAcknowledged by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Conexão oficial • Deriv")
        Spacer(Modifier.height(8.dp))
        Text("Consultar conta DEMO ou REAL, sem envio de ordens. O robô de simulação permanece completamente independente.", color = Muted)
        Spacer(Modifier.height(15.dp))
        InfoCard("Estado da conexão", state.status,
            if (state.status.startsWith("CONECTADO")) Green else if (state.status == "DESCONECTADO") Muted else Red,
            Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        InfoCard("Saldo recebido da Deriv (${state.accountType.displayName})",
            state.balance?.let { "${money(it)} ${state.currency}" } ?: "INDISPONÍVEL",
            if (state.balance != null) Green else Muted, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        InfoCard("Mercado público", "${state.marketStatus}${if (state.publicSymbolCount > 0) " • ${state.publicSymbolCount} ativos" else ""}",
            Accent, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text(state.message, color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = vm::checkPublic, modifier = Modifier.fillMaxWidth()) {
            Text("Testar feed público (sem conta)")
        }
        Spacer(Modifier.height(14.dp))
        SectionTitle("Escolha o tipo de conta")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(DerivAccountType.DEMO, DerivAccountType.REAL).forEach { choice ->
                FilterChip(selected = accountType == choice, onClick = {
                    accountType = choice
                    realAcknowledged = false
                    vm.disconnect()
                }, label = { Text(choice.displayName) })
            }
        }
        if (accountType == DerivAccountType.REAL) {
            Surface(color = CardDark, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("CONTA REAL — CONSULTA DE SALDO SOMENTE", color = Red, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Isto acessa dados financeiros reais. Não habilita o robô, não envia ordens e não altera o modo do simulador.",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = realAcknowledged, onCheckedChange = { realAcknowledged = it })
                        Text("Confirmo que quero consultar minha conta REAL", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Text("Cadastre um aplicativo PAT na Deriv e gere um token com permissões restritas. Nunca envie senha ou token por chat ou GitHub.",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = appId, onValueChange = { appId = it }, label = { Text("Deriv App ID") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = pat, onValueChange = { pat = it }, label = { Text("PAT (token de acesso)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Button(onClick = {
            vm.connect(appId, pat, accountType)
            pat = ""  // não salvar PAT no aparelho
        }, enabled = state.status != "CONECTANDO" && (accountType != DerivAccountType.REAL || realAcknowledged),
            modifier = Modifier.fillMaxWidth()) {
            Text("Conectar ${accountType.displayName} — somente leitura")
        }
        OutlinedButton(onClick = vm::disconnect, modifier = Modifier.fillMaxWidth()) { Text("Desconectar") }
        Spacer(Modifier.height(12.dp))
        Surface(color = CardDark, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("ORDENS REAIS: DESATIVADAS", color = Red, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("É possível consultar saldo REAL, mas não comprar contratos. O saldo DEMO e o saldo fictício do simulador não são misturados.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RiskSettingsPage(settings: RiskSettings, vm: SimViewModel) {
    var stake by remember(settings) { mutableStateOf(settings.stakePercent.toString()) }
    var stakeMax by remember(settings) { mutableStateOf(settings.maxStakePercent.toString()) }
    var daily by remember(settings) { mutableStateOf(settings.dailyLossPercent.toString()) }
    var losses by remember(settings) { mutableStateOf(settings.consecutiveLossLimit.toString()) }
    var payout by remember(settings) { mutableStateOf(settings.minPayoutPercent.toString()) }
    var concurrent by remember(settings) { mutableIntStateOf(settings.maxConcurrent) }
    var duration by remember(settings) { mutableIntStateOf(settings.durationMinutes) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSettings by remember { mutableStateOf<RiskSettings?>(null) }

    fun parseSettings(): RiskSettings? {
        val candidate = RiskSettings(
            stakePercent = stake.replace(',', '.').toDoubleOrNull() ?: return null,
            maxStakePercent = stakeMax.replace(',', '.').toDoubleOrNull() ?: return null,
            dailyLossPercent = daily.replace(',', '.').toDoubleOrNull() ?: return null,
            consecutiveLossLimit = losses.toIntOrNull() ?: return null,
            minPayoutPercent = payout.toIntOrNull() ?: return null,
            maxConcurrent = concurrent,
            durationMinutes = duration,
        )
        return candidate
    }

    if (confirmSettings != null) {
        AlertDialog(onDismissRequest = { confirmSettings = null },
            title = { Text("Confirmar aumento de risco") },
            text = { Text("Estas configurações ampliam a exposição simulada. Deseja realmente salvá-las? A IA nunca poderá aumentá-las automaticamente.") },
            confirmButton = { TextButton(onClick = { error = vm.saveSettings(confirmSettings!!); confirmSettings = null }) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = { confirmSettings = null }) { Text("Cancelar") } })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Gerenciamento de banca")
        Spacer(Modifier.height(6.dp))
        Text("Somente simulação interna. Alterações persistem no aparelho.", color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(15.dp))
        DecimalField("Entrada padrão (%)", stake) { stake = it }
        DecimalField("Entrada máxima (%)", stakeMax) { stakeMax = it }
        DecimalField("Limite de perda diária (%)", daily) { daily = it }
        DecimalField("Pausar após perdas consecutivas", losses) { losses = it }
        DecimalField("Payout mínimo (%)", payout) { payout = it }
        Spacer(Modifier.height(12.dp))
        Text("Máximo simultâneo: $concurrent", color = Light, fontWeight = FontWeight.SemiBold)
        Slider(value = concurrent.toFloat(), onValueChange = { concurrent = it.toInt().coerceIn(1, 4) }, valueRange = 1f..4f, steps = 2)
        Spacer(Modifier.height(8.dp))
        Text("Vencimento virtual", color = Light, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "1 minuto", 5 to "5 minutos", 0 to "Ambos").forEach { (minutes, label) ->
                FilterChip(selected = duration == minutes, onClick = { duration = minutes }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(color = CardDark, shape = RoundedCornerShape(12.dp)) {
            Text("Martingale: DESATIVADO  •  Conta real: BLOQUEADA", color = Muted, modifier = Modifier.padding(12.dp), textAlign = TextAlign.Start)
        }
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error.orEmpty(), color = Red)
        }
        Spacer(Modifier.height(15.dp))
        Button(onClick = {
            val candidate = parseSettings()
            error = candidate?.error() ?: if (candidate == null) "Preencha os valores com números válidos." else null
            if (candidate != null && error == null) {
                if (candidate.increasesRiskComparedTo(settings)) confirmSettings = candidate
                else error = vm.saveSettings(candidate)
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Salvar configurações") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DecimalField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

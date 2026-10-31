package br.com.velalab.ia

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Preferencias locais por ambiente, independentes do simulador ficticio.
 * Nao envia limites ao broker nem autoriza operacoes. Quando o executor DEMO/REAL
 * for implementado devera ler configuracao autenticada e aplicar OrderLedger
 * no servidor antes de enviar cada ordem; nao confiar apenas no telefone.
 */
internal data class BrokerDailyLimit(
    val percent: Double = 3.0,
    val amount: Double? = null,
) {
    fun validationError(): String? = when {
        !percent.isFinite() || percent < 0.1 || percent > 25.0 ->
            "O limite percentual precisa estar entre 0,1% e 25%."
        amount != null && (!amount.isFinite() || amount < 1.0 || amount > 1_000_000_000.0) ->
            "O teto monetario precisa ser positivo e valido."
        else -> null
    }
    fun increasesRiskComparedTo(old: BrokerDailyLimit): Boolean =
        percent > old.percent ||
        (old.amount != null && (amount == null || amount > old.amount))
}

internal class BrokerDailyLossStorage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "velalab_deriv_limits_v1", Context.MODE_PRIVATE
    )
    private fun prefix(type: DerivAccountType) = "broker_${type.apiName}_"

    fun load(type: DerivAccountType): BrokerDailyLimit {
        val prefix = prefix(type)
        val value = BrokerDailyLimit(
            percent = Double.fromBits(prefs.getLong(prefix + "percent", 3.0.toBits())),
            amount = if (prefs.contains(prefix + "amount")) {
                Double.fromBits(prefs.getLong(prefix + "amount", 0L))
            } else null
        )
        return if (value.validationError() == null) value else BrokerDailyLimit()
    }

    fun save(type: DerivAccountType, value: BrokerDailyLimit) {
        require(value.validationError() == null) { value.validationError().orEmpty() }
        val prefix = prefix(type)
        val edit = prefs.edit().putLong(prefix + "percent", value.percent.toBits())
        if (value.amount == null) edit.remove(prefix + "amount")
        else edit.putLong(prefix + "amount", value.amount.toBits())
        check(edit.commit()) { "Nao foi possivel salvar o limite no aparelho." }
    }
}

@Composable
internal fun BrokerDailyLossControls(type: DerivAccountType) {
    val context = LocalContext.current
    val storage = remember(context) { BrokerDailyLossStorage(context) }
    var saved by remember(type) { mutableStateOf(storage.load(type)) }
    var percentText by remember(type) { mutableStateOf(saved.percent.toString()) }
    var amountText by remember(type) { mutableStateOf(saved.amount?.toString().orEmpty()) }
    var info by remember(type) { mutableStateOf("") }
    var awaitingConfirmation by remember(type) { mutableStateOf<BrokerDailyLimit?>(null) }

    fun persist(value: BrokerDailyLimit) {
        try {
            storage.save(type, value)
            saved = value
            info = "Limite ${type.displayName} salvo neste celular. Protecao do servidor ainda nao conectada."
        } catch (_: Exception) {
            info = "Nao foi possivel salvar a configuracao."
        }
    }

    awaitingConfirmation?.let { candidate ->
        AlertDialog(
            onDismissRequest = { awaitingConfirmation = null },
            title = { Text("Confirmar aumento de risco") },
            text = { Text(
                "O novo limite permite perdas maiores para a conta ${type.displayName}. " +
                "Confirma a alteracao? Isto NAO habilita operacoes."
            ) },
            confirmButton = {
                TextButton(onClick = {
                    persist(candidate)
                    awaitingConfirmation = null
                }) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = { awaitingConfirmation = null }) { Text("Cancelar") }
            }
        )
    }

    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("Limite de perda diaria • ${type.displayName}",
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Escolha o percentual da banca e, se desejar, um valor fixo adicional. " +
                 "Quando ambos existirem, vale o limite menor.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = percentText, onValueChange = { percentText = it },
                label = { Text("Perda maxima diaria (%)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = amountText, onValueChange = { amountText = it },
                label = { Text("Teto na moeda da conta (opcional)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val percentage = percentText.trim().replace(',', '.').toDoubleOrNull()
                val amount = if (amountText.isBlank()) null
                             else amountText.trim().replace(',', '.').toDoubleOrNull()
                if (percentage == null || (amountText.isNotBlank() && amount == null)) {
                    info = "Digite valores numericos validos."
                } else {
                    val candidate = BrokerDailyLimit(percentage, amount)
                    val error = candidate.validationError()
                    if (error != null) info = error
                    else if (candidate.increasesRiskComparedTo(saved)) awaitingConfirmation = candidate
                    else persist(candidate)
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Salvar limite ${type.displayName}")
            }
            if (info.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(info, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Text("IMPORTANTE: esta configuracao esta salva localmente e ainda NAO controla " +
                 "operacoes da Deriv. O robô da corretora continua indisponivel nesta versao.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }
    }
}

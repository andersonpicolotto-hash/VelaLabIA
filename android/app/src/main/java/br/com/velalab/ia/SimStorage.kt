package br.com.velalab.ia

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Dados exclusivamente fictícios; nunca são registros de ordens da corretora. */
data class SimTrade(
    val id: Long,
    val timestamp: Long,
    val asset: String,
    val direction: String,
    val stake: Double,
    val payout: Double,
    val duration: Int,
    val won: Boolean,
    val result: Double,
)

data class RiskSettings(
    val stakePercent: Double = 0.5,
    val maxStakePercent: Double = 1.0,
    val dailyLossPercent: Double = 3.0,
    val consecutiveLossLimit: Int = 4,
    val minPayoutPercent: Int = 80,
    val maxConcurrent: Int = 1,
    val durationMinutes: Int = 1, // 0 = misto, 1 ou 5 minutos virtuais
) {
    fun error(): String? = when {
        !stakePercent.isFinite() || stakePercent <= 0.0 || stakePercent > 10.0 -> "Entrada deve ficar entre 0 e 10%."
        !maxStakePercent.isFinite() || maxStakePercent <= 0.0 || maxStakePercent > 10.0 -> "Entrada máxima deve ficar entre 0 e 10%."
        stakePercent > maxStakePercent -> "Entrada padrão não pode exceder a máxima."
        !dailyLossPercent.isFinite() || dailyLossPercent <= 0.0 || dailyLossPercent > 25.0 -> "Limite diário deve ficar entre 0 e 25%."
        consecutiveLossLimit !in 1..20 -> "Limite de perdas consecutivas inválido."
        minPayoutPercent !in 1..100 -> "Payout mínimo precisa estar entre 1 e 100%."
        maxConcurrent !in 1..4 -> "Máximo permitido: quatro operações simultâneas."
        durationMinutes !in listOf(0, 1, 5) -> "Vencimento deve ser 1 minuto, 5 minutos ou automático."
        else -> null
    }

    fun increasesRiskComparedTo(previous: RiskSettings): Boolean =
        stakePercent > previous.stakePercent ||
        maxStakePercent > previous.maxStakePercent ||
        dailyLossPercent > previous.dailyLossPercent ||
        consecutiveLossLimit > previous.consecutiveLossLimit ||
        maxConcurrent > previous.maxConcurrent ||
        minPayoutPercent < previous.minPayoutPercent
}

class SimStorage(context: Context) : SQLiteOpenHelper(context, "velalab_sim_v1.db", null, 1) {
    private val preferences = context.getSharedPreferences("velalab_config_v1", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE trades (
            id INTEGER PRIMARY KEY,
            timestamp INTEGER NOT NULL,
            asset TEXT NOT NULL,
            direction TEXT NOT NULL,
            stake REAL NOT NULL,
            payout REAL NOT NULL,
            duration INTEGER NOT NULL,
            won INTEGER NOT NULL,
            result REAL NOT NULL
        )""")
        db.execSQL("CREATE INDEX idx_trades_timestamp ON trades(timestamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Reservado para migrações incrementais; não apagar histórico automaticamente.
    }

    fun readSettings() = RiskSettings(
        stakePercent = Double.fromBits(preferences.getLong("stake", 0.5.toBits())),
        maxStakePercent = Double.fromBits(preferences.getLong("maxstake", 1.0.toBits())),
        dailyLossPercent = Double.fromBits(preferences.getLong("dailyloss", 3.0.toBits())),
        consecutiveLossLimit = preferences.getInt("streak", 4),
        minPayoutPercent = preferences.getInt("payout", 80),
        maxConcurrent = preferences.getInt("concurrent", 1),
        durationMinutes = preferences.getInt("duration", 1),
    )

    fun saveSettings(value: RiskSettings) {
        require(value.error() == null) { value.error().orEmpty() }
        preferences.edit()
            .putLong("stake", value.stakePercent.toBits())
            .putLong("maxstake", value.maxStakePercent.toBits())
            .putLong("dailyloss", value.dailyLossPercent.toBits())
            .putInt("streak", value.consecutiveLossLimit)
            .putInt("payout", value.minPayoutPercent)
            .putInt("concurrent", value.maxConcurrent)
            .putInt("duration", value.durationMinutes)
            .apply()
    }

    fun insertTrade(t: SimTrade) {
        writableDatabase.execSQL(
            "INSERT INTO trades (id,timestamp,asset,direction,stake,payout,duration,won,result) VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf(t.id, t.timestamp, t.asset, t.direction, t.stake, t.payout,
                t.duration, if (t.won) 1 else 0, t.result),
        )
    }

    fun readTrades(): List<SimTrade> {
        val results = mutableListOf<SimTrade>()
        readableDatabase.rawQuery("SELECT id,timestamp,asset,direction,stake,payout,duration,won,result FROM trades ORDER BY timestamp DESC, id DESC", null).use { c ->
            while (c.moveToNext()) {
                results.add(SimTrade(
                    id = c.getLong(0), timestamp = c.getLong(1), asset = c.getString(2),
                    direction = c.getString(3), stake = c.getDouble(4), payout = c.getDouble(5),
                    duration = c.getInt(6), won = c.getInt(7) == 1, result = c.getDouble(8),
                ))
            }
        }
        return results
    }
}

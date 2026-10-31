"""Risk/ledger separado por ambiente. Reserva idempotente ANTES de qualquer ordem.

Falha fechado. Em caso de timeout de compra, NÃO reenviar automaticamente.
"""
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal, ROUND_DOWN
from pathlib import Path
import sqlite3
import threading


D = lambda x: Decimal(str(x))


@dataclass(frozen=True)
class RiskLimits:
    stake_percent: Decimal = D("0.5")
    max_stake_percent: Decimal = D("1.0")
    max_daily_loss_percent: Decimal = D("3.0")
    max_consecutive_losses: int = 4
    max_open: int = 1
    min_payout_percent: Decimal = D("80")

    def __post_init__(self):
        if not (D("0") < self.stake_percent <= self.max_stake_percent <= D("1")):
            raise ValueError("Limites por entrada inválidos")
        if not (D("0") < self.max_daily_loss_percent <= D("3")):
            raise ValueError("Limite diário inválido")
        if not 1 <= self.max_open <= 4 or not 1 <= self.max_consecutive_losses <= 4:
            raise ValueError("Limites de concorrência ou perdas inválidos")
        if not D("80") <= self.min_payout_percent <= D("100"):
            raise ValueError("Payout mínimo inválido")


class RiskRejected(ValueError): pass


class OrderLedger:
    """Use um banco persistente; o runner deve possuir um único escritor."""
    def __init__(self, path: str, limits: RiskLimits | None = None):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.limits = limits or RiskLimits()
        self._lock = threading.RLock()
        with self._db() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS risk_reservations (
                request_id TEXT PRIMARY KEY, account_type TEXT NOT NULL, account_id TEXT NOT NULL,
                day TEXT NOT NULL, asset TEXT NOT NULL, stake TEXT NOT NULL, payout TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('PENDING','UNKNOWN','OPEN','SETTLED','REJECTED')),
                net TEXT, broker_contract_id TEXT, created_at TEXT NOT NULL)""")

    def _db(self): return sqlite3.connect(self.path, timeout=20, isolation_level=None)

    def reserve(self, *, request_id: str, account_type: str, account_id: str, asset: str,
                balance: str, stake: str, payout_percent: str, connected: bool,
                market_fresh: bool, open_positions_reconciled: bool, now: datetime | None = None) -> dict:
        if account_type not in ("DEMO", "REAL") or not account_id or not asset or not request_id or len(request_id) > 120:
            raise RiskRejected("Conta, ativo ou identificador inválido")
        if not (connected and market_fresh and open_positions_reconciled):
            raise RiskRejected("Dados, conexão ou posições abertas não confirmados")
        try:
            b, amount, payout = D(balance), D(stake), D(payout_percent)
            if not all(x.is_finite() for x in (b, amount, payout)):
                raise ValueError
        except Exception as e: raise RiskRejected("Valores inválidos") from e
        if b <= 0 or amount <= 0: raise RiskRejected("Saldo ou entrada inválida")
        if amount != amount.quantize(D("0.01"), rounding=ROUND_DOWN):
            raise RiskRejected("Entrada deve ter duas casas decimais")
        if payout < self.limits.min_payout_percent:
            raise RiskRejected("Payout abaixo do mínimo")
        if amount > (b * self.limits.max_stake_percent / 100):
            raise RiskRejected("Entrada excede percentual máximo")
        now = now or datetime.now(timezone.utc)
        if now.tzinfo is None: raise RiskRejected("Horário precisa conter fuso")
        day = now.astimezone(timezone.utc).date().isoformat()
        with self._lock, self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                if db.execute("SELECT 1 FROM risk_reservations WHERE request_id=?", (request_id,)).fetchone():
                    raise RiskRejected("Ordem duplicada: solicitação já registrada")
                rows = db.execute("SELECT asset,stake,status,net FROM risk_reservations WHERE account_type=? AND account_id=? AND day=? ORDER BY created_at,request_id",
                                  (account_type,account_id,day)).fetchall()
                open_rows = [r for r in rows if r[2] in ("PENDING", "UNKNOWN", "OPEN")]
                if len(open_rows) >= self.limits.max_open:
                    raise RiskRejected("Limite de operações simultâneas")
                if any(r[0] == asset for r in open_rows):
                    raise RiskRejected("Já existe operação aberta no mesmo ativo")
                # Pior cenário: toda posição ainda aberta perde o valor investido.
                exposure = sum((D(r[1]) for r in open_rows), D("0"))
                realised = sum((D(r[3]) for r in rows if r[2] == "SETTLED" and r[3] is not None), D("0"))
                gross_day_start = b - realised  # estimativa conservadora exige snapshot de saldo atual
                if gross_day_start <= 0: raise RiskRejected("Saldo de abertura inválido")
                budget = gross_day_start * self.limits.max_daily_loss_percent / 100
                if max(D("0"), -realised) + exposure + amount > budget:
                    raise RiskRejected("Exposição potencial supera perda diária")
                streak = 0
                for row in reversed(rows):
                    if row[2] != "SETTLED": continue
                    if D(row[3]) >= 0: break
                    streak += 1
                if streak >= self.limits.max_consecutive_losses:
                    raise RiskRejected("Pausa após perdas consecutivas")
                db.execute("""INSERT INTO risk_reservations
                    (request_id,account_type,account_id,day,asset,stake,payout,status,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?)""",
                    (request_id,account_type,account_id,day,asset,str(amount),str(payout),"PENDING",now.isoformat()))
                db.execute("COMMIT")
            except Exception:
                db.execute("ROLLBACK")
                raise
        return {"request_id": request_id, "state": "PENDING", "exposure": str(exposure + amount)}

    def mark_unknown(self, request_id: str):
        """Resultado de envio incerto. Manter exposição até reconciliação manual no broker."""
        self._mark(request_id, "UNKNOWN")

    def mark_open(self, request_id: str, contract_id: str):
        if not contract_id: raise ValueError("Contrato vazio")
        self._mark(request_id, "OPEN", contract_id=contract_id)

    def _mark(self, request_id: str, state: str, contract_id: str | None = None):
        with self._lock, self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                row = db.execute("SELECT status FROM risk_reservations WHERE request_id=?",(request_id,)).fetchone()
                if not row or row[0] not in ("PENDING", "UNKNOWN"):
                    raise RiskRejected("Reserva inexistente ou estado inválido")
                db.execute("UPDATE risk_reservations SET status=?,broker_contract_id=COALESCE(?,broker_contract_id) WHERE request_id=?",
                           (state,contract_id,request_id))
                db.execute("COMMIT")
            except Exception:
                db.execute("ROLLBACK")
                raise

    def settle(self, request_id: str, net: str):
        result = D(net)
        if not result.is_finite(): raise ValueError("Resultado inválido")
        with self._lock, self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                row = db.execute("SELECT status,stake FROM risk_reservations WHERE request_id=?", (request_id,)).fetchone()
                if not row or row[0] not in ("OPEN", "UNKNOWN"):
                    raise RiskRejected("Liquidação exige reconciliação da posição")
                if result < -D(row[1]): raise RiskRejected("Perda maior que a exposição")
                db.execute("UPDATE risk_reservations SET status='SETTLED',net=? WHERE request_id=?", (str(result),request_id))
                db.execute("COMMIT")
            except Exception:
                db.execute("ROLLBACK")
                raise

    def snapshot(self, account_type: str, account_id: str):
        with self._db() as db:
            rows = db.execute("SELECT request_id,status,asset,stake,net,broker_contract_id FROM risk_reservations WHERE account_type=? AND account_id=? ORDER BY created_at DESC",(account_type,account_id)).fetchall()
        return [dict(request_id=r[0], state=r[1], asset=r[2], stake=r[3], net=r[4], contract_id=r[5]) for r in rows]

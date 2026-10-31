"""Fase 3: validação de challengers, persistência e rollback; nunca altera risco."""
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import json
import sqlite3
from pathlib import Path


@dataclass(frozen=True)
class CandidateReport:
    name: str
    version: str
    validation_trades: int
    validation_net: float
    validation_drawdown: float
    test_trades: int
    test_net: float
    test_drawdown: float


def evaluate_candidate(report: CandidateReport, minimum_trades: int = 100, max_dd_units: float = 30.0) -> tuple[bool, str]:
    if report.validation_trades < minimum_trades or report.test_trades < minimum_trades:
        return False, "Amostras insuficientes em validação/teste"
    if report.validation_net <= 0 or report.test_net <= 0:
        return False, "Sem resultado positivo nas duas janelas fora do treino"
    if report.validation_drawdown > max_dd_units or report.test_drawdown > max_dd_units:
        return False, "Drawdown excessivo"
    return True, "Candidato elegível para promoção em ambiente demonstrativo"


class VersionRegistry:
    def __init__(self, path: str):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        with sqlite3.connect(path) as db:
            db.execute("""CREATE TABLE IF NOT EXISTS strategy_versions (
                id INTEGER PRIMARY KEY, created_at TEXT NOT NULL, name TEXT NOT NULL,
                version TEXT NOT NULL, report TEXT NOT NULL, state TEXT NOT NULL,
                UNIQUE (name, version))""")

    def register(self, report: CandidateReport, minimum_trades: int = 100) -> dict:
        ok, reason = evaluate_candidate(report, minimum_trades)
        state = "VALIDATED_DEMO_CANDIDATE" if ok else "REJECTED"
        with sqlite3.connect(self.path) as db:
            db.execute("INSERT INTO strategy_versions(created_at,name,version,report,state) VALUES (?,?,?,?,?)",
                       (datetime.now(timezone.utc).isoformat(), report.name, report.version, json.dumps(asdict(report)), state))
        return {"state": state, "reason": reason}

    def promote_demo(self, name: str, version: str) -> None:
        with sqlite3.connect(self.path) as db:
            row = db.execute("SELECT state FROM strategy_versions WHERE name=? AND version=?", (name, version)).fetchone()
            if not row or row[0] != "VALIDATED_DEMO_CANDIDATE":
                raise ValueError("Versão não aprovada para DEMO")
            db.execute("UPDATE strategy_versions SET state='ARCHIVED' WHERE name=? AND state='DEMO_ACTIVE'", (name,))
            db.execute("UPDATE strategy_versions SET state='DEMO_ACTIVE' WHERE name=? AND version=?", (name, version))

    def rollback_demo(self, name: str, previous_version: str) -> None:
        """Requer versão já conhecida; nunca habilita real por efeito colateral."""
        with sqlite3.connect(self.path) as db:
            prev = db.execute("SELECT state FROM strategy_versions WHERE name=? AND version=?", (name, previous_version)).fetchone()
            if not prev or prev[0] not in ("ARCHIVED", "DEMO_ACTIVE"):
                raise ValueError("Versão anterior indisponível")
            db.execute("UPDATE strategy_versions SET state='ROLLED_BACK' WHERE name=? AND state='DEMO_ACTIVE'", (name,))
            db.execute("UPDATE strategy_versions SET state='DEMO_ACTIVE' WHERE name=? AND version=?", (name, previous_version))

    def list_versions(self):
        with sqlite3.connect(self.path) as db:
            rows = db.execute("SELECT name,version,state,created_at FROM strategy_versions ORDER BY id DESC").fetchall()
        return [dict(name=r[0], version=r[1], state=r[2], created_at=r[3]) for r in rows]

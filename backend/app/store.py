import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from .models import RiskConfig, Trade


class Store:
    """SQLite mínimo. Transações são atômicas e acessos protegidos no processo."""
    def __init__(self, db_path: str):
        self.db_path = db_path
        self._lock = threading.RLock()
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as con:
            con.execute("PRAGMA journal_mode=WAL")
            con.execute("CREATE TABLE IF NOT EXISTS kv (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
            con.execute("""CREATE TABLE IF NOT EXISTS trades (
                id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT NOT NULL,
                mode TEXT NOT NULL CHECK (mode = 'SIMULATOR'),
                asset TEXT NOT NULL, direction TEXT NOT NULL,
                stake REAL NOT NULL, payout REAL NOT NULL,
                duration_minutes INTEGER NOT NULL, win INTEGER NOT NULL,
                net REAL NOT NULL
            )""")

    def _connect(self):
        return sqlite3.connect(self.db_path, timeout=10)

    def load_settings(self) -> RiskConfig:
        with self._lock, self._connect() as con:
            row = con.execute("SELECT value FROM kv WHERE name='settings'").fetchone()
        return RiskConfig.model_validate_json(row[0]) if row else RiskConfig()

    def save_settings(self, settings: RiskConfig):
        self._set("settings", settings.model_dump_json())

    def _set(self, name: str, value: str):
        with self._lock, self._connect() as con:
            con.execute("INSERT INTO kv (name,value) VALUES (?,?) ON CONFLICT(name) DO UPDATE SET value=excluded.value", (name, value))

    def read_status(self) -> str:
        # Nunca reiniciar um processo em modo ativo: estado seguro por padrão.
        with self._lock, self._connect() as con:
            row = con.execute("SELECT value FROM kv WHERE name='status'").fetchone()
        return row[0] if row else "PARADO"

    def set_status(self, status: str):
        assert status in ("ATIVO", "PAUSADO", "PARADO")
        self._set("status", status)

    def trades(self, limit: int | None = None) -> list[Trade]:
        sql = "SELECT id,timestamp,mode,asset,direction,stake,payout,duration_minutes,win,net FROM trades ORDER BY id DESC"
        if limit is not None:
            sql += " LIMIT ?"
        with self._lock, self._connect() as con:
            rows = con.execute(sql, (limit,) if limit is not None else ()).fetchall()
        return [Trade(id=r[0], timestamp=r[1], mode=r[2], asset=r[3], direction=r[4],
                      stake=r[5], payout=r[6], duration_minutes=r[7], win=bool(r[8]), net=r[9])
                for r in rows]

    def add_trade(self, asset: str, direction: str, stake: float, payout: float, duration: int, win: bool, net: float) -> Trade:
        now = datetime.now(timezone.utc).isoformat()
        with self._lock, self._connect() as con:
            cur = con.execute("""INSERT INTO trades
                (timestamp,mode,asset,direction,stake,payout,duration_minutes,win,net)
                VALUES (?,?,?,?,?,?,?,?,?)""",
                (now, "SIMULATOR", asset, direction, stake, payout, duration, int(win), net))
            item_id = cur.lastrowid
        return Trade(id=item_id, timestamp=now, asset=asset, direction=direction,
                     stake=stake, payout=payout, duration_minutes=duration, win=win, net=net)

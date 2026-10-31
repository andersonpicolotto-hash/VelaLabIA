"""Loop de leitura e armazenamento de candles públicos. NÃO executa trades.

Configure assets permitidos por nome; dados podem ter lacunas e atraso.
"""
import asyncio
from datetime import datetime, timezone
import json
import logging
import os
import signal
import sqlite3
from pathlib import Path
from .deriv_official import public_candles

log = logging.getLogger("velalab.observer")


class ObservationStore:
    def __init__(self, db_path):
        self.path = db_path
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(self.path) as db:
            db.execute("""CREATE TABLE IF NOT EXISTS market_observations (
                symbol TEXT NOT NULL, epoch INTEGER NOT NULL, open REAL NOT NULL,
                high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL,
                collected_at TEXT NOT NULL, PRIMARY KEY (symbol,epoch))""")

    def put(self, symbol, candles):
        now = datetime.now(timezone.utc).isoformat()
        rows=[]
        for c in candles:
            try:
                rows.append((symbol,int(c["epoch"]),float(c["open"]),float(c["high"]),
                             float(c["low"]),float(c["close"]),now))
            except (KeyError,ValueError,TypeError): continue
        if rows:
            with sqlite3.connect(self.path) as db:
                db.executemany("""INSERT OR IGNORE INTO market_observations
                    (symbol,epoch,open,high,low,close,collected_at) VALUES (?,?,?,?,?,?,?)""", rows)
        return len(rows)


async def observer_loop(db_path, symbols: list[str], *, interval_seconds=300, stop: asyncio.Event | None = None):
    """Análise de dados públicos mesmo sem negociações; segura para rodar em servidor futuro."""
    if not symbols: raise ValueError("Selecione ativos para acompanhar")
    if interval_seconds < 60: raise ValueError("Intervalo menor que 60s não permitido")
    db=ObservationStore(db_path)
    stop=stop or asyncio.Event()
    while not stop.is_set():
        for symbol in symbols:
            if stop.is_set(): break
            try:
                count=db.put(symbol,await public_candles(symbol,500))
                log.info("market symbol=%s records=%d",symbol,count)
            except Exception as e:
                # Não capturar mensagens de token/OTP: consultas são públicas.
                log.warning("market symbol=%s status=unavailable error_type=%s",symbol,type(e).__name__)
        try: await asyncio.wait_for(stop.wait(),timeout=interval_seconds)
        except asyncio.TimeoutError: continue


def main():
    logging.basicConfig(level=logging.INFO)
    symbols=[s.strip() for s in os.getenv("VELALAB_WATCH_SYMBOLS","").split(",") if s.strip()]
    asyncio.run(observer_loop(os.getenv("VELALAB_DB_PATH", "./velalab.db"),symbols))


if __name__ == "__main__": main()

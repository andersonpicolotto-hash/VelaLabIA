"""Fase 2: indicadores sobre OHLC cronológico, sem dados futuros.

Esta biblioteca calcula sinais candidatos; não garante acerto nem executa ordens.
"""
from dataclasses import dataclass
from datetime import datetime
from math import sqrt, isfinite
from statistics import mean, pstdev
from typing import Literal


@dataclass(frozen=True)
class Candle:
    timestamp: datetime
    open: float
    high: float
    low: float
    close: float
    volume: float = 0.0

    def __post_init__(self):
        if not all(isfinite(v) for v in (self.open, self.high, self.low, self.close, self.volume)):
            raise ValueError("Candle não finito")
        if self.low > min(self.open, self.close) or self.high < max(self.open, self.close) or self.high < self.low:
            raise ValueError("OHLC inválido")


def validate_candles(candles: list[Candle], minimum: int = 1) -> None:
    if len(candles) < minimum:
        raise ValueError(f"São necessários pelo menos {minimum} candles")
    if any(a.timestamp >= b.timestamp for a, b in zip(candles, candles[1:])):
        raise ValueError("Candles devem ter datas únicas em ordem crescente")


def ema(values: list[float], length: int) -> list[float | None]:
    if length <= 1: raise ValueError("Período EMA inválido")
    out: list[float | None] = [None] * len(values)
    if len(values) < length: return out
    out[length - 1] = mean(values[:length])
    alpha = 2 / (length + 1)
    for i in range(length, len(values)):
        out[i] = alpha * values[i] + (1 - alpha) * out[i - 1]
    return out


def rsi(values: list[float], length: int = 14) -> list[float | None]:
    out: list[float | None] = [None] * len(values)
    if length < 2 or len(values) <= length: return out
    changes = [b - a for a, b in zip(values, values[1:])]
    gain = sum(max(0.0, d) for d in changes[:length]) / length
    loss = sum(max(0.0, -d) for d in changes[:length]) / length
    def score(g, l): return 100.0 if l == 0 and g > 0 else (50.0 if l == 0 and g == 0 else 100 - 100 / (1 + g / l))
    out[length] = score(gain, loss)
    for i in range(length + 1, len(values)):
        delta = changes[i - 1]
        gain = (gain * (length - 1) + max(0, delta)) / length
        loss = (loss * (length - 1) + max(0, -delta)) / length
        out[i] = score(gain, loss)
    return out


def macd(values: list[float]) -> tuple[list[float | None], list[float | None]]:
    short, long = ema(values, 12), ema(values, 26)
    line = [a - b if a is not None and b is not None else None for a, b in zip(short, long)]
    valid = [v for v in line if v is not None]
    smoothed = ema(valid, 9)
    signal = [None] * (len(line) - len(valid)) + smoothed
    return line, signal


def bollinger(values: list[float], length: int = 20, deviations: float = 2):
    if length < 2: raise ValueError("Período Bollinger inválido")
    bands = []
    for i in range(len(values)):
        if i + 1 < length: bands.append(None); continue
        subset = values[i - length + 1:i + 1]
        m = mean(subset)
        bands.append((m - deviations * pstdev(subset), m, m + deviations * pstdev(subset)))
    return bands


STRATEGIES = ("tendencia_ema", "rsi_macd", "bollinger", "price_action", "suporte_resistencia", "rompimento_reteste")


def signals(candles: list[Candle], enabled: tuple[str, ...] = STRATEGIES) -> dict[str, Literal["CALL", "PUT"] | None]:
    validate_candles(candles)
    if set(enabled) - set(STRATEGIES): raise ValueError("Estratégia desconhecida")
    c = [x.close for x in candles]
    result: dict[str, Literal["CALL", "PUT"] | None] = {}
    if "tendencia_ema" in enabled:
        a, b, d = (ema(c, n)[-1] for n in (9, 21, 200))
        result["tendencia_ema"] = "CALL" if None not in (a,b,d) and a > b > d else "PUT" if None not in (a,b,d) and a < b < d else None
    if "rsi_macd" in enabled:
        oscillator = rsi(c)[-1]; line, sig = macd(c)
        result["rsi_macd"] = ("CALL" if oscillator is not None and line[-1] is not None and sig[-1] is not None and oscillator > 50 and line[-1] > sig[-1]
                              else "PUT" if oscillator is not None and line[-1] is not None and sig[-1] is not None and oscillator < 50 and line[-1] < sig[-1] else None)
    if "bollinger" in enabled:
        band = bollinger(c)[-1]
        result["bollinger"] = "CALL" if band and c[-1] < band[0] else "PUT" if band and c[-1] > band[2] else None
    if "price_action" in enabled:
        if len(candles) < 2: result["price_action"] = None
        else:
            p, q = candles[-2:]
            result["price_action"] = ("CALL" if p.close < p.open and q.close > q.open and q.close >= p.open and q.open <= p.close
                                      else "PUT" if p.close > p.open and q.close < q.open and q.close <= p.open and q.open >= p.close else None)
    if "suporte_resistencia" in enabled:
        if len(candles) < 22: result["suporte_resistencia"] = None
        else:
            previous = candles[-22:-2]; lo = min(x.low for x in previous); hi = max(x.high for x in previous)
            now = candles[-1]
            result["suporte_resistencia"] = "CALL" if now.low <= lo and now.close > lo and now.close > now.open else "PUT" if now.high >= hi and now.close < hi and now.close < now.open else None
    if "rompimento_reteste" in enabled:
        if len(candles) < 23: result["rompimento_reteste"] = None
        else:
            previous = candles[-23:-3]; hi = max(x.high for x in previous); lo = min(x.low for x in previous)
            breakout, retest, current = candles[-3:]
            result["rompimento_reteste"] = ("CALL" if breakout.close > hi and retest.low <= hi and current.close > hi
                                            else "PUT" if breakout.close < lo and retest.high >= lo and current.close < lo else None)
    return result


def combined_signal(candles: list[Candle], enabled: tuple[str, ...] = STRATEGIES, min_agreement: int = 2):
    """Combinação conservadora: sem aprovação se não houver >= min_agreement concordâncias e nenhuma contradição."""
    votes = signals(candles, enabled)
    yes = [v for v in votes.values() if v is not None]
    if len(yes) < min_agreement or len(set(yes)) != 1: return None
    return yes[0]

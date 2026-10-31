"""Fase 2/3: teste com entrada na PRÓXIMA abertura; evita olhar o futuro."""
from dataclasses import asdict, dataclass
from math import sqrt
from statistics import mean, stdev
from .analysis_engine import Candle, STRATEGIES, signals, validate_candles


@dataclass(frozen=True)
class BacktestTrade:
    signal_at: str
    enter_at: str
    exit_at: str
    direction: str
    strategy: str
    duration_minutes: int
    payout: float
    net_units: float


@dataclass(frozen=True)
class BacktestResult:
    trades: tuple[BacktestTrade, ...]
    win_rate: float
    net_units: float
    max_drawdown_units: float
    max_loss_streak: int
    profit_factor: float | None


def summarize(trades: list[BacktestTrade]) -> BacktestResult:
    wins = sum(t.net_units > 0 for t in trades)
    equity, peak, dd, streak, max_streak = 0., 0., 0., 0, 0
    for t in trades:
        equity += t.net_units
        peak = max(peak, equity)
        dd = max(dd, peak - equity)
        streak = streak + 1 if t.net_units < 0 else 0
        max_streak = max(streak, max_streak)
    profits = sum(t.net_units for t in trades if t.net_units > 0)
    losses = -sum(t.net_units for t in trades if t.net_units < 0)
    return BacktestResult(tuple(trades), wins/len(trades) if trades else 0., round(equity, 6), round(dd, 6), max_streak, (profits/losses if losses else None))


def backtest(candles: list[Candle], strategy: str, *, duration_minutes: int = 1, payout: float = .80,
             candle_minutes: int = 1, min_history: int = 205) -> BacktestResult:
    """Uma operação por vez; só usa candles conhecidos no instante do sinal.

    Ganho/perda em unidades de stake; empates tratados como perda (conservador).
    Não considera spread, slippage, variação de payout nem indisponibilidade de contrato.
    """
    validate_candles(candles, min_history + 2)
    if strategy not in STRATEGIES: raise ValueError("Estratégia desconhecida")
    if duration_minutes not in (1, 5) or candle_minutes != 1:
        raise ValueError("Suportados somente candles 1m e vencimentos 1/5m")
    if not 0 < payout <= 1: raise ValueError("Payout inválido")
    history: list[BacktestTrade] = []
    idx = min_history - 1
    while idx + duration_minutes < len(candles):
        # Ponto de corte inclusivo; gerador vê somente passado.
        prediction = signals(candles[:idx+1], (strategy,))[strategy]
        if prediction:
            entry_index = idx + 1
            exit_index = idx + duration_minutes
            entry = candles[entry_index].open
            exit_price = candles[exit_index].close
            net = payout if (exit_price > entry if prediction == "CALL" else exit_price < entry) else -1.
            history.append(BacktestTrade(candles[idx].timestamp.isoformat(), candles[entry_index].timestamp.isoformat(),
                                         candles[exit_index].timestamp.isoformat(), prediction, strategy, duration_minutes, payout, net))
            idx = exit_index
        else: idx += 1
    return summarize(history)


def chronological_walk_forward(candles: list[Candle], strategy: str, *, duration_minutes: int = 1,
                                payout: float = .80, min_history: int = 205) -> dict:
    """Avaliação por blocos temporais não sobrepostos, preservando contexto passado."""
    validate_candles(candles, min_history + 40)
    cut1 = int(len(candles) * .60)
    cut2 = int(len(candles) * .80)
    if cut1 <= min_history or cut2 <= cut1 + duration_minutes: raise ValueError("Dados insuficientes para walk-forward")
    # Reprocessa cada bloco com contexto histórico, mas não contabiliza trades de treino.
    full = backtest(candles, strategy, duration_minutes=duration_minutes, payout=payout, min_history=min_history)
    boundary1 = candles[cut1].timestamp.isoformat()
    boundary2 = candles[cut2].timestamp.isoformat()
    blocks = [[], [], []]
    for trade in full.trades:
        bucket = 0 if trade.signal_at < boundary1 else 1 if trade.signal_at < boundary2 else 2
        # Evita utilizar desfecho além do fim de treino/validação.
        end_boundary = (boundary1, boundary2, None)[bucket]
        if end_boundary is None or trade.exit_at < end_boundary: blocks[bucket].append(trade)
    return dict(training=asdict(summarize(blocks[0])), validation=asdict(summarize(blocks[1])),
                test=asdict(summarize(blocks[2])), caveat="Backtest simplificado; sem garantias ou execução real")

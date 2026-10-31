"""Simulador do MVP: não usa preços nem mercado reais, e não gera sinais financeiros."""
import random
from datetime import date, datetime
from threading import RLock
from .models import RiskConfig, SimulationState, Trade
from .store import Store


class Simulator:
    def __init__(self, store: Store, rng: random.Random | None = None):
        self.store = store
        self.rng = rng or random.SystemRandom()
        self.lock = RLock()
        self._status = "PARADO"  # fail-closed em toda reinicialização
        self.store.set_status(self._status)
        self.message = "Sem conexão operacional à Deriv. Apenas simulação fictícia."

    def history(self) -> list[Trade]:
        return self.store.trades()

    def summary(self) -> SimulationState:
        with self.lock:
            history = self.history()
            settings = self.store.load_settings()
            today_net = sum(t.net for t in history if datetime.fromisoformat(t.timestamp).astimezone().date() == date.today())
            streak = 0
            for trade in history:
                if trade.win:
                    break
                streak += 1
            return SimulationState(
                status=self._status,
                balance=10000 + sum(t.net for t in history),
                today_net=today_net,
                total_trades=len(history),
                total_wins=sum(1 for t in history if t.win),
                total_losses=sum(1 for t in history if not t.win),
                loss_streak=streak,
                risk_config=settings,
                message=self.message,
            )

    def set_state(self, value: str) -> SimulationState:
        with self.lock:
            if value == "ATIVO" and self._must_pause(self.summary()):
                self._pause("Proteção de risco: não é possível iniciar.")
            else:
                self._status = value
                self.store.set_status(value)
                self.message = f"Simulador {value.lower()}. Nenhuma ordem enviada à corretora."
            return self.summary()

    def _must_pause(self, state: SimulationState) -> bool:
        settings = state.risk_config
        day_start = state.balance - state.today_net
        return (
            state.balance <= 0 or
            -state.today_net >= day_start * settings.daily_loss_percent / 100 or
            state.loss_streak >= settings.consecutive_loss_limit
        )

    def _pause(self, reason: str):
        self._status = "PAUSADO"
        self.store.set_status(self._status)
        self.message = reason

    def change_settings(self, settings: RiskConfig, confirm_increased_risk: bool) -> RiskConfig:
        with self.lock:
            old = self.store.load_settings()
            increases = (
                settings.stake_percent > old.stake_percent or
                settings.max_stake_percent > old.max_stake_percent or
                settings.daily_loss_percent > old.daily_loss_percent or
                settings.consecutive_loss_limit > old.consecutive_loss_limit or
                settings.max_concurrent > old.max_concurrent or
                settings.min_payout_percent < old.min_payout_percent
            )
            if increases and not confirm_increased_risk:
                raise ValueError("Confirmação explícita exigida para aumentar risco")
            self.store.save_settings(settings)
            return settings

    def step(self) -> dict:
        """UMA oportunidade aleatória e vencimento imediato ACELERADO; somente quando ativo."""
        with self.lock:
            s = self.summary()
            if s.status != "ATIVO":
                return {"executed": False, "reason": "Simulador não está ativo"}
            if self._must_pause(s):
                self._pause("Limite de perda ou perdas consecutivas atingido.")
                return {"executed": False, "reason": self.message}

            payout = self.rng.choice((0.78, 0.82, 0.85))
            if payout * 100 < s.risk_config.min_payout_percent:
                return {"executed": False, "reason": "Payout fictício abaixo do mínimo"}

            stake = round(s.balance * s.risk_config.stake_percent / 100, 2)
            if stake <= 0 or stake > s.balance * s.risk_config.max_stake_percent / 100:
                self._pause("Entrada inválida ou acima do limite por operação.")
                return {"executed": False, "reason": self.message}
            day_start = s.balance - s.today_net
            if max(0, -s.today_net) + stake > day_start * s.risk_config.daily_loss_percent / 100:
                self._pause("Risco potencial excede o limite diário.")
                return {"executed": False, "reason": self.message}

            won = self.rng.random() < 0.48
            asset = self.rng.choice(("EUR/USD-SINTETICO", "GBP/USD-SINTETICO", "USD/JPY-SINTETICO"))
            direction = self.rng.choice(("CALL", "PUT"))
            duration = self.rng.choice((1, 5)) if s.risk_config.duration_minutes == 0 else s.risk_config.duration_minutes
            net = round(stake * payout if won else -stake, 2)
            trade = self.store.add_trade(asset, direction, stake, payout, duration, won, net)
            after = self.summary()
            if self._must_pause(after):
                self._pause("Proteção acionada: limite diário ou perdas consecutivas.")
            return {"executed": True, "trade": trade.model_dump(), "status": self._status,
                    "warning": "Resultado puramente aleatório, sem cotação real"}

from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, model_validator


class RiskConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")
    stake_percent: float = Field(0.5, gt=0, le=10)
    max_stake_percent: float = Field(1.0, gt=0, le=10)
    daily_loss_percent: float = Field(3.0, gt=0, le=25)
    consecutive_loss_limit: int = Field(4, ge=1, le=20)
    min_payout_percent: int = Field(80, ge=1, le=100)
    max_concurrent: int = Field(1, ge=1, le=4)
    duration_minutes: Literal[0, 1, 5] = 1

    @model_validator(mode="after")
    def ensure_stake_in_bounds(self):
        if self.stake_percent > self.max_stake_percent:
            raise ValueError("A entrada padrão não pode superar a máxima")
        return self


class Trade(BaseModel):
    id: int
    timestamp: str
    mode: Literal["SIMULATOR"] = "SIMULATOR"
    asset: str
    direction: Literal["CALL", "PUT"]
    stake: float
    payout: float
    duration_minutes: int
    win: bool
    net: float


class SimulationState(BaseModel):
    status: Literal["PARADO", "ATIVO", "PAUSADO"]
    mode: Literal["SIMULATOR"] = "SIMULATOR"
    balance: float
    today_net: float
    total_trades: int
    total_wins: int
    total_losses: int
    loss_streak: int
    open_count: int = 0
    risk_config: RiskConfig
    message: str

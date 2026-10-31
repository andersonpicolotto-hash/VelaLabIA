"""Endpoints de pesquisa/backtest. Não expõem ordens nem credenciais."""
import os
from datetime import datetime, timezone
from pathlib import Path
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field, model_validator
from .analysis_engine import Candle, signals, combined_signal, STRATEGIES
from .backtesting import backtest, chronological_walk_forward
from .learning import CandidateReport, VersionRegistry
from .deriv_official import DerivCredentials, DerivOfficialClient, public_candles


class CandleInput(BaseModel):
    timestamp: datetime
    open: float
    high: float
    low: float
    close: float
    volume: float = 0.0


class ResearchInput(BaseModel):
    candles: list[CandleInput] = Field(min_length=1, max_length=3000)
    enabled: list[str] = Field(default_factory=lambda: list(STRATEGIES))


class TestInput(ResearchInput):
    strategy: str = "tendencia_ema"
    duration_minutes: int = Field(1, ge=1, le=5)
    payout: float = Field(.80, ge=.01, le=1.0)
    min_history: int = Field(205, ge=30, le=500)


class CandidateInput(BaseModel):
    report: CandidateReport
    min_trades: int = Field(100, ge=30)


def build_research_router(registry_path: str) -> APIRouter:
    router = APIRouter(prefix="/api/research")
    registry = VersionRegistry(registry_path)

    def candles_from(request: ResearchInput) -> list[Candle]:
        return [Candle(**x.model_dump()) for x in request.candles]

    @router.get("/strategies")
    def strategies():
        return [{"name": key, "status": "EXPERIMENTAL - sem validação de lucro"} for key in STRATEGIES]

    @router.post("/signals")
    def research_signals(request: ResearchInput):
        try:
            candles = candles_from(request)
            return {"individuais":signals(candles,tuple(request.enabled)),
                    "combinado":combined_signal(candles,tuple(request.enabled)),
                    "fonte":"DADOS FORNECIDOS PELO USUÁRIO - NÃO CONECTADO À CORRETORA"}
        except ValueError as exc: raise HTTPException(status_code=422, detail=str(exc)) from exc

    @router.post("/backtest")
    def run_backtest(request: TestInput):
        try:
            if request.duration_minutes not in (1,5): raise ValueError("Duração 1 ou 5 minutos")
            candles = candles_from(request)
            return chronological_walk_forward(candles,request.strategy,duration_minutes=request.duration_minutes,
                                             payout=request.payout,min_history=request.min_history)
        except ValueError as exc: raise HTTPException(status_code=422, detail=str(exc)) from exc

    @router.get("/versions")
    def versions(): return registry.list_versions()

    @router.post("/versions/evaluate")
    def evaluate(request: CandidateInput):
        try: return registry.register(request.report,request.min_trades)
        except Exception as exc:
            raise HTTPException(status_code=409, detail="Versão duplicada ou inválida") from exc

    @router.get("/system")
    def system_state():
        return {"phases": ["MVP", "PESQUISA", "AVALIAÇÃO DE CANDIDATOS", "INFRA PREPARADA", "EXECUÇÃO BLOQUEADA", "TESTES EM ANDAMENTO"],
                "real_trading_enabled": False,
                "risk_defaults": {"entry_percent":0.5,"max_entry_percent":1.0,"daily_loss_percent":3.0,"loss_streak":4},
                "warning":"Modelos não validam precisão nem oferecem recomendação de investimento."}

    return router


def build_deriv_readonly_router() -> APIRouter:
    router=APIRouter(prefix="/api/deriv")

    def get_client() -> DerivOfficialClient:
        app_id, pat = os.getenv("DERIV_APP_ID", ""), os.getenv("DERIV_PAT", "")
        if not app_id or not pat: raise HTTPException(status_code=503, detail="Deriv não configurada. Nunca envie token pelo chat.")
        return DerivOfficialClient(DerivCredentials(app_id,pat))

    @router.get("/connection")
    async def connection():
        return {"status":"CONFIGURADA (não significa conectada)" if os.getenv("DERIV_PAT") else "DESCONECTADA",
                "real_orders_enabled":False,"warning":"Somente leitura; nenhum endpoint público envia ordens"}

    @router.get("/accounts")
    async def accounts():
        try:
            values=await get_client().accounts()
            return [{"account_id":a.get("account_id"),"account_type":a.get("account_type"),"currency":a.get("currency")}
                    for a in values]
        except HTTPException: raise
        except Exception as exc: raise HTTPException(status_code=502, detail="Consulta Deriv indisponível") from exc

    @router.get("/candles/{symbol}")
    async def candles(symbol: str, count: int = 500):
        try: return {"source":"DERIV - FEED PÚBLICO", "candles":await public_candles(symbol,count)}
        except Exception as exc: raise HTTPException(status_code=502, detail="Dados públicos não disponíveis") from exc

    return router

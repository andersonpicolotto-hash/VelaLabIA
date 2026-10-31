"""API local do simulador; integração Deriv DEMO de leitura disponível no Android."""
import os
import secrets
from fastapi import Depends, FastAPI, Header, HTTPException, Query, status
from pydantic import BaseModel
from .brokers import DerivAdapter
from .research_api import build_research_router, build_deriv_readonly_router
from .models import RiskConfig
from .simulator import Simulator
from .store import Store


class SettingsUpdate(BaseModel):
    settings: RiskConfig
    confirm_increased_risk: bool = False


def create_app(db_path: str | None = None, api_token: str | None = None) -> FastAPI:
    token = api_token if api_token is not None else os.getenv("VELALAB_API_TOKEN", "")
    if not token or len(token) < 24:
        raise RuntimeError("Configure VELALAB_API_TOKEN com pelo menos 24 caracteres")
    store = Store(db_path or os.getenv("VELALAB_DB_PATH", "./velalab.db"))
    sim = Simulator(store)
    app = FastAPI(title="VelaLab IA API - pesquisa", docs_url=None, redoc_url=None)

    def auth(authorization: str | None = Header(default=None)):
        sent = authorization.removeprefix("Bearer ") if authorization else ""
        if not secrets.compare_digest(sent, token):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Não autorizado")

    # Compartilham a mesma autenticação; nenhuma rota de compra/ordem é exposta.
    app.include_router(build_research_router(str(db_path or os.getenv("VELALAB_DB_PATH", "./velalab.db"))), dependencies=[Depends(auth)])
    app.include_router(build_deriv_readonly_router(), dependencies=[Depends(auth)])

    @app.get("/health")
    def health():
        return {"ok": True, "phase": 1, "broker_connected": False}

    @app.get("/api/status", dependencies=[Depends(auth)])
    def summary():
        return sim.summary()

    @app.get("/api/settings", dependencies=[Depends(auth)])
    def read_settings():
        return store.load_settings()

    @app.put("/api/settings", dependencies=[Depends(auth)])
    def update_settings(update: SettingsUpdate):
        try:
            return sim.change_settings(update.settings, update.confirm_increased_risk)
        except ValueError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc

    @app.get("/api/connection", dependencies=[Depends(auth)])
    def connection():
        return DerivAdapter().status()

    @app.post("/api/sim/start", dependencies=[Depends(auth)])
    def start():
        return sim.set_state("ATIVO")

    @app.post("/api/sim/pause", dependencies=[Depends(auth)])
    def pause():
        return sim.set_state("PAUSADO")

    @app.post("/api/sim/stop", dependencies=[Depends(auth)])
    def stop():
        return sim.set_state("PARADO")

    @app.post("/api/sim/step", dependencies=[Depends(auth)])
    def step():
        return sim.step()

    @app.get("/api/trades", dependencies=[Depends(auth)])
    def trades(limit: int = Query(50, ge=1, le=1000)):
        return store.trades(limit)

    return app


# Para usar: exportar VELALAB_API_TOKEN antes de iniciar Uvicorn.
# Falha explicitamente quando token não é configurado.
if os.getenv("VELALAB_API_TOKEN"):
    app = create_app()

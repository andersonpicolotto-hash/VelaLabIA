"""Deriv API oficial, com endpoints oficiais e sem autenticação simulada.

Por segurança esta biblioteca NÃO envia ordens automaticamente. O caminho de compra
pode ser usado exclusivamente após testes de integração e autorização explícita;
NUNCA executá-lo com credenciais em testes automatizados.
Docs: https://developers.deriv.com/docs/intro/api-overview/
"""
from dataclasses import dataclass
from decimal import Decimal
from typing import Any
from urllib.parse import urlparse, parse_qs
import json
import re
import os
import httpx
import websockets

BASE = "https://api.derivws.com"
PUBLIC_WS = "wss://api.derivws.com/trading/v1/options/ws/public"


class DerivError(RuntimeError): pass


def official_ws_url(url: str, account_type: str) -> bool:
    if account_type not in ("demo", "real"): return False
    p = urlparse(url)
    query = parse_qs(p.query, strict_parsing=True)
    return (p.scheme == "wss" and p.netloc == "api.derivws.com" and
            p.path == f"/trading/v1/options/ws/{account_type}" and
            p.username is None and p.password is None and not p.fragment and
            list(query) == ["otp"] and len(query["otp"]) == 1 and
            re.fullmatch(r"[\w.%\-]{8,512}",query["otp"][0]) is not None)


@dataclass(frozen=True)
class DerivCredentials:
    app_id: str
    token: str

    def __post_init__(self):
        if not re.fullmatch(r"[a-zA-Z0-9_\-]{1,80}",self.app_id) or not self.token or "\n" in self.token:
            raise ValueError("Credenciais inválidas")


class DerivOfficialClient:
    def __init__(self, credentials: DerivCredentials, transport: httpx.AsyncBaseTransport | None = None):
        self.credentials = credentials
        self.transport = transport

    def headers(self):
        return {"Authorization": f"Bearer {self.credentials.token}", "Deriv-App-ID": self.credentials.app_id}

    async def accounts(self) -> list[dict]:
        async with httpx.AsyncClient(timeout=15, transport=self.transport) as http:
            response = await http.get(f"{BASE}/trading/v1/options/accounts",headers=self.headers())
            response.raise_for_status()
            data = response.json().get("data")
            if isinstance(data, list): return data
            if isinstance(data, dict) and isinstance(data.get("accounts"), list): return data["accounts"]
            if isinstance(data, dict): return [data]
            raise DerivError("Resposta de contas não reconhecida")

    async def otp_websocket(self, account_id: str, account_type: str) -> str:
        if account_type not in ("demo","real") or not re.fullmatch(r"[\w\-]{1,100}",account_id):
            raise DerivError("Conta inválida")
        account = next((a for a in await self.accounts()
                        if str(a.get("account_id")) == account_id and a.get("account_type") == account_type), None)
        if account is None: raise DerivError("Conta/tipo não encontrados no resultado autenticado")
        async with httpx.AsyncClient(timeout=15, transport=self.transport) as http:
            resp = await http.post(f"{BASE}/trading/v1/options/accounts/{account_id}/otp",headers=self.headers())
            resp.raise_for_status()
            wsurl = (resp.json().get("data") or {}).get("url", "")
        if not official_ws_url(wsurl,account_type):
            raise DerivError("Endpoint OTP diferente da conta selecionada ou não oficial")
        return wsurl


async def ws_request(url: str, account_type: str, message: dict, *, req_id: int = 1) -> dict:
    """Uma solicitação com req_id; não repete chamadas com efeito financeiro."""
    if account_type == "public":
        if url != PUBLIC_WS: raise DerivError("Endpoint público inválido")
    elif not official_ws_url(url,account_type): raise DerivError("Endpoint não oficial ou conta incorreta")
    async with websockets.connect(url, open_timeout=12, close_timeout=5, max_size=2**20) as ws:
        await ws.send(json.dumps(dict(message,req_id=req_id)))
        # Mensagens de assinatura alheia não devem ser confundidas com a resposta.
        for _ in range(10):
            raw = await __import__("asyncio").wait_for(ws.recv(),timeout=15)
            data = json.loads(raw)
            if data.get("req_id") != req_id: continue
            if data.get("error"): raise DerivError("Deriv rejeitou a solicitação")
            return data
    raise DerivError("Resposta não confirmada")


async def public_active_symbols() -> list[dict]:
    data = await ws_request(PUBLIC_WS,"public",{"active_symbols":"brief"})
    if data.get("msg_type") != "active_symbols": raise DerivError("Resposta de símbolos inválida")
    return data.get("active_symbols", [])


async def read_account_balance(client: DerivOfficialClient, account_id: str, account_type: str):
    url = await client.otp_websocket(account_id,account_type)
    data = await ws_request(url,account_type,{"balance":1})
    if data.get("msg_type") != "balance": raise DerivError("Resposta de saldo inválida")
    return data["balance"]


async def request_proposal(client: DerivOfficialClient, account_id: str, account_type: str,
                           *, symbol: str, direction: str, stake: Decimal, duration_minutes: int, currency: str = "USD") -> dict:
    if direction not in ("CALL","PUT") or duration_minutes not in (1,5) or not re.fullmatch(r"[A-Z0-9_]{1,30}",symbol):
        raise DerivError("Tipo de contrato, vencimento ou ativo inválido")
    if stake <= 0 or not stake.is_finite(): raise DerivError("Valor inválido")
    url = await client.otp_websocket(account_id,account_type)
    data = await ws_request(url,account_type,{"proposal":1,"amount":float(stake),"basis":"stake",
        "contract_type":direction,"currency":currency,"duration":duration_minutes,"duration_unit":"m",
        "underlying_symbol":symbol})
    if data.get("msg_type") != "proposal": raise DerivError("Proposta não confirmada")
    return data["proposal"]


async def buy_confirmed_proposal(client: DerivOfficialClient, account_id: str, account_type: str,
                                  *, proposal_id: str, max_price: Decimal, gate: bool = False) -> dict:
    """Método de baixo nível, isolado; por padrão NÃO envia compra.

    Chamador deve reservar exposição em OrderLedger antes de definir gate=True.
    Em falhas/timeout tratar ordem como UNKNOWN e reconciliar, sem reenvio.
    """
    if not gate: raise DerivError("Compra desativada: consentimento e verificação pendentes")
    if account_type == "real" and os.environ.get("VELALAB_REAL_ORDERS_VALIDATED") != "CONFIRMADO_EM_AUDITORIA_EXTERNA":
        raise DerivError("Ordens REAL bloqueadas: validação operacional ainda pendente")
    if account_type not in ("demo","real") or not re.fullmatch(r"[\w-]{32,128}",proposal_id):
        raise DerivError("Tipo de conta ou proposta inválidos")
    if max_price <= 0 or not max_price.is_finite(): raise DerivError("Preço inválido")
    url = await client.otp_websocket(account_id,account_type)
    data = await ws_request(url,account_type,{"buy":proposal_id,"price":float(max_price)})
    if data.get("msg_type") != "buy" or not data.get("buy",{}).get("contract_id"):
        raise DerivError("Compra não confirmada; reconciliar antes de novo envio")
    return data["buy"]


async def public_candles(symbol: str, count: int = 500, granularity: int = 60) -> list[dict]:
    """Solicita candles históricos ao endpoint público oficial; nunca executa ordens."""
    if not re.fullmatch(r"[A-Za-z0-9_]{1,30}",symbol) or not 50 <= count <= 5000:
        raise DerivError("Ativo ou quantidade inválida")
    if granularity != 60: raise DerivError("Apenas candles de 1 minuto")
    data = await ws_request(PUBLIC_WS,"public",{
        "ticks_history":symbol,"end":"latest","count":count,"style":"candles", "granularity":granularity,
    })
    if data.get("msg_type") != "candles": raise DerivError("Resposta não contém candles")
    candles = data.get("candles")
    if not isinstance(candles,list): raise DerivError("Candles indisponíveis")
    return candles

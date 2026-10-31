"""Testes dos limites diarios por ambiente no ledger local. Nenhuma chamada a corretora."""
from decimal import Decimal

import pytest

from app.risk_engine import OrderLedger, RiskLimits, RiskRejected


def attempt(ledger, request_id, *, account_type="DEMO", asset="R_50", stake="50.00", balance="10000"):
    return ledger.reserve(
        request_id=request_id, account_type=account_type, account_id="conta-teste",
        asset=asset, balance=balance, stake=stake, payout_percent="85",
        connected=True, market_fresh=True, open_positions_reconciled=True,
    )


def test_limite_percentual_escolhido_bloqueia_entrada(tmp_path):
    limits = RiskLimits(max_daily_loss_percent=Decimal("0.5"), max_open=4)
    ledger = OrderLedger(str(tmp_path / "percentual.db"), limits=limits)
    assert attempt(ledger, "p1")["state"] == "PENDING"
    with pytest.raises(RiskRejected, match="perda diária"):
        attempt(ledger, "p2", asset="R_100", stake="10.00")


def test_teto_monetario_mais_restritivo_prevalece(tmp_path):
    limits = RiskLimits(
        max_daily_loss_percent=Decimal("3"), max_daily_loss_amount=Decimal("75"),
        max_open=4,
    )
    ledger = OrderLedger(str(tmp_path / "monetario.db"), limits=limits)
    assert attempt(ledger, "m1")["state"] == "PENDING"
    with pytest.raises(RiskRejected, match="perda diária"):
        attempt(ledger, "m2", asset="R_100", stake="30.00")


def test_valores_invalidos_e_contas_isoladas(tmp_path):
    with pytest.raises(ValueError, match="percentual"):
        RiskLimits(max_daily_loss_percent=Decimal("26"))
    with pytest.raises(ValueError, match="monetário"):
        RiskLimits(max_daily_loss_amount=Decimal("-1"))
    ledger = OrderLedger(str(tmp_path / "contas.db"), RiskLimits(max_daily_loss_percent=Decimal("0.5")))
    assert attempt(ledger, "real1", account_type="REAL")["state"] == "PENDING"
    assert attempt(ledger, "demo1", account_type="DEMO")["state"] == "PENDING"
    assert len(ledger.snapshot("REAL", "conta-teste")) == 1
    assert len(ledger.snapshot("DEMO", "conta-teste")) == 1

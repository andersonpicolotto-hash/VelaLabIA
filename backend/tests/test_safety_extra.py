"""Testes de garantias básicas da Fase 1: exclusivamente simulação interna."""

import random

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.models import RiskConfig
from app.simulator import Simulator
from app.store import Store

TOKEN = "token_teste_sem_credenciais_verdadeiras_12345"
HEADERS = {"Authorization": f"Bearer {TOKEN}"}


class AlwaysLose(random.Random):
    def choice(self, sequence):
        return sequence[-1]  # payout fictício de 85%

    def random(self):
        return 0.99999


def test_backend_refuses_weak_token(tmp_path):
    with pytest.raises(RuntimeError, match="VELALAB_API_TOKEN"):
        create_app(str(tmp_path / "test.db"), api_token="short")


def test_no_endpoint_for_real_or_practice_orders(tmp_path):
    with TestClient(create_app(str(tmp_path / "test.db"), TOKEN)) as client:
        for path in ("/api/real/buy", "/api/practice/buy", "/api/broker/order"):
            response = client.post(path, json={"amount": 100}, headers=HEADERS)
            assert response.status_code == 404
        response = client.get("/api/connection", headers=HEADERS).json()
        assert response["connected"] is False
        assert response["balance"] is None


def test_payout_filter_can_refuse_all_simulated_trades(tmp_path):
    sim = Simulator(Store(str(tmp_path / "test.db")), AlwaysLose())
    sim.change_settings(RiskConfig(min_payout_percent=100), confirm_increased_risk=False)
    sim.set_state("ATIVO")
    for _ in range(5):
        answer = sim.step()
        assert answer["executed"] is False
        assert "Payout" in answer["reason"]
    assert len(sim.history()) == 0


def test_daily_loss_protection_prevents_further_simulation(tmp_path):
    sim = Simulator(Store(str(tmp_path / "test.db")), AlwaysLose())
    sim.change_settings(
        RiskConfig(stake_percent=1, max_stake_percent=1, daily_loss_percent=1),
        confirm_increased_risk=True,
    )
    sim.set_state("ATIVO")
    outcome = sim.step()
    assert outcome["executed"] is True
    assert sim.summary().status == "PAUSADO"
    assert sim.summary().today_net < 0
    assert sim.set_state("ATIVO").status == "PAUSADO"
    assert sim.step()["executed"] is False


def test_reducing_payout_minimum_requires_explicit_confirmation(tmp_path):
    sim = Simulator(Store(str(tmp_path / "test.db")), AlwaysLose())
    with pytest.raises(ValueError, match="Confirmação explícita"):
        sim.change_settings(RiskConfig(min_payout_percent=70), confirm_increased_risk=False)
    updated = sim.change_settings(RiskConfig(min_payout_percent=70), confirm_increased_risk=True)
    assert updated.min_payout_percent == 70

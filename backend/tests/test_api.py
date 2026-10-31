import random
from fastapi.testclient import TestClient
from app.main import create_app
from app.simulator import Simulator
from app.store import Store

TOKEN = "token_de_testes_com_mais_de_24_caracteres"
HEADERS = {"Authorization": f"Bearer {TOKEN}"}


def test_auth_and_connection_status(tmp_path):
    with TestClient(create_app(str(tmp_path / "test.db"), TOKEN)) as client:
        assert client.get("/health").json() == {"ok": True, "phase": 1, "broker_connected": False}
        assert client.get("/api/status").status_code == 401
        state = client.get("/api/status", headers=HEADERS).json()
        assert state["mode"] == "SIMULATOR"
        assert state["status"] == "PARADO"
        deriv = client.get("/api/connection", headers=HEADERS).json()
        assert deriv["connected"] is False and deriv["balance"] is None
        assert deriv["mode"] == "PRACTICE"


def test_risk_settings_require_consent_and_persist(tmp_path):
    path = str(tmp_path / "test.db")
    with TestClient(create_app(path, TOKEN)) as client:
        settings = client.get("/api/settings", headers=HEADERS).json()
        settings["max_concurrent"] = 4
        data = {"settings": settings, "confirm_increased_risk": False}
        assert client.put("/api/settings", json=data, headers=HEADERS).status_code == 409
        data["confirm_increased_risk"] = True
        assert client.put("/api/settings", json=data, headers=HEADERS).status_code == 200
        assert client.get("/api/settings", headers=HEADERS).json()["max_concurrent"] == 4
        settings["stake_percent"] = 11
        assert client.put("/api/settings", json=data | {"settings": settings}, headers=HEADERS).status_code == 422
    assert Store(path).load_settings().max_concurrent == 4


def test_simulation_no_orders_until_started(tmp_path):
    with TestClient(create_app(str(tmp_path / "test.db"), TOKEN)) as client:
        assert client.post("/api/sim/step", headers=HEADERS).json()["executed"] is False
        assert client.post("/api/sim/start", headers=HEADERS).json()["status"] == "ATIVO"
        for _ in range(10):
            client.post("/api/sim/step", headers=HEADERS)
        trades = client.get("/api/trades", headers=HEADERS).json()
        assert len(trades) >= 1
        assert all(t["mode"] == "SIMULATOR" for t in trades)
        assert all(t["payout"] >= .80 for t in trades)
        assert client.post("/api/sim/stop", headers=HEADERS).json()["status"] == "PARADO"
        assert client.post("/api/sim/step", headers=HEADERS).json()["executed"] is False


def test_loss_streak_pauses_and_daily_risk(tmp_path):
    class LosingRandom(random.Random):
        def choice(self, sequence):
            return sequence[-1]  # payout 0.85
        def random(self):
            return 0.999  # perdas sucessivas

    simulator = Simulator(Store(str(tmp_path / "test.db")), LosingRandom())
    assert simulator.set_state("ATIVO").status == "ATIVO"
    for _ in range(4):
        simulator.step()
    assert simulator.summary().status == "PAUSADO"
    assert simulator.summary().loss_streak == 4
    assert simulator.step()["executed"] is False
    assert simulator.summary().balance < 10000


def test_fails_closed_on_restart(tmp_path):
    path = str(tmp_path / "test.db")
    first = Simulator(Store(path))
    assert first.set_state("ATIVO").status == "ATIVO"
    second = Simulator(Store(path))
    assert second.summary().status == "PARADO"

"""Contrato expansível de corretora; Deriv é a corretora principal.

Na Fase 1, a autenticação demonstrativa somente-leitura está no Android,
sem sincronizar com o servidor. Este adapter permanece intencionalmente inativo.
"""
from abc import ABC, abstractmethod
from dataclasses import dataclass
from enum import StrEnum


class BrokerMode(StrEnum):
    SIMULATOR = "SIMULATOR"
    PRACTICE = "PRACTICE"
    REAL = "REAL"


@dataclass(frozen=True)
class BrokerStatus:
    mode: BrokerMode
    connected: bool
    label: str
    balance: float | None


class BrokerAdapter(ABC):
    @abstractmethod
    def status(self) -> BrokerStatus:
        raise NotImplementedError

    @abstractmethod
    def get_balance(self) -> float | None:
        raise NotImplementedError


class SimulatorAdapter(BrokerAdapter):
    def __init__(self, balance_fn):
        self._balance_fn = balance_fn

    def status(self) -> BrokerStatus:
        return BrokerStatus(BrokerMode.SIMULATOR, True, "SIMULAÇÃO LOCAL", self.get_balance())

    def get_balance(self) -> float:
        return self._balance_fn()


class DerivAdapter(BrokerAdapter):
    """Reservado à migração para o servidor; não inventar conexão nem saldo."""

    def status(self) -> BrokerStatus:
        return BrokerStatus(BrokerMode.PRACTICE, False, "DESCONECTADO - DERIV BACKEND NÃO CONFIGURADO", None)

    def get_balance(self) -> None:
        return None

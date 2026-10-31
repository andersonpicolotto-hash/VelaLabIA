# ARQUIVO HISTÓRICO — corretora não prioritária

O usuário aprovou migrar a prioridade para **Deriv** em 30/10/2026. Consulte `docs/DERIV-INTEGRACAO.md`. Este documento permanece somente para histórico de investigação; nenhuma conexão IQ Option foi desenvolvida.

# Investigação de integração IQ Option — 30/10/2026

## Resultado

**NÃO CONFIRMADA** uma API pública oficial documentada para negociação algorítmica na conta PRACTICE de usuários comuns.

Documentos públicos consultados:

- Demonstração oficial: https://iqoption.com/pt/demo-trading — confirma conta demo oficial, mas não uma API de trading.
- Artigo IQ Option sobre bots: https://blog.iqoption.com/pt/how-to-set-up-a-trading-bot/ — educativo; não fornece especificação pública de uma API de execução para contas de usuários.
- Biblioteca de comunidade: https://github.com/iqoptionapi/iqoptionapi — declara explicitamente não ser uma biblioteca oficial.
- Avisos de segurança da corretora: https://blog.iqoption.com/en/safety-first-avoid-being-scammed/ — recomenda cuidado com acesso de terceiros e senhas.

Portanto, não foram implementados endpoints inventados, captura de cookie, engenharia reversa de WebSocket, solicitação de senhas nem envio de operações.

## Estado por função

| Função solicitada | Estado |
| --- | --- |
| Autenticação IQ Option PRACTICE | Não implementada; sem API oficial confirmada |
| Saldo PRACTICE | Não disponível |
| Ativos, velas/cotações, payout | Não disponíveis |
| Histórico da corretora | Não disponível |
| Ordem PRACTICE | Bloqueada |
| Ordem REAL | Bloqueada |
| Simulação separada | Implementada em Android e backend |

**Teste de conexão real:** não executado, pois não há método oficial/autorizado documentado nem credenciais fornecidas. Isso não significa que tenha sido testada e falhado uma conexão: o bloqueio é de viabilidade/autorização.

## Condições de retomada

Se a IQ Option oferecer documentação oficial, autorização explícita e mecanismo suportado para esse tipo de conta, será necessário revisar os termos vigentes, implementar integração somente leitura, conferir modos PRACTICE/REAL, testar em ambiente demonstrativo e verificar mecanismos de risco antes de considerar ordens automáticas.

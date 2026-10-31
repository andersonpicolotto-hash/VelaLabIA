# Checkpoint de retomada — 31/10/2026

**Projeto:** VelaLab IA. **Versão:** 0.2.0-laboratorio.
**Repositório:** `andersonpicolotto-hash/VelaLabIA`, conectado e com permissão de escrita; último estado encontrado: repositório **público**, contendo `README.md` e `.github/workflows/velalab-apk.yml`, sem ZIP do aplicativo, sem APK.

**Diretriz do usuário:** desenvolvimento das seis fases autorizado. Quer, futuramente, operações automáticas em dinheiro real na Deriv. Isso não autoriza executar uma ordem agora; REAL requer autorização operacional específica e teste comprovado. No Android, é possível selecionar REAL para **consulta de saldo** com confirmação; compra ainda bloqueada.

## Entregue nesta sessão (código-fonte, não aplicação em produção)

- F1: simulador Android existente + seleção de conexão DEMO/REAL **somente leitura**; nova tela de pesquisa claramente marcada experimental. Arquivos Kotlin ajustados; **não compilado**.
- F2: `backend/app/analysis_engine.py`, `backtesting.py` — indicadores, 6 estratégias-base, backtest de 1m/5m e avaliação treino/validação/teste sem uso explícito de barras futuras. Os candles precisam ser cronológicos e confiáveis. Backtests não incluem spread, variações de payout/latência ou indisponibilidade de ativo.
- F3: `backend/app/learning.py` — registro de candidatos, critérios mínimos de amostra, promoção somente DEMO e rollback manual da versão. Não há modelo ML treinado, auto-otimização em execução ou detecção real de drift.
- F4: `backend/Dockerfile`, `compose.yaml`, `backend/app/observer.py` — estrutura de servidor e coleta de candles pública, não executados numa VPS. Falta HTTPS, recuperação completa e push.
- F5: `backend/app/deriv_official.py`, `risk_engine.py`, `research_api.py` — validação de host/tipo da conta, autenticação PAT oficial via REST, URL OTP oficial, leitura pública, payload de proposta e método buy isolado que exige gate, sem rota de execução. Gestão de reservas idempotente em SQLite. **Nenhuma ordem, consulta autenticada ou credencial real testada.** Falta execução em DEMO de ponta a ponta, reconciliação e proteção abrangente sob falhas.
- F6: `backend/tests/test_research_and_live_safety.py`: testes unitários, uso de mocks para Deriv e verificações de negação da compra. Não existe ensaio de longo prazo.

## Testes realmente executados

- `cd backend && python -m pytest -q` — **22 passed** em 31/10/2026.
- `python -m compileall -q app` — sem erros.
- Estrutura YAML `compose.yaml` analisada com parser PyYAML.
- Compilação nativa da política de endpoint Kotlin com `kotlinc`, 7 verificações de URL DEMO/REAL aprovadas (não equivale ao build Android).
- **Não executado:** Android Gradle build, APK, GitHub Actions, rede real da Deriv, demonstração real, VPS, stress test.

## Gaps críticos antes de utilizar PRACTICE/REAL

1. Enviar `VelaLabIA-Completo-Base.zip` para raiz da branch `main` no GitHub; executar workflow e corrigir erros compilação.
2. Instalar e testar o APK no Galaxy S24 FE. Confirmar feed público e leitura DEMO via credenciais inseridas somente no app.
3. Validar ativos, payout, contratos, duração e preços com Deriv; executar ordens **DEMO** com reconciliação e recuperação de falhas.
4. Implementar controlador automático DEMO, limites de risco intradiário baseados em dados do broker, inspeção de posições, watchdog e interrupção de emergência.
5. Implantar opcionalmente servidor com HTTPS, segredos protegidos e backup, **somente após custo aprovado**.
6. Executar validação prolongada de estratégias; completar relatórios de desempenho e auditoria de perdas. Sem desempenho demonstrativo estável não se deve liberar REAL.
7. REAL: exigir confirmação explícita, autenticação individual, auditoria independente, confirmação do saldo/posições, limites fixos e opção de parada. O usuário solicitou objetivo futuro, mas **não houve ativação operacional de ordens reais**.

## Próximo passo no Codex

Abra os 3 arquivos de checkpoint, rode Python tests, **primeiro corrija o build Android**. Não reescreva módulos funcionais. Depois complete o fluxo DEMO ponta a ponta e testes falha/timeout. Salve checkpoint após cada pequena etapa. Se houver sinal de limite de créditos, commit e registre testes/pendências imediatamente. Não declare trabalho futuro garantido ou monitoramento de créditos sem ferramenta.

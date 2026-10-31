# VelaLab IA — Android + Deriv (base ampliada de desenvolvimento)

**Checkpoint:** 31/10/2026 · **Versão:** 0.2.0-laboratorio · **Estado:** protótipo de pesquisa, não sistema de negociação validado.

> ATENÇÃO: opções binárias são de alto risco; não há previsibilidade nem lucros garantidos. A simulação interna usa resultados **aleatórios e fictícios**; backtests usam candles fornecidos/obtidos e **não equivalem à execução de contratos**. A conta REAL pode ser consultada no app com sua autorização, mas **envio de ordens permanece desativado**. Nunca compartilhar senhas/PAT no GitHub, nesta conversa ou em logs.

## Funções incluídas neste pacote

**Android 13+ (Kotlin, Compose, escuro):** dashboard, simulador interno offline, histórico fictício, gerenciamento de banca, tela de pesquisa de estratégias, seleção de conta Deriv DEMO/REAL (consulta de saldo com confirmação para REAL); nenhuma ordem enviada pelo Android. Usa App ID + PAT temporariamente em memória para autenticação oficial no dispositivo. O app ainda **não foi compilado nem testado no Galaxy S24 FE**.

**Backend Python/FastAPI + SQLite:** API autenticada por Bearer; simulador separado; cálculos EMA 9/21/200, RSI, MACD, Bollinger, padrões de candles, regiões S/R, rompimento/reteste, combinação conservadora; backtesting com sinal no candle encerrado e entrada na próxima abertura; divisão temporal treino/validação/teste; histórico de versões e rollback somente DEMO; gerenciamento de risco persistente com exposição reservada e deduplicação; integração Deriv REST+WS em módulos separados; consulta opcional de contas e candles via API oficial (requer rede/configuração); código isolado de compra por proposal-id **bloqueado por padrão** e não exposto na API; observador de candles públicos (não faz trades).

**Infra:** Dockerfile e Compose de desenvolvimento local preparados (nada foi implantado); GitHub Actions para tentar compilar e publicar APK debug; 22 testes Python locais aprovados em 31/10/2026.

## O que NÃO está pronto

1. Compilação Android APK e teste no dispositivo — SDK Android/Gradle não acessíveis no ambiente atual.
2. Conexão real (rede), autenticação PAT, leitura de saldo e execução de qualquer contrato na Deriv — sem credenciais e sem testes de integração.
3. Robô automático conectado à corretora, reconciliação de operações, fechamento/resultado financeiro, push e infraestrutura VPS 24h — **não implementados/validados integralmente**.
4. IA de aprendizado contínuo em produção — há pesquisa/versionamento experimental, sem treinamento com dados reais, validação prolongada nem testes walk-forward completos para implantação.
5. Conta REAL para compra: **desabilitada** no produto. Existe apenas consulta read-only com consentimento, e um método de baixo nível isolado para desenvolvimento futuro.
6. Homologação/robustez e 3–6 meses de testes em PRACTICE — pendentes.

## Instalar o APK pelo GitHub

O repositório é: https://github.com/andersonpicolotto-hash/VelaLabIA

O GitHub Actions já tem workflow importador: `.github/workflows/velalab-apk.yml`. Envie o **ZIP inteiro, sem extrair**, para a raiz do repositório na branch `main` pelo link:

https://github.com/andersonpicolotto-hash/VelaLabIA/upload/main

Nome exigido: `VelaLabIA-Completo-Base.zip` (disponibilizado nesta conversa). O GitHub executará testes Python e tentativa de build APK. Confira Actions > VelaLab IA - importar codigo e gerar APK; se falhar, examinar logs e corrigir; **não considerar APK criado até artefato `VelaLabIA-fase1-APK` aparecer**. Depois baixe no navegador do celular, extraia e instale `app-debug.apk` somente depois de verificar a origem.

OBS: o repositório foi visto como **público** em 31/10/2026. Antes de subir qualquer arquivo privado, mude-o para Private, se desejar. O ZIP deste projeto não contém tokens conhecidos.

## Executar os testes do backend

```bash
cd backend
python -m venv .venv
source .venv/bin/activate  # Linux/macOS; no Windows: .venv\\Scripts\\activate
pip install -r requirements-dev.txt
python -m pytest -q
```

## Iniciar backend local

```bash
cd backend
export VELALAB_API_TOKEN="$(python -c 'import secrets;print(secrets.token_urlsafe(32))')"
uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Para Docker, crie `.env` **local, ignorado pelo Git**, com `VELALAB_API_TOKEN` aleatório, depois rode `docker compose up --build`. A API é vinculada somente a `127.0.0.1:8000`. Não exponha na internet sem HTTPS/reverse proxy e auditoria. O observador opcional usa `docker compose --profile observer up --build` e requer `VELALAB_WATCH_SYMBOLS`; não executa ordens. Nenhum serviço pago foi contratado.

### Endpoints principais autenticados

`GET /api/status`, `GET|PUT /api/settings`, `GET /api/trades`, `POST /api/sim/start|pause|stop|step`, `GET /api/research/strategies`, `POST /api/research/signals`, `POST /api/research/backtest`, `GET /api/research/versions`, `POST /api/research/versions/evaluate`, `GET /api/deriv/connection`, `GET /api/deriv/accounts` (somente se configurar PAT/App ID no servidor), `GET /api/deriv/candles/{symbol}` (dados públicos). **Não existe rota para enviar ordens.**

### Fontes técnicas

- https://developers.deriv.com/docs/intro/api-overview/
- https://developers.deriv.com/docs/intro/authentication/
- https://developers.deriv.com/docs/options/
- https://developers.deriv.com/docs/trading/buy/
- https://developers.deriv.com/docs/best-practices/

A disponibilidade real de contratos de 1/5 min e o payout devem ser consultados no momento da decisão, não presumidos.

## Checkpoints para economizar créditos

Consultar `docs/PLANO-E-CHECKPOINT.md`, `docs/PROMPT-PARA-CODEX-CONTINUAR.md` e `docs/RESULTADO-TESTES.txt`. Não pular fases de validação e não afirmar testes que não foram executados.

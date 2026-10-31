# VelaLab IA — Android + Deriv (base ampliada de desenvolvimento)

**Checkpoint:** 31/10/2026 · **Versão:** 0.4.0-real-opt-in · **Estado:** DEMO/REAL com executor experimental, NÃO homologado para negociação com dinheiro.

> ATENÇÃO: opções binárias são de alto risco; não há previsibilidade nem lucros garantidos. A simulação interna usa resultados **aleatórios e fictícios**; backtests usam candles fornecidos/obtidos e **não equivalem à execução de contratos**. O app agora permite selecionar e iniciar a conta REAL com confirmação explícita do usuário. Isso NÃO significa que seja seguro usá-la: a integração e a estratégia NÃO foram validadas em conta autenticada neste ambiente. Não há lucro garantido. Nunca compartilhar senhas/PAT no GitHub, nesta conversa ou em logs.

## Funções incluídas neste pacote

**Android 13+ (Kotlin, Compose, escuro):** dashboard, simulador interno offline, histórico fictício, gerenciamento de banca, tela de pesquisa de estratégias, seleção explícita de Deriv DEMO/REAL, executor automático experimental para cada modo com estratégia EMA9/EMA21 + RSI, proposta/compra/monitoramento por WebSocket, trava de perdas e diário separado por conta. REAL exige checkbox para conexão e diálogo de consentimento para iniciar. Nenhuma execução real foi validada. Usa App ID + PAT temporariamente em memória para autenticação oficial no dispositivo. O APK é compilado pelo GitHub Actions. **Não houve teste autenticado de execução DEMO ou REAL nem instalação validada no Galaxy S24 FE nesta sessão.**

**Backend Python/FastAPI + SQLite:** API autenticada por Bearer; simulador separado; cálculos EMA 9/21/200, RSI, MACD, Bollinger, padrões de candles, regiões S/R, rompimento/reteste, combinação conservadora; backtesting com sinal no candle encerrado e entrada na próxima abertura; divisão temporal treino/validação/teste; histórico de versões e rollback somente DEMO; gerenciamento de risco persistente com exposição reservada e deduplicação; integração Deriv REST+WS em módulos separados; consulta opcional de contas e candles via API oficial (requer rede/configuração); código isolado de compra por proposal-id **bloqueado por padrão** e não exposto na API; observador de candles públicos (não faz trades).

**Infra:** Dockerfile e Compose de desenvolvimento local preparados (nada foi implantado); GitHub Actions para tentar compilar e publicar APK debug; 22 testes Python locais aprovados em 31/10/2026.

## O que NÃO está pronto

1. Compilação Android APK e teste no dispositivo — SDK Android/Gradle não acessíveis no ambiente atual.
2. Testes autenticados de conexão, proposta, envio e liquidação na Deriv em DEMO e REAL — pendentes; não presumir funcionamento só porque compila.
3. Robô automático conectado à corretora, reconciliação de operações, fechamento/resultado financeiro, push e infraestrutura VPS 24h — **não implementados/validados integralmente**.
4. IA de aprendizado contínuo em produção — há pesquisa/versionamento experimental, sem treinamento com dados reais, validação prolongada nem testes walk-forward completos para implantação.
5. Conta REAL: **disponível para ativação manual**, sem bloqueio permanente. Não ativa automaticamente nem ao abrir o app. O executor só roda enquanto app/conexão estiverem ativos, não é serviço 24h. Falhas/ordens incertas bloqueiam novas compras até reconciliação.
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

`GET /api/status`, `GET|PUT /api/settings`, `GET /api/trades`, `POST /api/sim/start|pause|stop|step`, `GET /api/research/strategies`, `POST /api/research/signals`, `POST /api/research/backtest`, `GET /api/research/versions`, `POST /api/research/versions/evaluate`, `GET /api/deriv/connection`, `GET /api/deriv/accounts` (somente se configurar PAT/App ID no servidor), `GET /api/deriv/candles/{symbol}` (dados públicos). **Não existe rota backend para enviar ordens.** No Android existem executores separados DEMO/REAL por conexão WebSocket escolhida pelo usuário, sendo REAL iniciada somente após confirmação em diálogo.

### Fontes técnicas

- https://developers.deriv.com/docs/intro/api-overview/
- https://developers.deriv.com/docs/intro/authentication/
- https://developers.deriv.com/docs/options/
- https://developers.deriv.com/docs/trading/buy/
- https://developers.deriv.com/docs/best-practices/

A disponibilidade real de contratos de 1/5 min e o payout devem ser consultados no momento da decisão, não presumidos.

## Checkpoints para economizar créditos

Consultar `docs/PLANO-E-CHECKPOINT.md`, `docs/PROMPT-PARA-CODEX-CONTINUAR.md` e `docs/RESULTADO-TESTES.txt`. Não pular fases de validação e não afirmar testes que não foram executados.


## Robô DEMO experimental (novo)

Na aba **Conexão**, conecte a conta DEMO com seu App ID e PAT, configure o limite de perda diária e depois toque **Iniciar DEMO**. É necessário manter o aplicativo aberto e conectado. Os indicadores EMA9/EMA21/RSI são calculados com candles fechados; não há sinal garantido e pode passar horas sem operar. Os preços e contratos pretendem ser os da conta demonstrativa da Deriv (não o saldo fictício CV). O controle de perdas considera apenas operações DEMO registradas por este aplicativo, não trades feitos externamente. O teto não é proteção garantida da corretora.

**Atenção: esta integração foi codificada e compilada, mas não autenticada/testada com a conta do usuário neste ambiente.** Confirmar propostas, compras, status dos contratos e limites com pequenos valores DEMO antes de considerar funcional. Não há IA treinada nem validação de rentabilidade. A conta REAL continua incapaz de emitir ordens.


## Modo REAL (opt-in explícito, experimental, alto risco)

Na aba Conexão selecione **REAL**, confirme que deseja conectar a conta REAL, autentique-se com PAT e App ID da Deriv, defina limite de perda diária próprio da conta REAL, e clique **Iniciar REAL**. Antes do primeiro envio, é exibido um diálogo de confirmação indicando que negociações reais podem perder dinheiro. O robô não ativa REAL automaticamente ao iniciar nem ao conectar. O motor REAL grava histórico separado do DEMO e da simulação interna e limita a uma ordem por vez, percentual máximo de 1% por entrada e perda diária configurável. Estas proteções locais não são limites da corretora e podem falhar em condições de rede ou contabilidade inconsistente.

**Não utilizar dinheiro real como prova de funcionamento:** ainda faltam testes ponta a ponta na Deriv DEMO com conta autenticada, reconciliação de ordens incertas e auditoria do gerenciamento de risco. Uma compilação de APK NÃO demonstra que ordens reais funcionam corretamente nem que a estratégia é lucrativa.

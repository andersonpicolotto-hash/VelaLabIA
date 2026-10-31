# Integração com Deriv — nova corretora principal

**Verificado na documentação oficial em 30/10/2026.**

Fontes oficiais:
- https://developers.deriv.com/docs/intro/api-overview/
- https://developers.deriv.com/docs/intro/authentication/
- https://developers.deriv.com/docs/options/get-accounts/
- https://developers.deriv.com/docs/options/
- https://developers.deriv.com/docs/workflows/
- https://developers.deriv.com/docs/account/balance/
- https://developers.deriv.com/docs/data/active-symbols/

## Decisão de arquitetura

Deriv substitui IQ Option como **corretora principal** do VelaLab IA após aprovação do usuário. O código de IQ Option é mantido apenas como referência documental; nenhuma conexão IQ Option é implementada.

- **SIMULAÇÃO INTERNA**: créditos virtuais, eventos aleatórios locais, 100% separados da Deriv.
- **DEMO DERIV**: nesta Fase 1, aplicativo Android contém cliente **somente leitura**: consulta `GET /trading/v1/options/accounts`, seleciona apenas `account_type=demo`, solicita OTP via `POST /trading/v1/options/accounts/{accountId}/otp`, valida rigorosamente que a URL retornada seja o WebSocket **demo** no host oficial `api.derivws.com`, e lê `balance: 1` no WebSocket. Não há implementação de compras, vendas ou ordens.
- **MERCADO PÚBLICO**: botão para conectar a `wss://api.derivws.com/trading/v1/options/ws/public` e solicitar `active_symbols: brief`, sem conta/token. Informa apenas se recebeu lista, não que a conta foi autenticada.
- **REAL**: totalmente bloqueada; não existe cliente, rota, WebSocket ou controle para habilitar operações reais.
- **Backend FastAPI**: preserva a interface `BrokerAdapter`; `DerivAdapter` no backend retorna **DESCONECTADO** porque essa instância ainda não implementa a conexão. Na Fase 4, a operação contínua e a autenticação deverão migrar para uma implementação segura no servidor. Não confundir estado backend com estado do Android.

## Preparação pelo CELULAR (somente após gerar e instalar APK)

1. Abra a área para desenvolvedores: https://developers.deriv.com e entre na conta.
2. Registre um aplicativo tipo **PAT** e copie o **App ID**. App IDs legados podem não funcionar na nova API.
3. Na área de tokens, crie um PAT restrito à menor permissão necessária. A documentação da Deriv exige escopo **trade** para listar contas de Options e gerar OTP. **Atenção:** essa permissão é sensível, mesmo que nosso app só envie consultas. Use apenas em cenário demonstrativo e revogue o token se comprometido.
4. No aplicativo, abra **Conexão** → **Testar feed público (sem conta)**; esse teste usa o WS público sem credenciais.
5. Para testar sua própria conta demonstrativa, informe **Deriv App ID** e **PAT** na tela e pressione **Conectar DEMO — somente leitura**. O app procura a primeira conta `demo` encontrada; uma resposta com apenas contas reais é recusada.
6. Verifique o campo **Saldo recebido da Deriv (DEMO)**; esse valor não é o mesmo dos 10.000 créditos virtuais do simulador.
7. Toque em **Desconectar** quando terminar. A sessão também encerra quando a instância do aplicativo é destruída.

**Segurança:** nunca compartilhe PAT, senha, OTP ou tokens no ChatGPT, GitHub, capturas ou mensagens. Esta versão **não grava PAT em disco**; o token fica transitoriamente na memória enquanto o processo de autenticação é executado. Não são criados serviços externos, nem API de envio de ordens. A Deriv pode mudar documentação e regras de API: confirme disponibilidade por país, conta, contrato e termos vigentes antes de qualquer etapa operacional.

## O que ainda NÃO foi comprovado

- Não há PAT/App ID/autorização do usuário fornecidos neste ambiente, portanto nenhum teste autenticado DEMO foi realizado.
- Não foram executados WebSocket públicos ao vivo, nem autenticação na Deriv a partir deste ambiente; os endpoints e payloads foram conferidos na documentação oficial.
- Nenhum teste real de instalação, compilação Gradle ou execução no Galaxy S24 FE foi concluído.
- Esta fase não recebe ticks continuamente, não implementa indicadores, inteligência artificial, ordens ou servidor 24h.
- A conta REAL e negociações DEMO permanecem bloqueadas (somente leitura).

## Compatibilidade futura

Os recursos de ordens DEMO são exclusivos da **Fase 5**, exigem controles de risco, reconciliação, monitoramento, validação de payout/contrato e autorização específica de teste. Nada nesta escolha promete que determinados contratos, vencimentos ou mercados estarão disponíveis em sua jurisdição.

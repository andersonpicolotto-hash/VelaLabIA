# Integração com Deriv — nova corretora principal

**Revisto em 31/10/2026. Código de automação ainda experimental e sem testes autenticados.**

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
- **DEMO DERIV**: autenticação PAT/App ID + OTP, conexão WebSocket DEMO, leitura de saldo e executor automático experimental de opções com análise de candles EMA/RSI, proposta, compra e monitoramento de contrato. Ainda não testado com autenticação real.
- **MERCADO PÚBLICO**: botão para conectar a `wss://api.derivws.com/trading/v1/options/ws/public` e solicitar `active_symbols: brief`, sem conta/token. Informa apenas se recebeu lista, não que a conta foi autenticada.
- **REAL DERIV**: autenticada em WebSocket REAL via OTP, com executor isolado, histórico próprio, teto diário configurável, uma operação por vez, e confirmação expressa de início na interface. Não é habilitada automaticamente. Alto risco; sem validação de operação autêntica ou rentabilidade.
- **Backend FastAPI**: preserva a interface `BrokerAdapter`; `DerivAdapter` no backend retorna **DESCONECTADO** porque essa instância ainda não implementa a conexão. Na Fase 4, a operação contínua e a autenticação deverão migrar para uma implementação segura no servidor. Não confundir estado backend com estado do Android.

## Preparação pelo CELULAR (somente após gerar e instalar APK)

1. Abra a área para desenvolvedores: https://developers.deriv.com e entre na conta.
2. Registre um aplicativo tipo **PAT** e copie o **App ID**. App IDs legados podem não funcionar na nova API.
3. Na área de tokens, crie um PAT restrito para a conta/escopo necessários. O escopo de negociação é sensível: **não compartilhe nem guarde no repositório público**.
4. No aplicativo, abra **Conexão** → **Testar feed público (sem conta)**; esse teste usa o WS público sem credenciais.
5. Em Conexão escolha DEMO ou REAL, preencha App ID e PAT e conecte. Nunca confunda a conta selecionada: o WebSocket é validado contra DEMO ou REAL.
6. Confira o saldo da conta selecionada, configure separadamente a perda diária; para iniciar o executor, toque Iniciar DEMO ou, em REAL, aceite o diálogo explícito de risco.
7. Toque em **Desconectar** quando terminar. A sessão também encerra quando a instância do aplicativo é destruída.

**Segurança:** nunca compartilhe PAT, senha, OTP ou tokens no ChatGPT, GitHub, capturas ou mensagens. Esta versão **não grava PAT em disco**; o token fica transitoriamente na memória enquanto o processo de autenticação é executado. Não são criados serviços externos, nem API de envio de ordens. A Deriv pode mudar documentação e regras de API: confirme disponibilidade por país, conta, contrato e termos vigentes antes de qualquer etapa operacional.

## O que ainda NÃO foi comprovado

- Não há PAT/App ID do usuário fornecidos neste ambiente; nenhum teste autenticado DEMO ou REAL foi realizado.
- Não foram executados WebSocket públicos ao vivo, nem autenticação na Deriv a partir deste ambiente; os endpoints e payloads foram conferidos na documentação oficial.
- A compilação Android pelo GitHub Actions passou; instalação e operação contra a Deriv no Galaxy S24 FE não foram validadas nesta sessão.
- O código executa análise de candles quando o app está aberto; faltam testes de execução/monitoramento e recuperação em rede, validação de estratégia e servidor 24h.
- DEMO e REAL são opt-in manuais, porém a execução não foi homologada. Não usar fundos reais para testar conectividade.

## Compatibilidade futura

Executores DEMO e REAL estão no Android como protótipos. O modo REAL é tecnicamente selecionável mediante autorização por sessão, não uma recomendação para negociar dinheiro. Controles locais podem falhar sob condições inesperadas; faltam testes end-to-end, auditoria e validação antes de confiar no sistema.

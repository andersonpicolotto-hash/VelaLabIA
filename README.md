# VelaLab IA — código e build Android

## Estado verificado em 31/10/2026

- Repositório conectado ao ChatGPT.
- Código-fonte ampliado das fases 1–6 disponível como ZIP nesta conversa; **ainda não foi enviado à branch main**.
- Backend: **21 testes Python** e **7 verificações Kotlin da política de endpoint** aprovados localmente.
- APK Android: **não compilado**. Integração Deriv com credenciais reais: **não testada**.
- REAL: consulta de saldo read-only preparada; **nenhuma ordem real pode ser enviada pelo app**.
- IA: laboratório experimental e backtesting, não sistema validado para negociar.

## Importar projeto e gerar o APK

1. Baixe na conversa o arquivo `VelaLabIA-Completo-Base.zip`.
2. Faça upload do ZIP (sem descompactar ou renomear) em:
   https://github.com/andersonpicolotto-hash/VelaLabIA/upload/main
3. Confirme `Commit changes`.
4. Veja a execução de `VelaLab IA - importar codigo e gerar APK`:
   https://github.com/andersonpicolotto-hash/VelaLabIA/actions/workflows/velalab-apk.yml
5. Somente quando os passos passarem, baixe o artifact `VelaLabIA-APK-Preview`, extraia o `app-debug.apk` e instale.

O workflow verifica os testes Python, tenta compilar Android com Gradle 8.9, publica o APK e depois faz commit dos fontes na branch principal. **Se a compilação falhar, não haverá APK; analise os logs antes de dizer que funciona.**

## Segurança

O repositório estava **público** na última verificação. Mude para privado se não quiser expor o código. Não adicione PAT, senhas, tokens ou dados financeiros ao GitHub.

A Deriv oferece API oficial para conta demo e real, mas este aplicativo **não contém endpoints públicos para ordens**. Em particular, operar com dinheiro real depende de autorização operacional específica e testes independentes; indicadores/IA não garantem lucro.

Docs: https://developers.deriv.com/docs/intro/api-overview/

Assim que as fontes forem importadas, o README completo do ZIP substituirá este documento.

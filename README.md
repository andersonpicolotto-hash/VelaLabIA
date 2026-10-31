# VelaLab IA — Fase 1

Aplicativo Android experimental para **simulação interna fictícia** com integração de leitura para **Deriv DEMO** em desenvolvimento. **Não envia ordens e não opera com dinheiro real.**

## Como importar o projeto e gerar o APK

O arquivo fonte é `VelaLabIA-Deriv-Fase1.zip` (41 KB). Faça o upload **sem descompactar** para a raiz da branch `main`, com esse nome exato:

[Enviar pacote para o GitHub](https://github.com/andersonpicolotto-hash/VelaLabIA/upload/main)

Assim que o ZIP for enviado, o workflow [VelaLab IA - importar codigo e gerar APK](../../actions/workflows/velalab-apk.yml) irá:

1. Descompactar o arquivo de origem;
2. Executar os testes Python;
3. Compilar o APK Android debug com Gradle;
4. Disponibilizar o artefato `VelaLabIA-fase1-APK` na execução;
5. Salvar o código-fonte organizado em pastas no próprio repositório, somente se todos os testes e a compilação passarem.

O Android ainda **não foi compilado nem testado em dispositivo** neste repositório. Se a compilação falhar, conferir o log de Actions e corrigir antes de declarar o APK pronto.

## Segurança e limites

- A conta real fica bloqueada;
- O simulador utiliza créditos virtuais e não depende da Deriv;
- Não colocar senhas, tokens da Deriv ou segredos em arquivos do GitHub;
- A conexão DEMO depende de App ID e PAT próprios inseridos no aplicativo, com endpoint oficial e apenas leitura;
- Preferir repositório **privado**. Mude em Settings → General → Danger Zone → Change repository visibility, se apropriado;
- Somente Fase 1 em implementação. Fases 2 a 6 aguardam autorização.

## Continuar depois

Os arquivos `docs/PLANO-E-CHECKPOINT.md` e `docs/PROMPT-PARA-CODEX-CONTINUAR.md` são importados junto com o pacote e documentam as fases e os checkpoints de progresso.

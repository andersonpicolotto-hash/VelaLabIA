# Colocar o VelaLab IA no GitHub e gerar o APK pelo celular

**Situação em 30/10/2026:** o GitHub foi conectado ao ChatGPT, mas a conta ainda não possui repositórios disponíveis. O conector disponível pode criar e alterar arquivos em repositórios existentes, mas não criar um repositório novo. Por isso, a primeira ação abaixo deve ser feita pelo titular da conta. **O APK ainda não foi compilado.**

## Uma ação necessária do titular da conta

1. Pelo navegador do celular, abra https://github.com/new e entre na conta GitHub conectada ao ChatGPT.
2. Em **Repository name**, escreva `VelaLabIA`.
3. Selecione **Private**, para manter o código privado durante os testes.
4. Marque **Add a README file** (gera o ramo principal `main`); não ative templates adicionais.
5. Toque em **Create repository**.
6. Volte à conversa com ChatGPT e diga: **`Criei o repositório VelaLabIA. Pode enviar o projeto e gerar o APK.`**

Não envie usuário, senha, chave privada nem token. Com o repositório disponível no conector, o ChatGPT poderá encaminhar os arquivos-fonte e o workflow ao repositório, confirmar o commit e acompanhar o build se o GitHub disponibilizar os registros.

## Depois de o código estar no GitHub

1. Abra o repositório no navegador Android e acesse **Actions** → **Gerar APK VelaLab IA**.
2. Se o workflow não tiver rodado automaticamente no push para `main`, escolha **Run workflow**.
3. Confirme que as etapas do workflow terminaram com ícones verdes. Se aparecer vermelho, veja os logs: não é um APK pronto.
4. Abra o workflow finalizado e vá em **Artifacts** → **VelaLabIA-fase1-APK**.
5. Baixe o ZIP, extraia no aplicativo **Meus Arquivos** do Samsung e abra `app-debug.apk`.
6. Se o Android pedir permissão para instalar aplicativos desta fonte, habilite-a apenas para a instalação escolhida.
7. Abra o app e confirme no topo **SIMULAÇÃO INTERNA** e **DERIV: DESCONECTADO**. Teste primeiro uma simulação com créditos virtuais.

GitHub Actions pode ter cotas e limites de minutos de execução. Verifique o plano/consumo da sua conta antes de executar repetidamente os workflows. Não foi autorizado nenhum serviço pago.

## Limitações

- A simulação Android é local, fictícia e independente do backend Python.
- Nenhuma conta DEMO/REAL da Deriv recebe ordens; o Android tem consulta somente-leitura de conta DEMO e feed público, ainda sem testes ao vivo.
- Se a compilação falhar, o erro deverá ser corrigido no projeto e o workflow reexecutado.
- Somente testes em dispositivo físico podem comprovar a instalação e o uso real no Galaxy S24 FE.

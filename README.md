# Lume — leitor de livros para Android

App Android (Kotlin + Jetpack Compose) para ler PDFs e textos, no estilo Kindle / MEC Livros.

## O que ele faz

- **Importa PDFs** (botão *Adicionar*, ou "Abrir com" / "Compartilhar" a partir de outro app) e arquivos `.txt`.
- **Textos próprios**: escreva ou cole um texto, dê um título e ele vira um livro (dá para editar depois).
- **Dois modos para PDF**
  - *Texto*: o texto do PDF é extraído e reorganizado, então dá para mudar fonte, tamanho, espaçamento e margens.
  - *Páginas*: o PDF original, com zoom por pinça (até 6×), duplo toque e arrastar. PDFs escaneados (sem texto) abrem só neste modo.
- **Progresso**: porcentagem lida, página atual e **tempo restante** calculado com a *sua* velocidade de leitura,
  medida enquanto você lê (até ter dados suficientes, usa 200 palavras/min).
- **Modos de tela**: Claro, Leitura (sépia) e Escuro — valem também para as páginas do PDF.
- **Letra**: tamanho de 10 a 56 pt (controle deslizante, botões A−/A+ ou pinça no modo texto).
- **Fontes**: Literata, Lora, Atkinson Hyperlegible, Nunito, as do sistema, e qualquer `.ttf`/`.otf` que você importar.
- Lembra onde você parou em cada livro; toque no meio da tela para mostrar/ocultar as barras.

Tudo fica guardado só no aparelho; o app não pede nenhuma permissão nem usa internet.

## Como instalar

Baixe o `app-release.apk` (artefato **lume-apk** da aba *Actions* do GitHub, gerado a cada push) e abra no celular,
permitindo "instalar apps desconhecidos" quando o Android perguntar. Requer Android 7.0 ou mais novo.

## Como compilar

```
./gradlew :app:assembleRelease     # APK em app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest   # testes
```

Precisa do JDK 17+ e do Android SDK (plataforma 35).

> O arquivo `sideload.keystore` é uma chave de assinatura só para instalação direta, para que as atualizações
> instalem por cima da versão anterior. Não use essa chave para publicar em loja.

## Fontes incluídas

Literata, Lora, Atkinson Hyperlegible e Nunito são distribuídas sob a SIL Open Font License 1.1.

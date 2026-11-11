# Lume — leitor de livros para Android

App Android (Kotlin + Jetpack Compose) para ler PDFs e textos, no estilo Kindle / MEC Livros.

## O que ele faz

- **Importa PDFs** (botão *Adicionar*, ou "Abrir com" / "Compartilhar" a partir de outro app) e arquivos `.txt`.
- **Textos próprios**: escreva ou cole um texto, dê um título e ele vira um livro (dá para editar depois).
- **Dois modos para PDF**
  - *Texto*: o texto do PDF é extraído e reorganizado, então dá para mudar fonte, tamanho, espaçamento e margens.
  - *Páginas*: o PDF original, com zoom por pinça (até 6×), duplo toque e arrastar. PDFs escaneados (sem texto) abrem só neste modo.
- **Duas formas de passar as páginas** (em *Aa → Como passar as páginas*):
  - *Rolar a tela*: arrasta o texto para cima e para baixo.
  - *Tocar nas laterais*: uma tela por vez, como no Kindle — toque na direita avança, na esquerda volta
    (ou deslize para os lados); toque no meio abre o menu. Vale também para as páginas do PDF.
- **Frases marcadas**: no modo texto, segure o dedo sobre uma frase e toque em *Marcar frase*; ela fica destacada.
  Na tela inicial, a aba *Frases marcadas* lista os livros ("Fundação e Império — 5 frases marcadas"); tocando no
  livro aparecem as frases em tópicos, e tocar numa frase abre o livro exatamente nela.
- **Dicionário offline**: o mesmo toque longo mostra a definição da palavra que estava sob o dedo, e a aba
  *Dicionário* permite consultar qualquer palavra. São ~99 mil verbetes e ~408 mil formas flexionadas embutidos no
  app, sem internet. A busca entende plurais, femininos, diminutivos, verbos conjugados ("fizeram" → *fazer*),
  pronomes grudados ("fazê-lo", "dir-se-ia"), falta de acento e grafias antigas ou de Portugal ("acção", "óptimo").
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

## Dicionário

As definições vêm do [Wikcionário](https://pt.wiktionary.org) em português (licença
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/deed.pt-br)), a partir da extração feita pelo projeto
[kaikki.org](https://kaikki.org/ptwiktionary/). O arquivo `app/src/main/assets/dicionario.bin` é gerado por
`tools/build_dictionary.py` (as instruções estão no começo do script).

## Fontes incluídas

Literata, Lora, Atkinson Hyperlegible e Nunito são distribuídas sob a SIL Open Font License 1.1.

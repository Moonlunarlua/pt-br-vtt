# Estremere VTT

Mesa virtual de RPG para o sistema **Estremere**. Você (mestre) liga a mesa no seu PC e os jogadores entram pelo navegador, no PC ou no celular, estando na **mesma rede** (mesmo Wi-Fi ou cabo).

Esta primeira versão tem:

- **Mapa** com grade, imagem de fundo, zoom e tokens que todo mundo vê mexer em tempo real.
- **Chat** com rolador de dados (inclusive d2, d3 e d5) e aviso de **stun** no dano máximo.
- **Tabela de iniciativa** com rodadas, vida e condições.

## 1. Instalar (só uma vez)

1. Instale o **Node.js** versão LTS: https://nodejs.org/pt-br/download (é só ir clicando em "Avançar").
2. Baixe esta pasta (no GitHub: botão verde **Code → Download ZIP**) e descompacte onde quiser.

## 2. Abrir a mesa

1. Dê dois cliques em **`iniciar.bat`**.
   - Na primeira vez ele baixa o que precisa (demora um pouquinho).
   - Se o Windows perguntar sobre o **Firewall**, marque **Redes privadas** e clique em **Permitir**. Sem isso os jogadores não conseguem entrar.
2. A janela preta mostra:
   - o endereço para você: `http://localhost:3000`
   - o endereço para os jogadores, tipo `http://192.168.0.15:3000`
   - a **senha do mestre** e a **senha dos jogadores**.
3. Abra o seu endereço, entre com seu nome e a senha do mestre.
4. Mande para os jogadores o outro endereço e a senha dos jogadores. Dentro da mesa, o botão **Link da mesa** mostra o endereço com botão de copiar.

Deixe a janela preta aberta enquanto jogam. Fechar a janela fecha a mesa (nada se perde).

## 3. Como usar

### Mapa
- **+ Token** cria um token no meio da tela. Clique nele para editar nome, cor, imagem, tamanho e vida.
- Arraste para mover (encaixa na grade; segure **Shift** para mover livre). Aparece quantas casas andou.
- Roda do mouse = zoom. Arrastar o fundo = mover a câmera. No celular: arraste com um dedo e pinça com dois.
- **Duplo clique** faz um "ping" que todo mundo vê.
- **Régua** (ou tecla R) mede distância em casas. A diagonal conta como 1 casa.
- O mestre escolhe o **dono** de cada token. Jogador só move o que é dele e pode criar o próprio token.
- O mestre pode marcar um token como **oculto**: os jogadores nem recebem ele. Dica: marque "oculto" *antes* de dar nome a uma surpresa.
- **Cena** (só mestre): nome, tamanho em casas e imagem de fundo.

### Chat e dados
| Comando | O que faz |
|---|---|
| `/r 1d20+2 # Teste de Força` | Rola e mostra o comentário |
| `/dano 2d4` | Rolagem de dano. Se todos os dados saírem no máximo, avisa: **o alvo fica atordoado (stun)** |
| `/gr 1d20` | Rolagem secreta: só você e o mestre veem |
| `/r 1d20 vant` / `/r 1d20 desv` | Vantagem/desvantagem (o mesmo que `2d20kh1` / `2d20kl1`) |
| `/r 4d6kh3` | Rola 4, mantém os 3 maiores (os descartados aparecem riscados) |
| `/w Ana texto` | Sussurro só para a Ana (`/w mestre texto` fala só com o mestre) |
| `/me abre a porta` | Ação do personagem |
| `/ajuda` | Mostra essa lista |

Os botões **d2 … d20** rolam na hora. Se você digitar `+3` no campo antes de clicar, o modificador vai junto. Shift+clique rola em segredo.

Todas as rolagens são feitas **no servidor**: ninguém consegue trapacear pelo navegador. O **20 natural** fica verde e é destacado.

### Iniciativa
- Mestre: **+ Token selecionado** põe o token clicado no combate. Também dá para adicionar inimigos sem token no formulário de baixo (com vida, modificador e opção oculto).
- Jogador: clique no seu token e em **Entrar com meu token selecionado**, depois no 🎲 para rolar sua iniciativa.
- **Rolar NPCs** rola de todos os inimigos que ainda não rolaram (a rolagem de NPC é secreta, só o mestre vê).
- **Começar / Próximo ▶** avança o turno. A cada rodada nova, as condições com contador perdem 1 e somem quando zeram.
- Dano/cura: digite o número e clique em **−** ou **+** (Enter = dano). A vida é a mesma do token no mapa.
- Jogadores veem a vida dos personagens de jogador e só o **estado** dos inimigos: ileso, ferido, muito ferido, caído.
- A fórmula de iniciativa é `1d20` por padrão e o mestre pode trocar no campo **Fórmula** (o compêndio ainda não define).

## 4. Senhas

Ficam em `data/config.json` (criado na primeira vez). Abra no Bloco de Notas, troque `senhaMestre` e `senhaJogadores` e reinicie a mesa. Também dá para trocar a `porta` se a 3000 estiver ocupada.

## 5. Backup

Tudo da mesa (mapa, tokens, chat, combate e imagens enviadas) fica na pasta **`data/`**. Para fazer backup, copie essa pasta. Toda vez que a mesa liga ela guarda uma cópia do estado anterior em `data/state.backup.json`.

## 6. Deu problema?

- **Os jogadores não abrem o link:** confira se estão no mesmo Wi-Fi e libere o Node no Firewall do Windows (Painel de Controle → Firewall do Windows → Permitir um aplicativo → marque "Node.js" em Privada).
- **"Senha errada":** veja a senha na janela preta ou no `data/config.json`.
- **"Esse nome já é usado":** o nome já entrou como mestre (ou jogador). Use outro.

## Para quem programa

```
npm install
npm start          # sobe em http://localhost:3000
npm test           # testes do rolador de dados e do filtro de permissões
```

- `server.js`: Express + WebSocket (`ws`). O servidor é a fonte da verdade.
- `lib/dice.js`: parser e rolador. `lib/filter.js`: o que cada pessoa pode receber. `lib/store.js`: salva em `data/state.json`.
- `public/`: cliente em HTML/CSS/JS puro, sem etapa de build.

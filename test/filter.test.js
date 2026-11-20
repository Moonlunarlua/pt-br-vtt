'use strict';
const test = require('node:test');
const assert = require('node:assert');
const F = require('../lib/filter');
const { defaultState } = require('../lib/store');

const gm = { name: 'Luan', role: 'gm' };
const ana = { name: 'Ana', role: 'player' };
const bob = { name: 'Bob', role: 'player' };

function sample() {
  const s = defaultState();
  s.tokens = {
    a: { id: 'a', name: 'Heroína', owner: 'Ana', hidden: false, hp: 10, hpMax: 12 },
    o: { id: 'o', name: 'Orc', owner: '', hidden: false, hp: 3, hpMax: 10, notes: 'segredo' },
    h: { id: 'h', name: 'Emboscada', owner: '', hidden: true, hp: 8, hpMax: 8 },
  };
  s.combat.list = [
    { id: 'c1', name: 'Emboscada', tokenId: 'h', init: 19, mod: 2, conditions: [] },
    { id: 'c2', name: 'Heroína', tokenId: 'a', init: 12, conditions: [] },
    { id: 'c3', name: 'Orc', tokenId: 'o', init: 8, mod: 1, conditions: [] },
    { id: 'c4', name: 'Espião', tokenId: null, hidden: true, hp: 5, hpMax: 5, init: 5, conditions: [] },
  ];
  s.combat.turn = 0;
  s.chat = [
    { from: 'Luan', type: 'roll', gmOnly: true },
    { from: 'Ana', type: 'whisper', to: ['Bob'] },
    { from: 'Ana', type: 'text' },
    { from: 'Bob', type: 'whisper', to: ['Luan'] },
  ];
  return s;
}

test('mestre vê tudo do mapa e do combate', () => {
  const b = F.filterBoard(sample(), gm);
  assert.deepStrictEqual(Object.keys(b.tokens).sort(), ['a', 'h', 'o']);
  assert.strictEqual(b.tokens.o.hp, 3);
  assert.strictEqual(b.tokens.o.notes, 'segredo');
  assert.strictEqual(b.combat.list.length, 4);
  assert.strictEqual(b.combat.turnId, 'c1');
});

test('jogador não recebe token oculto nem anotação do mestre', () => {
  const b = F.filterBoard(sample(), ana);
  assert.deepStrictEqual(Object.keys(b.tokens).sort(), ['a', 'o']);
  assert.strictEqual(b.tokens.o.notes, undefined);
  assert.ok(!JSON.stringify(b).includes('Emboscada'));
  assert.ok(!JSON.stringify(b).includes('Espião'));
});

test('jogador vê vida de personagem de jogador, e só o estado de inimigo', () => {
  const b = F.filterBoard(sample(), bob);
  assert.strictEqual(b.tokens.a.hp, 10);
  assert.strictEqual(b.tokens.o.hp, undefined);
  assert.strictEqual(b.tokens.o.hpMax, undefined);
  assert.strictEqual(b.tokens.o.hpStatus, 'muito ferido');
  const orc = b.combat.list.find((c) => c.id === 'c3');
  assert.strictEqual(orc.hp, undefined);
  assert.strictEqual(orc.mod, undefined);
  assert.strictEqual(orc.hpStatus, 'muito ferido');
});

test('turno de combatente oculto não vaza', () => {
  const b = F.filterBoard(sample(), ana);
  assert.strictEqual(b.combat.turnId, null);
  assert.deepStrictEqual(b.combat.list.map((c) => c.id), ['c2', 'c3']);
});

test('chat: rolagem secreta e sussurros', () => {
  const s = sample();
  assert.strictEqual(F.filterChat(s.chat, ana).length, 2); // o próprio sussurro e o texto
  assert.strictEqual(F.filterChat(s.chat, bob).length, 3); // recebeu sussurro, texto e o próprio
  assert.strictEqual(F.filterChat(s.chat, gm).length, 3); // não vê sussurro Ana→Bob
});

test('permissão de mover token', () => {
  const s = sample();
  assert.ok(F.canEditToken(ana, s.tokens.a));
  assert.ok(!F.canEditToken(bob, s.tokens.a));
  assert.ok(!F.canEditToken(ana, s.tokens.o));
  assert.ok(F.canEditToken(gm, s.tokens.o));
});

test('estado de vida', () => {
  assert.strictEqual(F.hpStatus(10, 10), 'ileso');
  assert.strictEqual(F.hpStatus(6, 10), 'ferido');
  assert.strictEqual(F.hpStatus(5, 10), 'muito ferido');
  assert.strictEqual(F.hpStatus(0, 10), 'caído');
});

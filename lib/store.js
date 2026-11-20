'use strict';
// Guarda o estado da mesa em data/state.json, com escrita "debounced"
// (no máximo 1 gravação por segundo) e uma cópia de segurança ao iniciar.

const fs = require('fs');

function defaultState() {
  return {
    version: 1,
    users: {},
    scene: { name: 'Cena 1', cols: 30, rows: 20, bg: '', gridColor: '#000000', gridOpacity: 0.35 },
    tokens: {},
    chat: [],
    combat: { round: 0, turn: 0, formula: '1d20', list: [] },
  };
}

class Store {
  constructor(file) {
    this.file = file;
    this.timer = null;
    const base = defaultState();
    let loaded = {};
    if (fs.existsSync(file)) {
      try {
        loaded = JSON.parse(fs.readFileSync(file, 'utf8'));
        fs.copyFileSync(file, file.replace(/\.json$/, '.backup.json'));
      } catch (e) {
        const broken = file.replace(/\.json$/, `.quebrado-${Date.now()}.json`);
        fs.copyFileSync(file, broken);
        console.error(`state.json estava corrompido; salvei uma cópia em ${broken} e comecei do zero.`);
      }
    }
    this.state = {
      ...base,
      ...loaded,
      scene: { ...base.scene, ...(loaded.scene || {}) },
      combat: { ...base.combat, ...(loaded.combat || {}) },
    };
  }

  save() {
    if (this.timer) return;
    this.timer = setTimeout(() => this.flush(), 1000);
  }

  flush() {
    if (this.timer) { clearTimeout(this.timer); this.timer = null; }
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(this.state));
    fs.renameSync(tmp, this.file);
  }
}

module.exports = { Store, defaultState };

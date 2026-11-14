// Runs inside the sandbox container: speaks line-delimited JSON with the host over stdin/stdout, no network, no fs.
'use strict';
const readline = require('readline');
const vm = require('vm');
const send = (message) => process.stdout.write(JSON.stringify(message) + '\n');

let nextId = 1;
const pending = new Map();

// Everything the script can touch is built inside a vm context from strings only: an object or function of this realm handed to the script would
// reach the real Function constructor and with it require and process. Defence in depth: the container is the isolation boundary.
// The names must equal TypeScriptSignatures.RESERVED_TOOL_NAMES (a test compares them); the proxies never turn them into tool calls
// (probes by String(), template literals, await, JSON.stringify, console.log) and servers.x.__call('name', args) reaches a real tool of such a name.
const RESERVED_NAMES = ['then', 'toJSON', 'inspect', 'toString', 'valueOf', 'toLocaleString', 'constructor', 'hasOwnProperty', 'isPrototypeOf', 'propertyIsEnumerable', '__proto__', '__call'];

// Runs inside the context. One rule shared with prelude.js: strings raw, other values JSON, anything JSON cannot hold (circular, BigInt) falls back to a string.
function installInContext(callTool, writeLog, reserved) {
  let index = null;
  const show = (value) => {
    if (typeof value === 'string') return value;
    try { return JSON.stringify(value) ?? String(value); } catch (e) {
      try { return String(value); } catch (e2) { return Object.prototype.toString.call(value); }
    }
  };
  const call = (server, tool, args) => new Promise((resolve, reject) => {
    callTool(String(server), String(tool), JSON.stringify(args === undefined ? {} : args)).then(
      (reply) => { try { resolve(JSON.parse(reply)); } catch (e) { resolve(reply); } },
      (message) => reject(new Error(String(message))));
  });
  const isReserved = (name) => typeof name !== 'string' || reserved.includes(name);
  const tools = (server) => new Proxy({}, {
    get: (target, tool) => tool === '__call' ? (name, args) => call(server, name, args)
      : isReserved(tool) ? Reflect.get(target, tool) : (args) => call(server, tool, args),
  });
  // Same rule as prelude.js: with a server index, a name is the alias, the saved name or a Claude tool prefix (mcp__<name>__<tool>); any other name fails at once.
  const aliasOf = (name) => {
    if (index === null) return name;
    const plain = name.startsWith('mcp__') ? name.slice(5).split('__')[0] : name;
    const alias = Object.hasOwn(index.names, name) ? index.names[name] : Object.hasOwn(index.names, plain) ? index.names[plain] : undefined;
    if (alias === undefined) throw new Error(`Unknown server '${name.slice(0, 40)}'. Available in Code Mode: ${index.available}. Use list_servers.`);
    return alias;
  };
  globalThis.servers = new Proxy({}, { get: (target, server) => isReserved(server) ? Reflect.get(target, server) : tools(aliasOf(server)) });
  const log = (...values) => writeLog(values.map(show).join(' '));
  globalThis.console = { log, info: log, warn: log, error: log };
  return { show, setIndex: (json) => { index = typeof json === 'string' ? JSON.parse(json) : null; } };
}

const context = vm.createContext(Object.create(null));
const { show, setIndex } = vm.runInContext('(' + installInContext.toString() + ')', context)(
  (server, tool, args) => new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve, reject });
    send({ type: 'call', id, server, tool, args: JSON.parse(args) });
  }),
  (line) => send({ type: 'log', line: String(line) }),
  RESERVED_NAMES);

let finished = false;
function finish(message) {
  if (finished) return;
  finished = true;
  process.stdout.write(JSON.stringify(message) + '\n', () => process.exit(0));
}

// The script has no timers, so once its promise is unsettled with no tool call outstanding after the microtasks drained, nothing can ever settle it.
function failIfStuck() {
  setImmediate(() => {
    if (!finished && pending.size === 0) finish({ type: 'error', message: 'script did not finish: a promise never settled' });
  });
}

async function execute(code) {
  try {
    const promise = vm.runInContext('(async () => {' + code + '\n})()', context);
    failIfStuck();
    const value = await promise;
    finish({ type: 'done', value: value === undefined ? '' : show(value) });
  } catch (e) {
    let message;
    try { message = String(e); } catch (e2) { message = 'unknown error'; }
    finish({ type: 'error', message });
  }
}

function settle(message) {
  const waiter = pending.get(message.id);
  if (!waiter) return;
  pending.delete(message.id);
  if (message.error !== undefined) {
    waiter.reject(String(message.error));
    return;
  }
  waiter.resolve(String(message.value));
}

readline.createInterface({ input: process.stdin }).on('line', (line) => {
  let message;
  try {
    message = JSON.parse(line);
  } catch (e) {
    return;
  }
  if (message.type === 'run') {
    setIndex(message.servers);
    execute(String(message.code));
  }
  else if (message.type === 'result') {
    settle(message);
    failIfStuck();
  }
});

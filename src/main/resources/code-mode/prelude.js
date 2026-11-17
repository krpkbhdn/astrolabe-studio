(() => {
  const bridge = __bridge;
  delete globalThis.__bridge;
  const index = typeof __servers === 'string' ? JSON.parse(__servers) : null;
  delete globalThis.__servers;
  for (const name of ['Java', 'Packages', 'Graal', 'load', 'loadWithNewGlobal', 'Polyglot', 'java', 'javax', 'com', 'org', 'edu', 'net', 'print', 'printErr']) {
    delete globalThis[name];
  }

  // One rule shared with runner.js: strings raw, other values JSON, anything JSON cannot hold (circular, BigInt) falls back to a string and never throws.
  const show = (value) => {
    if (typeof value === 'string') return value;
    try { return JSON.stringify(value) ?? String(value); } catch (e) {
      try { return String(value); } catch (e2) { return Object.prototype.toString.call(value); }
    }
  };
  const log = (...args) => bridge.log(args.map(show).join(' '));
  globalThis.console = { log, info: log, warn: log, error: log };

  const call = (server, tool, args) => new Promise((resolve, reject) => {
    try {
      resolve(JSON.parse(bridge.call(String(server), String(tool), JSON.stringify(args ?? {}))));
    } catch (e) {
      reject(e);
    }
  });
  // Names the proxies never turn into tool calls (probes by String(), template literals, await, JSON.stringify, console.log): they answer like a plain object, and
  // servers.x.__call('name', args) reaches a real tool of such a name. Must equal TypeScriptSignatures.RESERVED_TOOL_NAMES (a test compares them).
  const RESERVED_NAMES = ['then', 'toJSON', 'inspect', 'toString', 'valueOf', 'toLocaleString', 'constructor', 'hasOwnProperty', 'isPrototypeOf', 'propertyIsEnumerable', '__proto__', '__call'];
  const isReserved = (name) => typeof name !== 'string' || RESERVED_NAMES.includes(name);
  const tools = (server) => new Proxy({}, {
    get: (target, tool) => tool === '__call' ? (name, args) => call(server, name, args)
      : isReserved(tool) ? Reflect.get(target, tool) : (args) => call(server, tool, args),
  });
  // Same rule as runner.js: with a server index, a name is the alias, the saved name or a Claude tool prefix (mcp__<name>__<tool>); any other name fails at once.
  const aliasOf = (name) => {
    if (index === null) return name;
    const plain = name.startsWith('mcp__') ? name.slice(5).split('__')[0] : name;
    const alias = Object.hasOwn(index.names, name) ? index.names[name] : Object.hasOwn(index.names, plain) ? index.names[plain] : undefined;
    if (alias === undefined) throw new Error(`Unknown server '${name.slice(0, 40)}'. Available in Code Mode: ${index.available}. Use list_servers.`);
    return alias;
  };
  globalThis.servers = new Proxy({}, { get: (target, server) => isReserved(server) ? Reflect.get(target, server) : tools(aliasOf(server)) });

  // Timers only queue here; the host loop sleeps until nextDelay() is over and then calls fire(), so JS never runs on another thread (no intervals).
  // Built-ins are captured now and the queue uses plain indexing, so a script that patches Date, Math or Array.prototype cannot disturb the loop.
  // Same value as MAX_TIMER_MS in runner.js, so nextDelay always fits a long.
  const MAX_TIMER_MS = 2 ** 31 - 1;
  const now = Date.now.bind(Date);
  const apply = Reflect.apply;
  const ceil = Math.ceil;
  const delayOf = (ms) => { const n = Number(ms); return n > 0 ? (n < MAX_TIMER_MS ? ceil(n) : MAX_TIMER_MS) : 0; };
  const timers = [];
  let count = 0;
  let nextTimer = 1;
  const earliestIndex = () => {
    let best = -1;
    for (let i = 0; i < count; i++) {
      if (best < 0 || timers[i].at < timers[best].at || (timers[i].at === timers[best].at && timers[i].id < timers[best].id)) best = i;
    }
    return best;
  };
  const removeAt = (i) => { timers[i] = timers[count - 1]; timers[count - 1] = undefined; count--; };
  globalThis.setTimeout = (fn, ms, ...args) => {
    if (typeof fn !== 'function') throw new TypeError('setTimeout needs a function');
    const id = nextTimer++;
    timers[count++] = { id, at: now() + delayOf(ms), fn, args };
    return id;
  };
  globalThis.clearTimeout = (id) => {
    for (let i = 0; i < count; i++) {
      if (timers[i].id === id) { removeAt(i); return; }
    }
  };

  return {
    finish: (promise) => {
      const outcome = { status: 'pending', value: '' };
      promise.then(
        (v) => { outcome.value = v === undefined ? '' : show(v); outcome.status = 'ok'; },
        (e) => { outcome.value = String(e); outcome.status = 'err'; });
      return outcome;
    },
    nextDelay: () => { const i = earliestIndex(); if (i < 0) return -1; const wait = timers[i].at - now(); return wait > 0 ? wait : 0; },
    fire: () => { const i = earliestIndex(); const timer = timers[i]; removeAt(i); apply(timer.fn, undefined, timer.args); },
  };
})()

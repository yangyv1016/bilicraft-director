/**
 * Real network smoke test for an isolated, offline-mode Paper 1.21.11 server.
 * This does not render Minecraft and does not verify Fabric input or camera visuals.
 * Install test-only dependencies, outside tracked source:
 *   npm --prefix .tooling/test-client install minecraft-protocol@1.68.0 minecraft-data
 * Run with Node 22+; the default target is strictly loopback port 25590.
 * The console operator applies the printed commands, then enters "continue" here.
 */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { randomBytes } from 'node:crypto';
import { createInterface } from 'node:readline/promises';
import { setTimeout as delay } from 'node:timers/promises';
import { readFile, writeFile } from 'node:fs/promises';

const require = createRequire(new URL('../.tooling/test-client/package.json', import.meta.url));
const minecraft = require('minecraft-protocol');
const ChatMessage = require('prismarine-chat')('1.21.11');
const CHANNEL = 'bilicraftdirector:director';
const MAGIC = 0x42434452;
const VERSION = 5;
const MAX_PACKET_BYTES = 30_000;
const port = Number(process.env.SMOKE_PORT ?? 25590);
assert(Number.isInteger(port) && port > 1023 && port <= 65535, 'Invalid SMOKE_PORT');
const suffix = randomBytes(2).toString('hex');
const group = `director-smoke-${suffix}`;
const results = [];
const bots = [];
const terminal = createInterface({ input: process.stdin, output: process.stdout });
let closing = false;

function pass(name, detail = '') {
  results.push({ name, passed: true, detail });
  console.log(`PASS ${name}${detail ? `: ${detail}` : ''}`);
}

function encode(type, value) {
  const header = Buffer.alloc(7);
  header.writeUInt32BE(MAGIC);
  header.writeUInt16BE(VERSION, 4);
  header[6] = type;
  if (type === 0) {
    const version = Buffer.alloc(4);
    version.writeInt32BE(value);
    return Buffer.concat([header, version]);
  }
  if (type === 4 || type === 9) {
    const targetId = typeof value === 'string' ? value : value.targetId;
    const seconds = typeof value === 'string' ? 0 : value.requestedDurationSeconds;
    assert.match(targetId, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
    assert(Number.isInteger(seconds) && seconds >= -2147483648 && seconds <= 2147483647, 'Duration must be int32');
    if (type === 4) assert(seconds === 0 || (seconds >= 180 && seconds <= 480), 'Invalid initial duration');
    const duration = Buffer.alloc(4);
    duration.writeInt32BE(seconds);
    return Buffer.concat([header, Buffer.from(targetId.replaceAll('-', ''), 'hex'), duration]);
  }
  if (type === 11) {
    assert(Number.isSafeInteger(value) && value > 0, 'Map request ID must be positive');
    const requestId = Buffer.alloc(8);
    requestId.writeBigInt64BE(BigInt(value));
    return Buffer.concat([header, requestId]);
  }
  assert([2, 6, 7].includes(type), 'Unsupported test request');
  return header;
}

// Independent implementation of protocol/README.md, not Minecraft packet codecs.
function decode(buffer) {
  assert(buffer.length <= MAX_PACKET_BYTES, 'Oversized plugin response');
  let offset = 0;
  function read(size, method) {
    assert(offset + size <= buffer.length, 'Truncated plugin response');
    const value = buffer[method](offset);
    offset += size;
    return value;
  }
  function boolean() {
    const value = read(1, 'readUInt8');
    assert(value === 0 || value === 1, 'Invalid boolean');
    return value === 1;
  }
  function text(maxBytes) {
    const length = read(2, 'readUInt16BE');
    assert(length <= maxBytes && offset + length <= buffer.length, 'Invalid string length');
    const value = new TextDecoder('utf-8', { fatal: true }).decode(buffer.subarray(offset, offset + length));
    offset += length;
    return value;
  }
  function uuid() {
    assert(offset + 16 <= buffer.length, 'Truncated UUID');
    const hex = buffer.subarray(offset, offset + 16).toString('hex');
    offset += 16;
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  function timestamp() {
    const value = Number(read(8, 'readBigInt64BE'));
    assert(Number.isSafeInteger(value), 'Timestamp exceeds JS safe integer range');
    return value;
  }
  function regions() {
    const count = read(2, 'readUInt16BE');
    assert(count <= 64, 'Too many exclusion regions');
    const exclusions = [];
    for (let index = 0; index < count; index++) {
      const region = { minX: read(8, 'readDoubleBE'), minZ: read(8, 'readDoubleBE'), maxX: read(8, 'readDoubleBE'), maxZ: read(8, 'readDoubleBE') };
      assert(Object.values(region).every(Number.isFinite) && region.minX <= region.maxX && region.minZ <= region.maxZ);
      exclusions.push(region);
    }
    return exclusions;
  }
  assert.equal(read(4, 'readUInt32BE'), MAGIC);
  assert.equal(read(2, 'readUInt16BE'), VERSION);
  const type = read(1, 'readUInt8');
  let message;
  if (type === 1) {
    message = { type, version: read(4, 'readInt32BE'), allowed: boolean(), worldKey: text(256), detail: text(1024) };
  } else if (type === 3) {
    const count = read(2, 'readUInt16BE');
    assert(count <= 128, 'Too many players');
    const players = [];
    const ids = new Set();
    for (let index = 0; index < count; index++) {
      const player = {
        id: uuid(), name: text(64), world: text(256),
        x: read(8, 'readDoubleBE'), y: read(8, 'readDoubleBE'), z: read(8, 'readDoubleBE'),
        yaw: read(4, 'readFloatBE'), pitch: read(4, 'readFloatBE'), lastActivityEpochMillis: timestamp()
      };
      assert([player.x, player.y, player.z, player.yaw, player.pitch].every(Number.isFinite));
      assert(!ids.has(player.id), 'Duplicate player');
      ids.add(player.id);
      players.push(player);
    }
    const sceneCount = read(2, 'readUInt16BE');
    assert(sceneCount <= 64, 'Too many scenes');
    const scenes = [];
    const sceneKeys = new Set();
    for (let index = 0; index < sceneCount; index++) {
      const id = text(64);
      const name = text(256);
      const kindId = read(1, 'readUInt8');
      assert(kindId <= 1, 'Unknown scene kind');
      const scene = {
        id, name, kind: ['VIEWPOINT', 'AREA'][kindId], world: text(256),
        x: read(8, 'readDoubleBE'), y: read(8, 'readDoubleBE'), z: read(8, 'readDoubleBE'),
        radius: read(8, 'readDoubleBE')
      };
      const key = JSON.stringify([scene.world, scene.id]);
      assert(!sceneKeys.has(key), 'Duplicate scene ID in the same world');
      sceneKeys.add(key);
      assert([scene.x, scene.y, scene.z, scene.radius].every(Number.isFinite));
      assert(scene.radius >= 6 && scene.radius <= 48, 'Invalid scene radius');
      scenes.push(scene);
    }
    message = { type, players, scenes };
  } else if (type === 5) {
    const state = read(1, 'readUInt8');
    assert(state <= 2, 'Unknown observation state');
    const targetId = boolean() ? uuid() : null;
    assert(targetId !== null || state === 2, 'Active observation missing target');
    const deadline = timestamp();
    const durationSeconds = read(4, 'readInt32BE');
    assert(state === 2 ? durationSeconds === 0 : durationSeconds >= 180 && durationSeconds <= 480,
      'Invalid confirmed observation duration');
    message = { type, state, targetId, deadline, durationSeconds, reason: text(1024) };
  } else if (type === 8) {
    message = { type, reason: text(1024) };
  } else if (type === 10) {
    const targetId = uuid();
    const accepted = boolean();
    const state = read(1, 'readUInt8');
    assert(state <= 2, 'Unknown duration reply state');
    const durationSeconds = read(4, 'readInt32BE');
    const deadline = timestamp();
    assert(!accepted && state === 2 ? durationSeconds === 0 && deadline === 0
      : durationSeconds >= 180 && durationSeconds <= 480, 'Invalid confirmed duration reply');
    message = { type, targetId, accepted, state, durationSeconds, deadline, reason: text(1024) };
  } else if (type === 12) {
    const requestId = timestamp();
    assert(requestId > 0, 'Invalid map request ID');
    const worldKey = text(256), configured = boolean(), baseUrl = text(1024), dynmapWorld = text(256);
    const exclusions = regions();
    if (configured) {
      const url = new URL(baseUrl);
      assert(['http:', 'https:'].includes(url.protocol) && !url.username && !url.password && !url.search && !url.hash);
      assert(dynmapWorld.trim().length > 0);
    } else assert(!baseUrl && !dynmapWorld && exclusions.length === 0, 'Unconfigured source carried data');
    message = { type, requestId, worldKey, configured, baseUrl, dynmapWorld, exclusions, detail: text(512) };
  } else if (type === 13) {
    const worldKey = text(256), worldId = uuid(), automaticPatrol = boolean();
    assert(worldKey.trim().length > 0 && !/[\u0000-\u001f\u007f-\u009f]/.test(worldKey), 'Invalid world policy identity');
    message = { type, worldKey, worldId, automaticPatrol, exclusions: regions() };
  } else {
    throw new Error(`Unexpected server message type ${type}`);
  }
  assert.equal(offset, buffer.length, 'Trailing plugin response data');
  return message;
}

function clickCommands(component, commands = []) {
  if (component === null || typeof component !== 'object') return commands;
  const click = component.click_event;
  if (click?.action === 'run_command' && typeof click.command === 'string') commands.push(click.command);
  for (const value of Object.values(component)) {
    if (value !== click) {
      if (Array.isArray(value)) value.forEach(child => clickCommands(child, commands));
      else if (value !== null && typeof value === 'object') clickCommands(value, commands);
    }
  }
  return commands;
}

class Bot {
  constructor(role, name = `${role}${suffix}`) {
    this.name = name;
    this.messages = [];
    this.chats = [];
    this.failure = null;
    this.active = role !== 'CameraBot';
    this.ready = false;
    this.position = { x: 0, y: 0, z: 0 };
    this.client = minecraft.createClient({
      host: '127.0.0.1', port, username: this.name,
      version: '1.21.11', auth: 'offline', disableChatSigning: true,
      clientSettings: { viewDistance: 2, locale: 'zh_cn' }
    });
    this.client.on('error', error => { this.failure = error; });
    this.client.on('end', reason => {
      if (!closing) this.failure = new Error(`${this.name} disconnected: ${reason}`);
    });
    this.client.on('kick_disconnect', packet => {
      if (!closing) this.failure = new Error(`${this.name} kicked: ${JSON.stringify(packet)}`);
    });
    this.client.on('position', packet => {
      for (const axis of ['x', 'y', 'z']) {
        this.position[axis] = packet.flags[axis] ? this.position[axis] + packet[axis] : packet[axis];
      }
      this.client.write('teleport_confirm', { teleportId: packet.teleportId });
      this.client.write('position', { ...this.position, flags: { onGround: false, hasHorizontalCollision: false } });
      if (!this.ready) {
        this.client.write('custom_payload', { channel: 'minecraft:register', data: Buffer.from(`${CHANNEL}\0`) });
        this.client.write('player_loaded', {});
        this.ready = true;
      }
    });
    this.client.on('system_chat', packet => {
      try {
        const chat = ChatMessage.fromNotch(packet.content);
        this.chats.push({ at: Date.now(), text: chat.toString(), commands: clickCommands(chat.json) });
      } catch (error) {
        this.failure = error;
      }
    });
    this.client.on('custom_payload', packet => {
      if (packet.channel !== CHANNEL) return;
      try {
        this.messages.push({ at: Date.now(), ...decode(packet.data) });
      } catch (error) {
        this.failure = error;
      }
    });
    this.activityTimer = setInterval(() => {
      if (this.ready && this.active) this.client.write('arm_animation', { hand: 0 });
    }, 1000);
    bots.push(this);
  }

  send(type, value) {
    this.client.write('custom_payload', { channel: CHANNEL, data: encode(type, value) });
  }

  command(command) {
    this.client.chat(`/${command}`);
  }

  async wait(predicate, description, timeout = 8000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      for (const bot of bots) if (bot.failure) throw bot.failure;
      const result = predicate();
      if (result) return result;
      await delay(40);
    }
    throw new Error(`Timeout: ${this.name} ${description}`);
  }

  async response(type, value, predicate, description) {
    const since = this.messages.length;
    this.send(type, value);
    return this.wait(() => this.messages.slice(since).find(predicate), description);
  }
}

async function consoleStep(name, commands) {
  console.log(`\nCONSOLE_STEP ${name}`);
  for (const command of commands) console.log(command);
  assert.equal((await terminal.question('Enter continue after the console commands complete:\n')).trim(), 'continue');
  await delay(500);
}

async function observe(camera, target) {
  const messagesSince = camera.messages.length;
  const chatsSince = new Map(bots.map(bot => [bot, bot.chats.length]));
  const sentAt = Date.now();
  camera.send(4, target.client.uuid);
  const notice = await camera.wait(() => camera.messages.slice(messagesSince)
    .find(message => message.type === 5 && message.state === 0 && message.targetId === target.client.uuid), 'NOTICE');
  assert(notice.deadline - sentAt >= 14_500 && notice.deadline - sentAt <= 16_000, 'Notice must reserve 15 seconds');
  const chat = await target.wait(() => target.chats.slice(chatsSince.get(target))
    .find(message => message.text.includes('将在 15 秒后开始')), 'targeted clickable notice');
  const reject = chat.commands.find(command => command.startsWith('/director reject '));
  const block = chat.commands.find(command => command.startsWith('/director block '));
  assert(reject && block, `Missing clickable actions in notice: ${JSON.stringify(chat)}`);
  await delay(350);
  for (const bot of bots) {
    if (bot !== target) assert(!bot.chats.slice(chatsSince.get(bot))
      .some(message => message.text.includes('将在 15 秒后开始')), `Private notice leaked to ${bot.name}`);
  }
  pass('private-clickable-15s-notice', target.name);
  return { messagesSince, sentAt, notice, reject, block };
}

async function waitEnded(camera, since, target) {
  return camera.wait(() => camera.messages.slice(since)
    .find(message => message.type === 5 && message.state === 2 && message.targetId === target.client.uuid), 'ENDED');
}

async function runSmoke() {
  console.log(`Smoke run ${suffix}: isolated loopback 127.0.0.1:${port}, Minecraft 1.21.11`);
  const camera = new Bot('CameraBot');
  const target = new Bot('TargetBot');
  const other = new Bot('OtherBot');
  const block = new Bot('BlockBot');
  const idle = new Bot('IdleBot');
  await Promise.all(bots.map(bot => bot.wait(() => bot.ready, 'initial position and PLAY state', 60_000)));
  pass('five-real-protocol-connections', bots.map(bot => bot.name).join(', '));

  await consoleStep('prepare-bots-without-data-permission', [
    'fill -2 99 -2 2 99 2 minecraft:stone',
    `gamemode spectator ${camera.name}`,
    ...bots.filter(bot => bot !== camera).map(bot => `gamemode adventure ${bot.name}`),
    ...bots.map(bot => `tp ${bot.name} 0 100 0`)
  ]);
  const denied = await camera.response(0, VERSION, message => message.type === 1, 'unauthorized handshake refusal');
  assert.equal(denied.allowed, false);
  pass('permission-denied-before-grant', denied.detail);
  const unauthorized = await camera.response(2, undefined, message => message.type === 1, 'unauthorized snapshot refusal');
  assert.equal(unauthorized.allowed, false);
  pass('snapshot-denied-without-permission');

  await consoleStep('grant-inherited-luckperms-data-permission', [
    `lp creategroup ${group}`,
    `lp group ${group} permission set bilicraftdirector.data.read true`,
    `lp user ${camera.name} parent add ${group}`
  ]);
  const allowed = await camera.response(0, VERSION, message => message.type === 1, 'authorized handshake');
  assert.equal(allowed.allowed, true);
  pass('luckperms-group-inherited-permission', allowed.detail);
  const unknownVersion = await camera.response(0, 99, message => message.type === 1, 'incompatible handshake');
  assert.equal(unknownVersion.allowed, false);
  pass('unknown-application-version-rejected');
  assert.equal((await camera.response(0, VERSION, message => message.type === 1, 'repeat authorized handshake')).allowed, true);
  const heartbeat = setInterval(() => camera.send(7), 2000);
  camera.heartbeatTimer = heartbeat;
  await delay(1100);
  const snapshot = await camera.response(2, undefined, message => message.type === 3, 'authorized snapshot');
  const names = new Set(snapshot.players.map(player => player.name));
  for (const bot of [target, other, block, idle]) assert(names.has(bot.name), `Missing active test target ${bot.name}`);
  assert(!names.has(camera.name), 'Camera must not appear as its own target');
  pass('authorized-structured-snapshot', `${snapshot.players.length} players, ${snapshot.scenes.length} scenes; camera excluded`);

  const first = await observe(camera, target);
  other.command(first.reject.slice(1));
  await delay(600);
  assert(!camera.messages.slice(first.messagesSince).some(message => message.type === 5 && message.state === 2));
  pass('other-player-cannot-use-target-token');
  target.command(first.reject.slice(1));
  const rejected = await waitEnded(camera, first.messagesSince, target);
  assert.match(rejected.reason, /拒绝/);
  assert(!camera.messages.slice(first.messagesSince).some(message => message.type === 5 && message.state === 1));
  pass('reject-during-notice-cancels-camera', rejected.reason);
  const rejectionStatusSince = target.chats.length;
  target.command('director status');
  await target.wait(() => target.chats.slice(rejectionStatusSince)
    .find(chat => /冷却剩余约 1440 分钟/.test(chat.text)), '24-hour rejection cooldown status');
  pass('rejection-cooldown-is-24-hours');
  const cooldown = await camera.response(4, target.client.uuid, message => message.type === 8, '24h rejection prevents re-observe');
  pass('rejected-target-not-immediately-eligible', cooldown.reason);

  const second = await observe(camera, other);
  other.command(first.reject.slice(1));
  await delay(600);
  assert(!camera.messages.slice(second.messagesSince).some(message => message.type === 5 && message.state === 2));
  pass('old-token-cannot-cancel-new-session');
  const following = await camera.wait(() => camera.messages.slice(second.messagesSince)
    .find(message => message.type === 5 && message.state === 1), 'FOLLOWING after notice', 18_000);
  assert(following.at >= second.notice.deadline - 100, 'Following started before 15-second deadline');
  pass('automatic-following-after-15s', `${following.at - second.sentAt}ms`);
  other.command(`tell ${camera.name} 停止跟随`);
  const privateStop = await waitEnded(camera, second.messagesSince, other);
  assert.match(privateStop.reason, /拒绝/);
  pass('private-tell-stops-following', privateStop.reason);

  const third = await observe(camera, block);
  block.command(third.block.slice(1));
  const blocked = await waitEnded(camera, third.messagesSince, block);
  assert.match(blocked.reason, /黑名单/);
  pass('clickable-blacklist-cancels-camera', blocked.reason);
  const statusSince = block.chats.length;
  block.command('director status');
  await block.wait(() => block.chats.slice(statusSince).find(chat => /长期黑名单：已开启/.test(chat.text)), 'blacklist status');
  pass('blacklist-status-enabled');
  await camera.response(4, block.client.uuid, message => message.type === 8, 'blacklisted target refusal');
  pass('blacklisted-target-not-eligible');

  const fourth = await observe(camera, idle);
  await camera.wait(() => camera.messages.slice(fourth.messagesSince)
    .find(message => message.type === 5 && message.state === 1), 'idle test starts following', 18_000);
  idle.active = false;
  const stoppedActivityAt = Date.now();
  const inactivity = await camera.wait(() => camera.messages.slice(fourth.messagesSince)
    .find(message => message.type === 5 && message.state === 2), '10s inactivity termination', 12_000);
  assert.match(inactivity.reason, /10 秒无动作/);
  assert(inactivity.at - stoppedActivityAt >= 8500, 'Idle timeout happened unexpectedly early');
  pass('ten-second-no-activity-termination', `${inactivity.at - stoppedActivityAt}ms after activity stopped`);
  const idleStatusSince = idle.chats.length;
  idle.command('director status');
  await idle.wait(() => idle.chats.slice(idleStatusSince)
    .find(chat => /冷却剩余约 30 分钟/.test(chat.text)), '30-minute normal cooldown status');
  pass('normal-cooldown-is-30-minutes');

  await consoleStep('revoke-inherited-data-permission', [
    `lp group ${group} permission unset bilicraftdirector.data.read`
  ]);
  const revoked = await camera.response(0, VERSION, message => message.type === 1 && !message.allowed, 'revoked data permission');
  assert.equal(revoked.allowed, false);
  pass('luckperms-revocation-applies');
  await writeFile(new URL('../.tooling/paper-test/integration-smoke-result.json', import.meta.url),
    JSON.stringify({ run: suffix, minecraft: '1.21.11', protocolVersion: VERSION, passed: true,
      accounts: bots.map(bot => ({ name: bot.name, id: bot.client.uuid })), checks: results }, null, 2));
  console.log(`SMOKE_SUCCESS ${results.length} checks. No Fabric rendering or physical input was tested.`);
}

async function verifyPersistence() {
  const previous = JSON.parse(await readFile(
    new URL('../.tooling/paper-test/integration-smoke-result.json', import.meta.url), 'utf8'));
  assert.equal(previous.passed, true, 'A successful smoke report is required');
  assert.equal(previous.protocolVersion, VERSION, 'A smoke report for the current protocol version is required');
  console.log(`Persistence verification for run ${previous.run}; restart the test server before this command.`);
  const actors = ['TargetBot', 'BlockBot', 'IdleBot'].map(role => {
    const account = previous.accounts.find(value => value.name.startsWith(role));
    assert(account && /^[a-z0-9_]{1,16}$/i.test(account.name), `Missing valid saved ${role}`);
    return { role, expectedId: account.id, bot: new Bot(role, account.name) };
  });
  await Promise.all(actors.map(({ bot }) => bot.wait(() => bot.ready, 'reconnected with original identity', 60_000)));
  for (const { role, expectedId, bot } of actors) {
    assert.equal(bot.client.uuid, expectedId, 'Offline identity must match the original test');
    const since = bot.chats.length;
    bot.command('director status');
    const status = await bot.wait(() => bot.chats.slice(since)
      .find(chat => chat.text.includes('长期黑名单：')), 'persisted privacy status');
    assert.equal(status.text.includes('长期黑名单：已开启'), role === 'BlockBot');
    const minutes = Number(status.text.match(/冷却剩余约 (\d+) 分钟/)?.[1]);
    assert(Number.isFinite(minutes) && minutes > 0, 'Run persistence verification before the 30-minute cooldown expires');
    if (role === 'IdleBot') assert(minutes <= 30, 'Normal cooldown must remain bounded by 30 minutes');
    else assert(minutes >= 1400 && minutes <= 1440, 'Rejection cooldown should survive a prompt test-server restart');
    pass('persisted-privacy-reloaded', `${bot.name}: ${status.text}`);
  }
  await writeFile(new URL('../.tooling/paper-test/integration-persistence-result.json', import.meta.url),
    JSON.stringify({ run: previous.run, protocolVersion: VERSION, passed: true, checks: results }, null, 2));
  console.log(`PERSISTENCE_SUCCESS ${results.length} original identities checked.`);
}

try {
  if (process.argv.includes('--verify-persistence')) await verifyPersistence();
  else await runSmoke();
} catch (error) {
  console.error('SMOKE_FAILED', error.stack ?? error);
  process.exitCode = 1;
} finally {
  closing = true;
  terminal.close();
  for (const bot of bots) {
    clearInterval(bot.activityTimer);
    clearInterval(bot.heartbeatTimer);
    bot.client.end('Smoke test finished');
  }
}

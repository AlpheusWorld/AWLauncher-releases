const PREFIX = '/api/friends';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const DOMAIN = /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+e4mc\.link$/;
const LOADERS = new Set(['VANILLA', 'FABRIC', 'QUILT', 'FORGE', 'NEOFORGE']);
const json = (value, status = 200) => Response.json(value, {status, headers:{'Cache-Control':'no-store', 'X-Content-Type-Options':'nosniff'}});
const digest = async value => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))), b => b.toString(16).padStart(2,'0')).join('');
const fail = (message, status = 400) => { throw Object.assign(new Error(message), {status}); };
const text = (value, max) => typeof value === 'string' && value.length <= max && !/[\u0000-\u001f\u007f]/.test(value);
async function bodyOf(request) {
  if (!request.headers.get('content-type')?.startsWith('application/json')) fail('Ожидается JSON', 415);
  const reader = request.body?.getReader();
  if (!reader) fail('Пустой запрос');
  const chunks = []; let size = 0;
  while (true) { const part = await reader.read(); if (part.done) break; size += part.value.length;
    if (size > 16384) { await reader.cancel(); fail('Слишком большой запрос', 413); } chunks.push(part.value); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk,offset); offset += chunk.length; }
  try { const value = JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));
    if (!value || typeof value !== 'object' || Array.isArray(value)) fail('Некорректный запрос'); return value;
  } catch { fail('Некорректный JSON'); }
}
function worldOf(value) {
  if (value == null) return null;
  if (!UUID.test(value.id) || !text(value.address,253) || !DOMAIN.test(value.address) ||
      !text(value.title,120) || !text(value.version,64) || !/^[A-Za-z0-9._+-]+$/.test(value.version) ||
      !LOADERS.has(value.loader) || !/^[a-f0-9]{64}$/.test(value.fingerprint)) fail('Некорректное описание мира');
  return {id:value.id,address:value.address,title:value.title,version:value.version,loader:value.loader,fingerprint:value.fingerprint};
}
async function limit(db, key, now, count, seconds) {
  const bucket = Math.floor(now / seconds);
  const row = await db.prepare('INSERT INTO aw_friends_rate (key,bucket,hits) VALUES (?,?,1) ON CONFLICT(key,bucket) DO UPDATE SET hits=hits+1 RETURNING hits').bind(key,bucket).first();
  if (row.hits > count) fail('Слишком много запросов, попробуй немного позже',429);
}
async function relationship(db, self, other) {
  return db.prepare('SELECT * FROM aw_friendships WHERE (sender=? AND recipient=?) OR (sender=? AND recipient=?)').bind(self,other,other,self).first();
}
export async function friendsFetch(request, env) {
  const url = new URL(request.url);
  if (!url.pathname.startsWith(PREFIX + '/')) return null;
  if (url.pathname === PREFIX + '/health' && request.method === 'GET') {
    if (!env.DB) return json({status:'unavailable'},503);
    try { await env.DB.prepare('SELECT id FROM aw_friends_users LIMIT 1').first(); return json({status:'ok',protocol:1}); }
    catch { return json({status:'unavailable'},503); }
  }
  if (request.method !== 'POST') return json({error:'Метод не поддерживается'},405);
  try {
    if (!env.DB) fail('Сервис друзей временно недоступен',503);
    const db = env.DB, now = Math.floor(Date.now()/1000), input = await bodyOf(request);
    const action = url.pathname.slice(PREFIX.length + 1);
    if (action === 'register') {
      if (!UUID.test(input.id) || !/^[A-Za-z0-9_-]{43}$/.test(input.token) || !text(input.name,48) || !input.name.trim()) fail('Некорректный профиль');
      const hash = await digest(input.token);
      const existing = await db.prepare('SELECT token_hash FROM aw_friends_users WHERE id=?').bind(input.id).first();
      if (existing) { if (existing.token_hash !== hash) fail('Нет доступа к профилю',401); return json({ok:true}); }
      const ip = await digest(request.headers.get('CF-Connecting-IP') || 'unknown');
      await limit(db,'register:'+ip,now,12,3600);
      await db.prepare('INSERT INTO aw_friends_users (id,token_hash,name,last_seen) VALUES (?,?,?,?)').bind(input.id,hash,input.name.trim(),now).run();
      return json({ok:true});
    }
    const auth = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.headers.get('Authorization') || '');
    if (!auth) fail('Нужен вход в сервис друзей',401);
    const self = await db.prepare('SELECT id,name FROM aw_friends_users WHERE token_hash=?').bind(await digest(auth[1])).first();
    if (!self) fail('Профиль друзей не найден',401);
    await limit(db,'user:'+self.id,now,90,60);
    if (action === 'sync') {
      if (!text(input.name,48) || !input.name.trim()) fail('Некорректное имя');
      const world = input.online === false ? null : worldOf(input.world);
      await db.batch([
        db.prepare('UPDATE aw_friends_users SET name=?,last_seen=? WHERE id=?').bind(input.name.trim(),input.online === false ? 0 : now,self.id),
        db.prepare('INSERT INTO aw_friends_presence (user_id,world,session_id) VALUES (?,?,?) ON CONFLICT(user_id) DO UPDATE SET world=excluded.world,session_id=excluded.session_id').bind(self.id,world ? JSON.stringify(world) : null,world?.id || null)
      ]);
      const rows = await db.prepare(`SELECT u.id,u.name,u.last_seen,f.sender,f.status,p.world,p.session_id,
        i.session_id AS invited_session,i.expires_at
        FROM aw_friendships f JOIN aw_friends_users u ON u.id=CASE WHEN f.sender=? THEN f.recipient ELSE f.sender END
        LEFT JOIN aw_friends_presence p ON p.user_id=u.id
        LEFT JOIN aw_friends_invites i ON i.host=u.id AND i.guest=?
        WHERE f.sender=? OR f.recipient=? LIMIT 192`).bind(self.id,self.id,self.id,self.id).all();
      const friends = [], requests = [], outgoing = [];
      for (const row of rows.results || []) {
        const person = {id:row.id,name:row.name,online:row.last_seen > now-45};
        if (row.status === 'pending') { (row.sender === self.id ? outgoing : requests).push(person); continue; }
        const state = person.online && row.world ? JSON.parse(row.world) : null;
        friends.push({...person,hosting:!!state,version:state?.version || '',loader:state?.loader || '',
          invite:state && row.invited_session === row.session_id && row.expires_at > now ? state : null});
      }
      if (Math.random() < .01) await db.batch([
        db.prepare('DELETE FROM aw_friends_invites WHERE expires_at<?').bind(now),
        db.prepare('DELETE FROM aw_friends_rate WHERE (key LIKE ? AND bucket<?) OR (key NOT LIKE ? AND bucket<?)').bind('register:%',Math.floor(now/3600)-24,'register:%',Math.floor(now/60)-1440)
      ]);
      return json({friends,requests,outgoing});
    }
    const other = input.id;
    if (!UUID.test(other) || other === self.id) fail('Проверь код друга');
    const relation = await relationship(db,self.id,other);
    if (action === 'request') {
      if (relation) return json({ok:true});
      const target = await db.prepare('SELECT id FROM aw_friends_users WHERE id=?').bind(other).first();
      if (!target) fail('Такой код друга не найден',404);
      const count = await db.prepare('SELECT COUNT(*) AS total FROM aw_friendships WHERE sender=? OR recipient=? OR sender=? OR recipient=?').bind(self.id,self.id,other,other).first();
      if (count.total >= 128) fail('Достигнут предел списка друзей');
      await limit(db,'request:'+self.id,now,10,60);
      // A canonical pair prevents opposite requests racing into duplicate friendships.
      await db.prepare('INSERT OR IGNORE INTO aw_friendships (pair,sender,recipient,status) VALUES (?,?,?,?)').bind([self.id,other].sort().join(':'),self.id,other,'pending').run();
    } else if (action === 'accept') {
      if (!relation || relation.recipient !== self.id || relation.status !== 'pending') fail('Заявка больше не доступна',404);
      await db.prepare('UPDATE aw_friendships SET status=? WHERE pair=?').bind('accepted',relation.pair).run();
    } else if (action === 'remove' || action === 'reject') {
      if (action === 'reject' && relation?.recipient !== self.id) fail('Заявка не найдена',404);
      await db.batch([
        db.prepare('DELETE FROM aw_friendships WHERE pair=?').bind([self.id,other].sort().join(':')),
        db.prepare('DELETE FROM aw_friends_invites WHERE (host=? AND guest=?) OR (host=? AND guest=?)').bind(self.id,other,other,self.id)
      ]);
    } else if (action === 'invite') {
      if (relation?.status !== 'accepted') fail('Сначала добавь игрока в друзья');
      const presence = await db.prepare('SELECT p.session_id,u.last_seen FROM aw_friends_presence p JOIN aw_friends_users u ON u.id=p.user_id WHERE p.user_id=?').bind(self.id).first();
      if (!presence?.session_id || presence.last_seen <= now-45) fail('Сначала открой свой мир для сети');
      await db.prepare('INSERT INTO aw_friends_invites (host,guest,session_id,expires_at) VALUES (?,?,?,?) ON CONFLICT(host,guest) DO UPDATE SET session_id=excluded.session_id,expires_at=excluded.expires_at').bind(self.id,other,presence.session_id,now+900).run();
    } else if (action === 'decline') {
      await db.prepare('DELETE FROM aw_friends_invites WHERE host=? AND guest=?').bind(other,self.id).run();
    } else fail('Неизвестное действие',404);
    return json({ok:true});
  } catch (error) { return json({error:error.status ? error.message : 'Сервис друзей временно недоступен'},error.status || 503); }
}

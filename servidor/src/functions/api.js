// Servidor de Cuaderna (Azure Functions, Node 20+).
// - Registra a cada persona que entra y deja que el administrador la autorice o la bloquee.
// - Hace de puente con Azure OpenAI y Azure Speech: las claves viven aquí, nunca en el móvil.
// - Recoge las sugerencias que se envían desde la app.
// - Guarda el estado de las notas pasadas (solo un código y horas, sin texto ni nombres) y lo borra a los 90 días.
// - Cuenta, de forma anónima (solo totales por día), cómo se difunde Cuaderna: notas pasadas, abiertas, instalaciones…
// Ajustes de la Function App: ADMIN_EMAIL, AOAI_ENDPOINT, AOAI_CLAVE, AOAI_DESPLIEGUE,
// SPEECH_CLAVE, SPEECH_REGION, ORIGENES (opcional), MAX_IA_DIA (opcional), MAX_VOZ_MIN_DIA (opcional),
// GOOGLE_CLIENT_ID y GOOGLE_CLIENT_SECRET (para entrar con Google: el secreto solo vive aquí).
const { app } = require("@azure/functions");
const { TableClient } = require("@azure/data-tables");
const crypto = require("crypto");

const CLIENT_ID = process.env.CLIENT_ID || "3dc57230-4cdb-4e60-bb83-2def7cd5441f";
const EMISOR = "https://login.microsoftonline.com/9188040d-6c67-4c5b-b112-36a304b66dad/v2.0";   // cuentas personales de Microsoft
// uno o varios correos de administrador, separados por comas (p. ej. tu cuenta de Microsoft y tu Gmail)
const ADMINS = (process.env.ADMIN_EMAIL || "").split(",").map(s => s.trim().toLowerCase()).filter(Boolean);
const ADMIN = ADMINS[0] || "";
const esAdmin = email => !!email && ADMINS.includes(String(email).toLowerCase());
// la dirección nueva y la antigua (que redirige) siempre valen, aunque en Azure haya otra lista
const lista = (env, base) => [...new Set([...(env || "").split(","), ...base].map(s => s.trim()).filter(Boolean))];
const ORIGENES = lista(process.env.ORIGENES, ["https://cuaderna.astillerosoftware.es", "https://astillerosoftware.es", "https://www.astillerosoftware.es", "https://jamormar.github.io"]);
const MAX_IA = +process.env.MAX_IA_DIA || 300;
const G_CLIENT = (process.env.GOOGLE_CLIENT_ID || "520507807981-ulvru7sh3ippbc510m1eo1ji532ptb8c.apps.googleusercontent.com").trim();
// el secreto puede venir solo o dentro del JSON que descarga Google ({"web":{"client_secret":…}}): se acepta de las dos formas
const G_SECRETO = (() => { let v = (process.env.GOOGLE_CLIENT_SECRET || "").trim().replace(/^["']|["']$/g, "");
  if (v.includes("client_secret")){ const m = v.match(/"client_secret"\s*:\s*"([^"]+)"/); if (m) return m[1].trim(); }
  if (v.startsWith("{")){ try { const j = JSON.parse(v); v = (j.web || j.installed || j).client_secret || ""; } catch(e){ const m = v.match(/"client_secret"\s*:\s*"([^"]+)"/); v = m ? m[1] : ""; } }
  return v.trim(); })();
const G_REDIRECCIONES = lista(process.env.GOOGLE_REDIRECTS, ["https://cuaderna.astillerosoftware.es/", "https://cuaderna.astillerosoftware.es/google.html", "https://jamormar.github.io/chispa/", "https://jamormar.github.io/chispa/google.html"]);
const MAX_VOZ = +process.env.MAX_VOZ_MIN_DIA || 60;
const hayIA = () => !!(process.env.AOAI_ENDPOINT && process.env.AOAI_CLAVE);
const hayVoz = () => !!(process.env.SPEECH_CLAVE && process.env.SPEECH_REGION);

// ---------- tablas ----------
const tablas = {};
async function tabla(nombre){
  if (!tablas[nombre]){
    const t = TableClient.fromConnectionString(process.env.AzureWebJobsStorage, nombre);
    try { await t.createTable(); } catch(e){}
    tablas[nombre] = t;
  }
  return tablas[nombre];
}
async function leer(t, p, r){ try { return await t.getEntity(p, r); } catch(e){ if (e.statusCode === 404) return null; throw e; } }

// ---------- identidad: el id_token de Microsoft que ya tiene la app ----------
let jwks = null, jwksT = 0;
async function clavesMS(forzar){
  if (forzar || !jwks || Date.now() - jwksT > 6 * 3600e3){
    const r = await fetch("https://login.microsoftonline.com/consumers/discovery/v2.0/keys");
    jwks = (await r.json()).keys || []; jwksT = Date.now();
  }
  return jwks;
}
let jwksG = null, jwksGT = 0;
async function clavesGoogle(forzar){
  if (forzar || !jwksG || Date.now() - jwksGT > 6 * 3600e3){
    const r = await fetch("https://www.googleapis.com/oauth2/v3/certs");
    jwksG = (await r.json()).keys || []; jwksGT = Date.now();
  }
  return jwksG;
}
const b64 = s => Buffer.from(s.replace(/-/g, "+").replace(/_/g, "/"), "base64");
class NoAutorizado extends Error {}
async function quienEs(request){
  const h = request.headers.get("authorization") || "";
  const tok = h.startsWith("Bearer ") ? h.slice(7).trim() : "";
  const partes = tok.split(".");
  if (partes.length !== 3) throw new NoAutorizado("Falta la identificación");
  const [h64, p64, f64] = partes;
  const cab = JSON.parse(b64(h64).toString()), dat = JSON.parse(b64(p64).toString());
  if (cab.alg !== "RS256") throw new NoAutorizado("Firma no válida");
  const google = dat.iss === "https://accounts.google.com" || dat.iss === "accounts.google.com";
  const claves = google ? clavesGoogle : clavesMS;
  let k = (await claves()).find(x => x.kid === cab.kid);
  if (!k) k = (await claves(true)).find(x => x.kid === cab.kid);
  if (!k) throw new NoAutorizado("Firma desconocida");
  const ok = crypto.verify("RSA-SHA256", Buffer.from(h64 + "." + p64), crypto.createPublicKey({key: k, format: "jwk"}), b64(f64));
  if (!ok) throw new NoAutorizado("Firma no válida");
  if (google){
    if (!G_CLIENT || dat.aud !== G_CLIENT) throw new NoAutorizado("Identificación de otra aplicación");
    if (dat.exp * 1000 < Date.now() - 60e3) throw new NoAutorizado("Identificación caducada");
    if (dat.email && dat.email_verified === false) throw new NoAutorizado("Correo de Google sin verificar");
    return { id: "g-" + dat.sub, email: String(dat.email || "").toLowerCase(), nombre: dat.name || "", proveedor: "google" };
  }
  if (dat.aud !== CLIENT_ID || dat.iss !== EMISOR) throw new NoAutorizado("Identificación de otra aplicación");
  if (dat.exp * 1000 < Date.now() - 60e3) throw new NoAutorizado("Identificación caducada");
  return { id: dat.oid || dat.sub, email: String(dat.email || dat.preferred_username || "").toLowerCase(), nombre: dat.name || "" };
}

// ---------- utilidades HTTP ----------
function cors(request){
  const o = request.headers.get("origin") || "";
  const h = {"Access-Control-Allow-Headers": "Authorization, Content-Type", "Access-Control-Allow-Methods": "GET, POST, OPTIONS", "Access-Control-Max-Age": "86400", "Vary": "Origin"};
  if (ORIGENES.includes(o) || /^https?:\/\/localhost(:\d+)?$/.test(o)) h["Access-Control-Allow-Origin"] = o;
  return h;
}
const json = (request, cuerpo, status = 200, extra = {}) => ({status, headers: {...cors(request), "Content-Type": "application/json", ...extra}, body: JSON.stringify(cuerpo)});
const hoy = () => new Date().toISOString().slice(0, 10);

async function usuarioDe(yo){
  const t = await tabla("usuarios");
  let u = await leer(t, "u", yo.id);
  const ahora = new Date().toISOString();
  if (!u){
    u = {partitionKey: "u", rowKey: yo.id, email: yo.email, nombre: yo.nombre, estado: esAdmin(yo.email) ? "autorizado" : "pendiente", creado: ahora, ultimo: ahora, iaTotal: 0, vozSegTotal: 0};
    await t.createEntity(u);
  } else if (Date.now() - new Date(u.ultimo).getTime() > 10 * 60e3 || u.email !== yo.email || (yo.nombre && u.nombre !== yo.nombre)){
    u.ultimo = ahora; u.email = yo.email; if (yo.nombre) u.nombre = yo.nombre;
    if (esAdmin(yo.email)) u.estado = "autorizado";
    await t.updateEntity(u, "Merge");
  }
  return u;
}
async function contar(u, campo, cantidad){
  const t = await tabla("usuarios");
  const dia = hoy(); const c = {partitionKey: "u", rowKey: u.rowKey};
  if (u.dia !== dia){ u.dia = dia; u.iaHoy = 0; u.vozSegHoy = 0; }
  if (campo === "ia"){ u.iaHoy = (u.iaHoy || 0) + cantidad; u.iaTotal = (u.iaTotal || 0) + cantidad; Object.assign(c, {dia, iaHoy: u.iaHoy, iaTotal: u.iaTotal, vozSegHoy: u.vozSegHoy || 0}); }
  else { u.vozSegHoy = (u.vozSegHoy || 0) + cantidad; u.vozSegTotal = (u.vozSegTotal || 0) + cantidad; Object.assign(c, {dia, vozSegHoy: u.vozSegHoy, vozSegTotal: u.vozSegTotal, iaHoy: u.iaHoy || 0}); }
  try { await t.updateEntity(c, "Merge"); } catch(e){}
}

// ---------- rutas ----------
const rutas = {
  async "POST registro"(request, yo){
    const u = await usuarioDe(yo);
    return {estado: u.estado, admin: esAdmin(yo.email), ia: hayIA(), voz: hayVoz()};
  },

  async "POST ia"(request, yo){
    const u = await usuarioDe(yo);
    if (u.estado !== "autorizado") return [{error: "Tu acceso aún no está autorizado"}, 403];
    if (!hayIA()) return [{error: "La IA no está configurada en el servidor"}, 503];
    if (u.dia === hoy() && (u.iaHoy || 0) >= MAX_IA) return [{error: "Has llegado al límite de IA de hoy"}, 429];
    const e = await request.json();
    let base = process.env.AOAI_ENDPOINT.trim(); try { base = new URL(base).origin; } catch(err){}
    const url = `${base}/openai/deployments/${encodeURIComponent(process.env.AOAI_DESPLIEGUE || "gpt-4.1-mini")}/chat/completions?api-version=2024-10-21`;
    const cuerpo = {messages: e.messages, temperature: e.temperature ?? 0.2, max_tokens: Math.min(+e.max_tokens || 900, 4000), ...(e.response_format ? {response_format: e.response_format} : {})};
    const r = await fetch(url, {method: "POST", headers: {"Content-Type": "application/json", "api-key": process.env.AOAI_CLAVE.trim()}, body: JSON.stringify(cuerpo)});
    const texto = await r.text();
    if (r.ok) await contar(u, "ia", 1);
    const extra = {}; for (const h of ["retry-after", "retry-after-ms"]) if (r.headers.get(h)) extra[h] = r.headers.get(h);
    return {crudo: texto, status: r.status, extra};
  },

  async "POST voz"(request, yo){
    const u = await usuarioDe(yo);
    if (u.estado !== "autorizado") return [{error: "Tu acceso aún no está autorizado"}, 403];
    if (!hayVoz()) return [{error: "La voz no está configurada en el servidor"}, 503];
    if (u.dia === hoy() && (u.vozSegHoy || 0) >= MAX_VOZ * 60) return [{error: "Has llegado al límite de voz de hoy"}, 429];
    const audio = Buffer.from(await request.arrayBuffer());
    const url = `https://${process.env.SPEECH_REGION}.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=es-ES&format=simple`;
    const r = await fetch(url, {method: "POST", headers: {"Ocp-Apim-Subscription-Key": process.env.SPEECH_CLAVE, "Content-Type": "audio/wav; codecs=audio/pcm; samplerate=16000", "Accept": "application/json"}, body: audio});
    const texto = await r.text();
    if (r.ok) await contar(u, "voz", Math.round(Math.max(0, audio.length - 44) / 32000));
    return {crudo: texto, status: r.status};
  },

  // Guarda una página web: título, descripción, imagen, texto y (si hay IA) un resumen
  async "POST web"(request, yo){
    const u = await usuarioDe(yo);
    if (u.estado !== "autorizado") return [{error: "Tu acceso aún no está autorizado"}, 403];
    const {url} = await request.json();
    let dir; try { dir = new URL(String(url)); } catch(e){ return [{error: "Dirección no válida"}, 400]; }
    if (!/^https?:$/.test(dir.protocol) || /^(localhost|127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.)/.test(dir.hostname)) return [{error: "Dirección no permitida"}, 400];
    const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 9000);
    let html = "";
    try { const r = await fetch(dir.href, {signal: ctl.signal, redirect: "follow", headers: {"User-Agent": "Mozilla/5.0 (compatible; CuadernaBot/1.0)", "Accept-Language": "es-ES,es;q=0.9"}});
      if (!/html|text/.test(r.headers.get("content-type") || "")) return {titulo: "", descripcion: "", imagen: "", texto: "", resumen: ""};
      html = (await r.text()).slice(0, 1500000); } catch(e){ return [{error: "No se pudo abrir la página"}, 502]; } finally { clearTimeout(t); }
    const ent = s => s.replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&nbsp;/g, " ").replace(/&#(\d+);/g, (m, n) => String.fromCharCode(+n));
    const meta = k => ent((html.match(new RegExp(`<meta[^>]+(?:property|name)=["']${k}["'][^>]*content=["']([^"']*)`, "i")) || html.match(new RegExp(`<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']${k}["']`, "i")) || [])[1] || "").trim();
    const titulo = meta("og:title") || ent((html.match(/<title[^>]*>([\s\S]*?)<\/title>/i) || [])[1] || "").trim();
    const descripcion = meta("og:description") || meta("description");
    let imagen = meta("og:image"); try { if (imagen) imagen = new URL(imagen, dir.href).href; } catch(e){ imagen = ""; }
    const cuerpo = (html.match(/<article[\s\S]*?<\/article>/i) || html.match(/<main[\s\S]*?<\/main>/i) || html.match(/<body[\s\S]*<\/body>/i) || [html])[0];
    const texto = ent(cuerpo.replace(/<(script|style|noscript|svg|nav|footer|header|form)[\s\S]*?<\/\1>/gi, " ").replace(/<br\s*\/?>|<\/(p|div|li|h\d)>/gi, "\n").replace(/<[^>]+>/g, " ")).replace(/[ \t]+/g, " ").replace(/\n\s*\n+/g, "\n").trim().slice(0, 40000);
    let resumen = "";
    if (hayIA() && texto.length > 200 && !(u.dia === hoy() && (u.iaHoy || 0) >= MAX_IA)){
      try {
        let base = process.env.AOAI_ENDPOINT.trim(); try { base = new URL(base).origin; } catch(e){}
        const r = await fetch(`${base}/openai/deployments/${encodeURIComponent(process.env.AOAI_DESPLIEGUE || "gpt-4.1-mini")}/chat/completions?api-version=2024-10-21`, {method: "POST", headers: {"Content-Type": "application/json", "api-key": process.env.AOAI_CLAVE.trim()},
          body: JSON.stringify({temperature: 0.2, max_tokens: 500, messages: [{role: "system", content: "Resume en español, en 3 a 5 viñetas breves que empiecen por «- », lo esencial de esta página web. Solo las viñetas."}, {role: "user", content: `${titulo}\n\n${texto.slice(0, 12000)}`}]})});
        if (r.ok){ resumen = ((await r.json()).choices?.[0]?.message?.content || "").trim(); await contar(u, "ia", 1); }
      } catch(e){}
    }
    return {titulo: titulo.slice(0, 200), descripcion: descripcion.slice(0, 600), imagen, texto, resumen};
  },

  async "POST sugerencia"(request, yo){
    const e = await request.json();
    const texto = String(e.texto || "").trim().slice(0, 4000);
    if (!texto) return [{error: "Escribe tu sugerencia"}, 400];
    const t = await tabla("sugerencias");
    const id = new Date().toISOString().replace(/[-:.TZ]/g, "") + crypto.randomBytes(3).toString("hex");
    await t.createEntity({partitionKey: "s", rowKey: id, email: yo.email, nombre: yo.nombre, tipo: String(e.tipo || "idea").slice(0, 20), texto, version: String(e.version || "").slice(0, 40), dispositivo: String(e.dispositivo || "").slice(0, 80), fecha: new Date().toISOString(), leida: false});
    return {ok: true};
  },

  async "GET admin/usuarios"(request, yo){
    soloAdmin(yo);
    const t = await tabla("usuarios"), lista = [];
    for await (const u of t.listEntities()) lista.push({id: u.rowKey, email: u.email, nombre: u.nombre, estado: u.estado, creado: u.creado, ultimo: u.ultimo, iaHoy: u.dia === hoy() ? u.iaHoy || 0 : 0, iaTotal: u.iaTotal || 0, vozMinTotal: Math.round((u.vozSegTotal || 0) / 60)});
    lista.sort((a, b) => (a.estado === "pendiente" ? 0 : 1) - (b.estado === "pendiente" ? 0 : 1) || String(b.creado).localeCompare(a.creado));
    return {usuarios: lista};
  },
  async "POST admin/usuario"(request, yo){
    soloAdmin(yo);
    const e = await request.json();
    if (!["autorizado", "bloqueado", "pendiente"].includes(e.estado)) return [{error: "Estado no válido"}, 400];
    const t = await tabla("usuarios"); const u = await leer(t, "u", String(e.id));
    if (!u) return [{error: "No existe"}, 404];
    if (esAdmin(u.email)) return [{error: "No puedes cambiar el acceso de un administrador"}, 400];
    await t.updateEntity({partitionKey: "u", rowKey: u.rowKey, estado: e.estado, cambiado: new Date().toISOString()}, "Merge");
    return {ok: true};
  },
  async "GET admin/sugerencias"(request, yo){
    soloAdmin(yo);
    const t = await tabla("sugerencias"), lista = [];
    for await (const s of t.listEntities()) lista.push({id: s.rowKey, email: s.email, nombre: s.nombre, tipo: s.tipo, texto: s.texto, version: s.version, dispositivo: s.dispositivo, fecha: s.fecha, leida: !!s.leida});
    lista.sort((a, b) => b.id.localeCompare(a.id));
    return {sugerencias: lista.slice(0, 300)};
  },
  async "POST admin/sugerencia"(request, yo){
    soloAdmin(yo);
    const e = await request.json(); const t = await tabla("sugerencias");
    if (e.borrar) await t.deleteEntity("s", String(e.id)); else await t.updateEntity({partitionKey: "s", rowKey: String(e.id), leida: !!e.leida}, "Merge");
    return {ok: true};
  }
};
// ---------- estadísticas anónimas: solo contadores por día y evento, sin datos de nadie ----------
const EVENTOS = new Set(["app_nueva", "app_dia", "sin_cuenta", "cuenta_conectada", "pasada_wa", "pasada_correo", "nota_abierta", "nota_hecha", "nota_guardar",
  "invita_probar", "invita_video", "bienvenida", "bienvenida_instalar", "visita_astillero"]);
async function sumarEvento(ev){
  const t = await tabla("estadisticas"), dia = hoy();
  for (let i = 0; i < 5; i++){
    const e = await leer(t, dia, ev);
    try {
      if (!e) await t.createEntity({partitionKey: dia, rowKey: ev, n: 1});
      else await t.updateEntity({partitionKey: dia, rowKey: ev, n: (e.n || 0) + 1}, "Merge", {etag: e.etag});
      return;
    } catch(err){ if (![409, 412].includes(err.statusCode)) throw err; }   // otro lo sumó a la vez: se reintenta
  }
}
const recientesIP = new Map();   // freno sencillo: como mucho 60 eventos por minuto desde la misma dirección
function frenar(request){
  const ip = (request.headers.get("x-forwarded-for") || "").split(",")[0].trim() || "?", ahora = Date.now();
  const l = (recientesIP.get(ip) || []).filter(x => ahora - x < 60000); l.push(ahora); recientesIP.set(ip, l);
  if (recientesIP.size > 5000) recientesIP.clear();
  return l.length > 60;
}
rutas["GET admin/estadisticas"] = async (request, yo) => {
  soloAdmin(yo);
  const t = await tabla("estadisticas"), desde = new Date(Date.now() - 90 * 864e5).toISOString().slice(0, 10), dias = {}, total = {};
  for await (const e of t.listEntities()){
    total[e.rowKey] = (total[e.rowKey] || 0) + (e.n || 0);
    if (e.partitionKey >= desde) (dias[e.partitionKey] ||= {})[e.rowKey] = e.n || 0;
  }
  const u = await tabla("usuarios"), usuarios = {total: 0, autorizado: 0, pendiente: 0, bloqueado: 0};
  for await (const x of u.listEntities()){ usuarios.total++; usuarios[x.estado] = (usuarios[x.estado] || 0) + 1; }
  return {dias, total, usuarios};
};
function soloAdmin(yo){ if (!esAdmin(yo.email)) throw new NoAutorizado("Solo el administrador"); }

app.http("api", {
  methods: ["GET", "POST", "OPTIONS"], authLevel: "anonymous", route: "{*ruta}",
  handler: async (request, context) => {
    if (request.method === "OPTIONS") return {status: 204, headers: cors(request)};
    const ruta = String(request.params.ruta || "").replace(/^\/+|\/+$/g, "");
    if (request.method === "GET" && ruta === "salud"){
      // comprobación de Google sin revelar el secreto: solo longitud, formato y los 4 últimos (como los muestra Google)
      const sec = G_SECRETO, bruto = process.env.GOOGLE_CLIENT_SECRET || "";
      const google = !sec ? "sin secreto" : `${bruto.includes("client_secret") ? "(sacado del JSON) " : ""}secreto de ${sec.length} caracteres${sec !== sec.trim() ? " (¡con espacios!)" : ""}${/^["']|["']$/.test(sec.trim()) ? " (¡con comillas!)" : ""}${sec.trim().startsWith("GOCSPX-") ? "" : " (no empieza por GOCSPX-)"} · acaba en ${sec.trim().slice(-4)} · cliente ${G_CLIENT.slice(0, 12)}…`;
      return json(request, {ok: true, ia: hayIA(), voz: hayVoz(), admin: ADMINS.length > 0, google});
    }
    // Entrar con Google: el canje del código y la renovación necesitan el secreto, que solo está aquí
    if (request.method === "POST" && ruta === "google/token"){
      if (!G_CLIENT || !G_SECRETO) return json(request, {error: "Google no está configurado en el servidor"}, 503);
      if (frenar(request)) return json(request, {error: "Demasiados intentos"}, 429);
      let e = {}; try { e = JSON.parse((await request.text()).slice(0, 4000)); } catch(err){}
      const p = new URLSearchParams({client_id: G_CLIENT, client_secret: G_SECRETO});
      if (e.grant_type === "authorization_code"){
        if (!e.code || !e.code_verifier || !G_REDIRECCIONES.includes(e.redirect_uri)) return json(request, {error: "Datos no válidos"}, 400);
        p.set("grant_type", "authorization_code"); p.set("code", e.code); p.set("code_verifier", e.code_verifier); p.set("redirect_uri", e.redirect_uri);
      } else if (e.grant_type === "refresh_token" && e.refresh_token){
        p.set("grant_type", "refresh_token"); p.set("refresh_token", e.refresh_token);
      } else return json(request, {error: "Datos no válidos"}, 400);
      const r = await fetch("https://oauth2.googleapis.com/token", {method: "POST", headers: {"Content-Type": "application/x-www-form-urlencoded"}, body: p});
      const t = await r.text();
      return {status: r.status, headers: {...cors(request), "Content-Type": "application/json", "Cache-Control": "no-store"}, body: t};
    }
    // enlaces cortos de notas pasadas: se guarda el paquete (ya comprimido) y se da una clave de 8 letras; caducan a los 180 días
    if (request.method === "POST" && ruta === "corto"){
      const d = (await request.text()).trim();
      if (!/^[zj][A-Za-z0-9_-]{4,6000}$/.test(d) || frenar(request)) return json(request, {error: "No válido"}, 400);
      const t = await tabla("cortos"), abc = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
      for (let i = 0; i < 4; i++){
        const id = [...crypto.randomBytes(8)].map(x => abc[x % abc.length]).join("");
        try { await t.createEntity({partitionKey: "c", rowKey: id, d, creado: new Date().toISOString()}); return json(request, {id}); }
        catch(e){ if (e.statusCode !== 409) throw e; }
      }
      return json(request, {error: "Inténtalo otra vez"}, 503);
    }
    if (request.method === "GET" && /^corto\/[A-Za-z0-9]{8}$/.test(ruta)){
      const e = await leer(await tabla("cortos"), "c", ruta.slice(6));
      if (!e || Date.now() - new Date(e.creado) > 180 * 864e5) return json(request, {error: "Enlace caducado"}, 404);
      return json(request, {d: e.d}, 200, {"Cache-Control": "public, max-age=3600"});
    }
    // formulario de contacto del portal astillerosoftware.es: anónimo; llega al panel de Administración como una sugerencia más
    if (request.method === "POST" && ruta === "contacto"){
      let e = {}; try { e = JSON.parse((await request.text()).slice(0, 8000)); } catch(err){}
      const nombre = String(e.nombre || "").trim().slice(0, 120), email = String(e.email || "").trim().slice(0, 200), mensaje = String(e.mensaje || "").trim().slice(0, 3000);
      if (e.web) return json(request, {ok: true});   // campo trampa para robots
      if (nombre.length < 2 || !/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email) || mensaje.length < 20) return json(request, {error: "Faltan datos"}, 400);
      if (frenar(request)) return json(request, {error: "Demasiados mensajes seguidos. Prueba en unos minutos."}, 429);
      const empresa = String(e.empresa || "").trim().slice(0, 120), tel = String(e.tel || "").trim().slice(0, 40);
      const texto = `${mensaje}\n\n— ${nombre}${empresa ? " · " + empresa : ""} · ${email}${tel ? " · " + tel : ""}`;
      const id = new Date().toISOString().replace(/[-:.TZ]/g, "") + crypto.randomBytes(3).toString("hex");
      await (await tabla("sugerencias")).createEntity({partitionKey: "s", rowKey: id, email, nombre: nombre + (empresa ? " (" + empresa + ")" : ""), tipo: "contacto web", texto, version: "astillerosoftware.es", dispositivo: "portal", fecha: new Date().toISOString(), leida: false});
      return json(request, {ok: true});
    }
    // estado de las notas pasadas: la página pública dice «vista» y «hecha»; la app de quien la mandó lo consulta.
    // Solo se guarda el identificador aleatorio de la nota, fechas y el progreso de la lista: ningún contenido ni dato personal.
    if (request.method === "POST" && ruta === "estado"){
      let e = {}; try { e = JSON.parse((await request.text()).slice(0, 600)); } catch(err){}
      const id = String(e.id || "");
      if (!/^[a-z0-9]{6,40}$/i.test(id) || !["vista", "hecha", "progreso"].includes(e.e) || frenar(request)) return json(request, {error: "No válido"}, 400);
      const t = await tabla("estados"), ahora = new Date().toISOString(), prev = await leer(t, "a", id) || {};
      const ent = {partitionKey: "a", rowKey: id, vista: prev.vista || ahora, cambio: ahora};
      if (e.e === "hecha") ent.hecha = prev.hecha || ahora;
      if (/^\d{1,3}\/\d{1,3}$/.test(String(e.p || ""))) ent.progreso = String(e.p);
      await t.upsertEntity(ent, "Merge");
      return json(request, {ok: true});
    }
    if (request.method === "POST" && ruta === "estados"){
      let e = {}; try { e = JSON.parse((await request.text()).slice(0, 6000)); } catch(err){}
      const ids = (Array.isArray(e.ids) ? e.ids : []).map(String).filter(x => /^[a-z0-9]{6,40}$/i.test(x)).slice(0, 100);
      if (frenar(request)) return json(request, {error: "Demasiadas consultas"}, 429);
      const t = await tabla("estados"), out = {};
      await Promise.all(ids.map(async id => { const x = await leer(t, "a", id); if (x) out[id] = {vista: x.vista || null, hecha: x.hecha || null, progreso: x.progreso || null, cambio: x.cambio || null}; }));
      return json(request, {estados: out}, 200, {"Cache-Control": "no-store"});
    }
    if (request.method === "POST" && ruta === "evento"){   // anónimo: sin cuenta y sin guardar quién
      try { const e = JSON.parse((await request.text()).slice(0, 200) || "{}");
        if (EVENTOS.has(e.e) && !frenar(request)) await sumarEvento(e.e); } catch(err){ context.warn(err); }
      return {status: 204, headers: cors(request)};
    }
    const f = rutas[`${request.method} ${ruta}`];
    if (!f) return json(request, {error: "No existe"}, 404);
    try {
      const yo = await quienEs(request);
      const r = await f(request, yo);
      if (Array.isArray(r)) return json(request, r[0], r[1]);
      if (r && r.crudo !== undefined) return {status: r.status, headers: {...cors(request), "Content-Type": "application/json", ...(r.extra || {})}, body: r.crudo};
      return json(request, r);
    } catch(e){
      if (e instanceof NoAutorizado) return json(request, {error: e.message}, 401);
      context.error(e);
      return json(request, {error: "Error del servidor"}, 500);
    }
  }
});

// Limpieza diaria: los estados de notas pasadas se borran a los 90 días de su último cambio
// (la app deja de consultarlos a los 90 días, así que no se guarda nada que ya no sirva).
app.timer("limpiarEstados", {
  schedule: "0 30 3 * * *",   // cada día a las 3:30 (UTC)
  handler: async (_t, context) => {
    const t = await tabla("estados"), limite = new Date(Date.now() - 90 * 864e5).toISOString();
    let borrados = 0;
    for await (const e of t.listEntities({queryOptions: {filter: `PartitionKey eq 'a' and cambio lt '${limite}'`}})){
      try { await t.deleteEntity(e.partitionKey, e.rowKey); borrados++; } catch(err){}
    }
    context.log(`limpiarEstados: ${borrados} borrados`);
  }
});

// Mantener despierto: en el plan de consumo, Azure apaga el servidor tras unos 20 minutos sin uso
// y la primera petición tarda mucho en despertarlo. Un toque cada 10 minutos de 6:00 a 24:00 (hora de España)
// lo evita; de paso deja abiertas las tablas y descargadas las claves para comprobar las sesiones.
// Son unas 3.300 ejecuciones al mes, muy por debajo del millón gratuito de Azure Functions.
app.timer("despierto", {
  schedule: "0 */10 5-22 * * *",   // UTC: 5:00–22:59 ≈ 6:00–23:59 en invierno y 7:00–00:59 en verano
  handler: async () => {
    await Promise.all(["usuarios", "sugerencias", "estadisticas", "estados"].map(n => tabla(n).catch(() => null)));
    await Promise.all([clavesMS().catch(() => null), clavesGoogle().catch(() => null)]);
  }
});

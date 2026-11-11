// Cuaderna · arranque: se ejecuta antes que nada (mudanza desde la dirección antigua)
/* Cuaderna se ha mudado de jamormar.github.io/chispa a cuaderna.astillerosoftware.es.
   Quien entra por la dirección antigua pasa a la nueva, salvo dos casos que necesitan un aviso. */
(() => {
  if (location.hostname !== "jamormar.github.io") return;
  const NUEVA = "https://cuaderna.astillerosoftware.es/";
  const destino = NUEVA + location.pathname.replace(/^\/chispa\/?/, "").replace(/^index\.html$/, "") + location.search + location.hash;
  const ls = k => { try { return localStorage.getItem(k); } catch(e){ return null; } };
  const ss = k => { try { return sessionStorage.getItem(k); } catch(e){ return null; } };
  const enApp = !!(window.Capacitor && Capacitor.isNativePlatform && Capacitor.isNativePlatform());
  const soloAqui = !ls("chispa.token") && !!ls("chispa.local");
  if (!enApp && (!soloAqui || ss("chispa.mudanza") === "nueva")){ window.stop(); location.replace(destino); return; }
  if (!enApp && ss("chispa.mudanza") === "quedar") return;   // quiere conectar su cuenta antes de mudarse
  window.stop();
  const caja = document.createElement("div");
  caja.style.cssText = "position:fixed;inset:0;z-index:99999;background:#F4ECDD;color:#2B1D14;display:flex;align-items:center;justify-content:center;padding:24px;font:16px/1.5 system-ui,sans-serif;text-align:center";
  const boton = (id, txt, fondo, color) => `<a id="${id}" href="#" style="display:block;margin:10px 0;padding:14px;border-radius:14px;background:${fondo};color:${color};text-decoration:none;font-weight:700">${txt}</a>`;
  caja.innerHTML = `<div style="max-width:380px"><img src="icon-192.png" alt="" style="width:72px;border-radius:18px"><h2 style="font-family:Georgia,serif;margin:14px 0 6px">Cuaderna se ha mudado</h2>` + (enApp
    ? `<p>Ahora vive en <b>cuaderna.astillerosoftware.es</b>. Instala la nueva versión de la app (una sola vez); tus notas siguen en tu nube y aparecerán al entrar con tu cuenta.</p>${boton("mNueva", "Cómo conseguir la nueva app", "#A8322D", "#FBF6EC")}<p style="font-size:13px;color:#7A6652">Después puedes desinstalar esta.</p>`
    : `<p>Ahora vive en <b>cuaderna.astillerosoftware.es</b>.</p><p>Estás usando Cuaderna <b>sin cuenta</b>, así que tus notas están guardadas solo aquí. Para llevártelas, conecta primero tu cuenta de Google o Microsoft: se suben a tu nube y en la nueva dirección aparecen al entrar.</p>${boton("mQuedar", "Conectar mi cuenta y llevarme las notas", "#A8322D", "#FBF6EC")}${boton("mNueva", "Ir a la nueva dirección y empezar de cero", "#FBF6EC", "#2B1D14")}`) + `</div>`;
  document.body.appendChild(caja);
  const ir = (k, url) => { try { sessionStorage.setItem("chispa.mudanza", k); } catch(e){} location.replace(url); };
  caja.querySelector("#mNueva").onclick = e => { e.preventDefault(); if (enApp){ const ins = "https://cuaderna.astillerosoftware.es/bienvenida/#instalar"; Promise.resolve().then(() => Capacitor.Plugins.ChispaNativo.abrirUrl({url: ins})).catch(() => { location.href = ins; }); } else ir("nueva", destino); };
  const q = caja.querySelector("#mQuedar"); if (q) q.onclick = e => { e.preventDefault(); ir("quedar", location.href); };
})();

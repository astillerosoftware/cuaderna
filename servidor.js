// Dirección del servidor de Cuaderna (Azure Functions). Vacía = sin servidor: la app funciona igual, sin IA/voz compartidas ni estadísticas.
window.CUADERNA_API = "https://cuaderna-jam-gwhugrdqcub6e9ba.swedencentral-01.azurewebsites.net/api";
// Identificador público del cliente de Google (Google Cloud › Credenciales). Vacío = sin «Entrar con Google».
window.CUADERNA_GOOGLE = "520507807981-ulvru7sh3ippbc510m1eo1ji532ptb8c.apps.googleusercontent.com";
// Estadísticas anónimas de difusión: solo se suma 1 al contador del día de cada evento; no se envía nada de la persona.
window.cuadernaEvento = function(e){
  try { const b = String(window.CUADERNA_API || "").replace(/\/+$/, ""); if (!b) return;
    fetch(b + "/evento", {method: "POST", headers: {"Content-Type": "text/plain"}, body: JSON.stringify({e}), keepalive: true, mode: "cors"}).catch(() => {}); } catch(err){}
};

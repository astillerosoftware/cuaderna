// Versión para Google Play: igual que el APK, pero sin leer el registro de llamadas.
// Google Play solo permite READ_CALL_LOG a apps de teléfono, identificación de llamadas y pocos casos más,
// así que en esta versión el aviso al colgar sale sin el nombre ni la duración de la llamada.
// Se ejecuta después de aplicar.mjs y del APK, justo antes de «gradlew bundleRelease».
import fs from "node:fs";
import path from "node:path";

const raiz = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const main = path.join(raiz, "android", "app", "src", "main");
function cambiar(fichero, fn) {
  const antes = fs.readFileSync(fichero, "utf8"), despues = fn(antes);
  if (despues === antes) throw new Error("No se pudo modificar " + fichero);
  fs.writeFileSync(fichero, despues);
}
cambiar(path.join(main, "AndroidManifest.xml"), m => m.replace(/\s*<uses-permission android:name="android\.permission\.READ_CALL_LOG"[^>]*\/>/g, ""));
cambiar(path.join(main, "java", "es", "astillerosoftware", "cuaderna", "ChispaNativoPlugin.java"),
  j => j.replace(", Manifest.permission.READ_CALL_LOG", ""));
const m = fs.readFileSync(path.join(main, "AndroidManifest.xml"), "utf8");
if (m.includes("READ_CALL_LOG")) throw new Error("Sigue apareciendo READ_CALL_LOG en el manifiesto");
console.log("Versión para Google Play preparada (sin registro de llamadas)");

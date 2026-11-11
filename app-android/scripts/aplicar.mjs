// Aplica las piezas propias de Cuaderna sobre el proyecto Android que genera Capacitor.
// Se ejecuta en GitHub Actions después de «npx cap add android».
import fs from "node:fs";
import path from "node:path";

const raiz = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const nativo = path.join(raiz, "nativo");
const app = path.join(raiz, "android", "app");
const main = path.join(app, "src", "main");

function copiar(origen, destino) {
  for (const e of fs.readdirSync(origen, {withFileTypes: true})) {
    const o = path.join(origen, e.name), d = path.join(destino, e.name);
    if (e.isDirectory()) { fs.mkdirSync(d, {recursive: true}); copiar(o, d); }
    else fs.copyFileSync(o, d);
  }
}
function cambiar(fichero, fn) {
  const antes = fs.readFileSync(fichero, "utf8"), despues = fn(antes);
  if (despues === antes) throw new Error("No se pudo modificar " + fichero);
  fs.writeFileSync(fichero, despues);
}

// 1) Código Java y recursos
copiar(path.join(nativo, "java"), path.join(main, "java"));
copiar(path.join(nativo, "res"), path.join(main, "res"));
// iconos: usamos los PNG del icono de Cuaderna en lugar de los adaptativos de Capacitor
fs.rmSync(path.join(main, "res", "mipmap-anydpi-v26"), {recursive: true, force: true});

// 2) AndroidManifest: permisos, compartir y widget
const permisos = [
  "android.permission.RECORD_AUDIO",
  "android.permission.MODIFY_AUDIO_SETTINGS",
  "android.permission.CAMERA",
  "android.permission.ACCESS_COARSE_LOCATION",
  "android.permission.ACCESS_FINE_LOCATION",
  "android.permission.POST_NOTIFICATIONS",
  "android.permission.SCHEDULE_EXACT_ALARM",
  "android.permission.RECEIVE_BOOT_COMPLETED",
  "android.permission.VIBRATE",
  ...JSON.parse(fs.readFileSync(path.join(nativo, "permisos-extra.json"), "utf8"))
];
const manifiesto = path.join(main, "AndroidManifest.xml");
cambiar(manifiesto, m => {
  // «permiso|N» limita el permiso hasta la versión N de Android (maxSdkVersion)
  const faltan = permisos.map(p => p.split("|")).filter(([p]) => !m.includes(`"${p}"`))
    .map(([p, max]) => `    <uses-permission android:name="${p}"${max ? ` android:maxSdkVersion="${max}"` : ""} />`).join("\n");
  m = m.replace(/(\s*)<application/, `\n${faltan}\n$1<application`);
  const compartir = `
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/*" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND_MULTIPLE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="es.astillerosoftware.cuaderna" android:host="auth" />
                <data android:scheme="es.astillerosoftware.cuaderna" android:host="google" />
                <data android:scheme="es.astillerosoftware.cuaderna" android:host="abrir" />
            </intent-filter>
            <meta-data android:name="android.app.shortcuts" android:resource="@xml/chispa_atajos" />
        </activity>`;
  m = m.replace("</activity>", compartir);
  const extra = fs.readFileSync(path.join(nativo, "manifiesto-aplicacion.xml"), "utf8");
  m = m.replace("</application>", extra + "\n    </application>");
  // sin copias de seguridad de los datos de la app: ahí viven los permisos del correo (Mail.Send) y no deben salir del móvil
  m = m.replace(/android:allowBackup="true"/, 'android:allowBackup="false"');
  if (!/android:allowBackup=/.test(m)) m = m.replace(/<application/, '<application android:allowBackup="false"');
  if (!/configChanges="[^"]*density/.test(m)) m = m.replace(/android:configChanges="([^"]*)"/, 'android:configChanges="$1|density"');
  return m;
});

// 3) build.gradle: versión y firma estable (para poder actualizar sin desinstalar)
const gradle = path.join(app, "build.gradle");
cambiar(gradle, g => {
  g = g.replace(/versionCode\s*=?\s*\d+/, 'versionCode = (System.getenv("VERSION_CODE") ?: "1").toInteger()');
  g = g.replace(/versionName\s*=?\s*"[^"]*"/, 'versionName = System.getenv("VERSION_NAME") ?: "1.0"');
  g = g.replace(/buildTypes\s*\{/, `signingConfigs {
        release {
            storeFile = file(System.getenv("CHISPA_KEYSTORE") ?: "chispa.keystore")
            storePassword = System.getenv("CHISPA_KS_PASS")
            keyAlias = "chispa"
            keyPassword = System.getenv("CHISPA_KS_PASS")
        }
    }
    buildTypes {`);
  g = g.replace(/buildTypes\s*\{\s*release\s*\{/, m0 => m0 + "\n            signingConfig = signingConfigs.release");
  return g;
});

console.log("Piezas de Cuaderna aplicadas");

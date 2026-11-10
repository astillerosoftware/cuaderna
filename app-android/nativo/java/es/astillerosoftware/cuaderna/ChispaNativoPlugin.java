package es.astillerosoftware.cuaderna;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import androidx.activity.result.ActivityResult;
import android.app.Activity;
import android.database.Cursor;
import android.provider.ContactsContract;
import java.util.HashMap;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Puente entre Android y la web de Cuaderna.
 * Envía a la web un evento «entrada» cuando la app se abre desde el widget,
 * desde un aviso o desde «Compartir con Cuaderna».
 */
@CapacitorPlugin(
    name = "ChispaNativo",
    permissions = {
        @Permission(alias = "llamadas", strings = {Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG}),
        @Permission(alias = "bluetooth", strings = {Manifest.permission.BLUETOOTH_CONNECT}),
        @Permission(alias = "contactos", strings = {Manifest.permission.READ_CONTACTS})
    }
)
public class ChispaNativoPlugin extends Plugin {

    static final String EXTRA_ACCION = "chispa_accion";
    private static final int MAX_IMAGENES = 6;
    private static final long MAX_BYTES = 12L * 1024 * 1024;

    private static ChispaNativoPlugin instancia;
    private static JSObject pendiente;

    @Override
    public void load() {
        instancia = this;
        Correo.programar(getContext());
        Suenos.programar(getContext());
        if (pendiente != null) {
            notifyListeners("entrada", pendiente, true);
            pendiente = null;
        }
    }

    /** Lee el intent con el que se abrió la app y se lo pasa a la web. */
    static void recibir(Context ctx, Intent intent) {
        JSObject datos = leer(ctx, intent);
        if (datos == null) return;
        if (instancia != null) instancia.notifyListeners("entrada", datos, true);
        else pendiente = datos;
    }

    @SuppressWarnings("deprecation")
    private static JSObject leer(Context ctx, Intent intent) {
        if (intent == null) return null;
        String accion = intent.getAction();

        // «Compartir con Chispa» desde otra app
        if (Intent.ACTION_SEND.equals(accion) || Intent.ACTION_SEND_MULTIPLE.equals(accion)) {
            JSObject o = new JSObject();
            o.put("accion", "compartir");
            CharSequence texto = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (texto != null) o.put("texto", texto.toString());
            String asunto = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            if (asunto != null) o.put("asunto", asunto);

            ArrayList<Uri> uris = new ArrayList<>();
            if (Intent.ACTION_SEND.equals(accion)) {
                Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) uris.add(u);
            } else {
                ArrayList<Uri> varias = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (varias != null) uris.addAll(varias);
            }
            JSArray imagenes = new JSArray();
            long total = 0;
            for (Uri u : uris) {
                if (imagenes.length() >= MAX_IMAGENES) break;
                String tipo = ctx.getContentResolver().getType(u);
                if (tipo == null || !tipo.startsWith("image/")) continue;
                byte[] bytes = leerBytes(ctx, u);
                if (bytes == null || total + bytes.length > MAX_BYTES) continue;
                total += bytes.length;
                imagenes.put("data:" + tipo + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP));
            }
            if (imagenes.length() > 0) o.put("imagenes", imagenes);
            intent.setAction(Intent.ACTION_MAIN);   // para no procesarlo dos veces
            return o;
        }

        // Widget, avisos y accesos directos
        String a = intent.getStringExtra(EXTRA_ACCION);
        if (a != null) {
            JSObject o = new JSObject();
            o.put("accion", a);
            for (String clave : new String[]{"quien", "nota", "duracion"}) {
                String v = intent.getStringExtra("chispa_" + clave);
                if (v != null) o.put(clave, v);
            }
            intent.removeExtra(EXTRA_ACCION);
            return o;
        }
        return null;
    }

    private static byte[] leerBytes(Context ctx, Uri u) {
        try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            long leidos = 0;
            while ((n = in.read(buf)) > 0) {
                leidos += n;
                if (leidos > MAX_BYTES) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Versión de la app nativa, para mostrarla en Ajustes y saber si hay una nueva. */
    @PluginMethod
    @SuppressWarnings("deprecation")
    public void info(PluginCall call) {
        JSObject r = new JSObject();
        try {
            PackageInfo pi = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            r.put("version", pi.versionName);
            r.put("codigo", Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Exception e) {
            r.put("version", "?");
            r.put("codigo", 0);
        }
        r.put("android", Build.VERSION.SDK_INT);
        // Quién la instaló: «com.android.vending» si viene de Google Play; vacío si se instaló a mano (APK)
        String instalador = "";
        try {
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            String paquete = getContext().getPackageName();
            String x = Build.VERSION.SDK_INT >= 30 ? pm.getInstallSourceInfo(paquete).getInstallingPackageName() : pm.getInstallerPackageName(paquete);
            if (x != null) instalador = x;
        } catch (Exception e) { }
        r.put("instalador", instalador);
        call.resolve(r);
    }

    private boolean permiso(String alias) {
        if ("bluetooth".equals(alias) && Build.VERSION.SDK_INT < 31) return true;
        return getPermissionState(alias) == PermissionState.GRANTED;
    }

    /** Ajustes de llamadas y coche guardados en el móvil. */
    @PluginMethod
    public void ajustes(PluginCall call) {
        SharedPreferences p = Ajustes.prefs(getContext());
        JSObject r = new JSObject();
        r.put("llamadas", p.getBoolean(Ajustes.LLAMADAS, false) && permiso("llamadas"));
        r.put("coche", p.getBoolean(Ajustes.COCHE, false));
        r.put("cocheDir", p.getString(Ajustes.COCHE_DIR, ""));
        r.put("cocheNombre", p.getString(Ajustes.COCHE_NOMBRE, ""));
        r.put("enCoche", Ajustes.enCoche(getContext()));
        r.put("permisoLlamadas", permiso("llamadas"));
        r.put("permisoBluetooth", permiso("bluetooth"));
        r.put("permisoContactos", permiso("contactos"));
        call.resolve(r);
    }

    @PluginMethod
    public void guardarAjustes(PluginCall call) {
        SharedPreferences.Editor e = Ajustes.prefs(getContext()).edit();
        if (call.hasOption("llamadas")) e.putBoolean(Ajustes.LLAMADAS, Boolean.TRUE.equals(call.getBoolean("llamadas")));
        if (call.hasOption("cocheDir")) {
            String dir = call.getString("cocheDir", "");
            e.putString(Ajustes.COCHE_DIR, dir);
            e.putString(Ajustes.COCHE_NOMBRE, call.getString("cocheNombre", ""));
            e.putBoolean(Ajustes.COCHE, dir != null && !dir.isEmpty());
        }
        e.apply();
        if (!Ajustes.prefs(getContext()).getBoolean(Ajustes.COCHE, false) && Ajustes.enCoche(getContext()))
            Ajustes.ponerEnCoche(getContext(), false);
        ajustes(call);
    }

    /** Pide el permiso de llamadas («llamadas») o de Bluetooth («bluetooth»). */
    @PluginMethod
    public void pedirPermiso(PluginCall call) {
        String tipo = call.getString("tipo", "llamadas");
        if (permiso(tipo)) { JSObject r = new JSObject(); r.put("concedido", true); call.resolve(r); return; }
        requestPermissionForAlias(tipo, call, "trasPermiso");
    }

    @PermissionCallback
    private void trasPermiso(PluginCall call) {
        JSObject r = new JSObject();
        r.put("concedido", permiso(call.getString("tipo", "llamadas")));
        call.resolve(r);
    }

    /** Dispositivos Bluetooth emparejados, para elegir el del coche. */
    @PluginMethod
    public void dispositivos(PluginCall call) {
        JSArray lista = new JSArray();
        if (!permiso("bluetooth")) { call.reject("Sin permiso de Bluetooth"); return; }
        try {
            BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
            if (ad != null && ad.getBondedDevices() != null) {
                for (BluetoothDevice d : ad.getBondedDevices()) {
                    JSObject o = new JSObject();
                    String nombre = d.getName();
                    o.put("nombre", nombre != null ? nombre : d.getAddress());
                    o.put("dir", d.getAddress());
                    lista.put(o);
                }
            }
        } catch (SecurityException e) {
            call.reject("Sin permiso de Bluetooth");
            return;
        }
        JSObject r = new JSObject();
        r.put("dispositivos", lista);
        call.resolve(r);
    }

    /** Muestra el aviso de «después de una llamada» para probarlo. */
    @PluginMethod
    public void probarLlamada(PluginCall call) {
        Ajustes.avisoLlamada(getContext(), "Prueba de Cuaderna", "2 min");
        call.resolve();
    }

    /** Activa o quita el modo coche a mano (para probarlo sin el coche). */
    @PluginMethod
    public void probarCoche(PluginCall call) {
        Ajustes.ponerEnCoche(getContext(), Boolean.TRUE.equals(call.getBoolean("dentro", true)));
        ajustes(call);
    }

    /** Abre un enlace fuera de la app (por ejemplo, para descargar la versión nueva). */
    @PluginMethod
    public void abrirUrl(PluginCall call) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(call.getString("url", "")));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo abrir el enlace");
        }
    }

    // ---------------------------------------------------------------- resumen por correo

    @PluginMethod
    public void correoEstado(PluginCall call) {
        JSObject r = new JSObject();
        r.put("conectado", Correo.conectado(getContext()));
        r.put("cuenta", Correo.cuenta(getContext()));
        r.put("ultimo", Correo.ultimo(getContext()));
        call.resolve(r);
    }

    /** Devuelve la dirección de Microsoft para dar permiso de envío de correo. */
    @PluginMethod
    public void conectarCorreo(PluginCall call) {
        try {
            JSObject r = new JSObject();
            r.put("url", Correo.urlLogin(getContext(), call.getString("pista", "")));
            call.resolve(r);
        } catch (Exception e) {
            call.reject("No se pudo preparar la conexión");
        }
    }

    @PluginMethod
    public void desconectarCorreo(PluginCall call) {
        Correo.desconectar(getContext());
        correoEstado(call);
    }

    /** Envía el resumen ahora. Con prueba=true se envía aunque no sea la hora ni haya nada. */
    @PluginMethod
    public void enviarResumen(PluginCall call) {
        boolean prueba = Boolean.TRUE.equals(call.getBoolean("prueba", true));
        new Thread(() -> {
            JSObject r = new JSObject();
            r.put("resultado", Correo.enviarSiToca(getContext(), prueba));
            call.resolve(r);
        }).start();
    }

    // ---------------------------------------------------------------- compartir

    /** Guarda el archivo (en base64) en la caché y abre el menú «Compartir» de Android. */
    private Uri archivoEnCache(String nombre, String datos) throws Exception {
        java.io.File carpeta = new java.io.File(getContext().getCacheDir(), "compartir");
        if (carpeta.exists()) { java.io.File[] viejos = carpeta.listFiles(); if (viejos != null) for (java.io.File f : viejos) f.delete(); }
        else carpeta.mkdirs();
        java.io.File archivo = new java.io.File(carpeta, nombre.replaceAll("[\\\\/:*?\"<>|]", " "));
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(archivo)) { out.write(Base64.decode(datos, Base64.DEFAULT)); }
        return androidx.core.content.FileProvider.getUriForFile(getContext(), "es.astillerosoftware.cuaderna.archivos", archivo);
    }

    /** Abre WhatsApp (o WhatsApp Business) directamente en el chat de un número, con el texto escrito o con un PDF adjunto. */
    @PluginMethod
    public void whatsapp(PluginCall call) {
        String tel = call.getString("tel", "").replaceAll("\\D", "");
        String texto = call.getString("texto", "");
        String datos = call.getString("datos", null);
        String nombre = call.getString("nombre", "Nota.pdf");
        String[] paquetes = {"com.whatsapp", "com.whatsapp.w4b"};
        try {
            if (datos != null && !datos.isEmpty()) {
                Uri uri = archivoEnCache(nombre, datos);
                for (String pkg : paquetes) {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("application/pdf");
                    i.setPackage(pkg);
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    if (!texto.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, texto);
                    if (!tel.isEmpty()) i.putExtra("jid", tel + "@s.whatsapp.net");
                    i.setClipData(android.content.ClipData.newRawUri(nombre, uri));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { getActivity().startActivity(i); call.resolve(); return; } catch (android.content.ActivityNotFoundException ignorado) { }
                }
            } else {
                Uri u = Uri.parse("https://api.whatsapp.com/send?phone=" + tel + "&text=" + Uri.encode(texto));
                for (String pkg : paquetes) {
                    Intent i = new Intent(Intent.ACTION_VIEW, u);
                    i.setPackage(pkg);
                    try { getActivity().startActivity(i); call.resolve(); return; } catch (android.content.ActivityNotFoundException ignorado) { }
                }
                getActivity().startActivity(new Intent(Intent.ACTION_VIEW, u));   // sin WhatsApp instalado: lo abre el navegador
                call.resolve(); return;
            }
            call.reject("WhatsApp no está instalado");
        } catch (Exception e) {
            call.reject("No se pudo abrir WhatsApp: " + e.getMessage());
        }
    }

    @PluginMethod
    public void compartirArchivo(PluginCall call) {
        String nombre = call.getString("nombre", "Cuaderna.pdf").replaceAll("[\\\\/:*?\"<>|]", " ");
        String datos = call.getString("datos", "");
        String tipo = call.getString("tipo", "application/pdf");
        String asunto = call.getString("asunto", "");
        try {
            java.io.File carpeta = new java.io.File(getContext().getCacheDir(), "compartir");
            if (carpeta.exists()) { java.io.File[] viejos = carpeta.listFiles(); if (viejos != null) for (java.io.File f : viejos) f.delete(); }
            else carpeta.mkdirs();
            java.io.File archivo = new java.io.File(carpeta, nombre);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(archivo)) { out.write(Base64.decode(datos, Base64.DEFAULT)); }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(getContext(), "es.astillerosoftware.cuaderna.archivos", archivo);
            Intent enviar = new Intent(Intent.ACTION_SEND);
            enviar.setType(tipo);
            enviar.putExtra(Intent.EXTRA_STREAM, uri);
            if (!asunto.isEmpty()) { enviar.putExtra(Intent.EXTRA_SUBJECT, asunto); enviar.putExtra(Intent.EXTRA_TITLE, asunto); }
            enviar.setClipData(android.content.ClipData.newRawUri(nombre, uri));
            enviar.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent menu = Intent.createChooser(enviar, "Compartir nota");
            menu.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(menu);
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo compartir: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- pregunta de la mañana (Sueños)

    @PluginMethod
    public void suenos(PluginCall call) {
        SharedPreferences p = Ajustes.prefs(getContext());
        SharedPreferences.Editor e = p.edit();
        if (call.hasOption("activo")) e.putBoolean(Suenos.P_ACTIVO, Boolean.TRUE.equals(call.getBoolean("activo")));
        if (call.hasOption("minutos")) e.putInt(Suenos.P_MINUTOS, Math.max(0, Math.min(120, call.getInt("minutos", 15))));
        if (call.hasOption("respaldo")) e.putString(Suenos.P_RESPALDO, call.getString("respaldo", ""));
        if (call.hasOption("soloFija")) e.putBoolean(Suenos.P_SOLO_FIJA, Boolean.TRUE.equals(call.getBoolean("soloFija")));
        e.commit();
        Suenos.programar(getContext());
        JSObject r = new JSObject();
        r.put("activo", Suenos.activo(getContext()));
        r.put("minutos", p.getInt(Suenos.P_MINUTOS, 15));
        r.put("respaldo", p.getString(Suenos.P_RESPALDO, "07:30"));
        r.put("proximo", p.getLong(Suenos.P_PROXIMO, 0));
        r.put("despertador", p.getLong(Suenos.P_DESPERTADOR, 0));
        r.put("soloFija", p.getBoolean(Suenos.P_SOLO_FIJA, false));
        r.put("saltadas", p.getInt(Suenos.P_SALTADAS, 0));
        r.put("reglas", 2);   // la web sabe que esta app ya distingue el despertador de otras alarmas
        call.resolve(r);
    }

    @PluginMethod
    public void probarSueno(PluginCall call) {
        Suenos.avisar(getContext(), true);
        call.resolve();
    }

    // ---------------------------------------------------------------- agenda del móvil

    /** Abre la agenda de Android para elegir un contacto (no necesita permiso: Android solo da ese contacto). */
    @PluginMethod
    public void elegirContacto(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI);
        startActivityForResult(call, i, "trasElegirContacto");
    }

    @ActivityCallback
    private void trasElegirContacto(PluginCall call, ActivityResult res) {
        JSObject r = new JSObject();
        if (call == null) return;
        if (res.getResultCode() != Activity.RESULT_OK || res.getData() == null || res.getData().getData() == null) { r.put("cancelado", true); call.resolve(r); return; }
        Uri uri = res.getData().getData();
        String id = null;
        try (Cursor c = getContext().getContentResolver().query(uri, new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { id = c.getString(0); r.put("nombre", c.getString(1)); }
        } catch (Exception e) {
            call.reject("No se pudo leer el contacto"); return;
        }
        if (id != null && permiso("contactos")) {   // con permiso, también la empresa
            HashMap<String, String> emp = empresas(id);
            if (emp.containsKey(id)) r.put("empresa", emp.get(id));
        }
        call.resolve(r);
    }

    private HashMap<String, String> empresas(String soloId) {
        HashMap<String, String> m = new HashMap<>();
        String sel = ContactsContract.Data.MIMETYPE + "=?" + (soloId != null ? " AND " + ContactsContract.Data.CONTACT_ID + "=?" : "");
        String[] args = soloId != null ? new String[]{ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, soloId} : new String[]{ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE};
        try (Cursor c = getContext().getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                new String[]{ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Organization.COMPANY}, sel, args, null)) {
            while (c != null && c.moveToNext()) { String e = c.getString(1); if (e != null && !e.trim().isEmpty()) m.put(c.getString(0), e.trim()); }
        } catch (Exception ignorado) { }
        return m;
    }

    /** Abre la agenda para elegir un número de teléfono (no necesita permiso: Android solo da ese número). */
    @PluginMethod
    public void elegirTelefono(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        startActivityForResult(call, i, "trasElegirTelefono");
    }

    @ActivityCallback
    private void trasElegirTelefono(PluginCall call, ActivityResult res) {
        if (call == null) return;
        JSObject r = new JSObject();
        if (res.getResultCode() != Activity.RESULT_OK || res.getData() == null || res.getData().getData() == null) { r.put("cancelado", true); call.resolve(r); return; }
        try (Cursor c = getContext().getContentResolver().query(res.getData().getData(),
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
            if (c != null && c.moveToFirst()) { r.put("nombre", c.getString(0)); r.put("tel", c.getString(1)); }
        } catch (Exception e) { call.reject("No se pudo leer el contacto"); return; }
        call.resolve(r);
    }

    private static String sinTildes(String s) {
        return java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().trim();
    }

    /** Teléfonos de la agenda de los nombres pedidos (coincidencia exacta del nombre, sin tildes). Solo con permiso de contactos. */
    @PluginMethod
    public void telefonos(PluginCall call) {
        JSObject r = new JSObject();
        if (!permiso("contactos")) { r.put("permiso", false); call.resolve(r); return; }
        HashMap<String, String> buscados = new HashMap<>();
        try { JSArray l = call.getArray("nombres", new JSArray()); for (int k = 0; k < l.length(); k++) { String n = l.getString(k); buscados.put(sinTildes(n), n); } } catch (Exception ignorado) { }
        JSObject res = new JSObject();
        try (Cursor c = getContext().getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String orig = buscados.get(sinTildes(c.getString(0))); if (orig == null || c.getString(1) == null) continue;
                JSArray lista = res.has(orig) ? (JSArray) res.get(orig) : new JSArray();
                JSObject t = new JSObject(); t.put("tel", c.getString(1)); t.put("movil", c.getInt(2) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE);
                lista.put(t); res.put(orig, lista);
            }
        } catch (Exception ignorado) { }
        r.put("permiso", true); r.put("telefonos", res); call.resolve(r);
    }

    /** Nombres (y empresa) de la agenda, para sugerirlos al escribir «Para quién». Solo se usan en el móvil. */
    @PluginMethod
    public void contactos(PluginCall call) {
        if (!permiso("contactos")) { JSObject r = new JSObject(); r.put("permiso", false); r.put("contactos", new JSArray()); call.resolve(r); return; }
        HashMap<String, String> emp = empresas(null);
        // teléfonos de cada contacto (para sugerir a quién pasar una nota o enviar un WhatsApp)
        HashMap<String, JSArray> tels = new HashMap<>();
        try (Cursor c = getContext().getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String num = c.getString(1); if (num == null || num.trim().isEmpty()) continue;
                JSArray l = tels.get(c.getString(0)); if (l == null) { l = new JSArray(); tels.put(c.getString(0), l); }
                JSObject t = new JSObject(); t.put("tel", num.trim()); t.put("movil", c.getInt(2) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE); l.put(t);
            }
        } catch (Exception ignorado) { }
        JSArray lista = new JSArray();
        try (Cursor c = getContext().getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME}, null, null, ContactsContract.Contacts.DISPLAY_NAME + " ASC")) {
            while (c != null && c.moveToNext()) {
                String nombre = c.getString(1); if (nombre == null || nombre.trim().isEmpty()) continue;
                JSObject o = new JSObject(); o.put("nombre", nombre.trim());
                String e = emp.get(c.getString(0)); if (e != null) o.put("empresa", e);
                JSArray t = tels.get(c.getString(0)); if (t != null) o.put("tels", t);
                lista.put(o);
            }
        } catch (Exception ignorado) { }
        JSObject r = new JSObject(); r.put("permiso", true); r.put("contactos", lista); call.resolve(r);
    }
}

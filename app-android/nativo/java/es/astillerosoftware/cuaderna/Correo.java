package es.astillerosoftware.cuaderna;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Resumen diario por correo.
 * La app entra en tu cuenta de Microsoft por su cuenta (no depende de la web), lee la agenda
 * que la web deja en OneDrive (Configuracion/agenda.json) y envía el resumen desde tu correo.
 * Si tienes dos móviles conectados, solo uno lo envía: el primero que deja la marca del día
 * en Configuracion/Envios.
 */
final class Correo {

    static final String CLIENT_ID = "3dc57230-4cdb-4e60-bb83-2def7cd5441f";
    static final String REDIRECT = "es.astillerosoftware.cuaderna://auth";
    static final String AUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0";
    static final String SCOPES = "openid profile offline_access User.Read Files.ReadWrite.AppFolder Mail.Send";
    static final String GRAPH = "https://graph.microsoft.com/v1.0";
    static final String WEB = "https://cuaderna.astillerosoftware.es/";
    static final int JOB_ID = 7101;

    private static final String P_REFRESH = "correo_refresh";
    private static final String P_CUENTA = "correo_cuenta";
    private static final String P_VERIFIER = "correo_verifier";
    private static final String P_STATE = "correo_state";
    private static final String P_ULTIMO = "correo_ultimo";
    // para no conectarse a OneDrive sin necesidad: día ya resuelto y hora del resumen (se recuerdan de la última comprobación)
    private static final String P_DIA_OK = "correo_dia_ok";
    private static final String P_HORA = "correo_hora";
    private static final Locale ES = new Locale("es", "ES");

    private Correo() {}

    // ------------------------------------------------------------ cuenta

    static boolean conectado(Context ctx) {
        return !Ajustes.prefs(ctx).getString(P_REFRESH, "").isEmpty();
    }

    static String cuenta(Context ctx) {
        return Ajustes.prefs(ctx).getString(P_CUENTA, "");
    }

    static String ultimo(Context ctx) {
        return Ajustes.prefs(ctx).getString(P_ULTIMO, "");
    }

    /** Prepara el inicio de sesión (PKCE) y devuelve la dirección a la que hay que ir. */
    static String urlLogin(Context ctx, String pista) throws Exception {
        String verifier = aleatorio(48), state = aleatorio(16);
        Ajustes.prefs(ctx).edit().putString(P_VERIFIER, verifier).putString(P_STATE, state).apply();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = b64(hash);
        StringBuilder u = new StringBuilder(AUTH + "/authorize?client_id=" + CLIENT_ID)
                .append("&response_type=code&response_mode=query")
                .append("&redirect_uri=").append(enc(REDIRECT))
                .append("&scope=").append(enc(SCOPES))
                .append("&code_challenge=").append(challenge).append("&code_challenge_method=S256")
                .append("&state=").append(state);
        if (pista != null && !pista.isEmpty()) u.append("&login_hint=").append(enc(pista));
        return u.toString();
    }

    /** Vuelta del inicio de sesión: cambia el código por los permisos. Devuelve "ok" o el error. */
    static String terminarLogin(Context ctx, Uri vuelta) {
        try {
            SharedPreferences p = Ajustes.prefs(ctx);
            String error = vuelta.getQueryParameter("error");
            if (error != null) {
                String d = vuelta.getQueryParameter("error_description");
                return "access_denied".equals(error) ? "Has cancelado el permiso" : (d != null ? d : error);
            }
            String code = vuelta.getQueryParameter("code");
            if (code == null || !p.getString(P_STATE, "-").equals(vuelta.getQueryParameter("state")))
                return "La respuesta de Microsoft no es válida. Inténtalo de nuevo";
            JSONObject t = token("grant_type=authorization_code&code=" + enc(code)
                    + "&redirect_uri=" + enc(REDIRECT)
                    + "&code_verifier=" + enc(p.getString(P_VERIFIER, "")));
            String cuenta = cuentaDe(t.optString("id_token", ""));
            p.edit().putString(P_REFRESH, t.getString("refresh_token")).putString(P_CUENTA, cuenta)
                    .remove(P_VERIFIER).remove(P_STATE).apply();
            programar(ctx);
            return "ok";
        } catch (Exception e) {
            return mensaje(e);
        }
    }

    static void desconectar(Context ctx) {
        Ajustes.prefs(ctx).edit().remove(P_REFRESH).remove(P_CUENTA).remove(P_ULTIMO).apply();
        programar(ctx);
    }

    /** Revisa cada media hora (con conexión) si toca enviar el resumen; sin red, salvo cuando de verdad toca. */
    static void programar(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!conectado(ctx)) { js.cancel(JOB_ID); return; }
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, ResumenJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(30 * 60 * 1000L)
                .setPersisted(true)
                .build();
        try { js.schedule(info); } catch (Exception ignorado) { }
    }

    // ------------------------------------------------------------ resumen

    /**
     * Envía el resumen si ya es la hora y nadie lo ha enviado hoy.
     * Con prueba=true se envía ya, aunque no haya nada, sin marcar el día.
     */
    /** Igual que comprobar(), pero deja anotado el resultado de cada intento (se ve en Ajustes). */
    static String enviarSiToca(Context ctx, boolean prueba) {
        if (!prueba) {   // atajo sin red: hoy ya está resuelto, o aún no es la hora
            android.content.SharedPreferences p = Ajustes.prefs(ctx);
            Calendar ahora = Calendar.getInstance();
            if (dia(ahora).equals(p.getString(P_DIA_OK, ""))) return "Hoy ya está hecho";
            String h = p.getString(P_HORA, null);
            if (h != null && hm(ahora).compareTo(h) < 0) return "Todavía no es la hora (" + h + ")";
        }
        String r = comprobar(ctx, prueba);
        if (!prueba && (r.startsWith("Enviado") || r.startsWith("El resumen de hoy ya") || r.startsWith("Sin novedades") || r.startsWith("Resumen desactivado")))
            Ajustes.prefs(ctx).edit().putString(P_DIA_OK, dia(Calendar.getInstance())).apply();
        if (!r.startsWith("Enviado") && !r.startsWith("Error"))   // los envíos y errores ya se anotan dentro
            Ajustes.prefs(ctx).edit().putString(P_ULTIMO, new SimpleDateFormat("d MMM HH:mm", ES).format(new Date()) + " · " + r).apply();
        return r;
    }

    private static String comprobar(Context ctx, boolean prueba) {
        if (!conectado(ctx)) return "El correo no está conectado";
        try {
            String acceso = acceso(ctx);
            String raiz = new JSONObject(pedir("GET", GRAPH + "/me/drive/special/approot", acceso, null, null).cuerpo).getString("id");
            JSONObject conf = buscar(acceso, raiz, "Configuracion");
            if (conf == null) return "Aún no hay datos de Cuaderna en OneDrive";
            String confId = conf.getString("id");
            List<JSONObject> ficheros = hijos(acceso, confId);
            JSONObject config = leerJson(buscarEn(ficheros, "config.json"));
            if (config == null) config = new JSONObject();
            if (!prueba && !config.optBoolean("resumenActivo", true)) return "Resumen desactivado";

            Calendar ahora = Calendar.getInstance();
            String hora = config.optString("resumenHora", "07:30");
            Ajustes.prefs(ctx).edit().putString(P_HORA, hora).apply();
            if (!prueba && hm(ahora) .compareTo(hora) < 0) return "Todavía no es la hora (" + hora + ")";

            String hoy = dia(ahora);
            String marcaId = null;
            if (!prueba) {
                JSONObject envios = buscar(acceso, confId, "Envios");
                if (envios == null) envios = crearCarpeta(acceso, confId, "Envios");
                Respuesta r = pedir("PUT", GRAPH + "/me/drive/items/" + envios.getString("id") + ":/" + hoy + ".json:/content?@microsoft.graph.conflictBehavior=fail",
                        acceso, "application/json", ("{\"estado\":\"enviando\",\"desde\":\"" + android.os.Build.MODEL + "\"}").getBytes(StandardCharsets.UTF_8), new int[]{409});
                if (r.estado == 409) return "El resumen de hoy ya se envió";
                marcaId = new JSONObject(r.cuerpo).getString("id");
            }

            JSONObject agenda = leerJson(buscarEn(ficheros, "agenda.json"));
            JSONArray notas = agenda != null ? agenda.optJSONArray("notas") : null;
            if (notas == null) notas = new JSONArray();

            String cuenta = cuenta(ctx);
            String aTrabajo = config.optString("correoTrabajo", "").trim();
            String aPersonal = config.optString("correoPersonal", "").trim();
            if (aTrabajo.isEmpty() && aPersonal.isEmpty()) aPersonal = cuenta;

            List<String> hecho = new ArrayList<>();
            try {
                if (aTrabajo.isEmpty() || aPersonal.isEmpty()) {
                    // un único correo con todo
                    String a = aTrabajo.isEmpty() ? aPersonal : aTrabajo;
                    if (enviarResumen(acceso, a, null, notas, ahora, prueba)) hecho.add("todo a " + a);
                } else {
                    if (enviarResumen(acceso, aTrabajo, "trabajo", notas, ahora, prueba)) hecho.add("trabajo");
                    if (enviarResumen(acceso, aPersonal, "personal", notas, ahora, prueba)) hecho.add("personal");
                }
            } catch (Exception e) {
                if (marcaId != null) borrar(acceso, marcaId);   // que lo intente otra vez más tarde
                throw e;
            }
            if (marcaId != null) {
                pedir("PUT", GRAPH + "/me/drive/items/" + marcaId + "/content", acceso, "application/json",
                        ("{\"estado\":\"enviado\",\"envios\":\"" + TextUtils.join(", ", hecho) + "\",\"desde\":\"" + android.os.Build.MODEL + "\"}").getBytes(StandardCharsets.UTF_8), null);
            }
            String res = hecho.isEmpty() ? "Sin novedades: no se envió nada" : "Enviado (" + TextUtils.join(", ", hecho) + ")";
            Ajustes.prefs(ctx).edit().putString(P_ULTIMO, new SimpleDateFormat("d MMM HH:mm", ES).format(new Date()) + " · " + res).apply();
            return res;
        } catch (Exception e) {
            String m = mensaje(e);
            Ajustes.prefs(ctx).edit().putString(P_ULTIMO, new SimpleDateFormat("d MMM HH:mm", ES).format(new Date()) + " · Error: " + m).apply();
            return "Error: " + m;
        }
    }

    /** Prepara y envía un resumen. ambito = "trabajo", "personal" o null (todo). Devuelve si se envió. */
    private static boolean enviarResumen(String acceso, String para, String ambito, JSONArray notas, Calendar ahora, boolean prueba) throws Exception {
        String hoy = dia(ahora);
        Calendar c = (Calendar) ahora.clone(); c.add(Calendar.DAY_OF_YEAR, -1);
        String ayer = dia(c);
        c = (Calendar) ahora.clone(); c.add(Calendar.DAY_OF_YEAR, 7);
        String semana = dia(c);

        List<JSONObject> paraHoy = new ArrayList<>(), atrasado = new ArrayList<>(), proximos = new ArrayList<>(),
                deAyer = new ArrayList<>(), anclas = new ArrayList<>();
        int sinFecha = 0;
        for (int i = 0; i < notas.length(); i++) {
            JSONObject n = notas.optJSONObject(i);
            if (n == null) continue;
            if (ambito != null && !ambito.equals(n.optString("amb", "trabajo"))) continue;
            boolean hecha = n.optBoolean("hecha", false);
            String f = n.optString("f", "");
            if (n.optBoolean("ancla", false) && !hecha && n.optString("t", "").isEmpty()) { anclas.add(n); continue; }
            if (!hecha && !f.isEmpty()) {
                if (f.equals(hoy)) paraHoy.add(n);
                else if (f.compareTo(hoy) < 0) atrasado.add(n);
                else if (f.compareTo(semana) <= 0) proximos.add(n);
            } else if (!hecha) sinFecha++;
            if (!hecha && ayer.equals(diaLocal(n.optString("cr", ""))) && (f.isEmpty() || f.compareTo(semana) > 0)) deAyer.add(n);
        }
        ordenar(paraHoy); ordenar(atrasado); ordenar(proximos);
        boolean hay = !paraHoy.isEmpty() || !atrasado.isEmpty() || !proximos.isEmpty() || !deAyer.isEmpty() || !anclas.isEmpty();
        if (!hay && !prueba) return false;

        String nombre = ambito == null ? "" : ("trabajo".equals(ambito) ? "Trabajo" : "Personal");
        String fecha = new SimpleDateFormat("EEEE d 'de' MMMM", ES).format(ahora.getTime());
        StringBuilder h = new StringBuilder();
        h.append("<div style=\"font-family:Segoe UI,Roboto,Arial,sans-serif;max-width:640px;margin:0 auto;color:#2B1D14\">")
         .append("<div style=\"background:#2B1D14;color:#FBF3E4;border-radius:16px;padding:18px 22px\">")
         .append("<div style=\"font-size:21px;font-weight:700;font-family:Georgia,serif\"><span style=\"display:inline-block;width:24px;height:24px;border-radius:50%;background:#A8322D;color:#FBF3E4;text-align:center;font-family:Georgia,serif;font-size:15px;line-height:24px;vertical-align:2px\">C</span> Cuaderna").append(nombre.isEmpty() ? "" : " · " + nombre).append("</div>")
         .append("<div style=\"opacity:.8;margin-top:4px;text-transform:capitalize\">").append(esc(fecha)).append("</div></div>");
        seccion(h, "Para hoy", paraHoy, true, false);
        seccion(h, "Atrasado", atrasado, true, true);
        seccion(h, "Próximos 7 días", proximos, true, false);
        seccion(h, "Anotado ayer", deAyer, false, false);
        if (!anclas.isEmpty()) seccion(h, "Anclas por completar", anclas, false, false);
        if (!hay) h.append("<p style=\"margin:22px 4px\">No tienes nada pendiente. ¡Buen día!</p>");
        if (sinFecha > 0) h.append("<p style=\"margin:18px 4px;color:#66728A\">Además tienes ").append(sinFecha).append(sinFecha == 1 ? " nota pendiente" : " notas pendientes").append(" sin fecha.</p>");
        h.append("<p style=\"margin:24px 4px\"><a href=\"").append(WEB).append("\" style=\"background:#A8322D;color:#FBF3E4;text-decoration:none;font-weight:700;padding:11px 18px;border-radius:12px;display:inline-block\">Abrir Cuaderna</a></p>")
         .append("<p style=\"font-size:12px;color:#A3ACBD;margin:18px 4px\">Resumen automático de Cuaderna. Solo se envía si hay algo que contar.</p></div>");

        List<String> partes = new ArrayList<>();
        if (!paraHoy.isEmpty()) partes.add(paraHoy.size() + " para hoy");
        if (!atrasado.isEmpty()) partes.add(atrasado.size() + (atrasado.size() == 1 ? " atrasada" : " atrasadas"));
        if (partes.isEmpty() && !proximos.isEmpty()) partes.add(proximos.size() + " esta semana");
        if (partes.isEmpty() && !deAyer.isEmpty()) partes.add(deAyer.size() + (deAyer.size() == 1 ? " idea de ayer" : " ideas de ayer"));
        String asunto = (prueba ? "[Prueba] " : "") + "Cuaderna" + (nombre.isEmpty() ? "" : " · " + nombre)
                + (partes.isEmpty() ? (hay ? ": tu resumen" : ": nada pendiente") : ": " + TextUtils.join(", ", partes));

        JSONObject msg = new JSONObject()
                .put("subject", asunto)
                .put("body", new JSONObject().put("contentType", "HTML").put("content", h.toString()))
                .put("toRecipients", new JSONArray().put(new JSONObject().put("emailAddress", new JSONObject().put("address", para))));
        JSONObject cuerpo = new JSONObject().put("message", msg).put("saveToSentItems", true);
        pedir("POST", GRAPH + "/me/sendMail", acceso, "application/json", cuerpo.toString().getBytes(StandardCharsets.UTF_8), null);
        return true;
    }

    private static void seccion(StringBuilder h, String titulo, List<JSONObject> lista, boolean conFecha, boolean rojo) {
        if (lista.isEmpty()) return;
        h.append("<h3 style=\"margin:24px 4px 8px;font-size:16px;color:").append(rojo ? "#C2412D" : "#2B1D14").append("\">")
         .append(esc(titulo)).append(" <span style=\"color:#A3ACBD;font-weight:400\">").append(lista.size()).append("</span></h3>")
         .append("<div style=\"border:1px solid #E4E8EF;border-radius:14px;overflow:hidden\">");
        int i = 0;
        for (JSONObject n : lista) {
            if (i++ >= 25) { h.append("<div style=\"padding:10px 14px;color:#66728A\">y ").append(lista.size() - 25).append(" más…</div>"); break; }
            String t = n.optString("t", ""), x = n.optString("x", ""), quien = n.optString("quien", "");
            if (t.isEmpty()) t = n.optBoolean("ancla", false) ? "Ancla de las " + horaLocal(n.optString("cr", "")) : (x.isEmpty() ? "Nota" : x);
            List<String> meta = new ArrayList<>();
            if (conFecha) meta.add(fechaCorta(n.optString("f", "")) + (n.optString("h", "").isEmpty() ? "" : " " + n.optString("h")));
            if (!quien.isEmpty()) meta.add(quien);
            String tipo = n.optString("tipo", "");
            if (!tipo.isEmpty() && !"nota".equals(tipo)) meta.add(tipo.substring(0, 1).toUpperCase(ES) + tipo.substring(1));
            h.append("<div style=\"padding:11px 14px;border-top:").append(i == 1 ? "0" : "1px solid #EEF1F6").append("\">")
             .append("<div style=\"font-weight:600\">").append(esc(t)).append("</div>");
            if (!x.isEmpty() && !x.equals(t)) h.append("<div style=\"color:#4A5670;font-size:14px;margin-top:2px\">").append(esc(x)).append("</div>");
            JSONArray items = n.optJSONArray("lp");
            if (items != null && items.length() > 0) {
                h.append("<ul style=\"margin:6px 0 0;padding-left:20px;font-size:14px;color:#4A5670\">");
                for (int k = 0; k < items.length() && k < 8; k++) h.append("<li>").append(esc(items.optString(k))).append("</li>");
                if (items.length() > 8) h.append("<li>…</li>");
                h.append("</ul>");
            }
            if (!meta.isEmpty()) h.append("<div style=\"color:#66728A;font-size:13px;margin-top:3px\">").append(esc(TextUtils.join(" · ", meta))).append("</div>");
            h.append("</div>");
        }
        h.append("</div>");
    }

    // ------------------------------------------------------------ Microsoft Graph

    private static String acceso(Context ctx) throws Exception {
        SharedPreferences p = Ajustes.prefs(ctx);
        JSONObject t;
        try {
            t = token("grant_type=refresh_token&refresh_token=" + enc(p.getString(P_REFRESH, "")));
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("invalid_grant")) {
                p.edit().remove(P_REFRESH).apply();
                programar(ctx);
                throw new Exception("La conexión del correo ha caducado. Vuelve a conectarlo en Ajustes");
            }
            throw e;
        }
        String nuevo = t.optString("refresh_token", "");
        if (!nuevo.isEmpty()) p.edit().putString(P_REFRESH, nuevo).apply();
        return t.getString("access_token");
    }

    private static JSONObject token(String datos) throws Exception {
        String cuerpo = "client_id=" + CLIENT_ID + "&scope=" + enc(SCOPES) + "&" + datos;
        Respuesta r = pedir("POST", AUTH + "/token", null, "application/x-www-form-urlencoded", cuerpo.getBytes(StandardCharsets.UTF_8), new int[]{400, 401});
        JSONObject j = new JSONObject(r.cuerpo);
        if (r.estado >= 400) throw new Exception(j.optString("error", "error") + ": " + primeraLinea(j.optString("error_description", "")));
        return j;
    }

    private static List<JSONObject> hijos(String acceso, String id) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        String url = GRAPH + "/me/drive/items/" + id + "/children?$top=500";
        while (url != null) {
            JSONObject j = new JSONObject(pedir("GET", url, acceso, null, null).cuerpo);
            JSONArray v = j.optJSONArray("value");
            if (v != null) for (int i = 0; i < v.length(); i++) out.add(v.getJSONObject(i));
            url = j.optString("@odata.nextLink", null);
        }
        return out;
    }

    private static JSONObject buscarEn(List<JSONObject> lista, String nombre) {
        for (JSONObject o : lista) if (nombre.equalsIgnoreCase(o.optString("name"))) return o;
        return null;
    }

    private static JSONObject buscar(String acceso, String padre, String nombre) throws Exception {
        return buscarEn(hijos(acceso, padre), nombre);
    }

    private static JSONObject crearCarpeta(String acceso, String padre, String nombre) throws Exception {
        JSONObject cuerpo = new JSONObject().put("name", nombre).put("folder", new JSONObject()).put("@microsoft.graph.conflictBehavior", "fail");
        Respuesta r = pedir("POST", GRAPH + "/me/drive/items/" + padre + "/children", acceso, "application/json",
                cuerpo.toString().getBytes(StandardCharsets.UTF_8), new int[]{409});
        if (r.estado == 409) return buscar(acceso, padre, nombre);
        return new JSONObject(r.cuerpo);
    }

    private static JSONObject leerJson(JSONObject item) throws Exception {
        if (item == null) return null;
        String url = item.optString("@microsoft.graph.downloadUrl", "");
        if (url.isEmpty()) return null;
        return new JSONObject(pedir("GET", url, null, null, null).cuerpo);
    }

    private static void borrar(String acceso, String id) {
        try { pedir("DELETE", GRAPH + "/me/drive/items/" + id, acceso, null, null, new int[]{404}); } catch (Exception ignorado) { }
    }

    private static final class Respuesta {
        int estado;
        String cuerpo;
    }

    private static Respuesta pedir(String metodo, String url, String acceso, String tipo, byte[] cuerpo) throws Exception {
        return pedir(metodo, url, acceso, tipo, cuerpo, null);
    }

    /** Petición HTTP sencilla. Lanza un error si la respuesta no es correcta, salvo los códigos permitidos. */
    private static Respuesta pedir(String metodo, String url, String acceso, String tipo, byte[] cuerpo, int[] permitidos) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(metodo);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        if (acceso != null) c.setRequestProperty("Authorization", "Bearer " + acceso);
        if (cuerpo != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", tipo != null ? tipo : "application/octet-stream");
            try (OutputStream o = c.getOutputStream()) { o.write(cuerpo); }
        }
        Respuesta r = new Respuesta();
        r.estado = c.getResponseCode();
        InputStream in = r.estado >= 400 ? c.getErrorStream() : c.getInputStream();
        r.cuerpo = in == null ? "" : leer(in);
        c.disconnect();
        if (r.estado >= 400) {
            if (permitidos != null) for (int p : permitidos) if (p == r.estado) return r;
            String m = "";
            try { JSONObject e = new JSONObject(r.cuerpo).optJSONObject("error"); if (e != null) m = e.optString("message", e.optString("code", "")); } catch (Exception ignorado) { }
            throw new Exception("Microsoft (" + r.estado + ")" + (m.isEmpty() ? "" : ": " + m));
        }
        return r;
    }

    // ------------------------------------------------------------ utilidades

    private static void ordenar(List<JSONObject> l) {
        Collections.sort(l, (a, b) -> (a.optString("f") + a.optString("h", "99")).compareTo(b.optString("f") + b.optString("h", "99")));
    }

    private static String dia(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    private static String hm(Calendar c) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(c.getTime());
    }

    private static Date desdeIso(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19));
        } catch (Exception e) {
            return null;
        }
    }

    private static String diaLocal(String iso) {
        Date d = desdeIso(iso);
        return d == null ? "" : new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d);
    }

    private static String horaLocal(String iso) {
        Date d = desdeIso(iso);
        return d == null ? "" : new SimpleDateFormat("HH:mm", Locale.US).format(d);
    }

    private static String fechaCorta(String f) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(f);
            return new SimpleDateFormat("EEE d MMM", ES).format(d);
        } catch (Exception e) {
            return f;
        }
    }

    private static String cuentaDe(String idToken) {
        try {
            String[] p = idToken.split("\\.");
            JSONObject j = new JSONObject(new String(Base64.decode(p[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
            return j.optString("preferred_username", j.optString("email", ""));
        } catch (Exception e) {
            return "";
        }
    }

    private static String aleatorio(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return b64(b);
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\n", "<br>");
    }

    private static String leer(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private static String primeraLinea(String s) {
        int i = s.indexOf('\n');
        String r = i > 0 ? s.substring(0, i) : s;
        return r.replaceFirst("^AADSTS\\d+:\\s*", "");
    }

    private static String mensaje(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}

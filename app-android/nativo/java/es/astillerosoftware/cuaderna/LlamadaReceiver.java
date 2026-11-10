package es.astillerosoftware.cuaderna;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.provider.CallLog;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;

import java.util.Locale;

/** Detecta el final de una llamada y pregunta si hay algo que anotar. */
public class LlamadaReceiver extends BroadcastReceiver {

    private static final String ESTADO = "llamada_estado";
    private static final String DESDE = "llamada_desde";   // cuándo empezó la llamada (descolgado o marcado)

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
        SharedPreferences p = Ajustes.prefs(ctx);
        String estado = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (estado == null) return;
        String anterior = p.getString(ESTADO, TelephonyManager.EXTRA_STATE_IDLE);
        if (estado.equals(anterior)) return;   // Android avisa dos veces de cada cambio
        SharedPreferences.Editor ed = p.edit().putString(ESTADO, estado);
        if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(estado)) ed.putLong(DESDE, System.currentTimeMillis());
        ed.apply();

        if (!p.getBoolean(Ajustes.LLAMADAS, false)) return;
        // Solo al colgar una llamada que se llegó a hablar (no las perdidas)
        if (!TelephonyManager.EXTRA_STATE_IDLE.equals(estado) || !TelephonyManager.EXTRA_STATE_OFFHOOK.equals(anterior)) return;

        final Context app = ctx.getApplicationContext();
        final long desde = p.getLong(DESDE, 0);
        final PendingResult pendiente = goAsync();
        final Handler h = new Handler(Looper.getMainLooper());
        // espera a que el registro de llamadas tenga la última; si aún no está, lo intenta una vez más
        h.postDelayed(() -> {
            boolean listo = true;
            try { listo = avisar(app, desde, false); } catch (Exception ignorado) { }
            if (listo) { pendiente.finish(); return; }
            h.postDelayed(() -> {
                try { avisar(app, desde, true); } catch (Exception ignorado) { } finally { pendiente.finish(); }
            }, 4000);
        }, 2500);
    }

    /** Devuelve false si el registro aún no tiene esta llamada y conviene reintentar. */
    private static boolean avisar(Context ctx, long desde, boolean ultimoIntento) {
        String quien = null, duracion = null;
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            String[] campos = {CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DURATION, CallLog.Calls.DATE, CallLog.Calls.TYPE};
            try (Cursor c = ctx.getContentResolver().query(CallLog.Calls.CONTENT_URI, campos, null, null, CallLog.Calls.DATE + " DESC")) {
                if (c != null && c.moveToFirst()) {
                    long cuando = c.getLong(3);
                    long segundos = c.getLong(2);
                    int tipo = c.getInt(4);
                    // ¿es esta llamada? (empezó poco antes de descolgar/marcar, o después)
                    boolean esta = desde == 0 ? System.currentTimeMillis() - cuando - segundos * 1000 < 10 * 60 * 1000L : cuando >= desde - 90 * 1000L;
                    if (!esta && !ultimoIntento) return false;
                    if (esta) {
                        // sin respuesta: nadie descolgó (duración 0), perdida, rechazada o bloqueada → no molestar
                        if (segundos == 0 || tipo == CallLog.Calls.MISSED_TYPE || tipo == CallLog.Calls.REJECTED_TYPE || tipo == CallLog.Calls.BLOCKED_TYPE) return true;
                        if (segundos < 5) return true;   // llamada de segundos: no molestar
                        String nombre = c.getString(1), numero = c.getString(0);
                        if (nombre != null && !nombre.trim().isEmpty()) quien = nombre.trim();
                        else if (numero != null && !numero.isEmpty()) quien = PhoneNumberUtils.formatNumber(numero, Locale.getDefault().getCountry());
                        if (quien == null && numero != null) quien = numero;
                        duracion = segundos < 60 ? segundos + " s" : Math.round(segundos / 60.0) + " min";
                    }
                }
            } catch (Exception ignorado) { }
        }
        Ajustes.avisoLlamada(ctx, quien, duracion);
        return true;
    }
}

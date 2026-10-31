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

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
        SharedPreferences p = Ajustes.prefs(ctx);
        String estado = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (estado == null) return;
        String anterior = p.getString(ESTADO, TelephonyManager.EXTRA_STATE_IDLE);
        if (estado.equals(anterior)) return;   // Android avisa dos veces de cada cambio
        p.edit().putString(ESTADO, estado).apply();

        if (!p.getBoolean(Ajustes.LLAMADAS, false)) return;
        // Solo al colgar una llamada que se llegó a hablar (no las perdidas)
        if (!TelephonyManager.EXTRA_STATE_IDLE.equals(estado) || !TelephonyManager.EXTRA_STATE_OFFHOOK.equals(anterior)) return;

        final Context app = ctx.getApplicationContext();
        final PendingResult pendiente = goAsync();
        // espera a que el registro de llamadas tenga la última
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { avisar(app); } catch (Exception ignorado) { } finally { pendiente.finish(); }
        }, 2500);
    }

    private static void avisar(Context ctx) {
        String quien = null, duracion = null;
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            String[] campos = {CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DURATION, CallLog.Calls.DATE};
            try (Cursor c = ctx.getContentResolver().query(CallLog.Calls.CONTENT_URI, campos, null, null, CallLog.Calls.DATE + " DESC")) {
                if (c != null && c.moveToFirst()) {
                    long cuando = c.getLong(3);
                    long segundos = c.getLong(2);
                    if (System.currentTimeMillis() - cuando - segundos * 1000 < 10 * 60 * 1000L) {
                        if (segundos > 0 && segundos < 5) return;   // llamada de segundos: no molestar
                        String nombre = c.getString(1), numero = c.getString(0);
                        if (nombre != null && !nombre.trim().isEmpty()) quien = nombre.trim();
                        else if (numero != null && !numero.isEmpty()) quien = PhoneNumberUtils.formatNumber(numero, Locale.getDefault().getCountry());
                        if (quien == null && numero != null) quien = numero;
                        if (segundos > 0) duracion = segundos < 60 ? segundos + " s" : Math.round(segundos / 60.0) + " min";
                    }
                }
            } catch (Exception ignorado) { }
        }
        Ajustes.avisoLlamada(ctx, quien, duracion);
    }
}

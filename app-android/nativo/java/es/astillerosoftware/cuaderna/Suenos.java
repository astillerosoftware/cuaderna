package es.astillerosoftware.cuaderna;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Pregunta de la mañana: «¿Has pensado esta noche en algo?».
 * Sigue al despertador del móvil (la próxima alarma del sistema) y avisa unos minutos después.
 * Si no hay despertador en las próximas 24 horas, usa la hora de respaldo de Ajustes.
 * Lo que se anote desde el aviso va al buzón «Sueños».
 *
 * Para no confundir el despertador con otras alarmas (pastillas, recordatorios…):
 * - Como mucho una pregunta cada 12 horas.
 * - Solo cuenta una alarma que suena tras un rato largo sin usar el móvil. Se mide con el tiempo que el
 *   procesador ha estado despierto en las 3 horas anteriores (SystemClock.uptimeMillis no cuenta el sueño profundo):
 *   con el móvil en reposo apenas avanza; si lo estabas usando, sí. Así vale para quien se levanta a las 3, a las 6 o a mediodía.
 * - Plan B: «solo hora fija» ignora las alarmas y pregunta siempre a la hora elegida.
 */
public class Suenos extends BroadcastReceiver {

    static final String ACCION_AVISAR = "es.astillerosoftware.cuaderna.SUENO";
    static final String P_ACTIVO = "suenos_activo";
    static final String P_MINUTOS = "suenos_minutos";
    static final String P_RESPALDO = "suenos_respaldo";      // "HH:MM" o ""
    static final String P_PROXIMO = "suenos_proximo";        // milisegundos del próximo aviso
    static final String P_DESPERTADOR = "suenos_despertador"; // milisegundos del despertador seguido (0 si es la hora de respaldo)
    static final String P_ULTIMO_DIA = "suenos_ultimo_dia";
    static final String P_ULTIMO_MS = "suenos_ultimo_ms";       // cuándo se preguntó por última vez
    static final String P_SOLO_FIJA = "suenos_solo_fija";       // true: no seguir alarmas, solo la hora de respaldo
    static final String P_MUESTRA_UP = "suenos_muestra_up";     // uptimeMillis y elapsedRealtime tomados ~3 h antes del despertador
    static final String P_MUESTRA_EL = "suenos_muestra_el";
    static final String P_SALTADAS = "suenos_saltadas";         // alarmas que no parecían el despertador (para Ajustes)
    static final String ACCION_MUESTRA = "es.astillerosoftware.cuaderna.SUENO_MUESTRA";
    static final long VENTANA = 3 * 3600_000L, DESPIERTO_MAX = 45 * 60_000L, ENTRE_PREGUNTAS = 12 * 3600_000L;
    static final int ID_MUESTRA = 7202;
    static final String CANAL = "chispa_suenos";
    static final int ID_AVISO = 7201;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (ACCION_MUESTRA.equals(a)) { tomarMuestra(ctx); return; }
        if (ACCION_AVISAR.equals(a)) avisar(ctx);
        programar(ctx);   // cambio de despertador, reinicio del móvil o después de avisar
    }

    static SharedPreferences prefs(Context ctx) { return Ajustes.prefs(ctx); }

    static boolean activo(Context ctx) { return prefs(ctx).getBoolean(P_ACTIVO, false); }

    /** Calcula y programa el próximo aviso (uno por día). */
    static void programar(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        SharedPreferences p = prefs(ctx);
        if (!activo(ctx)) {
            am.cancel(pendiente(ctx)); am.cancel(pendienteMuestra(ctx));
            p.edit().putLong(P_PROXIMO, 0).putLong(P_DESPERTADOR, 0).apply();
            return;
        }
        boolean soloFija = p.getBoolean(P_SOLO_FIJA, false);
        long ahora = System.currentTimeMillis(), minutos = p.getInt(P_MINUTOS, 15) * 60_000L, cuando = 0, despertador = 0;
        AlarmManager.AlarmClockInfo info = am.getNextAlarmClock();
        if (!soloFija && info != null && info.getTriggerTime() + minutos > ahora && info.getTriggerTime() - ahora < 24 * 3600_000L) {
            despertador = info.getTriggerTime();
            cuando = despertador + minutos;
        }
        String respaldo = p.getString(P_RESPALDO, "07:30");
        if (cuando == 0 && respaldo != null && respaldo.matches("\\d{2}:\\d{2}")) {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(respaldo.substring(0, 2)));
            c.set(Calendar.MINUTE, Integer.parseInt(respaldo.substring(3)));
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= ahora) c.add(Calendar.DAY_OF_YEAR, 1);
            cuando = c.getTimeInMillis();
        }
        // el despertador de hoy ya sonó y Android ya apunta al de mañana: se mantiene la pregunta pendiente de hoy
        long previo = p.getLong(P_PROXIMO, 0), despPrevio = p.getLong(P_DESPERTADOR, 0);
        if (previo > ahora && despPrevio > 0 && despPrevio <= ahora && (cuando == 0 || previo < cuando)) return;
        if (cuando == 0) { am.cancel(pendiente(ctx)); p.edit().putLong(P_PROXIMO, 0).apply(); return; }
        // si hoy ya se preguntó, el siguiente aviso es para otro día
        if (dia(cuando).equals(p.getString(P_ULTIMO_DIA, "")) && despertador == 0) {
            Calendar c = Calendar.getInstance(); c.setTimeInMillis(cuando); c.add(Calendar.DAY_OF_YEAR, 1); cuando = c.getTimeInMillis();
        }
        PendingIntent pi = pendiente(ctx);
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setWindow(AlarmManager.RTC_WAKEUP, cuando, 3 * 60_000L, pi);
            else am.set(AlarmManager.RTC_WAKEUP, cuando, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, cuando, pi);
        }
        p.edit().putLong(P_PROXIMO, cuando).putLong(P_DESPERTADOR, despertador).apply();
        // muestra para saber luego si el móvil estuvo en reposo antes de que sonara
        long muestra = (despertador > 0 ? despertador : cuando) - VENTANA;
        if (muestra > ahora) {
            try { am.setWindow(AlarmManager.RTC_WAKEUP, muestra, 10 * 60_000L, pendienteMuestra(ctx)); }
            catch (SecurityException e) { am.set(AlarmManager.RTC_WAKEUP, muestra, pendienteMuestra(ctx)); }
        } else if (p.getLong(P_MUESTRA_EL, 0) == 0) tomarMuestra(ctx);   // despertador muy cercano: se mide desde ahora
    }

    static void tomarMuestra(Context ctx) {
        prefs(ctx).edit().putLong(P_MUESTRA_UP, SystemClock.uptimeMillis()).putLong(P_MUESTRA_EL, SystemClock.elapsedRealtime()).apply();
    }

    private static PendingIntent pendienteMuestra(Context ctx) {
        Intent i = new Intent(ctx, Suenos.class).setAction(ACCION_MUESTRA);
        return PendingIntent.getBroadcast(ctx, ID_MUESTRA, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * ¿El móvil estaba en reposo antes de esta alarma? Se mira cuánto tiempo ha estado despierto el procesador
     * desde la muestra. Se toleran 45 minutos más los minutos de espera tras el despertador (en los que ya estás despierto).
     * Sin muestra válida (reinicio, alarma recién puesta) se da por buena, para no dejar de preguntar.
     */
    static boolean trasReposo(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long up0 = p.getLong(P_MUESTRA_UP, 0), el0 = p.getLong(P_MUESTRA_EL, 0);
        long up = SystemClock.uptimeMillis(), el = SystemClock.elapsedRealtime();
        p.edit().putLong(P_MUESTRA_UP, 0).putLong(P_MUESTRA_EL, 0).apply();   // cada muestra vale para una alarma
        if (el0 <= 0 || el < el0 || up < up0) return true;
        long transcurrido = el - el0, despierto = up - up0;
        if (transcurrido < 90 * 60_000L || transcurrido > 8 * 3600_000L) return true;   // muy poco margen o muestra vieja: no se juzga
        long espera = p.getInt(P_MINUTOS, 15) * 60_000L;
        long tolerado = DESPIERTO_MAX * transcurrido / VENTANA + espera;
        return despierto <= tolerado;
    }

    private static PendingIntent pendiente(Context ctx) {
        Intent i = new Intent(ctx, Suenos.class).setAction(ACCION_AVISAR);
        return PendingIntent.getBroadcast(ctx, ID_AVISO, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String dia(long ms) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms)); }

    /** Muestra la pregunta (una vez al día como mucho, salvo la prueba). */
    static void avisar(Context ctx) { avisar(ctx, false); }

    static void avisar(Context ctx, boolean prueba) {
        SharedPreferences p = prefs(ctx);
        String hoy = dia(System.currentTimeMillis());
        long ahora = System.currentTimeMillis();
        if (!prueba) {
            if (!activo(ctx) || ahora - p.getLong(P_ULTIMO_MS, 0) < ENTRE_PREGUNTAS) return;
            boolean deAlarma = p.getLong(P_DESPERTADOR, 0) > 0;
            if (deAlarma && !trasReposo(ctx)) {   // estabas usando el móvil: no era el despertador (pastillas, recordatorios…)
                p.edit().putInt(P_SALTADAS, p.getInt(P_SALTADAS, 0) + 1).putLong(P_DESPERTADOR, 0).putLong(P_PROXIMO, 0).apply();
                return;
            }
            p.edit().putString(P_ULTIMO_DIA, hoy).putLong(P_ULTIMO_MS, ahora).apply();
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CANAL, "Pregunta de la mañana", NotificationManager.IMPORTANCE_HIGH);
            c.setDescription("Después del despertador: ¿has pensado esta noche en algo?");
            nm.createNotificationChannel(c);
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_stat_chispa)
                .setColor(0xFFA8322D)
                .setContentTitle("¿Has pensado esta noche en algo?")
                .setContentText("Cuéntaselo a Cuaderna antes de que se te olvide")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(2 * 3600_000L)
                .setContentIntent(Ajustes.abrir(ctx, 301, "sueno", null, null))
                .addAction(R.drawable.ic_w_mic, "Dictar", Ajustes.abrir(ctx, 302, "sueno_voz", null, null))
                .addAction(R.drawable.ic_w_pluma, "Escribir", Ajustes.abrir(ctx, 301, "sueno", null, null));
        try { nm.notify(ID_AVISO, b.build()); } catch (SecurityException ignorado) { }
    }
}

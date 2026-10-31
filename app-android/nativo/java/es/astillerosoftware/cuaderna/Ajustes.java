package es.astillerosoftware.cuaderna;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/** Ajustes del teléfono (aviso al colgar y modo coche) y los avisos propios de Cuaderna. */
final class Ajustes {

    static final String LLAMADAS = "llamadas";
    static final String COCHE = "coche";
    static final String COCHE_DIR = "coche_dir";
    static final String COCHE_NOMBRE = "coche_nombre";
    static final String EN_COCHE = "en_coche";

    static final String CANAL_LLAMADAS = "chispa_llamadas";
    static final String CANAL_COCHE = "chispa_coche";
    static final int ID_LLAMADA = 7001;
    static final int ID_COCHE = 7002;

    private Ajustes() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("chispa", Context.MODE_PRIVATE);
    }

    static boolean enCoche(Context ctx) {
        return prefs(ctx).getBoolean(EN_COCHE, false);
    }

    /** Abre Cuaderna con una acción concreta (voz, anotar, llamada…). */
    static PendingIntent abrir(Context ctx, int codigo, String accion, String quien, String duracion) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.putExtra(ChispaNativoPlugin.EXTRA_ACCION, accion);
        if (quien != null) i.putExtra("chispa_quien", quien);
        if (duracion != null) i.putExtra("chispa_duracion", duracion);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, codigo, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static NotificationManager gestor(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel l = new NotificationChannel(CANAL_LLAMADAS, "Después de una llamada", NotificationManager.IMPORTANCE_HIGH);
            l.setDescription("Te pregunta si quieres anotar algo al colgar");
            nm.createNotificationChannel(l);
            NotificationChannel c = new NotificationChannel(CANAL_COCHE, "Modo coche", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Botón de grabar mientras vas en el coche");
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        return nm;
    }

    /** Aviso tras colgar: «¿Algo que anotar de la llamada con X?» */
    static void avisoLlamada(Context ctx, String quien, String duracion) {
        String texto = (quien != null ? "Con " + quien : "Llamada terminada") + (duracion != null ? " · " + duracion : "");
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CANAL_LLAMADAS)
                .setSmallIcon(R.drawable.ic_stat_chispa)
                .setColor(0xFFA8322D)
                .setContentTitle("¿Algo que anotar de la llamada?")
                .setContentText(texto)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setTimeoutAfter(2 * 60 * 1000L)   // si no se toca, desaparece a los 2 minutos
                .setContentIntent(abrir(ctx, 101, "llamada", quien, duracion))
                .addAction(R.drawable.ic_w_pluma, "Anotar", abrir(ctx, 101, "llamada", quien, duracion))
                .addAction(R.drawable.ic_w_mic, "Grabar", abrir(ctx, 102, "voz", quien, duracion));
        publicar(ctx, ID_LLAMADA, b.build());
    }

    /** Entra o sale del modo coche: aviso fijo con «Grabar» y widget con un único botón grande. */
    static void ponerEnCoche(Context ctx, boolean dentro) {
        prefs(ctx).edit().putBoolean(EN_COCHE, dentro).apply();
        NotificationManager nm = gestor(ctx);
        if (dentro) {
            String coche = prefs(ctx).getString(COCHE_NOMBRE, "");
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CANAL_COCHE)
                    .setSmallIcon(R.drawable.ic_stat_chispa)
                    .setColor(0xFFA8322D)
                    .setContentTitle("Modo coche")
                    .setContentText("Toca para dictar una idea" + (coche.isEmpty() ? "" : " · " + coche))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(abrir(ctx, 201, "voz", null, null))
                    .addAction(R.drawable.ic_w_mic, "Grabar", abrir(ctx, 201, "voz", null, null))
                    .addAction(R.drawable.ic_stat_chispa, "Salir del modo coche", PendingIntent.getBroadcast(ctx, 202,
                            new Intent(ctx, CocheReceiver.class).setAction(CocheReceiver.SALIR), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            publicar(ctx, ID_COCHE, b.build());
            Coche.programar(ctx);
        } else {
            nm.cancel(ID_COCHE);
            Coche.cancelar(ctx);
        }
        ChispaWidget.actualizar(ctx);
    }

    private static void publicar(Context ctx, int id, Notification n) {
        try {
            gestor(ctx).notify(id, n);
        } catch (SecurityException e) {
            // sin permiso de notificaciones: no se puede avisar
        }
    }
}

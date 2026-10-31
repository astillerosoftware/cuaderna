package es.astillerosoftware.cuaderna;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprueba de verdad si el móvil sigue conectado al coche. El aviso de desconexión de Android
 * no siempre llega (algunas marcas lo bloquean con la app cerrada), así que el modo coche se
 * revisa cada pocos minutos y al abrir la app, y se quita solo si el coche ya no está.
 */
final class Coche {
    static final int JOB_ID = 7702;
    private Coche() { }

    /** Mira si el coche elegido sigue conectado y, si no, sale del modo coche. «fin» se llama siempre al terminar. */
    static void comprobar(Context ctx, Runnable fin) {
        final Context app = ctx.getApplicationContext();
        final Runnable acabar = fin != null ? fin : () -> { };
        if (!Ajustes.enCoche(app)) { acabar.run(); return; }
        String dir = Ajustes.prefs(app).getString(Ajustes.COCHE_DIR, "");
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
        try {
            if (ad == null || !ad.isEnabled() || dir.isEmpty()) { Ajustes.ponerEnCoche(app, false); acabar.run(); return; }
        } catch (SecurityException e) { acabar.run(); return; }

        final int[] perfiles = {BluetoothProfile.A2DP, BluetoothProfile.HEADSET};
        final AtomicInteger pendientes = new AtomicInteger(perfiles.length);
        final AtomicBoolean conectado = new AtomicBoolean(false), cerrado = new AtomicBoolean(false);
        final Runnable decidir = () -> {
            if (cerrado.getAndSet(true)) return;
            if (conectado.get()) programar(app); else Ajustes.ponerEnCoche(app, false);
            acabar.run();
        };
        BluetoothProfile.ServiceListener oyente = new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int perfil, BluetoothProfile proxy) {
                try {
                    for (BluetoothDevice d : proxy.getConnectedDevices()) if (dir.equalsIgnoreCase(d.getAddress())) conectado.set(true);
                } catch (SecurityException ignorado) { conectado.set(true); }   // sin permiso no se puede saber: mejor no quitarlo
                try { ad.closeProfileProxy(perfil, proxy); } catch (Exception ignorado) { }
                if (pendientes.decrementAndGet() == 0) decidir.run();
            }
            @Override public void onServiceDisconnected(int perfil) { }
        };
        int pedidos = 0;
        for (int p : perfiles) {
            try { if (ad.getProfileProxy(app, oyente, p)) pedidos++; else pendientes.decrementAndGet(); }
            catch (SecurityException e) { pendientes.decrementAndGet(); conectado.set(true); }
        }
        if (pedidos == 0) { decidir.run(); return; }
        // por si algún perfil no contesta
        new Handler(Looper.getMainLooper()).postDelayed(decidir, 8000);
    }

    /** Próxima comprobación dentro de unos minutos. */
    static void programar(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, CocheJob.class))
                .setMinimumLatency(5 * 60 * 1000L)
                .setOverrideDeadline(15 * 60 * 1000L)
                .build();
        try { js.schedule(info); } catch (Exception ignorado) { }
    }

    static void cancelar(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }
}

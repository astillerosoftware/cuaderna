package es.astillerosoftware.cuaderna;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

/**
 * Pantalla principal de Cuaderna: carga la web y le pasa lo que llega
 * desde el widget, desde «Compartir» o desde los avisos.
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ChispaNativoPlugin.class);
        super.onCreate(savedInstanceState);
        // en el móvil, siempre en vertical (en tablets se deja girar)
        if (getResources().getConfiguration().smallestScreenWidthDp < 600) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        // Al volver desde «Recientes» o al recrearse la pantalla, Android repite la última orden
        // (por ejemplo, el botón Ancla del widget). Solo se atiende cuando es nueva de verdad.
        boolean repetida = savedInstanceState != null
                || (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
        if (!repetida && !vueltaLogin(getIntent())) ChispaNativoPlugin.recibir(this, getIntent());
        Correo.programar(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (Ajustes.enCoche(this)) Coche.comprobar(this, null);   // por si el coche se desconectó sin avisar
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!vueltaLogin(intent)) ChispaNativoPlugin.recibir(this, intent);
    }

    /** Vuelta de Microsoft tras conectar el correo (es.astillerosoftware.cuaderna://auth?code=…). */
    private boolean vueltaLogin(Intent intent) {
        Uri d = intent != null ? intent.getData() : null;
        if (d == null || !"es.astillerosoftware.cuaderna".equals(d.getScheme())) return false;
        intent.setData(null);
        if ("google".equals(d.getHost()) || "abrir".equals(d.getHost())) {   // vuelta de Google, o una nota pasada / un «hecho» abiertos desde el navegador
            String q = d.getEncodedQuery();
            try { getBridge().getWebView().loadUrl(Correo.WEB + (q != null ? "?" + q : "")); } catch (Exception ignorado) { }
            return true;
        }
        new Thread(() -> {
            String r = Correo.terminarLogin(getApplicationContext(), d);
            runOnUiThread(() -> {
                try { getBridge().getWebView().loadUrl(Correo.WEB + "?correo=" + Uri.encode(r)); } catch (Exception ignorado) { }
            });
        }).start();
        return true;
    }
}

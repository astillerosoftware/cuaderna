package es.astillerosoftware.cuaderna;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

/**
 * Widget de la pantalla de inicio: Grabar, Anotar y Ancla con un toque.
 * En modo coche se queda solo el botón de grabar, grande.
 */
public class ChispaWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager gestor, int[] ids) {
        boolean coche = Ajustes.enCoche(ctx);
        for (int id : ids) {
            RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_chispa);
            v.setViewVisibility(R.id.w_anotar, coche ? View.GONE : View.VISIBLE);
            v.setViewVisibility(R.id.w_ancla, coche ? View.GONE : View.VISIBLE);
            v.setTextViewText(R.id.w_grabar_txt, coche ? "Modo coche · Grabar" : "Grabar");
            v.setOnClickPendingIntent(R.id.w_grabar, abrir(ctx, "voz", 1));
            v.setOnClickPendingIntent(R.id.w_anotar, abrir(ctx, "anotar", 2));
            v.setOnClickPendingIntent(R.id.w_ancla, abrir(ctx, "ancla", 3));
            gestor.updateAppWidget(id, v);
        }
    }

    /** Vuelve a pintar todos los widgets (por ejemplo, al entrar o salir del coche). */
    static void actualizar(Context ctx) {
        AppWidgetManager gestor = AppWidgetManager.getInstance(ctx);
        int[] ids = gestor.getAppWidgetIds(new ComponentName(ctx, ChispaWidget.class));
        if (ids != null && ids.length > 0) new ChispaWidget().onUpdate(ctx, gestor, ids);
    }

    static PendingIntent abrir(Context ctx, String accion, int codigo) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.putExtra(ChispaNativoPlugin.EXTRA_ACCION, accion);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, codigo, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

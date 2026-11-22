package es.astillerosoftware.cuaderna;

import android.app.Activity;
import android.content.Context;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cuaderna Plus con Google Play Billing.
 * La app solo pide los precios, abre la ventana de pago de Google y devuelve el justificante (purchaseToken).
 * Quien decide si alguien tiene Plus es el servidor: comprueba el justificante con Google y lo confirma (acknowledge).
 */
final class Compras {
    static final String PRODUCTO = "cuaderna_plus";

    private static BillingClient cliente;
    private static PluginCall compraEnCurso;
    private static final List<Runnable> esperando = new ArrayList<>();
    private static boolean conectando;

    private Compras() { }

    /** Conecta con Google Play (una vez) y después ejecuta «luego». Si falla, «error» recibe el motivo. */
    private static synchronized void conectar(Context ctx, Runnable luego, PluginCall call) {
        if (cliente == null) {
            cliente = BillingClient.newBuilder(ctx.getApplicationContext())
                    .setListener(Compras::trasCompra)
                    .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                    .build();
        }
        if (cliente.isReady()) { luego.run(); return; }
        esperando.add(luego);
        if (conectando) return;
        conectando = true;
        cliente.startConnection(new BillingClientStateListener() {
            @Override public void onBillingSetupFinished(BillingResult r) {
                List<Runnable> l;
                synchronized (Compras.class) { conectando = false; l = new ArrayList<>(esperando); esperando.clear(); }
                if (r.getResponseCode() == BillingClient.BillingResponseCode.OK) { for (Runnable x : l) x.run(); }
                else if (call != null) call.reject("Google Play no está disponible (" + r.getResponseCode() + ")");
            }
            @Override public void onBillingServiceDisconnected() {
                synchronized (Compras.class) { conectando = false; }
            }
        });
    }

    /** Busca el producto Plus en Google Play y llama a «hecho» con él (o rechaza la llamada). */
    private static void conProducto(Context ctx, PluginCall call, java.util.function.Consumer<ProductDetails> hecho) {
        conectar(ctx, () -> {
            QueryProductDetailsParams p = QueryProductDetailsParams.newBuilder()
                    .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PRODUCTO).setProductType(BillingClient.ProductType.SUBS).build()))
                    .build();
            cliente.queryProductDetailsAsync(p, (r, res) -> {
                List<ProductDetails> lista = res != null ? res.getProductDetailsList() : null;
                if (r.getResponseCode() != BillingClient.BillingResponseCode.OK || lista == null || lista.isEmpty()) {
                    call.reject("Plus todavía no está disponible en Google Play");
                    return;
                }
                hecho.accept(lista.get(0));
            });
        }, call);
    }

    /** Planes de Plus con sus precios, tal como los muestra Google Play en la moneda de cada persona. */
    static void planes(Context ctx, PluginCall call) {
        conProducto(ctx, call, pd -> {
            JSArray planes = new JSArray();
            List<ProductDetails.SubscriptionOfferDetails> ofertas = pd.getSubscriptionOfferDetails();
            if (ofertas != null) for (ProductDetails.SubscriptionOfferDetails o : ofertas) {
                JSObject x = new JSObject();
                x.put("plan", o.getBasePlanId());
                x.put("oferta", o.getOfferId() != null ? o.getOfferId() : "");
                x.put("token", o.getOfferToken());
                JSArray fases = new JSArray();
                for (ProductDetails.PricingPhase f : o.getPricingPhases().getPricingPhaseList()) {
                    JSObject y = new JSObject();
                    y.put("precio", f.getFormattedPrice());
                    y.put("micros", f.getPriceAmountMicros());
                    y.put("moneda", f.getPriceCurrencyCode());
                    y.put("periodo", f.getBillingPeriod());
                    y.put("ciclos", f.getBillingCycleCount());
                    fases.put(y);
                }
                x.put("fases", fases);
                planes.put(x);
            }
            JSObject r = new JSObject();
            r.put("planes", planes);
            call.resolve(r);
        });
    }

    /** Abre la ventana de pago de Google Play para el plan elegido. */
    static void comprar(Activity act, PluginCall call) {
        String token = call.getString("token", ""), cuenta = call.getString("cuenta", "");
        if (token == null || token.isEmpty()) { call.reject("Falta el plan"); return; }
        conProducto(act, call, pd -> act.runOnUiThread(() -> {
            BillingFlowParams.Builder b = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(pd).setOfferToken(token).build()));
            // identificador anónimo de la cuenta de Cuaderna: así el servidor sabe de quién es el pago, sin nombres ni correos
            if (cuenta != null && !cuenta.isEmpty()) b.setObfuscatedAccountId(cuenta);
            synchronized (Compras.class) {
                if (compraEnCurso != null) compraEnCurso.reject("Compra sustituida");
                compraEnCurso = call;
            }
            call.setKeepAlive(true);
            BillingResult r = cliente.launchBillingFlow(act, b.build());
            if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) terminar(r, null);
        }));
    }

    private static void trasCompra(BillingResult r, List<Purchase> compras) { terminar(r, compras); }

    private static synchronized void terminar(BillingResult r, List<Purchase> compras) {
        PluginCall call = compraEnCurso;
        compraEnCurso = null;
        if (call == null) return;
        call.setKeepAlive(false);
        int c = r.getResponseCode();
        JSObject o = new JSObject();
        if (c == BillingClient.BillingResponseCode.USER_CANCELED) { o.put("cancelado", true); call.resolve(o); return; }
        if (c == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) { o.put("yaTiene", true); call.resolve(o); return; }
        if (c != BillingClient.BillingResponseCode.OK) { call.reject("No se pudo completar el pago (" + c + ")"); return; }
        o.put("compras", lista(compras));
        call.resolve(o);
    }

    /** Suscripciones que Google Play tiene a nombre de esta persona (para recuperar Plus en otro móvil o tras reinstalar). */
    static void compras(Context ctx, PluginCall call) {
        conectar(ctx, () -> cliente.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                (r, l) -> {
                    if (r.getResponseCode() != BillingClient.BillingResponseCode.OK) { call.reject("No se pudieron leer las compras"); return; }
                    JSObject o = new JSObject();
                    o.put("compras", lista(l));
                    call.resolve(o);
                }), call);
    }

    private static JSArray lista(List<Purchase> compras) {
        JSArray a = new JSArray();
        if (compras != null) for (Purchase p : compras) {
            if (!p.getProducts().contains(PRODUCTO)) continue;
            JSObject x = new JSObject();
            x.put("token", p.getPurchaseToken());
            x.put("pagada", p.getPurchaseState() == Purchase.PurchaseState.PURCHASED);
            x.put("confirmada", p.isAcknowledged());
            a.put(x);
        }
        return a;
    }
}

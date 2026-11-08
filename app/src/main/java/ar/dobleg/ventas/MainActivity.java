package ar.dobleg.ventas;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONObject;

/**
 * Doble G Ventas: una ventana (WebView) que abre el sistema de la PC por http dentro del Wi-Fi.
 * La cámara se maneja con el lector nativo de Android: no depende de https ni de certificados.
 * El código leído se escribe en el campo del escáner del sistema (lo recibe window.__codigoNativo).
 */
public class MainActivity extends AppCompatActivity {

    private static final String URL_POR_DEFECTO = "http://192.168.1.46:8501";

    private WebView web;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> archivoPendiente;
    private boolean dialogoAbierto = false;

    private final ActivityResultLauncher<ScanOptions> lector =
            registerForActivityResult(new ScanContract(), resultado -> {
                if (resultado.getContents() != null) {
                    entregarCodigo(resultado.getContents());
                }
            });

    private final ActivityResultLauncher<Intent> selectorArchivos =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), resultado -> {
                if (archivoPendiente != null) {
                    archivoPendiente.onReceiveValue(
                            WebChromeClient.FileChooserParams.parseResult(resultado.getResultCode(), resultado.getData()));
                    archivoPendiente = null;
                }
            });

    /** Lo que el sistema web puede pedirle a la app. */
    public class Puente {
        @JavascriptInterface
        public void escanear() {
            runOnUiThread(() -> {
                ScanOptions o = new ScanOptions();
                o.setDesiredBarcodeFormats(ScanOptions.ONE_D_CODE_TYPES);
                o.setPrompt("Apuntá al código de barras");
                o.setBeepEnabled(true);
                o.setOrientationLocked(true);
                o.setCaptureActivity(CapturaVertical.class);
                lector.launch(o);
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("dobleg", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        web.addJavascriptInterface(new Puente(), "AndroidScanner");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    pedirDireccion("No se pudo conectar con la PC.\nRevisá que la PC esté prendida, con el sistema abierto y en el mismo Wi-Fi.");
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (archivoPendiente != null) {
                    archivoPendiente.onReceiveValue(null);
                }
                archivoPendiente = callback;
                try {
                    selectorArchivos.launch(params.createIntent());
                } catch (Exception e) {
                    archivoPendiente = null;
                    return false;
                }
                return true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (prefs.getString("url", null) == null) {
            pedirDireccion("Escribí la dirección de la PC (la ves en la ventana del sistema o en el Modo móvil).");
        } else {
            cargar();
        }
    }

    private void cargar() {
        String base = prefs.getString("url", URL_POR_DEFECTO);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        web.loadUrl(base + "/?movil=1");          // abre directo en Modo móvil
    }

    private void pedirDireccion(String motivo) {
        if (dialogoAbierto || isFinishing()) {
            return;
        }
        dialogoAbierto = true;
        final EditText campo = new EditText(this);
        campo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        campo.setText(prefs.getString("url", URL_POR_DEFECTO));
        new AlertDialog.Builder(this)
                .setTitle("Dirección de la PC")
                .setMessage(motivo + "\n\nEjemplo: http://192.168.1.46:8501")
                .setView(campo)
                .setCancelable(false)
                .setPositiveButton("Guardar y abrir", (d, w) -> {
                    String u = campo.getText().toString().trim();
                    if (!u.startsWith("http")) {
                        u = "http://" + u;
                    }
                    prefs.edit().putString("url", u).apply();
                    dialogoAbierto = false;
                    cargar();
                })
                .setNegativeButton("Reintentar", (d, w) -> {
                    dialogoAbierto = false;
                    if (prefs.getString("url", null) == null) {
                        prefs.edit().putString("url", URL_POR_DEFECTO).apply();
                    }
                    cargar();
                })
                .show();
    }

    /** Escribe el código leído en el sistema, como si lo hubiera tipeado un lector USB. */
    private void entregarCodigo(String codigo) {
        String js = "if (window.__codigoNativo) { window.__codigoNativo(" + JSONObject.quote(codigo) + "); }";
        web.evaluateJavascript(js, null);
        Toast.makeText(this, "Leído: " + codigo, Toast.LENGTH_SHORT).show();
    }
}

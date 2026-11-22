package com.atakmap.android.signaldf.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.signaldf.plugin.R;
import com.atakmap.coremap.log.Log;

/**
 * The radio's own web GUI, full width, inside ATAK.
 *
 * <p><b>Why this exists instead of a settings screen of our own.</b> The
 * obvious feature is a frequency box in the Signal DF pane, and on
 * krakensdr_doa 1.8.1 it cannot be built honestly. There is no settings API:
 * the node middleware on 8042 defines only {@code GET /}, {@code POST /doapost}
 * and {@code POST /prpost}, and it reads {@code settings.json} only to push it
 * out to KrakenRF's cloud -- it never takes settings in. The DSP carries no
 * {@code ext_upd_flag} in this version either, so writing that file from
 * outside changes nothing until a restart. The one remaining route is posting
 * to the Dash GUI's own {@code /_dash-update-component}, a browser-to-server
 * callback protocol rather than an API, which would break every time KrakenRF
 * moved a control.
 *
 * <p>So the radio's setup stays the radio's, and this shows the real thing:
 * every control, always complete, always current, nothing to maintain, and the
 * operator never leaves the map app. Signal DF does the map; the Kraken does
 * the radio.
 *
 * <p><b>Not {@code com.atakmap.android.gui.WebViewer}.</b> ATAK has its own
 * viewer and it is the wrong tool here: its bytecode never calls
 * {@code setJavaScriptEnabled}, so JavaScript is off, and it presents as an
 * {@code AlertDialog} built for simple help pages. The Kraken's GUI is a Dash
 * app and is entirely JavaScript, so it sits at "Loading..." forever there.
 * Measured on 5.8.0.3, 2026-09-27.
 *
 * <p>Built to match {@code samples/helloworld/WebViewDropDownReceiver}, and the
 * three things it does that are not obvious are all load-bearing: the WebView
 * is constructed with the <b>host's</b> context on the main thread via
 * {@code mapView.post} or it fails outright; it is added to an inflated
 * container rather than handed to {@code showDropDown} directly; and it is sent
 * to {@code about:blank} once at construction, without which the view stays
 * inconsistent across repeated opens.
 *
 * <p>Opens wide, because this is the one screen in the plugin where the pane is
 * the point rather than the map.
 */
public class RadioSetupDropDown extends DropDownReceiver implements OnStateListener {

    private static final String TAG = "SignalDF.RadioSetup";

    private final Context pluginContext;
    private final LinearLayout container;

    private WebView web;
    private String pending;

    public RadioSetupDropDown(final MapView mapView, final Context pluginContext) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.container = (LinearLayout) PluginLayoutInflater.inflate(
                pluginContext, R.layout.web_container, null);

        // The host's context, on the main thread. Both are requirements, not
        // style: the SDK sample is explicit that construction fails otherwise.
        mapView.post(new Runnable() {
            @Override
            public void run() {
                web = new WebView(mapView.getContext());
                web.setVerticalScrollBarEnabled(true);
                web.setHorizontalScrollBarEnabled(true);

                WebSettings s = web.getSettings();
                // The whole reason this class exists rather than ATAK's viewer.
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setDatabaseEnabled(true);
                s.setAllowContentAccess(true);
                s.setBuiltInZoomControls(true);
                s.setDisplayZoomControls(false);
                // The Kraken's GUI is laid out for a desktop browser; without
                // these its configuration tables render off the side of a phone.
                s.setUseWideViewPort(true);
                s.setLoadWithOverviewMode(true);
                // Deliberately NOT setAllowFileAccessFromFileURLs or
                // setAllowUniversalAccessFromFileURLs -- the SDK sample calls
                // those out as against the security guidelines, and nothing
                // here loads a file: URL.

                web.setWebChromeClient(new ChromeClient());
                web.setWebViewClient(new Client());
                // Without this first load the view stays inconsistent across
                // repeated opens. Straight from the sample.
                web.loadUrl("about:blank");

                web.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT));
                container.addView(web);

                if (pending != null) {
                    web.loadUrl(pending);
                    pending = null;
                }
            }
        });
    }

    /** Opens the radio's GUI at {@code url}, full width. */
    public void show(final String url) {
        if (web == null)
            // Construction is still queued on the main thread; load it when it lands.
            pending = url;
        else
            web.loadUrl(url);

        if (!isVisible()) {
            setRetain(true);
            // Full width less the handle: that strip is the grab handle, and a
            // pane covering it cannot be swiped closed again.
            if (!isPortrait())
                showDropDown(container, FULL_WIDTH - HANDLE_THICKNESS_LANDSCAPE,
                        FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, false, this);
            else
                showDropDown(container, FULL_WIDTH, FULL_HEIGHT
                        - HANDLE_THICKNESS_PORTRAIT, FULL_WIDTH, HALF_HEIGHT, false, this);
        }
    }

    private static class Client extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            Log.d(TAG, "loading " + url);
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.d(TAG, "loaded " + url);
            super.onPageFinished(view, url);
        }
    }

    private static class ChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            Log.d(TAG, m.message() + " (line " + m.lineNumber() + " of " + m.sourceId() + ")");
            return super.onConsoleMessage(m);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Opened directly by the Signal DF pane, not by broadcast.
    }

    @Override
    public void onDropDownVisible(boolean visible) {
        if (web == null)
            return;
        // A Dash app polls. A hidden page polling the radio over the same wifi
        // the bearings arrive on is a cost with nothing to show for it.
        if (visible)
            web.onResume();
        else
            web.onPause();
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
        if (web != null)
            web.onPause();
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    protected void disposeImpl() {
        if (web == null)
            return;
        // A WebView left attached keeps its own thread, and its parent, alive
        // past a plugin reload.
        container.removeView(web);
        web.stopLoading();
        web.destroy();
        web = null;
    }
}

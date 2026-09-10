package com.atakmap.android.featurelayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;

/**
 * Esri's sign-in page in an ATAK drop-down pane. A dialog cannot take keyboard input
 * inside ATAK (the input method keeps serving the main window), a drop-down can, which
 * is how the SDK's hello-world sample hosts a WebView. The web view is created on the
 * map view's context, as that sample insists.
 */
public class SignInDropDown extends DropDownReceiver implements OnStateListener {

    public interface Handler {
        /** The page reached the redirect; the pane is already closing. */
        void onRedirect(String url);

        /** The pane closed without reaching the redirect. */
        void onCancelled();
    }

    private final LinearLayout root;
    private WebView web;
    private Handler handler;
    private boolean redirected;
    private String redirectPrefix;
    /** ATAK's window mode before we asked it to resize for the keyboard. */
    private Integer savedSoftInput;

    public SignInDropDown(MapView mapView) {
        super(mapView);
        root = new LinearLayout(mapView.getContext());
        root.setOrientation(LinearLayout.VERTICAL);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView webView() {
        if (web == null) {
            web = new WebView(getMapView().getContext());
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setBuiltInZoomControls(true);
            web.getSettings().setDisplayZoomControls(false);
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    // Keep the focused field above the keyboard: the page scrolls it into view.
                    view.evaluateJavascript("document.addEventListener('focusin',function(e){"
                            + "setTimeout(function(){try{e.target.scrollIntoView({block:'center'});}catch(x){}},350);});", null);
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return intercept(request.getUrl().toString());
                }

                @Override
                @SuppressWarnings("deprecation")
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return intercept(url);
                }
            });
            web.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(web);
        }
        return web;
    }

    private boolean intercept(String url) {
        if (redirectPrefix == null || !url.startsWith(redirectPrefix))
            return false;
        redirected = true;
        final Handler h = handler;
        closeDropDown();
        if (h != null)
            h.onRedirect(url);
        return true;
    }

    /** Main thread. */
    public void show(String url, String redirectPrefix, Handler handler) {
        this.handler = handler;
        this.redirectPrefix = redirectPrefix;
        this.redirected = false;
        final WebView w = webView();
        w.loadUrl("about:blank");
        // ATAK's window normally ignores the keyboard, which in landscape hides the login
        // fields under it. Ask it to resize while this pane is up; restored on close.
        final android.view.Window win = activityWindow();
        if (win != null && savedSoftInput == null) {
            savedSoftInput = win.getAttributes().softInputMode;
            win.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        showDropDown(root, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, FULL_HEIGHT, false, this);
        w.loadUrl(url);
        w.requestFocus();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
    }

    private android.view.Window activityWindow() {
        final Context c = getMapView().getContext();
        return c instanceof android.app.Activity ? ((android.app.Activity) c).getWindow() : null;
    }

    @Override
    public void onDropDownClose() {
        final android.view.Window win = activityWindow();
        if (win != null && savedSoftInput != null) {
            win.setSoftInputMode(savedSoftInput);
            savedSoftInput = null;
        }
        final Handler h = handler;
        handler = null;
        if (web != null)
            web.loadUrl("about:blank");
        if (!redirected && h != null)
            h.onCancelled();
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    protected void disposeImpl() {
        if (web != null) {
            web.destroy();
            web = null;
        }
    }
}

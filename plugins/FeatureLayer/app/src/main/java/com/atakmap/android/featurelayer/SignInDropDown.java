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
    /** How far the keyboard reaches over the pane, last we measured; 0 when it is down. */
    private int keyboardOverlap;
    /**
     * ATAK runs full screen, and a full-screen window ignores ADJUST_RESIZE: on a Galaxy
     * Tab S7+ the keyboard covered the login fields (operator, 2026-11-02). So the pane
     * measures the keyboard itself, from what is left of the screen, and pads the web view
     * up out of its way.
     */
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener keyboardWatch =
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    final android.graphics.Rect visible = new android.graphics.Rect();
                    root.getWindowVisibleDisplayFrame(visible);
                    final int[] at = new int[2];
                    root.getLocationOnScreen(at);
                    final int bottom = at[1] + root.getHeight(); // the pane is a fixed size; padding is inside it
                    // Less than a sixth of the screen is a nav bar or a gesture strip, not a keyboard.
                    int overlap = Math.max(0, bottom - visible.bottom);
                    if (overlap < root.getRootView().getHeight() / 6)
                        overlap = 0;
                    if (overlap == keyboardOverlap)
                        return;
                    keyboardOverlap = overlap;
                    root.setPadding(0, 0, 0, overlap);
                    // The field the keyboard came up for is centered again in the smaller view.
                    if (overlap > 0 && web != null)
                        web.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (web != null)
                                    web.evaluateJavascript("try{var a=document.activeElement;"
                                            + "if(a&&a!==document.body)a.scrollIntoView({block:'center'});}catch(x){}", null);
                            }
                        }, 150);
                }
            };

    public SignInDropDown(MapView mapView) {
        super(mapView);
        root = new LinearLayout(mapView.getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.getViewTreeObserver().addOnGlobalLayoutListener(keyboardWatch);
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
        keyboardOverlap = 0;
        root.setPadding(0, 0, 0, 0);
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
        root.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardWatch);
        if (web != null) {
            web.destroy();
            web = null;
        }
    }
}

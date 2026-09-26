package com.atakmap.android.ipaws.data;

import com.atakmap.android.ipaws.net.Http;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Which state the phone is in, asked on a fresh install until it is answered.
 *
 * <p>The operator's answer for what a new install should show was "the state the phone
 * is in", so that the plugin does something the moment it loads and the filter pane has
 * something in it to change. {@code /points/{lat},{lon}} answers it directly.
 *
 * <p>Asked only while nothing is selected, and again each minute while the question
 * cannot be answered -- no fix yet, or no network, which is exactly the moment a first
 * start in the field tends to happen. It never overrides a choice, and it
 * never runs again after the operator has picked -- a filter that quietly re-homes
 * itself when a strike team drives across a state line is the opposite of what they
 * wanted, which was a filter that stays where it was put.
 */
public final class HomeState {

    private static final String TAG = "IPAWS";

    public interface Found {
        void onState(String stateCode);

        /**
         * The service answered, and the position is outside every state it covers --
         * an answer, so the caller stops asking. Not called when the question could
         * not be asked at all (no fix, no network): that is worth asking again.
         */
        void onOutside();

        /** No usable fix, or no answer from the service. Worth asking again later. */
        void onUnavailable();
    }

    private HomeState() {
    }

    /** A position that is not a real fix; ATAK reports 0,0 before it has one. */
    public static boolean isUsable(GeoPoint p) {
        return p != null && p.isValid()
                && !(Math.abs(p.getLatitude()) < 0.0001 && Math.abs(p.getLongitude()) < 0.0001);
    }

    public static void resolve(final GeoPoint at, final Found cb) {
        if (!isUsable(at)) {
            Log.d(TAG, "no usable position, so no home state yet");
            cb.onUnavailable();
            return;
        }
        final String url = String.format(Locale.US,
                "https://api.weather.gov/points/%.4f,%.4f", at.getLatitude(), at.getLongitude());
        Http.get(url, new Http.Callback() {
            @Override
            public void onSuccess(byte[] body) {
                try {
                    final JSONObject doc = new JSONObject(
                            new String(body, FileSystemUtils.UTF8_CHARSET));
                    final JSONObject props = doc.optJSONObject("properties");
                    final JSONObject rel = props == null ? null
                            : props.optJSONObject("relativeLocation");
                    final JSONObject relProps = rel == null ? null
                            : rel.optJSONObject("properties");
                    final String state = relProps == null ? null
                            : relProps.optString("state", null);
                    if (state != null && Areas.isKnown(state)) {
                        Log.d(TAG, "home state resolved as " + state);
                        cb.onState(state);
                    } else {
                        // Outside the feed's coverage, which is not an error: the
                        // operator picks, and the pane already says to.
                        Log.d(TAG, "position is outside the service's states");
                        cb.onOutside();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "could not read the point lookup", e);
                    cb.onUnavailable();
                }
            }

            @Override
            public void onFailure(int status, String error) {
                Log.d(TAG, "home state lookup unavailable (" + error + ")");
                cb.onUnavailable();
            }
        });
    }
}

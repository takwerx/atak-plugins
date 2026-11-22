package com.atakmap.android.firecast;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.dropdown.DropDown.OnStateListener;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.firecast.plugin.R;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.map.layer.feature.AttributeSet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * What one detection means, in words, and what it does not.
 *
 * <p>This panel is the plugin's argument. A heat detection is the most authoritative
 * looking thing on an ATAK screen and it is a probabilistic statement about a box - so
 * every one of them gets its size, its age, its provenance and an explicit list of what
 * it does not say, and the list is not the small print at the bottom, it is a section
 * with a heading like every other.
 */
public class DetectionDetailsReceiver extends DropDownReceiver implements OnStateListener {

    private static final String TAG = "FireCast";

    private final Context pluginContext;
    private final DetectionFeed feed;
    private final View view;
    private final LinearLayout body;
    private HeatDetection current;

    public DetectionDetailsReceiver(MapView mapView, Context pluginContext, DetectionFeed feed) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.feed = feed;
        this.view = PluginLayoutInflater.inflate(pluginContext, R.layout.details, null);
        DetectionFeed.coveredBy(view);
        this.body = view.findViewById(R.id.details_body);
        // Back closes the panel and leaves the map alone; ATAK's own close is not where a
        // thumb expects it.
        view.findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeDropDown();
                feed.reopenPane();
            }
        });
        view.findViewById(R.id.btn_goto).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (current != null)
                    feed.goTo(current);
            }
        });
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        HeatDetection d = feed.byUid(intent.getStringExtra("detectionUid"));
        if (d == null) {
            // From the radial menu: the map item carries the detection's key.
            final String uid = intent.getStringExtra("targetUID");
            final MapItem item = uid == null ? null : getMapView().getRootGroup().deepFindItem("uid", uid);
            if (item != null)
                d = feed.byUid(item.getMetaString("firecast_uid", null));
        }
        if (d == null) {
            Log.d(TAG, "details: no detection for that item");
            return;
        }
        show(d);
    }

    /** Opens the panel on one detection. Main thread. */
    public void show(HeatDetection d) {
        current = d;
        final long now = System.currentTimeMillis();
        ((TextView) view.findViewById(R.id.details_title))
                .setText(d.incidentName != null ? d.incidentName : d.sourceTitle + " heat detection");
        ((TextView) view.findViewById(R.id.details_subtitle))
                .setText(d.sensor + "  ·  " + d.ageText(now) + " old  ·  " + DetectionStyles.ageBand(d.ageMillis(now)));
        body.removeAllViews();

        section("What it covers", coverage(d));
        section("When", when(d, now));
        final String heat = heat(d);
        if (heat != null)
            section("Heat", heat);
        final String conf = confidence(d);
        if (conf != null)
            section("Confidence", conf);
        final String flags = flags(d);
        if (flags != null)
            section("Flagged", flags);
        if ("feds".equals(d.sourceId) && d.trackingId != null)
            body.addView(runFromPerimeter(d));
        bullets("What this does not mean", d.caveats);
        final String raw = raw(d);
        if (raw != null)
            section("As the service served it", raw);

        showDropDown(view, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT, this);
    }

    /** Opens the model page with this outline as the start, like picking it from the Start list. */
    private View runFromPerimeter(final HeatDetection d) {
        final android.widget.Button b = (android.widget.Button) PluginLayoutInflater.inflate(pluginContext,
                R.layout.model_action_row, null).findViewById(R.id.action);
        ((android.view.ViewGroup) b.getParent()).removeView(b);
        b.setText("Run a Model from this perimeter");
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final com.atakmap.map.layer.feature.geometry.Envelope e = d.footprint.mapped == null ? null
                        : d.footprint.mapped.getEnvelope();
                if (e == null)
                    return;
                final Intent i = new Intent(com.atakmap.android.firecast.model.ModelReceiver.ACTION)
                        .putExtra("fedsId", d.trackingId).putExtra("fedsAt", d.acquired)
                        .putExtra("fedsBox", new double[] { e.minY, e.minX, e.maxY, e.maxX })
                        .putExtra("fedsDays", d.persistenceHours == null ? Double.NaN : d.persistenceHours / 24d);
                closeDropDown();
                com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(i);
            }
        });
        return b;
    }

    private String coverage(HeatDetection d) {
        final StringBuilder sb = new StringBuilder();
        if ("feds".equals(d.sourceId)) {
            // NASA's outline is simplified to follow its 375 m steps; "exactly as served"
            // was RADR's sentence and not true here (operator, 2026-10-30).
            sb.append(d.incidentName != null ? d.incidentName.replace("NASA perimeter, ", "") + " inside NASA's outline of the fire.\n"
                    : "NASA's outline of the fire.\n");
        } else if (d.footprint.kind == Footprint.Kind.MAPPED) {
            sb.append("A mapped area, drawn exactly as the service served it.\n");
        } else {
            sb.append(d.footprint.sizeText()).append(" on the ground");
            if (d.footprint.kind == Footprint.Kind.PIXEL) {
                if (d.pixelCount != null && d.pixelCount > 1)
                    sb.append(", across the ").append(d.pixelCount).append(" pixels hot in the latest scan");
            } else if (d.pixelCount != null && d.pixelCount > 1)
                sb.append(", per sensor pixel, and the record merges ").append(d.pixelCount).append(" of them");
            sb.append(".\n");
        }
        sb.append("How that is known: ").append(d.footprint.basis).append(".\n");
        switch (d.footprint.kind) {
            case MEASURED:
            case DERIVED:
            case NOMINAL:
                sb.append("Something inside this box was hot when the satellite looked. The center of the box is the center of the sensor pixel, not the fire.");
                break;
            case PIXEL:
                sb.append("Something inside each square was hot when the satellite looked. The squares are the satellite's pixels, not the fire's edge.");
                break;
            default:
                sb.append("feds".equals(d.sourceId)
                        ? "The outline is every edge the fire had reached by that pass, burning or not, drawn with its corners thinned but its shape kept."
                        : "The outline is the burned or radiating ground itself, at the time of the pass.");
                break;
        }
        return sb.toString();
    }

    private String when(HeatDetection d, long now) {
        final StringBuilder sb = new StringBuilder();
        if (d.acquired > 0) {
            sb.append(utc(d.acquired)).append("  (").append(local(d.acquired)).append(" local)\n");
            sb.append(d.ageText(now)).append(" ago").append(d.day == null ? "" : d.day ? ", a daytime pass" : ", a night pass");
        } else {
            sb.append("The record carried no usable time, so its age is unknown. Treat it as old.");
        }
        if (d.persistenceHours != null) {
            sb.append("\nDetected in this place for ");
            sb.append(d.persistenceHours < 1
                    ? Math.round(d.persistenceHours * 60) + " min"
                    : String.format(Locale.US, "%.1f h", d.persistenceHours));
            sb.append(d.persistenceHours < 0.5
                    ? " - short enough that one hot scan is as likely as a fire."
                    : " - something has been burning here, not one odd scan.");
        }
        if (d.trackingId != null)
            sb.append("\nThe feed follows it as ").append(d.trackingId).append('.');
        return sb.toString();
    }

    private String heat(HeatDetection d) {
        final List<String> parts = new ArrayList<>();
        if (d.frpMw != null)
            parts.add("Heat " + d.frpNumber()
                    + " - fire radiative power, the energy the sensor saw radiating from the pixel."
                    + " A burn pile reads a few MW, a running wildfire hundreds; cloud or smoke makes it read low.");
        if (d.brightnessK != null)
            parts.add("Brightness temperature " + d.brightnessText()
                    + " - the whole pixel's apparent temperature, not the flame's.");
        if (d.classification != null)
            parts.add("The feed classes it: " + d.classification + ".");
        return parts.isEmpty() ? null : join(parts);
    }

    private String confidence(HeatDetection d) {
        if (d.confidenceText == null && d.confidence == HeatDetection.Confidence.UNKNOWN)
            return null;
        final StringBuilder sb = new StringBuilder();
        sb.append(d.confidenceText == null ? d.confidence.name().toLowerCase(Locale.US) : d.confidenceText);
        sb.append('\n');
        switch (d.confidence) {
            case HIGH:
                sb.append("The feed is confident the signal is a real hot spot. It says nothing about whether it is a wildfire.");
                break;
            case NOMINAL:
                sb.append("The ordinary case: the signal passed the detection test without being a strong one.");
                break;
            case LOW:
                sb.append("A weak or marginal detection. Worth knowing about, not worth acting on alone.");
                break;
            default:
                sb.append("The feed states no confidence for this kind of record.");
                break;
        }
        return sb.toString();
    }

    private String flags(HeatDetection d) {
        if (!d.suspect())
            return null;
        final List<String> parts = new ArrayList<>();
        if (d.inIndustrialMask)
            parts.add("This falls inside NIFC's static industrial heat source mask - a place that reads hot most days. "
                    + "The mask is a yearly snapshot and knows nothing about today: a real fire at a plant is still a fire.");
        if (d.classedNonFire)
            parts.add("The feed's own class for this record says it is probably not a wildfire.");
        return join(parts);
    }

    private String raw(HeatDetection d) {
        final AttributeSet a = d.raw;
        if (a == null)
            return null;
        final List<String> keys = new ArrayList<>(a.getAttributeNames());
        Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
        final StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (k.startsWith("_"))
                continue;
            String v;
            try {
                v = a.getStringAttribute(k);
            } catch (Exception e) {
                continue;
            }
            if (v == null || v.trim().isEmpty())
                continue;
            sb.append(k).append(": ").append(v).append('\n');
        }
        return sb.length() == 0 ? null : sb.toString().trim();
    }

    private static String join(List<String> parts) {
        final StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(p);
        }
        return sb.toString();
    }

    private static String utc(long ms) {
        final SimpleDateFormat f = new SimpleDateFormat("dd MMM HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms)) + "Z";
    }

    private static String local(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    // ---- section rendering --------------------------------------------------------

    private void section(String heading, String text) {
        if (text == null || text.trim().isEmpty())
            return;
        body.addView(headingView(heading));
        body.addView(bodyView(text));
    }

    private void bullets(String heading, String[] lines) {
        if (lines == null || lines.length == 0)
            return;
        body.addView(headingView(heading));
        for (String line : lines) {
            if (line == null || line.trim().isEmpty())
                continue;
            final TextView t = bodyView("•  " + line.trim());
            final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            lp.bottomMargin = dp(6);
            t.setLayoutParams(lp);
            body.addView(t);
        }
    }

    private TextView headingView(String text) {
        final TextView t = new TextView(view.getContext());
        t.setText(text);
        // Cyan, bold and full strength: the faint small-caps heading of the pane's
        // controls was too easy to read past on a page of prose, and the operator
        // could not see where one section ended (2026-10-11).
        t.setAllCaps(true);
        t.setTextSize(12f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF00E5FF);
        t.setGravity(Gravity.START);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView bodyView(String text) {
        final TextView t = new TextView(view.getContext());
        t.setText(text);
        t.setTextSize(14f);
        t.setTextColor(Color.WHITE);
        t.setLineSpacing(dp(4), 1f);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * view.getResources().getDisplayMetrics().density);
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
    public void onDropDownClose() {
    }

    @Override
    protected void disposeImpl() {
    }
}

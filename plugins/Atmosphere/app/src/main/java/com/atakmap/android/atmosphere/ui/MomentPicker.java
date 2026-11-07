package com.atakmap.android.atmosphere.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.DatePicker;
import android.widget.TimePicker;

import java.util.Calendar;

/**
 * A moment and a stretch before it: the day, then the time, then how long before it.
 * The rain before a fire started (operator, 2026-10-13: "what was rain in last 24 hours
 * before the fire?"), asked the same way by Rain Gauges and Rain Totals. The phone's
 * own time zone; today is the latest day offered. Every dialog on ATAK's context.
 */
final class MomentPicker {

    interface Picked {
        void picked(long millis, int hours);
    }

    interface Words {
        /** "24 h before Sun Oct 11, 1:57 pm", what a stretch reads as in the list. */
        String of(int hours, long millis);
    }

    /** @param earliest the first moment offered, or 0 for any; an earlier time on its day is taken as it */
    static void pick(final Context ctx, long current, final long earliest, final int[] hours, final int currentHours,
            final String title, final Words words, final Picked picked) {
        final Calendar c = Calendar.getInstance();
        if (current > 0)
            c.setTimeInMillis(current);
        final DatePickerDialog d = new DatePickerDialog(ctx, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, final int year, final int month, final int day) {
                new TimePickerDialog(ctx, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker t, int hour, int minute) {
                        final Calendar at = Calendar.getInstance();
                        at.clear();
                        at.set(year, month, day, hour, minute);
                        length(ctx, Math.max(earliest, at.getTimeInMillis()), hours, currentHours, title, words,
                                picked);
                    }
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                        android.text.format.DateFormat.is24HourFormat(ctx)).show();
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        d.getDatePicker().setMaxDate(System.currentTimeMillis());
        if (earliest > 0)
            d.getDatePicker().setMinDate(earliest);
        d.show();
    }

    private static void length(Context ctx, final long at, final int[] hours, int currentHours, String title,
            Words words, final Picked picked) {
        final String[] names = new String[hours.length];
        int checked = 0;
        for (int i = 0; i < hours.length; i++) {
            names[i] = words.of(hours[i], at);
            if (hours[i] == currentHours)
                checked = i;
        }
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        picked.picked(at, hours[which]);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private MomentPicker() {
    }
}

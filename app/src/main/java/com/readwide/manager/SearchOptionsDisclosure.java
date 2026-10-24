package com.readwide.manager;

import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Presentation only: collapsing search options must not change the matcher or preferences. */
final class SearchOptionsDisclosure {
    private SearchOptionsDisclosure() { }

    static void bind(TextView toggle, LinearLayout content, CheckBox... conditions) {
        final CharSequence label = toggle.getText();
        boolean active = false;
        for (CheckBox condition : conditions) active |= condition.isChecked();
        content.setVisibility(active ? View.VISIBLE : View.GONE);
        Runnable refresh = () -> {
            boolean expanded = content.getVisibility() == View.VISIBLE;
            StringBuilder text = new StringBuilder(expanded ? "\u25be " : "\u25b8 ").append(label);
            if (!expanded) {
                for (CheckBox condition : conditions) {
                    if (condition.isChecked()) text.append(" · ").append(condition.getText());
                }
            }
            toggle.setText(text);
        };
        toggle.setFocusable(true);
        toggle.setOnClickListener(v -> {
            content.setVisibility(content.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            refresh.run();
        });
        refresh.run();
    }
}

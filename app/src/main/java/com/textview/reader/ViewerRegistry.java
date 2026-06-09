package com.textview.reader;

import android.app.Activity;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

final class ViewerRegistry {
    private static final Set<WeakReference<Activity>> ACTIVE_VIEWERS = new LinkedHashSet<>();

    private ViewerRegistry() {}

    static synchronized void activate(Activity activity) {
        cleanupLocked();
        for (WeakReference<Activity> ref : ACTIVE_VIEWERS) {
            Activity other = ref.get();
            if (other != null && other != activity && !other.isFinishing()) {
                other.finish();
            }
        }
        ACTIVE_VIEWERS.add(new WeakReference<>(activity));
        cleanupLocked();
    }

    static synchronized void unregister(Activity activity) {
        Iterator<WeakReference<Activity>> it = ACTIVE_VIEWERS.iterator();
        while (it.hasNext()) {
            Activity a = it.next().get();
            if (a == null || a == activity) {
                it.remove();
            }
        }
    }

    private static void cleanupLocked() {
        Iterator<WeakReference<Activity>> it = ACTIVE_VIEWERS.iterator();
        while (it.hasNext()) {
            Activity a = it.next().get();
            if (a == null || a.isFinishing()) {
                it.remove();
            }
        }
    }
}

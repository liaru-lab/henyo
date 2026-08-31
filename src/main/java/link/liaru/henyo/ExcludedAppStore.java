package link.liaru.henyo;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ExcludedAppStore {
    private static final String PREFS = "targeting";
    private static final String KEY_PACKAGES = "excluded_packages";

    private ExcludedAppStore() {}

    public static Set<String> load(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return Collections.unmodifiableSet(new HashSet<>(
                preferences.getStringSet(KEY_PACKAGES, Collections.emptySet())));
    }

    public static void save(Context context, Set<String> packageNames) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_PACKAGES, new HashSet<>(packageNames))
                .apply();
    }
}

package nc.ticagou.pointage;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.WindowManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Mode kiosque de la tablette de pointage (ADR 0023, maquettes P3 à P5) : la tablette reste sur Ti'Cagou.
 * <ul>
 *   <li>tablette gérée (Ti'Cagou propriétaire de l'appareil, Managed Google Play) : verrouillage complet, sans confirmation ;</li>
 *   <li>sinon : épinglage d'écran d'Android, confirmé une fois par la directrice.</li>
 * </ul>
 * L'écran reste allumé tant que le mode est actif, et le choix est gardé : au redémarrage, l'application le rétablit.
 * Sortir du mode demande le code d'une personne de la direction, vérifié par le serveur avant l'appel à {@code disable}.
 */
@CapacitorPlugin(name = "KioskMode")
public class KioskModePlugin extends Plugin {

    static final String PREFERENCES = "ticagou.kiosque";
    static final String ENABLED = "actif";

    static boolean wanted(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }

    /** Rétablit le mode au démarrage de l'activité (redémarrage de la tablette, application relancée). */
    static void restore(Activity activity) {
        if (wanted(activity)) {
            apply(activity, true);
        }
    }

    private static void apply(Activity activity, boolean on) {
        activity.runOnUiThread(() -> {
            if (on) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try {
                    activity.startLockTask();
                } catch (IllegalArgumentException | SecurityException ignored) {
                    // Épinglage refusé par le système : l'écran reste allumé, la directrice réessaiera.
                }
            } else {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try {
                    activity.stopLockTask();
                } catch (IllegalArgumentException | SecurityException ignored) {
                    // Déjà sorti.
                }
            }
        });
    }

    private void remember(boolean on) {
        SharedPreferences.Editor editor = getContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit();
        editor.putBoolean(ENABLED, on).apply();
    }

    @PluginMethod
    public void enable(PluginCall call) {
        remember(true);
        apply(getActivity(), true);
        call.resolve(state());
    }

    @PluginMethod
    public void disable(PluginCall call) {
        remember(false);
        apply(getActivity(), false);
        call.resolve(state());
    }

    @PluginMethod
    public void status(PluginCall call) {
        call.resolve(state());
    }

    private JSObject state() {
        ActivityManager manager = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        DevicePolicyManager policy = (DevicePolicyManager) getContext().getSystemService(Context.DEVICE_POLICY_SERVICE);
        JSObject result = new JSObject();
        result.put("enabled", wanted(getContext()));
        result.put("locked", manager != null && manager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE);
        result.put("managed", policy != null && policy.isDeviceOwnerApp(getContext().getPackageName()));
        return result;
    }
}

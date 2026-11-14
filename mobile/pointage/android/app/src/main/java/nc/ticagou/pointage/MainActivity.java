package nc.ticagou.pointage;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

/** Application Ti'Cagou Pointage : plugin local du mode kiosque, rétabli à chaque démarrage (ADR 0023). */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(KioskModePlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        KioskModePlugin.restore(this);
    }
}

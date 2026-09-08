package tw.tib.financisto.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import tw.tib.financisto.R;
import tw.tib.financisto.ai.AiDefaultAccountProvider;
import tw.tib.financisto.utils.MyPreferences;

public class BlotterActivity extends AppCompatActivity implements AiDefaultAccountProvider {
    public BlotterActivity() {
        super(R.layout.fragment_container);
    }

    /** 帳戶明細頁 → 把當前 filter 的帳戶露給 AI 浮動鈕當語音記帳預設帳戶。 */
    @Override
    public long getAiDefaultAccountId() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container_view);
        return (f instanceof BlotterFragment) ? ((BlotterFragment) f).getAiDefaultAccountId() : -1;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(MyPreferences.switchLocale(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (MyPreferences.isSecureWindow()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }

        Intent intent = getIntent();
        Bundle args = null;
        if (intent != null) {
            args = intent.getExtras();
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.fragment_container_view, BlotterFragment.class, args)
                    .commit();
        }
    }
}

package juloo.keyboard2;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.widget.Toast;
import juloo.keyboard2.rime.RimeEngine;

public class SettingsActivity extends PreferenceActivity
{
  @Override
  public void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    // The preferences can't be read when in direct-boot mode. Avoid crashing
    // and don't allow changing the settings.
    // Run the config migration on this prefs as it might be different from the
    // one used by the keyboard, which have been migrated.
    try
    {
      Config.migrate(getPreferenceManager().getSharedPreferences());
    }
    catch (Exception _e) { fallbackEncrypted(); return; }
    addPreferencesFromResource(R.xml.settings);

    boolean foldableDevice = FoldStateTracker.isFoldableDevice(this);
    findPreference("margin_bottom_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("margin_bottom_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_portrait_unfolded").setEnabled(foldableDevice);
    findPreference("horizontal_margin_landscape_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_unfolded").setEnabled(foldableDevice);
    findPreference("keyboard_height_landscape_unfolded").setEnabled(foldableDevice);
    setup_rime_preferences();
  }

  void setup_rime_preferences()
  {
    final RimeEngine engine = RimeEngine.instance(this);
    findPreference("rime_deploy").setOnPreferenceClickListener(
        new Preference.OnPreferenceClickListener() {
          public boolean onPreferenceClick(Preference p)
          {
            toast(R.string.rime_deploy_started);
            engine.add_listener(new RimeEngine.Listener() {
              public void on_rime_state_changed(RimeEngine.State s)
              {
                if (s == RimeEngine.State.DEPLOYING)
                  return;
                engine.remove_listener(this);
                toast(s == RimeEngine.State.READY ?
                    R.string.rime_deploy_done : R.string.rime_deploy_failed);
              }
            });
            if (engine.state() == RimeEngine.State.STOPPED)
              engine.start();
            else
              engine.redeploy();
            return true;
          }
        });
    findPreference("rime_sync").setOnPreferenceClickListener(
        new Preference.OnPreferenceClickListener() {
          public boolean onPreferenceClick(Preference p)
          {
            engine.sync_user_data(new RimeEngine.Callback() {
              public void done(boolean ok)
              {
                toast(ok ? R.string.rime_sync_done : R.string.rime_sync_failed);
              }
            });
            return true;
          }
        });
    final String user_dir = engine.user_data_dir().getAbsolutePath();
    Preference dir_pref = findPreference("rime_user_dir");
    dir_pref.setSummary(user_dir);
    dir_pref.setOnPreferenceClickListener(
        new Preference.OnPreferenceClickListener() {
          public boolean onPreferenceClick(Preference p)
          {
            ClipboardManager cm =
              (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Rime", user_dir));
            toast(R.string.rime_path_copied);
            return true;
          }
        });
  }

  void toast(int msg)
  {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
  }

  void fallbackEncrypted()
  {
    // Can't communicate with the user here.
    finish();
  }

  protected void onStop()
  {
    DirectBootAwarePreferences
      .copy_preferences_to_protected_storage(this,
          getPreferenceManager().getSharedPreferences());
    super.onStop();
  }
}

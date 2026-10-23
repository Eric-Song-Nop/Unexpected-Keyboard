package juloo.keyboard2.rime;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Manage the lifecycle of librime: extracting the data files, deploying
    and the input session. Every methods must be called from the main thread
    except where noted. Deployments run on a background thread. */
public final class RimeEngine
{
  static final String TAG = "juloo.keyboard2.rime";

  public static enum State
  {
    /** [start()] hasn't been called. */
    STOPPED,
    /** Data is being extracted or deployed. */
    DEPLOYING,
    READY,
    /** Deployment failed. The session might still be usable. */
    FAILED,
    /** librime couldn't be loaded on this device. */
    UNAVAILABLE,
  }

  public static interface Listener
  {
    /** Called on the main thread when the state changes. */
    public void on_rime_state_changed(State state);
  }

  static RimeEngine _instance = null;

  public static RimeEngine instance(Context ctx)
  {
    if (_instance == null)
      _instance = new RimeEngine(ctx.getApplicationContext());
    return _instance;
  }

  final Context _context;
  final Handler _handler;
  final List<Listener> _listeners = new ArrayList<Listener>();
  State _state = State.STOPPED;
  long _session = 0;
  boolean _initialized = false;

  RimeEngine(Context ctx)
  {
    _context = ctx;
    _handler = new Handler(Looper.getMainLooper());
  }

  public State state() { return _state; }

  public boolean is_ready()
  {
    return (_state == State.READY || _state == State.FAILED) && _session != 0;
  }

  public void add_listener(Listener l)
  {
    if (!_listeners.contains(l))
      _listeners.add(l);
  }

  public void remove_listener(Listener l)
  {
    _listeners.remove(l);
  }

  /** The directory where users can put their own schemas and configuration
      files. Accessible from a file manager on most devices. */
  public File user_data_dir()
  {
    File d = _context.getExternalFilesDir("rime");
    if (d == null)
      d = new File(_context.getFilesDir(), "rime-user");
    d.mkdirs();
    return d;
  }

  File shared_data_dir()
  {
    return new File(_context.getFilesDir(), "rime-shared");
  }

  File log_dir()
  {
    File d = new File(_context.getCacheDir(), "rime-log");
    d.mkdirs();
    return d;
  }

  /** Initialize and deploy if needed. Does nothing if already started. */
  public void start()
  {
    if (_state != State.STOPPED)
      return;
    if (!Rime.is_available())
    {
      set_state(State.UNAVAILABLE);
      return;
    }
    // Files are not accessible before the device is unlocked.
    if (VERSION.SDK_INT >= 24)
    {
      UserManager um = (UserManager)_context.getSystemService(Context.USER_SERVICE);
      if (um != null && !um.isUserUnlocked())
        return;
    }
    run_deploy(false);
  }

  /** Re-deploy the configuration, for example after the user modified their
      files. */
  public void redeploy()
  {
    if (_state == State.UNAVAILABLE || _state == State.DEPLOYING)
      return;
    run_deploy(true);
  }

  /** Synchronize the user dictionaries with the sync directory. [on_done] is
      called on the main thread with the result. */
  public void sync_user_data(final Callback on_done)
  {
    if (!is_ready())
    {
      if (on_done != null)
        on_done.done(false);
      return;
    }
    destroy_session();
    set_state(State.DEPLOYING);
    new Thread(new Runnable() {
      public void run()
      {
        final boolean ok = Rime.nativeSyncUserData();
        _handler.post(new Runnable() {
          public void run()
          {
            _session = Rime.nativeCreateSession();
            set_state(State.READY);
            if (on_done != null)
              on_done.done(ok);
          }
        });
      }
    }, "rime-sync").start();
  }

  public static interface Callback
  {
    public void done(boolean success);
  }

  void run_deploy(final boolean force)
  {
    destroy_session();
    set_state(State.DEPLOYING);
    final boolean was_initialized = _initialized;
    new Thread(new Runnable() {
      public void run()
      {
        boolean ok;
        try
        {
          boolean data_updated = extract_assets_if_needed();
          if (was_initialized)
            Rime.nativeFinalize();
          Rime.nativeInit(shared_data_dir().getAbsolutePath(),
              user_data_dir().getAbsolutePath(),
              log_dir().getAbsolutePath());
          ok = Rime.nativeDeploy(force || data_updated || !was_initialized);
        }
        catch (Exception e)
        {
          Log.e(TAG, "Deployment failed", e);
          ok = false;
        }
        final boolean ok_ = ok;
        _handler.post(new Runnable() {
          public void run()
          {
            _initialized = true;
            _session = Rime.nativeCreateSession();
            if (_session == 0)
              set_state(State.FAILED);
            else
              set_state(ok_ ? State.READY : State.FAILED);
          }
        });
      }
    }, "rime-deploy").start();
  }

  void destroy_session()
  {
    if (_session != 0)
    {
      Rime.nativeDestroySession(_session);
      _session = 0;
    }
  }

  void set_state(State s)
  {
    _state = s;
    for (Listener l : new ArrayList<Listener>(_listeners))
      l.on_rime_state_changed(s);
  }

  /** Copy the bundled data files to [shared_data_dir()] when the app is
      installed or updated. Returns [true] if files were copied. Called from
      the deployment thread. */
  boolean extract_assets_if_needed() throws Exception
  {
    File shared = shared_data_dir();
    File stamp = new File(shared, ".version");
    String version = app_version();
    if (stamp.exists() && version.equals(read_file(stamp)))
      return false;
    AssetManager assets = _context.getAssets();
    BufferedReader list = new BufferedReader(new InputStreamReader(
          assets.open("rime/files.txt"), "UTF-8"));
    String path;
    byte[] buf = new byte[65536];
    while ((path = list.readLine()) != null)
    {
      if (path.length() == 0)
        continue;
      // Paths are prefixed with "shared/".
      File dst = new File(shared, path.substring(path.indexOf('/') + 1));
      dst.getParentFile().mkdirs();
      InputStream in = assets.open("rime/" + path);
      OutputStream out = new FileOutputStream(dst);
      int n;
      while ((n = in.read(buf)) > 0)
        out.write(buf, 0, n);
      out.close();
      in.close();
    }
    list.close();
    OutputStream out = new FileOutputStream(stamp);
    out.write(version.getBytes("UTF-8"));
    out.close();
    return true;
  }

  String app_version()
  {
    try
    {
      PackageInfo pi = _context.getPackageManager()
        .getPackageInfo(_context.getPackageName(), 0);
      return pi.versionName + "-" + pi.lastUpdateTime;
    }
    catch (Exception e)
    {
      return "unknown";
    }
  }

  static String read_file(File f)
  {
    try
    {
      BufferedReader r = new BufferedReader(new InputStreamReader(
            new java.io.FileInputStream(f), "UTF-8"));
      String s = r.readLine();
      r.close();
      return (s == null) ? "" : s;
    }
    catch (Exception e)
    {
      return "";
    }
  }

  /* Session API. Must only be called when [is_ready()]. */

  public boolean process_key(int keysym, int mask)
  {
    return Rime.nativeProcessKey(_session, keysym, mask);
  }

  /** Text committed by the last action or [null]. */
  public String get_commit()
  {
    return Rime.nativeGetCommit(_session);
  }

  public Rime.Context get_context()
  {
    return Rime.nativeGetContext(_session);
  }

  public Rime.Status get_status()
  {
    return Rime.nativeGetStatus(_session);
  }

  public boolean commit_composition()
  {
    return Rime.nativeCommitComposition(_session);
  }

  public void clear_composition()
  {
    Rime.nativeClearComposition(_session);
  }

  /** Interleaved texts and comments. */
  public String[] get_candidates(int start, int limit)
  {
    return Rime.nativeGetCandidates(_session, start, limit);
  }

  public boolean select_candidate(int index)
  {
    return Rime.nativeSelectCandidate(_session, index);
  }

  public boolean delete_candidate(int index)
  {
    return Rime.nativeDeleteCandidate(_session, index);
  }

  public boolean change_page(boolean backward)
  {
    return Rime.nativeChangePage(_session, backward);
  }

  public void set_option(String option, boolean value)
  {
    Rime.nativeSetOption(_session, option, value);
  }

  public boolean get_option(String option)
  {
    return Rime.nativeGetOption(_session, option);
  }

  public String[] get_schema_list()
  {
    return Rime.nativeGetSchemaList();
  }

  public boolean select_schema(String schema_id)
  {
    return Rime.nativeSelectSchema(_session, schema_id);
  }
}

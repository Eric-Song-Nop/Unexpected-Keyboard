package juloo.keyboard2.rime;

import android.util.Log;

/** Bindings to librime. Implemented in [vendor/rime_jni/rime_jni.cc]. The
    library is loaded lazily, [is_available()] must be checked before calling
    any other function. */
public final class Rime
{
  static boolean _loaded = false;
  static boolean _load_attempted = false;

  /** Whether the native library could be loaded. */
  public static synchronized boolean is_available()
  {
    if (_load_attempted)
      return _loaded;
    _load_attempted = true;
    try
    {
      System.loadLibrary("rime_jni");
      _loaded = true;
    }
    catch (Throwable e)
    {
      Log.e("juloo.keyboard2.rime", "Failed to load librime", e);
    }
    return _loaded;
  }

  /** Returned by [nativeGetContext]. Fields are set from JNI. */
  public static final class Context
  {
    public String preedit;
    public int cursor_pos;
    public int sel_start;
    public int sel_end;
    public String commit_text_preview;
    public int page_size;
    public int page_no;
    public boolean is_last_page;
    /** Index of the highlighted candidate on the current page. */
    public int highlighted;
    /** Candidates on the current page. */
    public String[] candidates;
    public String[] comments;
    public String[] labels;

    public Context() {}

    public boolean is_composing()
    {
      return preedit != null && preedit.length() > 0;
    }
  }

  /** Returned by [nativeGetStatus]. Fields are set from JNI. */
  public static final class Status
  {
    public String schema_id;
    public String schema_name;
    public boolean is_disabled;
    public boolean is_composing;
    public boolean is_ascii_mode;
    public boolean is_full_shape;
    public boolean is_simplified;
    public boolean is_traditional;
    public boolean is_ascii_punct;

    public Status() {}
  }

  /* Modifier masks, from librime's [key_table.h]. */
  public static final int MASK_SHIFT = 1 << 0;
  public static final int MASK_LOCK = 1 << 1;
  public static final int MASK_CONTROL = 1 << 2;
  public static final int MASK_ALT = 1 << 3;
  public static final int MASK_SUPER = 1 << 26;
  public static final int MASK_RELEASE = 1 << 30;

  /* Lifecycle */
  public static native boolean nativeInit(String shared_dir, String user_dir,
      String log_dir);
  public static native void nativeFinalize();
  public static native boolean nativeDeploy(boolean full_check);
  public static native boolean nativeSyncUserData();
  public static native String nativeGetVersion();

  /* Sessions */
  public static native long nativeCreateSession();
  public static native void nativeDestroySession(long session);
  public static native boolean nativeFindSession(long session);

  /* Input */
  public static native boolean nativeProcessKey(long session, int keysym, int mask);
  public static native boolean nativeSimulateKeySequence(long session, String seq);
  public static native String nativeGetCommit(long session);
  public static native boolean nativeCommitComposition(long session);
  public static native void nativeClearComposition(long session);
  public static native Context nativeGetContext(long session);
  public static native Status nativeGetStatus(long session);
  public static native String nativeGetInput(long session);
  public static native void nativeSetCaretPos(long session, int pos);

  /* Candidates. [nativeGetCandidates] returns interleaved text and comments. */
  public static native String[] nativeGetCandidates(long session, int start, int limit);
  public static native boolean nativeSelectCandidate(long session, int index);
  public static native boolean nativeSelectCandidateOnCurrentPage(long session, int index);
  public static native boolean nativeDeleteCandidate(long session, int index);
  public static native boolean nativeChangePage(long session, boolean backward);

  /* Options and schemas */
  public static native void nativeSetOption(long session, String option, boolean value);
  public static native boolean nativeGetOption(long session, String option);
  public static native String nativeGetStateLabel(long session, String option, boolean state);
  /** Interleaved schema ids and names. */
  public static native String[] nativeGetSchemaList();
  public static native boolean nativeSelectSchema(long session, String schema_id);
}

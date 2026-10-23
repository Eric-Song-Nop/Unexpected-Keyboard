package juloo.keyboard2.rime;

import android.view.KeyEvent;

/** Route the keys typed on the keyboard to librime and forward the results
    (committed text, composition and candidates) to [Callback]. */
public final class RimeInput
{
  /* X11 keysyms, used by librime. */
  public static final int XK_BackSpace = 0xff08;
  public static final int XK_Tab = 0xff09;
  public static final int XK_Return = 0xff0d;
  public static final int XK_Escape = 0xff1b;
  public static final int XK_Delete = 0xffff;
  public static final int XK_Home = 0xff50;
  public static final int XK_Left = 0xff51;
  public static final int XK_Up = 0xff52;
  public static final int XK_Right = 0xff53;
  public static final int XK_Down = 0xff54;
  public static final int XK_Page_Up = 0xff55;
  public static final int XK_Page_Down = 0xff56;
  public static final int XK_End = 0xff57;
  public static final int XK_F1 = 0xffbe;
  public static final int XK_F4 = 0xffc1;
  public static final int XK_space = 0x20;

  final RimeEngine _engine;
  final Callback _callback;
  /** Whether Rime is enabled in the settings and for the current editor. */
  boolean _enabled = false;
  /** Whether a composition is ongoing. */
  boolean _composing = false;

  public RimeInput(RimeEngine engine, Callback callback)
  {
    _engine = engine;
    _callback = callback;
  }

  public RimeEngine engine() { return _engine; }

  /** Called when the input starts or the configuration changes. */
  public void set_enabled(boolean enabled)
  {
    if (_enabled && !enabled)
      reset();
    _enabled = enabled;
    if (enabled)
      _engine.start();
  }

  /** Whether keys should be handled by Rime. */
  public boolean active()
  {
    return _enabled && _engine.is_ready();
  }

  /** Whether the keyboard is currently in Rime's ASCII mode. Uppercase
      letters are not handled the same in Chinese mode, autocapitalisation
      should be disabled. */
  public boolean is_ascii_mode()
  {
    if (!active())
      return true;
    return _engine.get_option("ascii_mode");
  }

  public boolean is_composing()
  {
    return active() && _composing;
  }

  /** A character key has been pressed. Returns [true] if the key has been
      handled. */
  public boolean handle_char(char c, int mask)
  {
    if (!active())
      return false;
    if (c >= 0x20 && c < 0x7f)
    {
      if (process(c, mask))
        return true;
    }
    // The key is not handled by Rime. Commit the composition before the
    // character is inserted.
    commit_pending();
    return false;
  }

  /** Android key event. Returns [true] if the key has been handled. */
  public boolean handle_keyevent(int keycode, int mask)
  {
    if (!active())
      return false;
    int keysym = keysym_of_keycode(keycode);
    if (keysym == 0)
    {
      commit_pending();
      return false;
    }
    // Only send navigation keys when composing so that they keep working
    // outside of a composition. F4 opens the schema menu.
    if (!_composing && keysym != XK_F4 && (mask & Rime.MASK_CONTROL) == 0)
      return false;
    if (process(keysym, mask))
      return true;
    commit_pending();
    return false;
  }

  /** Space bar. */
  public boolean handle_space(int mask)
  {
    return active() && process(XK_space, mask);
  }

  public boolean handle_backspace()
  {
    return is_composing() && process(XK_BackSpace, 0);
  }

  /** Enter or the action key. */
  public boolean handle_enter()
  {
    return is_composing() && process(XK_Return, 0);
  }

  /** Move the cursor inside the composition. */
  public boolean handle_cursor(int d)
  {
    if (!is_composing())
      return false;
    int keysym = (d < 0) ? XK_Left : XK_Right;
    for (int i = Math.abs(d); i > 0; i--)
      _engine.process_key(keysym, 0);
    refresh();
    return true;
  }

  /** Commit the current composition as-is. Called before inserting text not
      typed through Rime. */
  public void commit_pending()
  {
    if (!is_composing())
      return;
    _engine.commit_composition();
    refresh();
  }

  /** Abort the current composition. */
  public void reset()
  {
    if (_engine.is_ready())
      _engine.clear_composition();
    _composing = false;
    _callback.rime_update(null, null);
  }

  public void select_candidate(int index)
  {
    if (!active())
      return;
    _engine.select_candidate(index);
    refresh();
  }

  public void delete_candidate(int index)
  {
    if (!active())
      return;
    _engine.delete_candidate(index);
    refresh();
  }

  public void toggle_ascii_mode()
  {
    if (!active())
      return;
    commit_pending();
    _engine.set_option("ascii_mode", !_engine.get_option("ascii_mode"));
    refresh();
  }

  public void toggle_option(String option)
  {
    if (!active())
      return;
    _engine.set_option(option, !_engine.get_option(option));
    refresh();
  }

  /** Open Rime's switcher menu (schemas and options). */
  public void open_switcher()
  {
    if (!active())
      return;
    process(XK_F4, 0);
  }

  public void select_schema(String schema_id)
  {
    if (!active())
      return;
    commit_pending();
    _engine.select_schema(schema_id);
    refresh();
  }

  /** Notify the callback of the current state without processing a key. */
  public void refresh()
  {
    if (!active())
    {
      _composing = false;
      _callback.rime_update(null, null);
      return;
    }
    String commit = _engine.get_commit();
    if (commit != null && commit.length() > 0)
      _callback.rime_commit(commit);
    Rime.Context ctx = _engine.get_context();
    Rime.Status st = _engine.get_status();
    _composing = ctx != null && (ctx.is_composing()
        || (ctx.candidates != null && ctx.candidates.length > 0));
    _callback.rime_update(ctx, st);
  }

  boolean process(int keysym, int mask)
  {
    boolean handled = _engine.process_key(keysym, mask);
    // Also send the release event, some processors (eg. ascii_composer)
    // react to it.
    _engine.process_key(keysym, mask | Rime.MASK_RELEASE);
    refresh();
    return handled;
  }

  /** Convert Android meta state to Rime modifiers. */
  public static int mask_of_meta_state(int meta)
  {
    int mask = 0;
    if ((meta & KeyEvent.META_SHIFT_ON) != 0) mask |= Rime.MASK_SHIFT;
    if ((meta & KeyEvent.META_CTRL_ON) != 0) mask |= Rime.MASK_CONTROL;
    if ((meta & KeyEvent.META_ALT_ON) != 0) mask |= Rime.MASK_ALT;
    if ((meta & KeyEvent.META_META_ON) != 0) mask |= Rime.MASK_SUPER;
    return mask;
  }

  static int keysym_of_keycode(int keycode)
  {
    switch (keycode)
    {
      case KeyEvent.KEYCODE_DEL: return XK_BackSpace;
      case KeyEvent.KEYCODE_TAB: return XK_Tab;
      case KeyEvent.KEYCODE_ENTER: return XK_Return;
      case KeyEvent.KEYCODE_NUMPAD_ENTER: return XK_Return;
      case KeyEvent.KEYCODE_ESCAPE: return XK_Escape;
      case KeyEvent.KEYCODE_FORWARD_DEL: return XK_Delete;
      case KeyEvent.KEYCODE_MOVE_HOME: return XK_Home;
      case KeyEvent.KEYCODE_MOVE_END: return XK_End;
      case KeyEvent.KEYCODE_DPAD_LEFT: return XK_Left;
      case KeyEvent.KEYCODE_DPAD_UP: return XK_Up;
      case KeyEvent.KEYCODE_DPAD_RIGHT: return XK_Right;
      case KeyEvent.KEYCODE_DPAD_DOWN: return XK_Down;
      case KeyEvent.KEYCODE_PAGE_UP: return XK_Page_Up;
      case KeyEvent.KEYCODE_PAGE_DOWN: return XK_Page_Down;
      case KeyEvent.KEYCODE_SPACE: return XK_space;
      case KeyEvent.KEYCODE_GRAVE: return '`';
      default:
        if (keycode >= KeyEvent.KEYCODE_F1 && keycode <= KeyEvent.KEYCODE_F12)
          return XK_F1 + (keycode - KeyEvent.KEYCODE_F1);
        if (keycode >= KeyEvent.KEYCODE_A && keycode <= KeyEvent.KEYCODE_Z)
          return 'a' + (keycode - KeyEvent.KEYCODE_A);
        if (keycode >= KeyEvent.KEYCODE_0 && keycode <= KeyEvent.KEYCODE_9)
          return '0' + (keycode - KeyEvent.KEYCODE_0);
        return 0;
    }
  }

  public static interface Callback
  {
    /** Insert text committed by Rime. */
    public void rime_commit(String text);
    /** The composition or candidates changed. Arguments are [null] when Rime
        is inactive or the composition is reset. */
    public void rime_update(Rime.Context ctx, Rime.Status status);
  }
}

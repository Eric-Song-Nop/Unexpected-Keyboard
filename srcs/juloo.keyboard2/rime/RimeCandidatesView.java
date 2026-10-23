package juloo.keyboard2.rime;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import juloo.keyboard2.Config;
import juloo.keyboard2.R;

/** Shows Rime's composition and candidates above the keyboard. When there is
    no composition, shows the current schema and mode toggles. */
public class RimeCandidatesView extends LinearLayout
{
  /** Number of candidates loaded at a time. More candidates are loaded when
      scrolling to the end. */
  static final int LOAD_COUNT = 40;
  static final int MAX_CANDIDATES = 400;

  RimeInput _input = null;
  boolean _show_comments = true;

  TextView _preedit_view;
  LinearLayout _row;
  HorizontalScrollView _scroll;
  LinearLayout _candidates_layout;
  LinearLayout _status_layout;
  TextView _schema_button;
  TextView _ascii_button;
  TextView _menu_button;
  TextView _status_message;

  final List<TextView> _candidate_views = new ArrayList<TextView>();
  int _loaded_count = 0;
  boolean _has_more = false;
  int _highlighted = -1;

  int _label_color;
  int _sublabel_color;
  float _text_size = 0.f;
  int _gap;

  public RimeCandidatesView(Context context, AttributeSet attrs)
  {
    super(context, attrs);
    setOrientation(VERTICAL);
    _label_color = resolve_color(R.attr.colorLabel);
    _sublabel_color = resolve_color(R.attr.colorSubLabel);
    _gap = getResources().getDimensionPixelSize(R.dimen.candidates_gap);
    build_views();
  }

  public void set_input(RimeInput input)
  {
    _input = input;
  }

  int resolve_color(int attr)
  {
    TypedValue v = new TypedValue();
    if (getContext().getTheme().resolveAttribute(attr, v, true))
      return v.data;
    return 0xFF808080;
  }

  void build_views()
  {
    Context ctx = getContext();
    _preedit_view = new TextView(ctx);
    _preedit_view.setTextColor(_sublabel_color);
    _preedit_view.setSingleLine(true);
    _preedit_view.setEllipsize(TextUtils.TruncateAt.START);
    _preedit_view.setPadding(_gap, 0, _gap, 0);
    _preedit_view.setGravity(Gravity.CENTER_VERTICAL);
    addView(_preedit_view, new LayoutParams(LayoutParams.MATCH_PARENT, 0));

    _row = new LinearLayout(ctx);
    _row.setOrientation(HORIZONTAL);
    addView(_row, new LayoutParams(LayoutParams.MATCH_PARENT, 0));

    _scroll = new HorizontalScrollView(ctx);
    _scroll.setHorizontalScrollBarEnabled(false);
    _scroll.setFillViewport(true);
    _candidates_layout = new LinearLayout(ctx);
    _candidates_layout.setOrientation(HORIZONTAL);
    _candidates_layout.setGravity(Gravity.CENTER_VERTICAL);
    _scroll.addView(_candidates_layout, new ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    _scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
      public void onScrollChange(View v, int x, int y, int ox, int oy)
      {
        if (_has_more
            && x + _scroll.getWidth() >= _candidates_layout.getWidth() - _scroll.getWidth() / 2)
          load_more_candidates();
      }
    });
    _row.addView(_scroll, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1.f));

    _status_layout = new LinearLayout(ctx);
    _status_layout.setOrientation(HORIZONTAL);
    _status_layout.setGravity(Gravity.CENTER_VERTICAL);
    _status_message = make_text_view();
    _status_message.setTextColor(_sublabel_color);
    _status_message.setBackground(null);
    _status_layout.addView(_status_message, new LayoutParams(0,
          LayoutParams.MATCH_PARENT, 1.f));
    _schema_button = make_button(new View.OnClickListener() {
      public void onClick(View v)
      {
        if (_input != null) _input.open_switcher();
      }
    });
    _ascii_button = make_button(new View.OnClickListener() {
      public void onClick(View v)
      {
        if (_input != null) _input.toggle_ascii_mode();
      }
    });
    _menu_button = make_button(new View.OnClickListener() {
      public void onClick(View v)
      {
        if (_input != null) _input.toggle_option("full_shape");
      }
    });
    _row.addView(_status_layout, new LayoutParams(LayoutParams.MATCH_PARENT,
          LayoutParams.MATCH_PARENT));
  }

  TextView make_text_view()
  {
    TextView v = new TextView(getContext());
    v.setTextColor(_label_color);
    v.setSingleLine(true);
    v.setGravity(Gravity.CENTER);
    v.setBackgroundResource(R.drawable.suggestions_item_background);
    int pad = getResources().getDimensionPixelSize(R.dimen.candidates_suggestions_padding);
    v.setPadding(pad * 2, 0, pad * 2, 0);
    if (_text_size > 0.f)
      v.setTextSize(TypedValue.COMPLEX_UNIT_PX, _text_size);
    return v;
  }

  TextView make_button(View.OnClickListener l)
  {
    TextView v = make_text_view();
    v.setOnClickListener(l);
    LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT,
        LayoutParams.MATCH_PARENT);
    lp.setMargins(_gap, _gap / 2, 0, _gap / 2);
    _status_layout.addView(v, lp);
    return v;
  }

  /** Set the sizes to match the keyboard. */
  public void refresh_config(Config config)
  {
    _show_comments = config.rime_show_comments;
    float row_height =
      config.keyboard_rows_height_pixels * (1 - config.key_vertical_margin);
    _text_size = row_height * config.characterSize * config.labelTextSize;
    int preedit_height = (int)(row_height * 0.45f);
    _preedit_view.getLayoutParams().height = preedit_height;
    _preedit_view.setTextSize(TypedValue.COMPLEX_UNIT_PX, preedit_height * 0.7f);
    _row.getLayoutParams().height = (int)row_height;
    for (TextView v : _candidate_views)
      v.setTextSize(TypedValue.COMPLEX_UNIT_PX, _text_size);
    _schema_button.setTextSize(TypedValue.COMPLEX_UNIT_PX, _text_size * 0.8f);
    _ascii_button.setTextSize(TypedValue.COMPLEX_UNIT_PX, _text_size * 0.8f);
    _menu_button.setTextSize(TypedValue.COMPLEX_UNIT_PX, _text_size * 0.8f);
    _status_message.setTextSize(TypedValue.COMPLEX_UNIT_PX, _text_size * 0.6f);
    requestLayout();
  }

  /** Show the state of the engine when no session is available. */
  public void set_engine_state(RimeEngine.State state)
  {
    switch (state)
    {
      case DEPLOYING:
        show_message(getResources().getString(R.string.rime_status_deploying));
        break;
      case FAILED:
        show_message(getResources().getString(R.string.rime_status_failed));
        break;
      case UNAVAILABLE:
        show_message(getResources().getString(R.string.rime_status_unavailable));
        break;
      case STOPPED:
        show_message(getResources().getString(R.string.rime_status_locked));
        break;
      default:
        break;
    }
  }

  void show_message(String msg)
  {
    _preedit_view.setText("");
    clear_candidates();
    _scroll.setVisibility(View.GONE);
    _status_layout.setVisibility(View.VISIBLE);
    _status_message.setText(msg);
    _schema_button.setVisibility(View.GONE);
    _ascii_button.setVisibility(View.GONE);
    _menu_button.setVisibility(View.GONE);
  }

  /** Update from Rime's state. Arguments might be [null]. */
  public void update(Rime.Context ctx, Rime.Status status)
  {
    boolean has_candidates = ctx != null && ctx.candidates != null
      && ctx.candidates.length > 0;
    boolean composing = ctx != null && (ctx.is_composing() || has_candidates);
    _preedit_view.setText((ctx != null) ? preedit_text(ctx) : "");
    if (composing)
    {
      _status_layout.setVisibility(View.GONE);
      _scroll.setVisibility(View.VISIBLE);
      load_candidates(ctx);
    }
    else
    {
      clear_candidates();
      _scroll.setVisibility(View.GONE);
      _status_layout.setVisibility(View.VISIBLE);
      update_status(status);
    }
  }

  void update_status(Rime.Status status)
  {
    _status_message.setText("");
    if (status == null)
    {
      if (_input != null)
        set_engine_state(_input.engine().state());
      return;
    }
    _schema_button.setVisibility(View.VISIBLE);
    _ascii_button.setVisibility(View.VISIBLE);
    _menu_button.setVisibility(View.VISIBLE);
    String name = status.schema_name;
    if (name == null || name.length() == 0)
      name = status.schema_id;
    _schema_button.setText(name + " ▾");
    _ascii_button.setText(status.is_ascii_mode ? "A" : "中");
    _menu_button.setText(status.is_full_shape ? "全" : "半");
  }

  /** Preedit with a cursor. */
  CharSequence preedit_text(Rime.Context ctx)
  {
    if (ctx.preedit == null)
      return "";
    String p = ctx.preedit;
    // [cursor_pos] is in bytes in UTF-8.
    int cursor = utf16_index_of_utf8(p, ctx.cursor_pos);
    if (cursor < p.length())
      return p.substring(0, cursor) + "‸" + p.substring(cursor);
    return p;
  }

  static int utf16_index_of_utf8(String s, int byte_pos)
  {
    int bytes = 0;
    int i = 0;
    while (i < s.length() && bytes < byte_pos)
    {
      int c = s.codePointAt(i);
      bytes += (c < 0x80) ? 1 : (c < 0x800) ? 2 : (c < 0x10000) ? 3 : 4;
      i += Character.charCount(c);
    }
    return i;
  }

  void clear_candidates()
  {
    _candidates_layout.removeAllViews();
    _loaded_count = 0;
    _has_more = false;
    _highlighted = -1;
  }

  void load_candidates(Rime.Context ctx)
  {
    clear_candidates();
    _highlighted = ctx.page_no * ctx.page_size + ctx.highlighted;
    // Candidates are loaded through the candidate list iterator to allow
    // scrolling through more than one page. Fallback to the current page if
    // the iterator is not supported (eg. the switcher menu).
    if (_input != null)
    {
      String[] cands = _input.engine().get_candidates(0, LOAD_COUNT);
      if (cands != null && cands.length > 0)
      {
        add_candidates(cands, 0);
        _scroll.scrollTo(0, 0);
        return;
      }
    }
    int base = ctx.page_no * ctx.page_size;
    for (int i = 0; i < ctx.candidates.length; i++)
      add_candidate(base + i, ctx.candidates[i],
          (ctx.comments != null) ? ctx.comments[i] : null);
    _has_more = false;
    _scroll.scrollTo(0, 0);
  }

  void load_more_candidates()
  {
    if (_input == null || !_has_more || _loaded_count >= MAX_CANDIDATES)
      return;
    String[] cands = _input.engine().get_candidates(_loaded_count, LOAD_COUNT);
    if (cands == null || cands.length == 0)
    {
      _has_more = false;
      return;
    }
    add_candidates(cands, _loaded_count);
  }

  void add_candidates(String[] cands, int start)
  {
    int n = cands.length / 2;
    for (int i = 0; i < n; i++)
      add_candidate(start + i, cands[i * 2], cands[i * 2 + 1]);
    _has_more = (n == LOAD_COUNT);
  }

  void add_candidate(final int index, String text, String comment)
  {
    TextView v = obtain_candidate_view(_loaded_count);
    SpannableStringBuilder b = new SpannableStringBuilder(text);
    if (_show_comments && comment != null && comment.length() > 0)
    {
      int start = b.length();
      b.append(" ").append(comment);
      b.setSpan(new RelativeSizeSpan(0.6f), start, b.length(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      b.setSpan(new ForegroundColorSpan(_sublabel_color), start, b.length(),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    v.setText(b);
    v.setTypeface(null, (index == _highlighted) ? Typeface.BOLD : Typeface.NORMAL);
    v.setOnClickListener(new View.OnClickListener() {
      public void onClick(View _v)
      {
        if (_input != null) _input.select_candidate(index);
      }
    });
    v.setOnLongClickListener(new View.OnLongClickListener() {
      public boolean onLongClick(View _v)
      {
        if (_input != null) _input.delete_candidate(index);
        return true;
      }
    });
    LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT,
        LayoutParams.MATCH_PARENT);
    lp.setMargins(_gap / 2, _gap / 2, _gap / 2, _gap / 2);
    _candidates_layout.addView(v, lp);
    _loaded_count++;
  }

  TextView obtain_candidate_view(int i)
  {
    while (_candidate_views.size() <= i)
    {
      TextView v = make_text_view();
      v.setMinWidth((int)(_text_size * 1.8f));
      _candidate_views.add(v);
    }
    return _candidate_views.get(i);
  }
}

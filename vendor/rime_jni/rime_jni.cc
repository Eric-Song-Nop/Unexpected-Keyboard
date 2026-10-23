// JNI bindings to librime used by [juloo.keyboard2.rime.Rime].

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <rime_api.h>

#define LOG_TAG "juloo.keyboard2.rime"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static RimeApi *rime = nullptr;
static bool initialized = false;

// Notifications are emitted from the maintenance thread.
static std::mutex notif_mutex;
static bool deploy_failed = false;

static void notification_handler(void *, RimeSessionId, const char *type,
    const char *value)
{
  if (type == nullptr || value == nullptr)
    return;
  LOGI("notification %s: %s", type, value);
  if (std::string(type) == "deploy")
  {
    std::lock_guard<std::mutex> lock(notif_mutex);
    if (std::string(value) == "failure")
      deploy_failed = true;
    else if (std::string(value) == "start")
      deploy_failed = false;
  }
}

/** [NewStringUTF] expects modified UTF-8 and cannot handle characters
    outside of the BMP (eg. emojis). Convert to UTF-16 manually. */
static jstring to_jstring(JNIEnv *env, const char *s)
{
  if (s == nullptr)
    return nullptr;
  std::vector<jchar> out;
  const unsigned char *p = (const unsigned char *)s;
  while (*p)
  {
    uint32_t c = *p;
    int extra = 0;
    if (c < 0x80) extra = 0;
    else if ((c & 0xE0) == 0xC0) { c &= 0x1F; extra = 1; }
    else if ((c & 0xF0) == 0xE0) { c &= 0x0F; extra = 2; }
    else if ((c & 0xF8) == 0xF0) { c &= 0x07; extra = 3; }
    else { p++; out.push_back(0xFFFD); continue; }
    p++;
    bool valid = true;
    for (int i = 0; i < extra; i++)
    {
      if ((*p & 0xC0) != 0x80) { valid = false; break; }
      c = (c << 6) | (*p & 0x3F);
      p++;
    }
    if (!valid)
      out.push_back(0xFFFD);
    else if (c >= 0x10000)
    {
      c -= 0x10000;
      out.push_back((jchar)(0xD800 + (c >> 10)));
      out.push_back((jchar)(0xDC00 + (c & 0x3FF)));
    }
    else
      out.push_back((jchar)c);
  }
  return env->NewString(out.data(), (jsize)out.size());
}

/** Convert a Java string to UTF-8. Characters outside of the BMP are
    properly encoded, unlike [GetStringUTFChars]. */
static std::string from_jstring(JNIEnv *env, jstring js)
{
  std::string out;
  if (js == nullptr)
    return out;
  jsize len = env->GetStringLength(js);
  const jchar *chars = env->GetStringChars(js, nullptr);
  for (jsize i = 0; i < len; i++)
  {
    uint32_t c = chars[i];
    if (c >= 0xD800 && c < 0xDC00 && i + 1 < len
        && chars[i + 1] >= 0xDC00 && chars[i + 1] < 0xE000)
    {
      c = 0x10000 + ((c - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
      i++;
    }
    if (c < 0x80)
      out += (char)c;
    else if (c < 0x800)
    {
      out += (char)(0xC0 | (c >> 6));
      out += (char)(0x80 | (c & 0x3F));
    }
    else if (c < 0x10000)
    {
      out += (char)(0xE0 | (c >> 12));
      out += (char)(0x80 | ((c >> 6) & 0x3F));
      out += (char)(0x80 | (c & 0x3F));
    }
    else
    {
      out += (char)(0xF0 | (c >> 18));
      out += (char)(0x80 | ((c >> 12) & 0x3F));
      out += (char)(0x80 | ((c >> 6) & 0x3F));
      out += (char)(0x80 | (c & 0x3F));
    }
  }
  env->ReleaseStringChars(js, chars);
  return out;
}

static jobjectArray new_string_array(JNIEnv *env, int size)
{
  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray arr = env->NewObjectArray(size, string_class, nullptr);
  env->DeleteLocalRef(string_class);
  return arr;
}

static void set_string_array_elem(JNIEnv *env, jobjectArray arr, int i,
    const char *s)
{
  jstring js = to_jstring(env, s);
  env->SetObjectArrayElement(arr, i, js);
  if (js != nullptr)
    env->DeleteLocalRef(js);
}

static void set_string_field(JNIEnv *env, jclass cls, jobject obj,
    const char *name, const char *value)
{
  jfieldID f = env->GetFieldID(cls, name, "Ljava/lang/String;");
  jstring js = to_jstring(env, value);
  env->SetObjectField(obj, f, js);
  if (js != nullptr)
    env->DeleteLocalRef(js);
}

static void set_int_field(JNIEnv *env, jclass cls, jobject obj,
    const char *name, int value)
{
  env->SetIntField(obj, env->GetFieldID(cls, name, "I"), value);
}

static void set_bool_field(JNIEnv *env, jclass cls, jobject obj,
    const char *name, bool value)
{
  env->SetBooleanField(obj, env->GetFieldID(cls, name, "Z"), value);
}

static jobject new_object(JNIEnv *env, jclass cls)
{
  jmethodID ctor = env->GetMethodID(cls, "<init>", "()V");
  return env->NewObject(cls, ctor);
}

static const char *rime_modules[] = {
  "default", "lua", "octagram", "predict", nullptr
};

static void fill_traits(RimeTraits *traits, const std::string &shared_dir,
    const std::string &user_dir, const std::string &log_dir)
{
  traits->shared_data_dir = shared_dir.c_str();
  traits->user_data_dir = user_dir.c_str();
  traits->distribution_name = "Unexpected Keyboard";
  traits->distribution_code_name = "unexpected-keyboard";
  traits->distribution_version = "1";
  traits->app_name = "rime.unexpected-keyboard";
  traits->modules = rime_modules;
  traits->min_log_level = 1;
  traits->log_dir = log_dir.c_str();
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeInit(JNIEnv *env, jclass,
    jstring jshared, jstring juser, jstring jlog)
{
  if (initialized)
    return JNI_TRUE;
  rime = rime_get_api();
  if (rime == nullptr)
    return JNI_FALSE;
  // The strings must outlive the call to [setup] as glog keeps pointers.
  static std::string shared_dir, user_dir, log_dir;
  shared_dir = from_jstring(env, jshared);
  user_dir = from_jstring(env, juser);
  log_dir = from_jstring(env, jlog);
  RIME_STRUCT(RimeTraits, traits);
  fill_traits(&traits, shared_dir, user_dir, log_dir);
  static bool setup_done = false;
  if (!setup_done)
  {
    rime->setup(&traits);
    setup_done = true;
  }
  rime->set_notification_handler(notification_handler, nullptr);
  rime->initialize(&traits);
  initialized = true;
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_juloo_keyboard2_rime_Rime_nativeFinalize(JNIEnv *, jclass)
{
  if (!initialized)
    return;
  rime->finalize();
  initialized = false;
}

/** Start the maintenance (deploy) and wait for it to finish. Returns
    [false] if the deployment failed. Must not be called from the UI thread
    as it can take a long time. */
JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeDeploy(JNIEnv *, jclass,
    jboolean full_check)
{
  if (!initialized)
    return JNI_FALSE;
  {
    std::lock_guard<std::mutex> lock(notif_mutex);
    deploy_failed = false;
  }
  if (rime->start_maintenance(full_check ? True : False))
    rime->join_maintenance_thread();
  std::lock_guard<std::mutex> lock(notif_mutex);
  return deploy_failed ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeSyncUserData(JNIEnv *, jclass)
{
  if (!initialized)
    return JNI_FALSE;
  jboolean r = rime->sync_user_data() ? JNI_TRUE : JNI_FALSE;
  rime->join_maintenance_thread();
  return r;
}

JNIEXPORT jstring JNICALL
Java_juloo_keyboard2_rime_Rime_nativeGetVersion(JNIEnv *env, jclass)
{
  RimeApi *api = rime_get_api();
  return to_jstring(env, api->get_version());
}

JNIEXPORT jlong JNICALL
Java_juloo_keyboard2_rime_Rime_nativeCreateSession(JNIEnv *, jclass)
{
  if (!initialized)
    return 0;
  return (jlong)rime->create_session();
}

JNIEXPORT void JNICALL
Java_juloo_keyboard2_rime_Rime_nativeDestroySession(JNIEnv *, jclass,
    jlong session)
{
  if (!initialized || session == 0)
    return;
  rime->destroy_session((RimeSessionId)session);
}

JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeFindSession(JNIEnv *, jclass,
    jlong session)
{
  if (!initialized || session == 0)
    return JNI_FALSE;
  return rime->find_session((RimeSessionId)session) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeProcessKey(JNIEnv *, jclass,
    jlong session, jint keycode, jint mask)
{
  if (!initialized)
    return JNI_FALSE;
  return rime->process_key((RimeSessionId)session, keycode, mask)
    ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeSimulateKeySequence(JNIEnv *env, jclass,
    jlong session, jstring jseq)
{
  if (!initialized)
    return JNI_FALSE;
  std::string seq = from_jstring(env, jseq);
  return rime->simulate_key_sequence((RimeSessionId)session, seq.c_str())
    ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_juloo_keyboard2_rime_Rime_nativeGetCommit(JNIEnv *env, jclass,
    jlong session)
{
  if (!initialized)
    return nullptr;
  RIME_STRUCT(RimeCommit, commit);
  if (!rime->get_commit((RimeSessionId)session, &commit))
    return nullptr;
  jstring r = to_jstring(env, commit.text);
  rime->free_commit(&commit);
  return r;
}

JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeCommitComposition(JNIEnv *, jclass,
    jlong session)
{
  if (!initialized)
    return JNI_FALSE;
  return rime->commit_composition((RimeSessionId)session)
    ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_juloo_keyboard2_rime_Rime_nativeClearComposition(JNIEnv *, jclass,
    jlong session)
{
  if (!initialized)
    return;
  rime->clear_composition((RimeSessionId)session);
}

/** Returns a [Rime.Context] object or [null]. */
JNIEXPORT jobject JNICALL
Java_juloo_keyboard2_rime_Rime_nativeGetContext(JNIEnv *env, jclass,
    jlong session)
{
  if (!initialized)
    return nullptr;
  RIME_STRUCT(RimeContext, ctx);
  if (!rime->get_context((RimeSessionId)session, &ctx))
    return nullptr;
  jclass cls = env->FindClass("juloo/keyboard2/rime/Rime$Context");
  jobject obj = new_object(env, cls);
  set_string_field(env, cls, obj, "preedit", ctx.composition.preedit);
  set_int_field(env, cls, obj, "cursor_pos", ctx.composition.cursor_pos);
  set_int_field(env, cls, obj, "sel_start", ctx.composition.sel_start);
  set_int_field(env, cls, obj, "sel_end", ctx.composition.sel_end);
  set_string_field(env, cls, obj, "commit_text_preview",
      ctx.commit_text_preview);
  set_int_field(env, cls, obj, "page_size", ctx.menu.page_size);
  set_int_field(env, cls, obj, "page_no", ctx.menu.page_no);
  set_bool_field(env, cls, obj, "is_last_page", ctx.menu.is_last_page);
  set_int_field(env, cls, obj, "highlighted",
      ctx.menu.highlighted_candidate_index);
  int n = ctx.menu.num_candidates;
  jobjectArray texts = new_string_array(env, n);
  jobjectArray comments = new_string_array(env, n);
  jobjectArray labels = new_string_array(env, n);
  bool has_labels = RIME_STRUCT_HAS_MEMBER(ctx, ctx.select_labels)
    && ctx.select_labels != nullptr;
  const char *select_keys = ctx.menu.select_keys;
  for (int i = 0; i < n; i++)
  {
    set_string_array_elem(env, texts, i, ctx.menu.candidates[i].text);
    set_string_array_elem(env, comments, i, ctx.menu.candidates[i].comment);
    std::string label;
    if (has_labels && ctx.select_labels[i] != nullptr)
      label = ctx.select_labels[i];
    else if (select_keys != nullptr && (size_t)i < strlen(select_keys))
      label = std::string(1, select_keys[i]);
    else
      label = std::to_string((i + 1) % 10);
    set_string_array_elem(env, labels, i, label.c_str());
  }
  env->SetObjectField(obj,
      env->GetFieldID(cls, "candidates", "[Ljava/lang/String;"), texts);
  env->SetObjectField(obj,
      env->GetFieldID(cls, "comments", "[Ljava/lang/String;"), comments);
  env->SetObjectField(obj,
      env->GetFieldID(cls, "labels", "[Ljava/lang/String;"), labels);
  env->DeleteLocalRef(texts);
  env->DeleteLocalRef(comments);
  env->DeleteLocalRef(labels);
  env->DeleteLocalRef(cls);
  rime->free_context(&ctx);
  return obj;
}

/** Returns a [Rime.Status] object or [null]. */
JNIEXPORT jobject JNICALL
Java_juloo_keyboard2_rime_Rime_nativeGetStatus(JNIEnv *env, jclass,
    jlong session)
{
  if (!initialized)
    return nullptr;
  RIME_STRUCT(RimeStatus, st);
  if (!rime->get_status((RimeSessionId)session, &st))
    return nullptr;
  jclass cls = env->FindClass("juloo/keyboard2/rime/Rime$Status");
  jobject obj = new_object(env, cls);
  set_string_field(env, cls, obj, "schema_id", st.schema_id);
  set_string_field(env, cls, obj, "schema_name", st.schema_name);
  set_bool_field(env, cls, obj, "is_disabled", st.is_disabled);
  set_bool_field(env, cls, obj, "is_composing", st.is_composing);
  set_bool_field(env, cls, obj, "is_ascii_mode", st.is_ascii_mode);
  set_bool_field(env, cls, obj, "is_full_shape", st.is_full_shape);
  set_bool_field(env, cls, obj, "is_simplified", st.is_simplified);
  set_bool_field(env, cls, obj, "is_traditional", st.is_traditional);
  set_bool_field(env, cls, obj, "is_ascii_punct", st.is_ascii_punct);
  env->DeleteLocalRef(cls);
  rime->free_status(&st);
  return obj;
}

/** Return the list of candidates starting at index [start], at most [limit]
    candidates. Used to show more candidates than a single page. */
JNIEXPORT jobjectArray JNICALL
Java_juloo_keyboard2_rime_Rime_nativeGetCandidates(JNIEnv *env, jclass,
    jlong session, jint start, jint limit)
{
  if (!initialized)
    return nullptr;
  RimeCandidateListIterator it = {0};
  std::vector<std::string> texts;
  std::vector<std::string> comments;
  if (!rime->candidate_list_from_index((RimeSessionId)session, &it, start))
    return new_string_array(env, 0);
  while ((int)texts.size() < limit && rime->candidate_list_next(&it))
  {
    texts.push_back(it.candidate.text ? it.candidate.text : "");
    comments.push_back(it.candidate.comment ? it.candidate.comment : "");
  }
  rime->candidate_list_end(&it);
  // Interleaved [text, comment] pairs.
  jobjectArray arr = new_string_array(env, (int)texts.size() * 2);
  for (size_t i = 0; i < texts.size(); i++)
  {
    set_string_array_elem(env, arr, (int)i * 2, texts[i].c_str());
    set_string_array_elem(env, arr, (int)i * 2 + 1, comments[i].c_str());
  }
  return arr;
}

JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeSelectCandidate(JNIEnv *, jclass,
    jlong session, jint index)
{
  if (!initialized)
    return JNI_FALSE;
  return rime->select_candidate((RimeSessionId)session, index)
    ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeSelectCandidateOnCurrentPage(JNIEnv *,
    jclass, jlong session, jint index)
{
  if (!initialized)
    return JNI_FALSE;
  return rime->select_candidate_on_current_page((RimeSessionId)session, index)
    ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeDeleteCandidate(JNIEnv *, jclass,
    jlong session, jint index)
{
  if (!initialized)
    return JNI_FALSE;
  return rime->delete_candidate((RimeSessionId)session, index)
    ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeChangePage(JNIEnv *, jclass,
    jlong session, jboolean backward)
{
  if (!initialized)
    return JNI_FALSE;
  return rime->change_page((RimeSessionId)session, backward ? True : False)
    ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_juloo_keyboard2_rime_Rime_nativeSetCaretPos(JNIEnv *, jclass,
    jlong session, jint pos)
{
  if (!initialized)
    return;
  rime->set_caret_pos((RimeSessionId)session, pos);
}

JNIEXPORT jstring JNICALL
Java_juloo_keyboard2_rime_Rime_nativeGetInput(JNIEnv *env, jclass,
    jlong session)
{
  if (!initialized)
    return nullptr;
  return to_jstring(env, rime->get_input((RimeSessionId)session));
}

JNIEXPORT void JNICALL
Java_juloo_keyboard2_rime_Rime_nativeSetOption(JNIEnv *env, jclass,
    jlong session, jstring jopt, jboolean value)
{
  if (!initialized)
    return;
  std::string opt = from_jstring(env, jopt);
  rime->set_option((RimeSessionId)session, opt.c_str(), value ? True : False);
}

JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeGetOption(JNIEnv *env, jclass,
    jlong session, jstring jopt)
{
  if (!initialized)
    return JNI_FALSE;
  std::string opt = from_jstring(env, jopt);
  return rime->get_option((RimeSessionId)session, opt.c_str())
    ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_juloo_keyboard2_rime_Rime_nativeGetStateLabel(JNIEnv *env, jclass,
    jlong session, jstring jopt, jboolean state)
{
  if (!initialized)
    return nullptr;
  std::string opt = from_jstring(env, jopt);
  return to_jstring(env, rime->get_state_label((RimeSessionId)session,
        opt.c_str(), state ? True : False));
}

/** Interleaved [schema_id, name] pairs. */
JNIEXPORT jobjectArray JNICALL
Java_juloo_keyboard2_rime_Rime_nativeGetSchemaList(JNIEnv *env, jclass)
{
  if (!initialized)
    return nullptr;
  RimeSchemaList list = {0};
  if (!rime->get_schema_list(&list))
    return new_string_array(env, 0);
  jobjectArray arr = new_string_array(env, (int)list.size * 2);
  for (size_t i = 0; i < list.size; i++)
  {
    set_string_array_elem(env, arr, (int)i * 2, list.list[i].schema_id);
    set_string_array_elem(env, arr, (int)i * 2 + 1, list.list[i].name);
  }
  rime->free_schema_list(&list);
  return arr;
}

JNIEXPORT jboolean JNICALL
Java_juloo_keyboard2_rime_Rime_nativeSelectSchema(JNIEnv *env, jclass,
    jlong session, jstring jid)
{
  if (!initialized)
    return JNI_FALSE;
  std::string id = from_jstring(env, jid);
  return rime->select_schema((RimeSessionId)session, id.c_str())
    ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"

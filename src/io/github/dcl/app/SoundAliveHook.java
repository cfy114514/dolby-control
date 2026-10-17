package io.github.dcl.app;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;

import io.github.libxposed.api.XposedModule;

/** SoundAlive 16.1.32 UI only. All controller I/O runs on one worker. */
public final class SoundAliveHook extends XposedModule {
    private static final String PACKAGE = "com.sec.android.app.soundalive";
    private static final String ACTIVITY = PACKAGE + ".activity.DolbyAtmosActivity";
    private static final String TAG = "DolbyControlSoundAlive";
    private static final String ROW_TAG = "io.github.dcl.spatial_audio_8";
    private Handler main;
    private ExecutorService worker;
    private Map<Activity, WeakReference<Page>> pages;
    private AtomicBoolean statusQueued;
    private LatestTask<ManualChoice> manualTasks;
    private LatestTask<Boolean> reassertTasks;
    private volatile ManualChoice latestManualChoice;
    private volatile boolean controllerKnownReady;
    private ThreadLocal<Integer> explicitDepth;
    private Method nativeUpdate;
    private boolean installed;
    private boolean repainting;
    private long lastFailureLog = -30000;

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!PACKAGE.equals(param.getPackageName()) || installed) return;
        ArrayList<HookHandle> handles = new ArrayList<>();
        try {
            ClassLoader loader = param.getClassLoader();
            Class<?> activity = Class.forName(ACTIVITY, false, loader);
            Class<?> effects = Class.forName("r2.h", false, loader);
            Method initRows = activity.getDeclaredMethod("u");
            Method update = activity.getDeclaredMethod("B");
            Method selectRadio = activity.getDeclaredMethod("z", int.class);
            Method resume = activity.getDeclaredMethod("onResume");
            Method pause = inheritedMethod(activity, "onPause");
            Method destroy = activity.getDeclaredMethod("onDestroy");
            Method manual = effects.getDeclaredMethod("k", int.class);
            Method apply = effects.getDeclaredMethod("i", int.class, boolean.class);
            main = new Handler(Looper.getMainLooper());
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "DCL-SoundAlive-controller");
                thread.setDaemon(true);
                return thread;
            });
            pages = new WeakHashMap<>();
            statusQueued = new AtomicBoolean();
            manualTasks = new LatestTask<>(worker, android.os.SystemClock::elapsedRealtime, 20000,
                    (choice, deadline) -> {
                        try {
                            deliver(checked(RootClient.callBefore("manual",
                                    new JSONObject().put("profile", choice.profile), deadline)));
                        } catch (Throwable error) { failed(error, choice.source, true, choice); }
                    }, choice -> failed(new java.io.IOException("Mode selection expired"),
                            choice.source, true, choice));
            reassertTasks = new LatestTask<>(worker, android.os.SystemClock::elapsedRealtime, 20000,
                    (ignored, deadline) -> {
                        try { deliver(checked(RootClient.callBefore("reassert", new JSONObject(), deadline))); }
                        catch (Throwable error) { failed(error, null, false); }
                    }, ignored -> { });
            explicitDepth = new ThreadLocal<>();
            nativeUpdate = update;

            // k() is used by explicit row/tile/provider changes. Refresh z()/B()
            // and restore y()->i() never count as a user's manual selection.
            handles.add(hook(manual).intercept(chain -> {
                int value = (Integer) chain.getArg(0);
                boolean ordinary = value >= 0 && value <= 3;
                Integer previous = explicitDepth.get();
                if (ordinary) {
                    explicitDepth.set(previous == null ? 1 : previous + 1);
                    enqueueManual(value, null);
                }
                try {
                    return chain.proceed();
                } finally {
                    if (ordinary) {
                        if (previous == null) explicitDepth.remove();
                        else explicitDepth.set(previous);
                        // The nested native i() has finished. Keep only the
                        // newest pending restore, after the latest manual intent.
                        enqueueReassert();
                    }
                }
            }));
            handles.add(hook(apply).intercept(chain -> {
                Object result = chain.proceed();
                Integer depth = explicitDepth.get();
                if (depth == null || depth == 0) enqueueReassert();
                return result;
            }));
            handles.add(hook(initRows).intercept(chain -> {
                Object result = chain.proceed();
                safePage((Activity) chain.getThisObject(), true);
                return result;
            }));
            handles.add(hook(update).intercept(chain -> {
                Object result = chain.proceed();
                if (!repainting) safePage((Activity) chain.getThisObject(), false);
                return result;
            }));
            handles.add(hook(selectRadio).intercept(chain -> {
                Object result = chain.proceed();
                if (!repainting) safePage((Activity) chain.getThisObject(), false);
                return result;
            }));
            handles.add(hook(resume).intercept(chain -> {
                Object result = chain.proceed();
                safePage((Activity) chain.getThisObject(), true);
                return result;
            }));
            handles.add(hook(pause).intercept(chain -> {
                if (activity.isInstance(chain.getThisObject()))
                    stopPage((Activity) chain.getThisObject(), false);
                return chain.proceed();
            }));
            handles.add(hook(destroy).intercept(chain -> {
                stopPage((Activity) chain.getThisObject(), true);
                return chain.proceed();
            }));
            // These exact shipped callers may already contain AOT-inlined
            // copies of k()/i(). Deoptimize only their narrow entry points.
            deoptimize(manual);
            deoptimizeCaller(loader, "l2.d", "onClick", View.class);
            deoptimizeCaller(loader, "r2.h", "y");
            deoptimizeCaller(loader, "f.u", "onReceive", android.content.Context.class, android.content.Intent.class);
            deoptimizeCaller(loader, PACKAGE + ".compatibility.SAContentProvider", "insert", android.net.Uri.class, android.content.ContentValues.class);
            installed = true;
            Log.i(TAG, "SoundAlive native mode row hooks installed (API 101)");
        } catch (Throwable error) {
            for (HookHandle handle : handles) {
                try { handle.unhook(); } catch (Throwable ignored) { }
            }
            if (worker != null) worker.shutdownNow();
            Log.e(TAG, "SoundAlive hooks unavailable; keep native UI", error);
        }
    }

    private void deoptimizeCaller(ClassLoader loader, String type, String name, Class<?>... args) {
        try { deoptimize(Class.forName(type, false, loader).getDeclaredMethod(name, args)); }
        catch (Throwable error) { Log.w(TAG, "Optional caller deoptimization unavailable: " + type + "." + name, error); }
    }

    private static Method inheritedMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredMethod(name); }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }

    private void safePage(Activity activity, boolean inject) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            WeakReference<Activity> reference = new WeakReference<>(activity);
            main.post(() -> {
                Activity current = reference.get();
                if (current != null) safePage(current, inject);
            });
            return;
        }
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            WeakReference<Page> reference = pages.get(activity);
            Page page = reference == null ? null : reference.get();
            ViewGroup container = activity.findViewById(id(activity, "layout_dolby_atmos_mode"));
            if (container == null) return;
            if (page == null || page.container != container || page.row.getParent() != container) {
                if (!inject) return;
                page = inject(activity, container);
                pages.put(activity, new WeakReference<>(page));
            }
            // Native z()/B() knows only the first four radios. It may just
            // have selected one of them; never leave a second radio checked
            // while the asynchronous actual-mode read is in flight.
            page.radio.setChecked(false);
            page.syncEnabled();
            if (inject) page.startPoll();
            enqueueStatus();
        } catch (Throwable error) {
            Log.e(TAG, "Cannot extend native mode rows", error);
        }
    }

    private Page inject(Activity activity, ViewGroup container) throws Throwable {
        // Inflate the shipped layout offscreen: this preserves Samsung's exact
        // row, radio, font, spacing and background styles without module resources.
        int layout = activity.getResources().getIdentifier("activity_dolby_atmos", "layout", PACKAGE);
        if (layout == 0) throw new Exception("Native Dolby layout missing");
        View template = LayoutInflater.from(activity).inflate(layout, null, false);
        LinearLayout row = template.findViewById(id(activity, "layout_dolby_atmos_mode_voice"));
        View musicTemplate = template.findViewById(id(activity, "layout_dolby_atmos_mode_music"));
        View nativeVoice = activity.findViewById(id(activity, "layout_dolby_atmos_mode_voice"));
        View nativeMusic = activity.findViewById(id(activity, "layout_dolby_atmos_mode_music"));
        RadioButton radio = row == null ? null : row.findViewById(id(activity, "radio_dolby_atmos_mode_voice"));
        if (row == null || musicTemplate == null || nativeVoice == null || nativeMusic == null || radio == null)
            throw new Exception("Native Dolby row structure changed");
        // Reject a duplicate from a partial/earlier injection instead of appending.
        if (container.findViewWithTag(ROW_TAG) != null) throw new Exception("Spatial row already present");
        ArrayList<TextView> text = new ArrayList<>();
        collectText(row, text);
        if (text.size() < 2) throw new Exception("Native row text missing");
        ViewGroup templateParent = (ViewGroup) musicTemplate.getParent();
        int musicIndex = templateParent.indexOfChild(musicTemplate);
        if (musicIndex < 1) throw new Exception("Native divider missing");
        View divider = templateParent.getChildAt(musicIndex - 1);
        if (divider instanceof ViewGroup) throw new Exception("Unexpected native divider");
        templateParent.removeView(divider);
        ((ViewGroup) row.getParent()).removeView(row);
        resetIds(row);
        divider.setId(View.generateViewId());
        row.setTag(ROW_TAG);
        radio.setChecked(false);
        radio.setClickable(false);
        radio.setFocusable(false);
        text.get(0).setText("Spatial Audio");
        text.get(1).setText(words(activity, "手动空间音频模式", "Manual spatial audio mode"));
        Page page = new Page(activity, container, row, radio, nativeMusic, text.get(1));
        row.setOnClickListener(v -> {
            if (!v.isEnabled()) return;
            // No speculative checkmark: only a successful, active profile8
            // readback may select this new radio button.
            enqueueManual(8, page);
        });
        Drawable oldVoiceBackground = nativeVoice.getBackground();
        try {
            container.addView(divider);
            container.addView(row);
            Drawable middle = nativeMusic.getBackground();
            if (middle != null && middle.getConstantState() != null)
                nativeVoice.setBackground(middle.getConstantState().newDrawable(activity.getResources(), activity.getTheme()).mutate());
        } catch (Throwable error) {
            container.removeView(row);
            container.removeView(divider);
            nativeVoice.setBackground(oldVoiceBackground);
            throw error;
        }
        page.syncEnabled();
        return page;
    }

    private void stopPage(Activity activity, boolean remove) {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        WeakReference<Page> reference = remove ? pages.remove(activity) : pages.get(activity);
        Page page = reference == null ? null : reference.get();
        if (page != null) page.stopPoll();
    }

    private synchronized void enqueueManual(int profile, Page source) {
        try {
            ManualChoice choice = new ManualChoice(profile, source, controllerKnownReady);
            latestManualChoice = choice;
            manualTasks.submit(choice);
        } catch (Throwable error) {
            Log.e(TAG, "Cannot queue manual mode", error);
        }
    }

    private void enqueueReassert() {
        try {
            reassertTasks.submit(Boolean.TRUE);
        } catch (Throwable error) {
            Log.e(TAG, "Cannot queue mode restore", error);
        }
    }

    private static final class ManualChoice {
        final int profile;
        final WeakReference<Page> source;
        final boolean wasReady;

        ManualChoice(int profile, Page source, boolean wasReady) {
            this.profile = profile;
            this.source = new WeakReference<>(source);
            this.wasReady = wasReady;
        }
    }

    private void enqueueStatus() {
        if (!statusQueued.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    deliver(checked(RootClient.call("status", new JSONObject())));
                } catch (Throwable error) {
                    failed(error, null, false);
                } finally {
                    statusQueued.set(false);
                }
            });
        } catch (Throwable error) {
            statusQueued.set(false);
            Log.e(TAG, "Cannot queue mode status", error);
        }
    }

    private static JSONObject checked(JSONObject result) throws Exception {
        if (result == null || !result.optBoolean("ok", false))
            throw new Exception(result == null ? "Controller unavailable" : result.optString("error", "Controller rejected request"));
        // The controller's flat status contract is deliberately strict: no
        // inference from a remembered target to the actual runtime profile.
        result.getInt("profile");
        result.getInt("selected_profile");
        result.getBoolean("active");
        result.getBoolean("ready");
        return result;
    }

    private void deliver(JSONObject result) {
        main.post(() -> {
            controllerKnownReady = result.optBoolean("ready", false);
            for (WeakReference<Page> reference : new ArrayList<>(pages.values())) {
                Page page = reference.get();
                if (page != null) {
                    try { page.render(result); }
                    catch (Throwable error) { Log.e(TAG, "Cannot display actual mode", error); }
                }
            }
        });
    }

    private void failed(Throwable error, WeakReference<Page> source, boolean notify) {
        failed(error, source, notify, null);
    }

    private void failed(Throwable error, WeakReference<Page> source, boolean notify, ManualChoice choice) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastFailureLog >= 30000 || notify) {
            lastFailureLog = now;
            Log.w(TAG, "Controller request failed", error);
        }
        main.post(() -> {
            Page origin = source == null ? null : source.get();
            Activity owner = origin == null ? foregroundActivity() : origin.activity.get();
            boolean ordinary = choice != null && choice.profile >= 0 && choice.profile <= 3;
            boolean knownReady = choice != null && choice.wasReady;
            for (WeakReference<Page> reference : new ArrayList<>(pages.values())) {
                Page page = reference.get();
                if (page != null && page.ready && page.activity.get() == owner) knownReady = true;
            }
            for (WeakReference<Page> reference : new ArrayList<>(pages.values())) {
                Page page = reference.get();
                if (page != null) page.unavailable();
            }
            controllerKnownReady = false;
            boolean current = choice == null || latestManualChoice == choice;
            if (notify && current && (!ordinary || knownReady) && owner != null &&
                    !owner.isFinishing() && !owner.isDestroyed() && owner.hasWindowFocus()) {
                Toast.makeText(owner, ordinary
                        ? words(owner, "系统档位已切换，保持选择未确认，请重试", "System mode changed; selection hold is unconfirmed. Please retry.")
                        : words(owner, "切换未确认，请检查 Dolby Control 后台", "Mode not confirmed; check Dolby Control"), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private Activity foregroundActivity() {
        for (Activity activity : new ArrayList<>(pages.keySet()))
            if (activity != null && !activity.isDestroyed() && activity.hasWindowFocus()) return activity;
        return null;
    }

    private static int id(Activity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", PACKAGE);
    }

    private static String words(Activity activity, String chinese, String english) {
        Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
        return "zh".equals(locale.getLanguage()) ? chinese : english;
    }

    private static void resetIds(View view) {
        view.setId(View.generateViewId());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) resetIds(group.getChildAt(i));
        }
    }

    private static void collectText(View view, ArrayList<TextView> text) {
        if (view instanceof TextView && !(view instanceof RadioButton)) text.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), text);
        }
    }

    private final class Page {
        final WeakReference<Activity> activity;
        final ViewGroup container;
        final LinearLayout row;
        final RadioButton radio;
        final View nativeMusic;
        final TextView description;
        boolean ready;
        boolean overriding;
        Runnable poll;

        Page(Activity owner, ViewGroup container, LinearLayout row, RadioButton radio, View nativeMusic, TextView description) {
            this.activity = new WeakReference<>(owner);
            this.container = container;
            this.row = row;
            this.radio = radio;
            this.nativeMusic = nativeMusic;
            this.description = description;
        }

        void syncEnabled() {
            boolean enabled = ready && nativeMusic.isEnabled();
            row.setEnabled(enabled);
            row.setAlpha(nativeMusic.getAlpha());
            radio.setEnabled(enabled);
        }

        void restoreNative() {
            radio.setChecked(false);
            Activity owner = activity.get();
            if (!overriding || owner == null) return;
            overriding = false;
            repainting = true;
            try { nativeUpdate.invoke(owner); }
            catch (Throwable error) { Log.w(TAG, "Native radio refresh failed", error); }
            finally { repainting = false; }
        }

        void unavailable() {
            try {
                ready = false;
                restoreNative();
                syncEnabled();
            } catch (Throwable error) { Log.w(TAG, "Cannot restore native display", error); }
        }

        void render(JSONObject result) {
            Activity owner = activity.get();
            if (owner == null || owner.isDestroyed() || row.getParent() != container ||
                    owner.findViewById(id(owner, "layout_dolby_atmos_mode")) != container) return;
            ready = result.optBoolean("ready");
            int actual = result.optInt("profile", -1);
            int target = result.optInt("selected_profile", -1);
            boolean active = ready && result.optBoolean("active") && nativeMusic.isEnabled();
            if (active && (actual == 8 || (actual >= 0 && actual <= 3))) {
                String[] names = {"auto", "movie", "music", "voice"};
                for (int i = 0; i < names.length; i++) {
                    RadioButton button = owner.findViewById(id(owner, "radio_dolby_atmos_mode_" + names[i]));
                    if (button != null) button.setChecked(actual == i);
                }
                radio.setChecked(actual == 8);
                overriding = true;
            } else restoreNative();
            description.setText(target == 8 && !active
                    ? words(owner, "已记住选择，等待 Dolby 处理器激活", "Selection saved; waiting for Dolby processing")
                    : words(owner, "手动空间音频模式", "Manual spatial audio mode"));
            syncEnabled();
        }

        void startPoll() {
            if (poll != null) return;
            WeakReference<Page> reference = new WeakReference<>(this);
            poll = new Runnable() {
                @Override public void run() {
                    Page page = reference.get();
                    if (page == null) return;
                    Activity owner = page.activity.get();
                    if (owner == null || owner.isDestroyed() || owner.isFinishing() ||
                            owner.findViewById(id(owner, "layout_dolby_atmos_mode")) != page.container) {
                        page.poll = null;
                        return;
                    }
                    if (owner.hasWindowFocus() && page.row.isAttachedToWindow()) {
                        page.syncEnabled();
                        enqueueStatus();
                    }
                    main.postDelayed(this, 1500);
                }
            };
            main.postDelayed(poll, 1500);
        }

        void stopPoll() {
            if (poll != null) main.removeCallbacks(poll);
            poll = null;
        }
    }
}

package io.github.dcl.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small native UI. All root work is serialized away from the main thread. */
public final class MainActivity extends Activity {
    private static final int BG = 0xfff4f4f7, WHITE = Color.WHITE;
    private static final int INK = 0xff17181c, MUTED = 0xff767982;
    private static final int BLUE = 0xff3267d6, LINE = 0xffe8e9ed, ERROR = 0xffa4443d;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Record> records = new LinkedHashMap<>();
    private final Map<String, Integer> pending = new LinkedHashMap<>();
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Map<String, String> invalidInputs = new LinkedHashMap<>();
    private final List<View> editViews = new ArrayList<>();
    private FrameLayout root;
    private Button neutralButton, presetButton, enableButton, applyButton;
    private TextView homeState, adjustLink, settingsMessage, editCount, refreshButton;
    private LinearLayout settingsBody;
    private boolean destroyed, inFlight, settingsPage, ready, active, supported, formFresh;
    private boolean moreOpen, homeNoticeError;
    private int profile = -1, settingsProfile = -1;
    private String enhancement = "unknown", formSignature = "", homeNotice = "";
    private String tableIdentity = "";
    private JSONObject restoredDraft;
    private final OnBackInvokedCallback back = this::goBack;

    private interface Job { JSONObject run() throws Exception; }
    private interface Reply { void accept(JSONObject result, Exception error); }

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setForceDarkAllowed(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });
        setContentView(root);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, back);
        if (savedState != null) {
            moreOpen = savedState.getBoolean("more_open", false);
            try { restoredDraft = new JSONObject(savedState.getString("edit_draft", "{}")); }
            catch (Exception ignored) { restoredDraft = null; }
        }
        if (savedState != null && savedState.getBoolean("settings_page", false)) showSettings();
        else showHome();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("settings_page", settingsPage);
        state.putBoolean("more_open", moreOpen);
        try {
            if (restoredDraft != null) {
                state.putString("edit_draft", restoredDraft.toString());
                return;
            }
            JSONObject draft = new JSONObject().put("profile", settingsProfile)
                    .put("table_identity", tableIdentity).put("baseline", baseline(records))
                    .put("pending", new JSONObject(pending));
            JSONObject invalid = new JSONObject();
            for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
                if (entry.getValue().invalid) invalid.put(entry.getKey(), entry.getValue().inputText());
            }
            draft.put("invalid", invalid);
            state.putString("edit_draft", draft.toString());
        } catch (Exception ignored) { /* A fresh read is safer than a partial draft. */ }
    }

    @Override public void onResume() {
        super.onResume();
        if (!inFlight) {
            if (settingsPage) fetchSettings(); else fetchStatus();
        }
    }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(back);
        worker.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() { goBack(); }

    private void goBack() {
        if (settingsPage) {
            showHome();
            if (!inFlight) fetchStatus();
        } else finish();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static boolean supportedProfile(int value) {
        return value == 0 || value == 1 || value == 2 || value == 3 || value == 8;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x153267d6), shape(color, radius), null);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(BLUE);
        button.setTypeface(Typeface.create(Typeface.SANS_SERIF, 600, false));
        button.setMinimumHeight(dp(48));
        button.setMinHeight(dp(48));
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setBackground(ripple(WHITE, 14));
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void gap(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private void line(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(dp(20), 0, dp(20), 0);
        parent.addView(divider, params);
    }

    private void showHome() {
        settingsPage = false;
        formFresh = false;
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout content = vertical();
        content.setPadding(dp(24), dp(54), dp(24), dp(32));
        TextView title = text("音效", 38, INK);
        title.setTypeface(Typeface.create(Typeface.SANS_SERIF, 600, false));
        content.addView(title);
        gap(content, 43);

        LinearLayout enhancementBox = vertical();
        enhancementBox.setPadding(dp(20), dp(21), dp(20), dp(20));
        enhancementBox.setBackground(shape(WHITE, 24));
        TextView label = text("增强处理", 18, INK);
        label.setTypeface(Typeface.create(Typeface.SANS_SERIF, 600, false));
        enhancementBox.addView(label);
        gap(enhancementBox, 17);
        LinearLayout segments = horizontal();
        segments.setPadding(dp(4), dp(4), dp(4), dp(4));
        segments.setBackground(shape(0xfff0f1f5, 15));
        neutralButton = button("关闭增强");
        presetButton = button("三星预设");
        segments.addView(neutralButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        segments.addView(presetButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        neutralButton.setOnClickListener(view -> setEnhancement("neutral"));
        presetButton.setOnClickListener(view -> setEnhancement("preset"));
        enhancementBox.addView(segments, matchWrap());
        TextView preserve = text("保留空间效果", 13, MUTED);
        preserve.setPadding(0, dp(13), 0, 0);
        enhancementBox.addView(preserve);
        content.addView(enhancementBox, matchWrap());
        gap(content, 13);

        LinearLayout navigate = horizontal();
        navigate.setPadding(dp(20), dp(19), dp(19), dp(19));
        navigate.setMinimumHeight(dp(65));
        navigate.setBackground(ripple(WHITE, 22));
        adjustLink = text("声音调节", 17, INK);
        navigate.addView(adjustLink, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView chevron = text("›", 28, 0xffa5a7ae);
        navigate.addView(chevron);
        navigate.setContentDescription("声音调节，打开详细设置");
        navigate.setFocusable(true);
        navigate.setOnClickListener(view -> {
            if (ready && !inFlight) showSettings();
        });
        content.addView(navigate, matchWrap());

        homeState = text("正在读取…", 13, MUTED);
        homeState.setPadding(dp(5), dp(18), dp(5), dp(8));
        homeState.setOnClickListener(view -> { if (!inFlight && ready) fetchStatus(); });
        content.addView(homeState, matchWrap());
        enableButton = button("启用");
        enableButton.setVisibility(View.GONE);
        enableButton.setOnClickListener(view -> enable());
        LinearLayout.LayoutParams enableParams = new LinearLayout.LayoutParams(dp(112), dp(48));
        enableParams.topMargin = dp(5);
        content.addView(enableButton, enableParams);
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        bindHome();
    }

    private void bindSegment(Button button, boolean selected, boolean enabled) {
        button.setSelected(selected);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .45f);
        button.setTextColor(selected ? BLUE : MUTED);
        button.setBackground(ripple(selected ? WHITE : Color.TRANSPARENT, 12));
        button.setContentDescription(button.getText() + (selected ? "，已选择" : ""));
    }

    private void bindHome() {
        if (settingsPage || homeState == null) return;
        boolean knownScope = ready && active && supported && supportedProfile(profile);
        boolean canChange = knownScope && !inFlight;
        bindSegment(neutralButton, knownScope && "neutral".equals(enhancement), canChange);
        bindSegment(presetButton, knownScope && "preset".equals(enhancement), canChange);
        adjustLink.setAlpha(ready ? 1f : .45f);
        enableButton.setEnabled(!inFlight);
        enableButton.setVisibility(!ready && !inFlight ? View.VISIBLE : View.GONE);
        if (inFlight) homeState.setText("正在处理…");
        else if (!homeNotice.isEmpty()) homeState.setText(homeNotice);
        else if (!ready) homeState.setText("启用后可调整音效，需要 Root 授权。");
        else if (!active) homeState.setText("空间效果尚未激活，播放音频后轻点重试。");
        else if (!supported || !supportedProfile(profile)) homeState.setText("当前声音模式暂不支持增强调节。");
        else if ("custom".equals(enhancement)) homeState.setText("已使用自定义调节");
        else if ("unknown".equals(enhancement)) homeState.setText("增强状态暂未读到，轻点重试。");
        else homeState.setText("");
        homeState.setTextColor(homeNotice.isEmpty() || !homeNoticeError ? MUTED : ERROR);
    }

    private void fetchStatus() {
        run(() -> RootClient.call("status", new JSONObject()), (result, error) -> {
            if (error != null || result == null || !result.optBoolean("ok", false)) {
                ready = false; active = false; supported = false; enhancement = "unknown";
            } else {
                ready = result.optBoolean("ready", false);
                active = result.optBoolean("active", false);
                supported = result.optBoolean("enhancement_supported", false);
                profile = result.optInt("profile", -1);
                enhancement = result.optString("enhancement", "unknown");
            }
            bindHome();
        });
    }

    private void enable() {
        final Context context = getApplicationContext();
        run(() -> {
            RootClient.ensureStarted(context);
            return RootClient.call("status", new JSONObject());
        }, (result, error) -> {
            if (error == null && result != null && result.optBoolean("ok", false)) {
                ready = result.optBoolean("ready", false);
                active = result.optBoolean("active", false);
                supported = result.optBoolean("enhancement_supported", false);
                profile = result.optInt("profile", -1);
                enhancement = result.optString("enhancement", "unknown");
            } else { ready = false; active = false; supported = false; }
            bindHome();
            if (!ready && !settingsPage) showHomeError("尚未启用，请允许 Root 授权后重试。");
        });
    }

    private void setEnhancement(String mode) {
        if (!ready || !active || !supported || mode.equals(enhancement)) return;
        run(() -> {
            JSONObject changed = RootClient.call("enhancement", new JSONObject().put("mode", mode));
            if (!changed.optBoolean("ok", false)) return changed;
            JSONObject answer = new JSONObject().put("ok", true);
            if (changed.has("ready") && changed.has("enhancement")) answer.put("status", changed);
            else try { answer.put("status", RootClient.call("status", new JSONObject())); }
            catch (Exception ignored) { answer.put("read_failed", true); }
            return answer;
        }, (result, error) -> {
            if (error != null || result == null || !result.optBoolean("ok", false)) {
                enhancement = "unknown";
                bindHome();
                if (!settingsPage) showHomeError("调整未完成，请稍后重试。");
                return;
            }
            JSONObject status = result.optJSONObject("status");
            if (status != null && status.optBoolean("ok", false)) {
                active = status.optBoolean("active", false);
                supported = status.optBoolean("enhancement_supported", false);
                profile = status.optInt("profile", -1);
                enhancement = status.optString("enhancement", "unknown");
                pending.clear();
                invalidInputs.clear();
                restoredDraft = null;
                if (status.optBoolean("pending", false) || !status.optBoolean("dsp_observed", false)) {
                    homeNotice = editMessage(status);
                    homeNoticeError = false;
                }
                bindHome();
            } else {
                enhancement = "unknown";
                bindHome();
                if (!settingsPage) showHomeError("指令已提交，状态暂未读到，轻点重试。");
            }
        });
    }

    private void showHomeError(String text) {
        homeNotice = text;
        homeNoticeError = true;
        bindHome();
    }

    private void showSettings() {
        settingsPage = true;
        formFresh = false;
        root.removeAllViews();
        LinearLayout page = vertical();
        LinearLayout toolbar = horizontal();
        toolbar.setPadding(dp(8), dp(8), dp(12), dp(8));
        Button previous = button("‹");
        previous.setTextSize(31);
        previous.setTextColor(INK);
        previous.setBackground(ripple(Color.TRANSPARENT, 16));
        previous.setContentDescription("返回音效");
        previous.setOnClickListener(view -> goBack());
        toolbar.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("声音调节", 24, INK);
        title.setTypeface(Typeface.create(Typeface.SANS_SERIF, 600, false));
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        refreshButton = text("刷新", 14, BLUE);
        refreshButton.setPadding(dp(12), dp(14), dp(8), dp(14));
        refreshButton.setBackground(ripple(Color.TRANSPARENT, 12));
        refreshButton.setOnClickListener(view -> {
            if (inFlight) return;
            if (!pending.isEmpty() || hasInvalid()) {
                pending.clear();
                invalidInputs.clear();
                for (Binding binding : bindings.values()) binding.bind(binding.record);
                updateEditFooter();
            }
            fetchSettings();
        });
        toolbar.addView(refreshButton);
        page.addView(toolbar, matchWrap());
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        settingsBody = vertical();
        settingsBody.setPadding(dp(22), dp(7), dp(22), dp(20));
        settingsMessage = text("读取声音设置…", 13, MUTED);
        settingsMessage.setPadding(dp(3), dp(3), dp(3), dp(18));
        settingsBody.addView(settingsMessage, matchWrap());
        scroll.addView(settingsBody);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = horizontal();
        footer.setPadding(dp(24), dp(10), dp(22), dp(14));
        editCount = text("", 12, MUTED);
        footer.addView(editCount, new LinearLayout.LayoutParams(0, -2, 1));
        applyButton = button("应用调整");
        applyButton.setTextColor(WHITE);
        applyButton.setBackground(ripple(BLUE, 16));
        applyButton.setOnClickListener(view -> applyEdits());
        footer.addView(applyButton, new LinearLayout.LayoutParams(dp(132), dp(48)));
        page.addView(footer, matchWrap());
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        formSignature = "";
        bindings.clear(); editViews.clear();
        // Always validate a retained draft against a fresh profile/table read.
        // The user may have changed the native Dolby page while this page was away.
        fetchSettings();
    }

    private void fetchSettings() {
        captureInvalidInputs();
        formFresh = false;
        run(() -> RootClient.call("settings", new JSONObject()), (result, error) -> {
            if (!settingsPage) return;
            if (error != null || result == null || !result.optBoolean("ok", false)) {
                settingsMessage.setText("暂时无法读取，轻点右上角重试。");
                settingsMessage.setTextColor(ERROR);
                formFresh = false;
                updateEditFooter();
                return;
            }
            installSettings(result, false);
        });
    }

    private void installSettings(JSONObject data, boolean afterApply) {
        boolean isActive = data.optBoolean("active", false);
        int actualProfile = data.optInt("profile", -1);
        String identity = data.optString("table_identity", "");
        Map<String, Record> fresh = new LinkedHashMap<>();
        JSONArray list = data.optJSONArray("records");
        if (isActive && supportedProfile(actualProfile) && list != null) {
            for (int i = 0; i < list.length(); i++) {
                Record record = Record.read(list.optJSONObject(i), actualProfile);
                if (record != null) fresh.put(record.id(), record);
            }
        }
        boolean hadDraft = !pending.isEmpty() || !invalidInputs.isEmpty();
        boolean scopeChanged = hadDraft && (settingsProfile != actualProfile ||
                !sameBaseline(baseline(records), fresh) || !sameIdentity(tableIdentity, identity));
        if (afterApply || scopeChanged) { pending.clear(); invalidInputs.clear(); }
        ready = data.optBoolean("ready", false);
        supported = data.optBoolean("enhancement_supported", false);
        enhancement = data.optString("enhancement", "unknown");
        active = isActive; profile = actualProfile; settingsProfile = actualProfile;
        tableIdentity = identity;
        records.clear(); records.putAll(fresh);
        if (restoredDraft != null) {
            JSONObject draft = restoredDraft;
            restoredDraft = null;
            JSONObject values = draft.optJSONObject("pending"), invalid = draft.optJSONObject("invalid");
            boolean savedEdits = (values != null && values.length() > 0) || (invalid != null && invalid.length() > 0);
            if (savedEdits && draft.optInt("profile", -99) == actualProfile &&
                    sameBaseline(draft.optJSONArray("baseline"), fresh) &&
                    sameIdentity(draft.optString("table_identity", ""), identity)) {
                if (values != null) for (String id : fresh.keySet())
                    if (values.has(id)) pending.put(id, values.optInt(id));
                if (invalid != null) for (String id : fresh.keySet())
                    if (invalid.has(id)) invalidInputs.put(id, invalid.optString(id));
            } else if (savedEdits) scopeChanged = true;
        }
        for (String id : new ArrayList<>(pending.keySet())) {
            Record record = records.get(id);
            int value = pending.get(id);
            if (record == null || value < record.minimum || value > record.maximum || value == record.current) pending.remove(id);
        }
        String signature = actualProfile + ":" + records.keySet().toString();
        if (!signature.equals(formSignature)) buildForm();
        else for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            Record record = records.get(entry.getKey());
            if (record != null) entry.getValue().bind(record);
        }
        formFresh = ready && isActive && supportedProfile(actualProfile) && list != null;
        if (afterApply && data.optBoolean("pending", false)) settingsMessage.setText(editMessage(data));
        else if (!isActive) settingsMessage.setText("空间效果尚未激活，播放音频后刷新。");
        else if (records.isEmpty()) settingsMessage.setText("当前声音模式没有可调项目。");
        else settingsMessage.setText(scopeChanged ? "声音设置已变化，未应用的调整已清除。" :
                afterApply ? editMessage(data) : "调整只在点“应用调整”后生效。");
        settingsMessage.setTextColor(MUTED);
        updateEditFooter();
    }

    private static String editMessage(JSONObject result) {
        if (result.optBoolean("pending", false)) return "调整已保存，等待声音模式激活。";
        if (!result.optBoolean("dsp_observed", false)) return "设置已保存，尚未确认实时生效。";
        return "调整已应用";
    }

    private static boolean sameIdentity(String before, String after) {
        return before.isEmpty() || after.isEmpty() || before.equals(after);
    }

    private static JSONArray baseline(Map<String, Record> source) {
        JSONArray result = new JSONArray();
        for (Record record : source.values()) {
            try { result.put(new JSONObject().put("profile", record.profile).put("key", record.key).put("current", record.current)); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        return result;
    }

    private static boolean sameBaseline(JSONArray before, Map<String, Record> after) {
        if (before == null || before.length() != after.size()) return false;
        for (int i = 0; i < before.length(); i++) {
            JSONObject previous = before.optJSONObject(i);
            if (previous == null) return false;
            Record record = after.get(previous.optInt("profile", -99) + ":" + previous.optString("key", ""));
            if (record == null || !previous.has("current") || record.current != previous.optInt("current")) return false;
        }
        return true;
    }

    private void captureInvalidInputs() {
        for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            if (entry.getValue().invalid) invalidInputs.put(entry.getKey(), entry.getValue().inputText());
        }
    }

    private Record find(String key) {
        for (Record record : records.values()) if (record.key.equals(key)) return record;
        return null;
    }

    private LinearLayout group(String title) {
        TextView heading = text(title, 14, MUTED);
        heading.setPadding(dp(5), dp(18), dp(5), dp(10));
        settingsBody.addView(heading, matchWrap());
        LinearLayout body = vertical();
        body.setBackground(shape(WHITE, 20));
        settingsBody.addView(body, matchWrap());
        return body;
    }

    private void buildForm() {
        if (settingsBody == null) return;
        settingsBody.removeAllViews();
        settingsBody.addView(settingsMessage, matchWrap());
        bindings.clear(); editViews.clear();
        formSignature = settingsProfile + ":" + records.keySet().toString();
        if (records.isEmpty()) return;

        if (find("ieq-enable") != null) {
            LinearLayout body = group("智能均衡");
            addToggle(body, find("ieq-enable"), "智能均衡", null);
        }
        if (find("dialog-enhancer-enable") != null || find("dialog-enhancer-amount") != null) {
            LinearLayout body = group("对白");
            addToggle(body, find("dialog-enhancer-enable"), "对白增强", null);
            if (find("dialog-enhancer-amount") != null) {
                if (find("dialog-enhancer-enable") != null) line(body);
                addNumber(body, find("dialog-enhancer-amount"), "增强强度", "所有声音模式共用");
            }
        }
        if (find("volume-leveler-enable") != null || find("volume-leveler-amount") != null) {
            LinearLayout body = group("音量");
            addToggle(body, find("volume-leveler-enable"), "音量均衡", "DDP / Atmos 播放时系统可能保持关闭");
            if (find("volume-leveler-amount") != null) {
                if (find("volume-leveler-enable") != null) line(body);
                addNumber(body, find("volume-leveler-amount"), "均衡强度", "关闭音量均衡时不生效");
            }
        }

        String[] moreKeys = {"mi-ieq-steering-enable", "mi-dialog-enhancer-steering-enable",
                "mi-dv-leveler-steering-enable", "mi-surround-compressor-steering-enable",
                "volume-leveler-in-target", "volume-leveler-out-target", "volume-max-boost"};
        boolean hasMore = false;
        for (String key : moreKeys) if (find(key) != null) hasMore = true;
        if (!hasMore) return;
        gap(settingsBody, 20);
        LinearLayout more = vertical();
        more.setBackground(shape(WHITE, 20));
        LinearLayout disclosure = horizontal();
        disclosure.setPadding(dp(19), dp(17), dp(19), dp(17));
        disclosure.setBackground(ripple(Color.TRANSPARENT, 20));
        TextView label = text("更多设置", 16, INK);
        disclosure.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text(moreOpen ? "⌃" : "⌄", 21, MUTED);
        disclosure.addView(arrow);
        more.addView(disclosure, matchWrap());
        LinearLayout details = vertical();
        details.setVisibility(moreOpen ? View.VISIBLE : View.GONE);
        more.addView(details, matchWrap());
        disclosure.setContentDescription("更多设置，" + (moreOpen ? "已展开" : "已折叠"));
        disclosure.setFocusable(true);
        disclosure.setOnClickListener(view -> {
            moreOpen = !moreOpen;
            details.setVisibility(moreOpen ? View.VISIBLE : View.GONE);
            arrow.setText(moreOpen ? "⌃" : "⌄");
            disclosure.setContentDescription("更多设置，" + (moreOpen ? "已展开" : "已折叠"));
        });
        String[] steering = {"智能均衡自动引导", "对白自动引导", "音量均衡自动引导", "环绕压缩自动引导"};
        for (int i = 0; i < 4; i++) if (find(moreKeys[i]) != null) {
            line(details);
            addToggle(details, find(moreKeys[i]), steering[i], "MI2 引导，与增强开关独立");
        }
        String[] names = {"音量输入目标", "音量输出目标", "最大增益"};
        for (int i = 4; i < moreKeys.length; i++) if (find(moreKeys[i]) != null) {
            line(details);
            addNumber(details, find(moreKeys[i]), names[i - 4], "所有声音模式共用 · 原始整数");
        }
        settingsBody.addView(more, matchWrap());
        updateEditFooter();
    }

    private void addToggle(LinearLayout parent, Record record, String label, String note) {
        if (record == null || !record.flag) return;
        LinearLayout row = horizontal();
        row.setPadding(dp(19), dp(15), dp(18), dp(15));
        LinearLayout wording = vertical();
        wording.addView(text(label, 16, INK));
        if (note != null) {
            TextView hint = text(note, 11, MUTED);
            hint.setPadding(0, dp(4), dp(5), 0);
            wording.addView(hint);
        }
        row.addView(wording, new LinearLayout.LayoutParams(0, -2, 1));
        Switch control = new Switch(this);
        control.setShowText(false);
        control.setContentDescription(label);
        control.setThumbTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{BLUE, 0xffb7bbc4}));
        control.setTrackTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{0xffc7d8fc, 0xffe0e2e8}));
        row.addView(control);
        parent.addView(row, matchWrap());
        Binding binding = new Binding(record) {
            @Override void show(int value) { control.setChecked(value != 0); }
        };
        bindings.put(record.id(), binding);
        control.setOnCheckedChangeListener((view, checked) -> {
            if (!binding.binding) changed(binding, checked ? 1 : 0);
        });
        editViews.add(control);
        binding.bind(record);
    }

    private void addNumber(LinearLayout parent, Record record, String label, String note) {
        if (record == null || record.flag) return;
        LinearLayout body = vertical();
        body.setPadding(dp(19), dp(14), dp(18), dp(15));
        LinearLayout title = horizontal();
        title.addView(text(label, 15, INK), new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(title, matchWrap());
        boolean smallRange = record.minimum >= 0 && record.maximum <= 9;
        if (smallRange) {
            TextView amount = text("", 15, BLUE);
            title.addView(amount);
            SeekBar slider = new SeekBar(this);
            slider.setMin(record.minimum);
            slider.setMax(record.maximum);
            slider.setProgressTintList(ColorStateList.valueOf(BLUE));
            slider.setThumbTintList(ColorStateList.valueOf(BLUE));
            slider.setContentDescription(label);
            LinearLayout.LayoutParams sliderParams = matchWrap();
            sliderParams.topMargin = dp(8);
            body.addView(slider, sliderParams);
            Binding binding = new Binding(record) {
                @Override void show(int value) { slider.setProgress(value); amount.setText(String.valueOf(value)); }
            };
            bindings.put(record.id(), binding);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                    amount.setText(String.valueOf(value));
                    if (fromUser && !binding.binding) changed(binding, value);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            });
            editViews.add(slider);
            binding.bind(record);
        } else {
            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setTextSize(15);
            input.setTextColor(INK);
            input.setGravity(Gravity.END);
            input.setPadding(dp(9), dp(8), dp(9), dp(8));
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
            input.setSelectAllOnFocus(true);
            input.setBackground(shape(0xfff2f3f6, 9));
            input.setContentDescription(label + "，范围" + record.minimum + "至" + record.maximum);
            title.addView(input, new LinearLayout.LayoutParams(dp(94), dp(43)));
            Binding binding = new Binding(record) {
                @Override void show(int value) {
                    String target = String.valueOf(value);
                    if (!target.equals(input.getText().toString())) input.setText(target);
                    input.setTextColor(INK);
                }
                @Override String inputText() { return input.getText().toString(); }
                @Override void restoreInvalid(String value) {
                    input.setText(value);
                    input.setTextColor(ERROR);
                }
            };
            bindings.put(record.id(), binding);
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (binding.binding) return;
                    try {
                        int value = Integer.parseInt(s.toString());
                        if (value < binding.record.minimum || value > binding.record.maximum) throw new NumberFormatException();
                        binding.invalid = false;
                        input.setTextColor(INK);
                        changed(binding, value);
                    } catch (NumberFormatException invalid) {
                        binding.invalid = true;
                        invalidInputs.put(binding.record.id(), s.toString());
                        input.setTextColor(ERROR);
                        updateEditFooter();
                    }
                }
                @Override public void afterTextChanged(Editable editable) { }
            });
            editViews.add(input);
            binding.bind(record);
        }
        TextView hint = text(note + " · " + record.minimum + "～" + record.maximum, 11, MUTED);
        hint.setPadding(0, dp(6), 0, 0);
        body.addView(hint);
        parent.addView(body, matchWrap());
    }

    private abstract class Binding {
        Record record;
        boolean binding, invalid;
        Binding(Record record) { this.record = record; }
        final void bind(Record updated) {
            record = updated;
            invalid = false;
            binding = true;
            try {
                show(pending.containsKey(record.id()) ? pending.get(record.id()) : record.current);
                if (invalidInputs.containsKey(record.id())) {
                    restoreInvalid(invalidInputs.get(record.id()));
                    invalid = true;
                }
            }
            finally { binding = false; }
        }
        String inputText() { return ""; }
        void restoreInvalid(String value) { }
        abstract void show(int value);
    }

    private void changed(Binding binding, int value) {
        binding.invalid = false;
        invalidInputs.remove(binding.record.id());
        if (value == binding.record.current) pending.remove(binding.record.id());
        else pending.put(binding.record.id(), value);
        updateEditFooter();
    }

    private boolean hasInvalid() {
        for (Binding binding : bindings.values()) if (binding.invalid) return true;
        return false;
    }

    private void updateEditFooter() {
        if (!settingsPage || applyButton == null) return;
        boolean available = formFresh && ready && active && supportedProfile(settingsProfile);
        boolean enabled = available && !inFlight;
        for (View control : editViews) control.setEnabled(enabled);
        applyButton.setEnabled(enabled && !pending.isEmpty() && !hasInvalid());
        applyButton.setVisibility(records.isEmpty() ? View.GONE : View.VISIBLE);
        applyButton.setAlpha(applyButton.isEnabled() ? 1f : .4f);
        applyButton.setText(inFlight ? "正在处理…" : "应用调整");
        editCount.setText(hasInvalid() ? "请填写范围内的整数" : pending.isEmpty() ? "" : pending.size() + " 项未应用");
        editCount.setTextColor(hasInvalid() ? ERROR : MUTED);
        refreshButton.setEnabled(!inFlight);
        refreshButton.setText(!pending.isEmpty() || hasInvalid() ? "还原" : "刷新");
    }

    private void applyEdits() {
        if (!formFresh || !ready || !active || inFlight || pending.isEmpty() || hasInvalid()) return;
        final JSONObject args = new JSONObject();
        final JSONArray edits = new JSONArray();
        final JSONArray expected = new JSONArray();
        try {
            for (Map.Entry<String, Integer> entry : pending.entrySet()) {
                Record record = records.get(entry.getKey());
                if (record == null) continue;
                edits.put(new JSONObject().put("profile", record.profile).put("key", record.key).put("value", entry.getValue()));
                expected.put(new JSONObject().put("profile", record.profile).put("key", record.key).put("current", record.current));
            }
            if (edits.length() == 0) return;
            args.put("edits", edits);
            args.put("expected_records", expected).put("expected_profile", settingsProfile);
            if (!tableIdentity.isEmpty()) args.put("expected_table_identity", tableIdentity);
        } catch (Exception invalid) { return; }
        run(() -> {
            JSONObject changed = RootClient.call("edit", args);
            if (!changed.optBoolean("ok", false)) return changed;
            JSONObject answer = new JSONObject().put("ok", true);
            if (changed.optJSONArray("records") != null) answer.put("settings", changed);
            else if (changed.optJSONObject("settings") != null) answer.put("settings", changed.optJSONObject("settings"));
            else try { answer.put("settings", RootClient.call("settings", new JSONObject())); }
            catch (Exception ignored) { answer.put("read_failed", true); }
            return answer;
        }, (result, error) -> {
            if (error != null || result == null || !result.optBoolean("ok", false)) {
                enhancement = "unknown";
                formFresh = false;
                if (settingsPage) {
                    settingsMessage.setText("调整未完成，声音状态可能已变化，请还原后刷新。");
                    settingsMessage.setTextColor(ERROR);
                    updateEditFooter();
                } else showHomeError("调整未完成，请轻点重试。");
                return;
            }
            pending.clear();
            invalidInputs.clear();
            JSONObject fresh = result.optJSONObject("settings");
            if (fresh != null && fresh.optBoolean("ok", false)) {
                if (settingsPage) installSettings(fresh, true);
                else { active = fresh.optBoolean("active", false); profile = fresh.optInt("profile", -1); }
            } else if (settingsPage) {
                settingsMessage.setText("指令已提交，状态暂未读到，请刷新。");
                settingsMessage.setTextColor(MUTED);
                active = false;
                updateEditFooter();
            }
            enhancement = "unknown";
            if (!settingsPage) fetchStatus();
        });
    }

    private void run(Job job, Reply reply) {
        if (inFlight || destroyed) return;
        inFlight = true;
        homeNotice = "";
        bindHome(); updateEditFooter();
        worker.execute(() -> {
            JSONObject answer = null;
            Exception failure = null;
            try { answer = job.run(); }
            catch (Exception error) { failure = error; }
            final JSONObject result = answer;
            final Exception error = failure;
            main.post(() -> {
                if (destroyed) return;
                inFlight = false;
                reply.accept(result, error);
                bindHome(); updateEditFooter();
            });
        });
    }

    private static final class Record {
        final int profile, current, original, minimum, maximum;
        final String key;
        final boolean flag;
        Record(JSONObject data) {
            profile = data.optInt("profile", -99); key = data.optString("key", "");
            current = data.optInt("current"); original = data.optInt("original");
            minimum = data.optInt("minimum"); maximum = data.optInt("maximum");
            flag = data.optBoolean("is_flag", false);
        }
        String id() { return profile + ":" + key; }
        static Record read(JSONObject data, int activeProfile) {
            if (data == null || !data.has("current") || !data.has("minimum") || !data.has("maximum")) return null;
            Record record = new Record(data);
            if (record.profile != -1 && record.profile != activeProfile) return null;
            if (record.minimum > record.maximum || record.current < record.minimum || record.current > record.maximum) return null;
            if (record.flag && (record.minimum != 0 || record.maximum != 1)) return null;
            return record;
        }
    }
}

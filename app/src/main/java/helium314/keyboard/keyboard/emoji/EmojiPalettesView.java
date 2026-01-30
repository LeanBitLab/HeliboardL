/*
 * Copyright (C) 2013 The Android Open Source Project
 * Copyright (C) 2026 LeanBitLab
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.emoji;

import java.util.HashMap;
import java.util.Map;

import helium314.keyboard.latin.BuildConfig;
import android.content.Intent;
import android.widget.Button;
import android.os.Build;
import helium314.keyboard.settings.SettingsActivity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Environment;
import helium314.keyboard.latin.utils.DictionaryInfoUtils;
import helium314.keyboard.latin.common.Links;
import helium314.keyboard.latin.dictionary.Dictionary;
import java.io.File;
import android.widget.Toast;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.Key;
import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.keyboard.KeyboardActionListener;
import helium314.keyboard.keyboard.KeyboardId;
import helium314.keyboard.keyboard.KeyboardLayoutSet;
import helium314.keyboard.keyboard.KeyboardSwitcher;
import helium314.keyboard.keyboard.KeyboardView;
import helium314.keyboard.keyboard.MainKeyboardView;
import helium314.keyboard.keyboard.PointerTracker;
import helium314.keyboard.keyboard.internal.KeyDrawParams;
import helium314.keyboard.keyboard.internal.KeyVisualAttributes;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.AudioAndHapticFeedbackManager;
import helium314.keyboard.latin.SingleDictionaryFacilitator;
import helium314.keyboard.latin.dictionary.Dictionary;
import helium314.keyboard.latin.dictionary.DictionaryFactory;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.SingleDictionaryFacilitator;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.utils.DictionaryInfoUtils;
import helium314.keyboard.latin.utils.ResourceUtils;

import static helium314.keyboard.latin.common.Constants.NOT_A_COORDINATE;

/**
 * View class to implement Emoji palettes.
 * The Emoji keyboard consists of group of views layout/emoji_palettes_view.
 * <ol>
 * <li>Emoji category tabs.
 * <li>Delete button.
 * <li>Emoji keyboard pages that can be scrolled by swiping horizontally or by
 * selecting a tab.
 * <li>Back to main keyboard button and enter button.
 * </ol>
 * Because of the above reasons, this class doesn't extend {@link KeyboardView}.
 */
public final class EmojiPalettesView extends LinearLayout
        implements View.OnClickListener, EmojiViewCallback {
    private static final class PagerViewHolder extends RecyclerView.ViewHolder {
        private long mCategoryId;

        private PagerViewHolder(View itemView) {
            super(itemView);
        }
    }

    private final class PagerAdapter extends RecyclerView.Adapter<PagerViewHolder> {
        private boolean mInitialized;
        private final Map<Integer, RecyclerView> mViews = new HashMap<>(mEmojiCategory.getShownCategories().size());

        private PagerAdapter(ViewPager2 pager) {
            setHasStableIds(true);
            pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    var categoryId = (int) getItemId(position);
                    setCurrentCategoryId(categoryId, false);
                    var recyclerView = mViews.get(position);
                    if (recyclerView != null) {
                        updateState(recyclerView, categoryId);
                    }
                }
            });
        }

        @Override
        public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
            recyclerView.setItemViewCacheSize(mEmojiCategory.getShownCategories().size());
        }

        @NonNull
        @Override
        public PagerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            var view = LayoutInflater.from(parent.getContext()).inflate(R.layout.emoji_category_view, parent, false);
            var viewHolder = new PagerViewHolder(view);
            var emojiRecyclerView = getRecyclerView(view);

            emojiRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    // Ignore this message. Only want the actual page selected.
                }

                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    updateState(recyclerView, viewHolder.mCategoryId);
                }
            });

            emojiRecyclerView.setPersistentDrawingCache(PERSISTENT_NO_CACHE);
            return viewHolder;
        }

        @Override
        public void onBindViewHolder(PagerViewHolder holder, int position) {
            holder.mCategoryId = getItemId(position);
            var recyclerView = getRecyclerView(holder.itemView);
            mViews.put(position, recyclerView);
            recyclerView.setAdapter(new EmojiPalettesAdapter(mEmojiCategory, (int) holder.mCategoryId,
                    EmojiPalettesView.this));

            if (!mInitialized) {
                recyclerView.scrollToPosition(mEmojiCategory.getCurrentCategoryPageId());
                mInitialized = true;
            }
        }

        @Override
        public int getItemCount() {
            return mEmojiCategory.getShownCategories().size();
        }

        @Override
        public void onViewDetachedFromWindow(PagerViewHolder holder) {
            if (holder.mCategoryId == EmojiCategory.ID_RECENTS) {
                // Needs to save pending updates for recent keys when we get out of the recents
                // category because we don't want to move the recent emojis around while the
                // user
                // is in the recents category.
                getRecentsKeyboard().flushPendingRecentKeys();
                getRecyclerView(holder.itemView).getAdapter().notifyDataSetChanged();
            }
        }

        @Override
        public long getItemId(int position) {
            return mEmojiCategory.getShownCategories().get(position).mCategoryId;
        }

        private static RecyclerView getRecyclerView(View view) {
            return view.findViewById(R.id.emoji_keyboard_list);
        }

        private void updateState(@NonNull RecyclerView recyclerView, long categoryId) {
            if (categoryId != mEmojiCategory.getCurrentCategoryId()) {
                return;
            }

            final int offset = recyclerView.computeVerticalScrollOffset();
            final int extent = recyclerView.computeVerticalScrollExtent();
            final int range = recyclerView.computeVerticalScrollRange();
            final float percentage = offset / (float) (range - extent);

            final int currentCategorySize = mEmojiCategory.getCurrentCategoryPageCount();
            final int a = (int) (percentage * currentCategorySize);
            final float b = percentage * currentCategorySize - a;
            mEmojiCategoryPageIndicatorView.setCategoryPageId(currentCategorySize, a, b);

            LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
            final int firstCompleteVisibleBoard = layoutManager.findFirstCompletelyVisibleItemPosition();
            final int firstVisibleBoard = layoutManager.findFirstVisibleItemPosition();
            mEmojiCategory.setCurrentCategoryPageId(
                    firstCompleteVisibleBoard > 0 ? firstCompleteVisibleBoard : firstVisibleBoard);
        }
    }

    private static SingleDictionaryFacilitator sDictionaryFacilitator;

    private boolean initialized = false;
    private final Colors mColors;
    private final EmojiLayoutParams mEmojiLayoutParams;
    private LinearLayout mTabStrip;
    private EmojiCategoryPageIndicatorView mEmojiCategoryPageIndicatorView;
    private KeyboardActionListener mKeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER;
    private final EmojiCategory mEmojiCategory;
    private ViewPager2 mPager;

    private static final int ID_SEARCH_TAB = -2;
    private LinearLayout mSearchContainer;
    private RecyclerView mSearchResultsList;
    private TextView mSearchEmptyView;
    private EmojiSearchAdapter mSearchAdapter;
    private EditText mSearchBar;
    private boolean mInSearchMode = false;
    private KeyboardActionListener mOriginalActionListener;

    private EditorInfo mEditorInfo;

    private long mDownloadId = -1;
    private final BroadcastReceiver mDownloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == mDownloadId) {
                mDownloadId = -1;
                handleDownloadCompletion(context);
            }
        }
    };

    public EmojiPalettesView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.emojiPalettesViewStyle);
    }

    public EmojiPalettesView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
        mColors = Settings.getValues().mColors;
        final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(context, null);
        final Resources res = context.getResources();
        mEmojiLayoutParams = new EmojiLayoutParams(res);
        builder.setSubtype(RichInputMethodSubtype.Companion.getEmojiSubtype());
        builder.setKeyboardGeometry(ResourceUtils.getKeyboardWidth(context, Settings.getValues()),
                mEmojiLayoutParams.getEmojiKeyboardHeight());
        final KeyboardLayoutSet layoutSet = builder.build();
        final TypedArray emojiPalettesViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.EmojiPalettesView, defStyle, R.style.EmojiPalettesView);
        mEmojiCategory = new EmojiCategory(context, layoutSet, emojiPalettesViewAttr);
        emojiPalettesViewAttr.recycle();
        setFitsSystemWindows(true);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final Resources res = getContext().getResources();
        // The main keyboard expands to the entire this {@link KeyboardView}.
        final int width = ResourceUtils.getKeyboardWidth(getContext(), Settings.getValues())
                + getPaddingLeft() + getPaddingRight();
        final int height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues())
                + getPaddingTop() + getPaddingBottom();
        mEmojiCategoryPageIndicatorView.mWidth = width;
        setMeasuredDimension(width, height);
    }

    public void initialize() { // needs to be delayed for access to EmojiTabStrip, which is not a child of this
                               // view
        if (initialized)
            return;
        mEmojiCategory.initialize();
        mTabStrip = (LinearLayout) KeyboardSwitcher.getInstance().getEmojiTabStrip();

        // Add Search Tab (First Item)
        if (Settings.getValues().mSecondaryStripVisible) {
            addTab(mTabStrip, ID_SEARCH_TAB); // Special ID for search
            for (final EmojiCategory.CategoryProperties properties : mEmojiCategory.getShownCategories()) {
                addTab(mTabStrip, properties.mCategoryId);
            }
        }

        mPager = findViewById(R.id.emoji_pager);
        mPager.setAdapter(new PagerAdapter(mPager));
        mEmojiLayoutParams.setEmojiListProperties(mPager);
        mEmojiCategoryPageIndicatorView = findViewById(R.id.emoji_category_page_id_view);
        mEmojiLayoutParams.setCategoryPageIdViewProperties(mEmojiCategoryPageIndicatorView);

        // Search Initialization
        mSearchContainer = findViewById(R.id.emoji_search_container);
        mSearchResultsList = findViewById(R.id.emoji_search_results);
        mSearchEmptyView = findViewById(R.id.emoji_search_empty_view);

        // Horizontal Grid/Linear for side-by-side layout
        mSearchResultsList.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(getContext(),
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
        mSearchAdapter = new EmojiSearchAdapter(emoji -> {
            mKeyboardActionListener.onTextInput(emoji);
            // Optionally close search or keep it open for multiple inputs?
            // restore standard behavior: stop search
            stopSearchMode();
            return kotlin.Unit.INSTANCE;
        });
        mSearchResultsList.setAdapter(mSearchAdapter);

        // Listener for close button (defined in XML now)
        findViewById(R.id.emoji_search_close_btn).setOnClickListener(v -> stopSearchMode());

        mSearchBar = findViewById(R.id.emoji_search_bar);
        mSearchBar.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                performSearch(s.toString());
            }
        });

        setCurrentCategoryId(mEmojiCategory.getCurrentCategoryId(), true);
        mEmojiCategoryPageIndicatorView.setColors(mColors.get(ColorType.EMOJI_CATEGORY_SELECTED),
                mColors.get(ColorType.STRIP_BACKGROUND));
        initialized = true;
    }

    // Override addTab to handle custom icons if needed
    private void addTab(final LinearLayout host, final int categoryId) {
        final ImageView iconView = new ImageView(getContext());
        mColors.setBackground(iconView, ColorType.STRIP_BACKGROUND);
        mColors.setColor(iconView, ColorType.EMOJI_CATEGORY);
        iconView.setScaleType(ImageView.ScaleType.CENTER);

        if (categoryId == ID_SEARCH_TAB) {
            // Use search icon
            iconView.setImageResource(R.drawable.sym_keyboard_search_rounded);
            iconView.setContentDescription("Search Emojis");
        } else {
            iconView.setImageResource(mEmojiCategory.getCategoryTabIcon(categoryId));
            iconView.setContentDescription(mEmojiCategory.getAccessibilityDescription(categoryId));
        }

        iconView.setTag((long) categoryId);
        host.addView(iconView);
        iconView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        iconView.setOnClickListener(this);
    }

    private int toPx(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private void setupCategoryTabs() {
        mTabStrip.removeAllViews();
        if (Settings.getValues().mSecondaryStripVisible) {
            addTab(mTabStrip, ID_SEARCH_TAB); // Special ID for search
            for (final EmojiCategory.CategoryProperties properties : mEmojiCategory.getShownCategories()) {
                addTab(mTabStrip, properties.mCategoryId);
            }
        }
    }

    private void startSearchMode() {
        android.util.Log.d("EmojiSearch", "startSearchMode() called");
        if (mInSearchMode)
            return;
        mInSearchMode = true;

        // 1. Prepare Strip for Search UI
        mTabStrip.removeAllViews();
        Context ctx = getContext();

        // Main Container in Strip
        LinearLayout stripContainer = new LinearLayout(ctx);
        stripContainer.setOrientation(LinearLayout.HORIZONTAL);
        stripContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mTabStrip.addView(stripContainer);

        // --- Left Side: Input (Weight 0.4) ---
        LinearLayout inputContainer = new LinearLayout(ctx);
        inputContainer.setOrientation(LinearLayout.HORIZONTAL);
        inputContainer.setGravity(android.view.Gravity.CENTER_VERTICAL);
        inputContainer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.4f));
        inputContainer.setPadding(toPx(4), 0, toPx(4), 0);

        // Search Icon
        ImageView searchIcon = new ImageView(ctx);
        searchIcon.setImageResource(R.drawable.sym_keyboard_search_rounded);
        mColors.setColor(searchIcon, ColorType.KEY_TEXT);
        searchIcon.setAlpha(0.7f);
        int iconSize = toPx(20);
        searchIcon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
        inputContainer.addView(searchIcon);

        // Edit Text
        mSearchBar = new EditText(ctx);
        mSearchBar.setBackground(null);
        mSearchBar.setHint("Search");
        mSearchBar.setTextColor(mColors.get(ColorType.KEY_TEXT));
        mSearchBar.setHintTextColor(mColors.get(ColorType.KEY_TEXT) & 0x00FFFFFF | 0x80000000);
        mSearchBar.setTextSize(14); // Smaller text for strip
        mSearchBar.setSingleLine(true);
        mSearchBar.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mSearchBar.setPadding(toPx(4), 0, toPx(4), 0);
        mSearchBar.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mSearchBar.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            public void afterTextChanged(Editable s) {
                performSearch(s.toString());
            }
        });
        inputContainer.addView(mSearchBar);

        // Close Button
        ImageButton closeBtn = new ImageButton(ctx);
        closeBtn.setImageResource(R.drawable.ic_close_rounded);
        closeBtn.setBackground(null); // Transparent
        mColors.setColor(closeBtn, ColorType.KEY_TEXT);
        closeBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        closeBtn.setOnClickListener(v -> stopSearchMode());
        int btnSize = toPx(48); // Increased from 30 for better touch target
        closeBtn.setLayoutParams(new LinearLayout.LayoutParams(btnSize, btnSize));
        inputContainer.addView(closeBtn);

        stripContainer.addView(inputContainer);

        // --- Divider ---
        View divider = new View(ctx);
        divider.setBackgroundColor(0x55888888); // Semi-transparent gray
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(toPx(1),
                ViewGroup.LayoutParams.MATCH_PARENT);
        divParams.setMargins(0, toPx(4), 0, toPx(4));
        divider.setLayoutParams(divParams);
        stripContainer.addView(divider);

        // --- Right Side: Results (Weight 0.6) ---
        // Check for dictionary presence (show button if missing)
        if (sDictionaryFacilitator == null) {
            Button downloadBtn = new Button(ctx);
            downloadBtn.setText("Download Dictionary");
            downloadBtn.setTextSize(12); // Keep it small to fit
            downloadBtn.setAllCaps(false);
            downloadBtn.setOnClickListener(v -> {
                if ("standard".equals(BuildConfig.FLAVOR)) {
                    downloadEmojiDictionary();
                    downloadBtn.setText("Downloading...");
                    downloadBtn.setEnabled(false);
                } else {
                    Intent intent = new Intent(ctx, SettingsActivity.class);
                    intent.putExtra("screen", "dictionaries");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                }
            });
            downloadBtn.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.6f));
            stripContainer.addView(downloadBtn);

            // Initialize results list too but don't add it, or just leave it null?
            // Better to have it just in case logic continues, but strictly we don't need
            // it.
            // But existing code uses mSearchAdapter later.
            // Let's safe initialize it but not add to view.
        } else {
            RecyclerView resultsList = new RecyclerView(ctx);
            resultsList.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.6f));
            resultsList.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));

            mSearchAdapter = new EmojiSearchAdapter(emoji -> {
                mKeyboardActionListener.onTextInput(emoji);
                // Optional: stopSearchMode();
                return kotlin.Unit.INSTANCE;
            });
            resultsList.setAdapter(mSearchAdapter);
            stripContainer.addView(resultsList);
        }

        // 2. Adjust Main View Visibility
        mEmojiCategoryPageIndicatorView.setVisibility(View.GONE);
        mPager.setVisibility(View.GONE);
        // Ensure XML container is gone
        if (mSearchContainer != null)
            mSearchContainer.setVisibility(View.GONE);

        // 3. Switch Bottom Row to Alpha Keyboard
        MainKeyboardView bottomRow = findViewById(R.id.bottom_row_keyboard);
        mOriginalActionListener = mKeyboardActionListener;

        // Listener for input... (Kept same as before)
        bottomRow.setKeyboardActionListener(new KeyboardActionListener() {
            @Override
            public void onCodeInput(int primaryCode, int x, int y, boolean isKeyRepeat) {
                if (primaryCode == KeyCode.DELETE) {
                    Editable text = mSearchBar.getText();
                    if (text != null && text.length() > 0)
                        text.delete(text.length() - 1, text.length());
                } else if (primaryCode == helium314.keyboard.latin.common.Constants.CODE_SPACE) {
                    mSearchBar.append(" ");
                } else if (primaryCode > 0) {
                    mSearchBar.append(String.valueOf((char) primaryCode));
                } else if (primaryCode == helium314.keyboard.latin.common.Constants.CODE_ENTER) {
                    stopSearchMode();
                }
            }

            @Override
            public void onPressKey(int p, int r, boolean s, HapticEvent h) {
                mOriginalActionListener.onPressKey(p, r, s, h);
            }

            @Override
            public void onReleaseKey(int p, boolean w) {
                mOriginalActionListener.onReleaseKey(p, w);
            }

            @Override
            public void onTextInput(String t) {
                mSearchBar.append(t);
            }

            // Empty implementations...
            @Override
            public void onLongPressKey(int p) {
            }

            @Override
            public boolean onKeyDown(int k, android.view.KeyEvent e) {
                return false;
            }

            @Override
            public boolean onKeyUp(int k, android.view.KeyEvent e) {
                return false;
            }

            @Override
            public void onStartBatchInput() {
            }

            @Override
            public void onUpdateBatchInput(helium314.keyboard.latin.common.InputPointers p) {
            }

            @Override
            public void onEndBatchInput(helium314.keyboard.latin.common.InputPointers p) {
            }

            @Override
            public void onCancelBatchInput() {
            }

            @Override
            public void onCancelInput() {
            }

            @Override
            public void onFinishSlidingInput() {
            }

            @Override
            public boolean onCustomRequest(int r) {
                return false;
            }

            @Override
            public boolean onHorizontalSpaceSwipe(int s) {
                return false;
            }

            @Override
            public boolean onVerticalSpaceSwipe(int s) {
                return false;
            }

            @Override
            public void onEndSpaceSwipe() {
            }

            @Override
            public boolean toggleNumpad(boolean w, boolean f) {
                return false;
            }

            @Override
            public void onMoveDeletePointer(int s) {
            }

            @Override
            public void onUpWithDeletePointerActive() {
            }

            @Override
            public void resetMetaState() {
            }
        });

        // Load Alpha Keyboard
        KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(ctx, null);
        builder.setSubtype(RichInputMethodManager.getInstance().getCurrentSubtype());
        builder.setKeyboardGeometry(ResourceUtils.getKeyboardWidth(ctx, Settings.getValues()),
                ResourceUtils.getKeyboardHeight(ctx.getResources(), Settings.getValues()));
        KeyboardLayoutSet kls = builder.build();
        bottomRow.setKeyboard(kls.getKeyboard(KeyboardId.ELEMENT_ALPHABET));

        // Focus
        mSearchBar.requestFocus();
    }

    private void stopSearchMode() {
        android.util.Log.d("EmojiSearch", "stopSearchMode");
        if (!mInSearchMode)
            return;
        mInSearchMode = false;

        setupBottomRowKeyboard(null, mOriginalActionListener);

        // Restore UI
        setupCategoryTabs();
        mEmojiCategoryPageIndicatorView.setVisibility(View.VISIBLE);
        mPager.setVisibility(View.VISIBLE);
        if (mSearchContainer != null)
            mSearchContainer.setVisibility(View.GONE);

        if (mSearchBar != null)
            mSearchBar.setText(""); // Clear text
        mSearchBar = null; // Clear reference
    }

    private void performSearch(String query) {
        android.util.Log.d("EmojiSearch", "performSearch: " + query);
        if (sDictionaryFacilitator == null || TextUtils.isEmpty(query)) {
            if (mSearchAdapter != null)
                mSearchAdapter.submitList(java.util.Collections.emptyList());
            // No empty view in strip, just empty list
            return;
        }

        final String lowerQuery = query.toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> results = new java.util.ArrayList<>();
        java.util.List<String> allEmojis = mEmojiCategory.getAllEmojiKeys();

        for (String emoji : allEmojis) {
            String desc = getDescription(emoji);
            if (desc != null && desc.toLowerCase(java.util.Locale.ROOT).contains(lowerQuery)) {
                results.add(emoji);
                if (results.size() >= 50)
                    break;
            }
        }
        android.util.Log.d("EmojiSearch", "Found " + results.size() + " results");
        if (mSearchAdapter != null)
            mSearchAdapter.submitList(results);
    }

    public void startEmojiPalettes(final KeyVisualAttributes keyVisualAttr,
            final EditorInfo editorInfo, final KeyboardActionListener keyboardActionListener) {
        stopSearchMode(); // Ensure clean state
        mEditorInfo = editorInfo; // Saved
        initialize();
        setupBottomRowKeyboard(editorInfo, keyboardActionListener);
        final KeyDrawParams params = new KeyDrawParams();
        params.updateParams(mEmojiLayoutParams.getBottomRowKeyboardHeight(), keyVisualAttr);
        setupSidePadding();
        initDictionaryFacilitator();
    }

    @Override
    public void onClick(View v) {
        final Object tag = v.getTag();
        if (tag instanceof Long) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this,
                    HapticEvent.KEY_PRESS);
            final int categoryId = ((Long) tag).intValue();

            if (categoryId == ID_SEARCH_TAB) {
                startSearchMode();
                return;
            }

            if (categoryId != mEmojiCategory.getCurrentCategoryId()) {
                setCurrentCategoryId(categoryId, false);
                updateEmojiCategoryPageIdView();
            }
        }
    }

    // Need to modify setupBottomRowKeyboard to use mEditorInfo if passed null
    private void setupBottomRowKeyboard(final EditorInfo editorInfo,
            final KeyboardActionListener keyboardActionListener) {
        EditorInfo ei = editorInfo != null ? editorInfo : mEditorInfo;
        MainKeyboardView keyboardView = findViewById(R.id.bottom_row_keyboard);
        keyboardView.setKeyboardActionListener(keyboardActionListener);
        PointerTracker.switchTo(keyboardView);
        final KeyboardLayoutSet kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(getContext(), ei);
        final Keyboard keyboard = kls.getKeyboard(KeyboardId.ELEMENT_EMOJI_BOTTOM_ROW);
        keyboardView.setKeyboard(keyboard);
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji
     * buttons.
     */
    @Override
    public void onPressKey(final Key key) {
        final int code = key.getCode();
        mKeyboardActionListener.onPressKey(code, 0, true, HapticEvent.KEY_PRESS);
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji
     * buttons.
     * This may be called without any prior call to
     * {@link EmojiViewCallback#onPressKey(Key)}.
     */
    @Override
    public void onReleaseKey(final Key key) {
        addRecentKey(key);
        final int code = key.getCode();
        if (code == KeyCode.MULTIPLE_CODE_POINTS) {
            mKeyboardActionListener.onTextInput(key.getOutputText());
        } else {
            mKeyboardActionListener.onCodeInput(code, NOT_A_COORDINATE, NOT_A_COORDINATE, false);
        }
        mKeyboardActionListener.onReleaseKey(code, false);
        if (Settings.getValues().mAlphaAfterEmojiInEmojiView)
            mKeyboardActionListener.onCodeInput(KeyCode.ALPHA, NOT_A_COORDINATE, NOT_A_COORDINATE, false);
    }

    @Override
    public String getDescription(String emoji) {
        if (sDictionaryFacilitator == null) {
            return null;
        }

        var wordProperty = sDictionaryFacilitator.getWordProperty(emoji);
        if (wordProperty == null || !wordProperty.mHasShortcuts) {
            return null;
        }

        return wordProperty.mShortcutTargets.get(0).mWord;
    }

    public void setHardwareAcceleratedDrawingEnabled(final boolean enabled) {
        if (!enabled)
            return;
        // TODO: Should use LAYER_TYPE_SOFTWARE when hardware acceleration is off?
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private void addRecentKey(final Key key) {
        if (Settings.getValues().mIncognitoModeEnabled) {
            // We do not want to log recent keys while being in incognito
            return;
        }
        if (mEmojiCategory.isInRecentTab()) {
            getRecentsKeyboard().addPendingKey(key);
            return;
        }
        getRecentsKeyboard().addKeyFirst(key);
        mPager.getAdapter().notifyItemChanged(mEmojiCategory.getRecentTabId());
    }

    private void setupSidePadding() {
        final SettingsValues sv = Settings.getValues();
        final int keyboardWidth = ResourceUtils.getKeyboardWidth(getContext(), sv);
        final TypedArray keyboardAttr = getContext().obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard);
        final float leftPadding = keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.mSidePaddingScale;
        final float rightPadding = keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.mSidePaddingScale;
        keyboardAttr.recycle();
        mPager.setPadding(
                (int) leftPadding,
                mPager.getPaddingTop(),
                (int) rightPadding,
                mPager.getPaddingBottom());
        mEmojiCategoryPageIndicatorView.setPadding(
                (int) leftPadding,
                mEmojiCategoryPageIndicatorView.getPaddingTop(),
                (int) rightPadding,
                mEmojiCategoryPageIndicatorView.getPaddingBottom());
        // setting width does not do anything, so we have some workaround in
        // EmojiCategoryPageIndicatorView
    }

    public void stopEmojiPalettes() {
        if (!initialized)
            return;
        getRecentsKeyboard().flushPendingRecentKeys();
    }

    private DynamicGridKeyboard getRecentsKeyboard() {
        return mEmojiCategory.getKeyboard(EmojiCategory.ID_RECENTS, 0);
    }

    public void setKeyboardActionListener(final KeyboardActionListener listener) {
        mKeyboardActionListener = listener;
    }

    private void updateEmojiCategoryPageIdView() {
        if (mEmojiCategoryPageIndicatorView == null) {
            return;
        }
        mEmojiCategoryPageIndicatorView.setCategoryPageId(
                mEmojiCategory.getCurrentCategoryPageCount(),
                mEmojiCategory.getCurrentCategoryPageId(), 0.0f);
    }

    private void setCurrentCategoryId(final int categoryId, final boolean initial) {
        final int oldCategoryId = mEmojiCategory.getCurrentCategoryId();
        if (initial || oldCategoryId != categoryId) {
            mEmojiCategory.setCurrentCategoryId(categoryId);

            if (mPager.getScrollState() != ViewPager2.SCROLL_STATE_DRAGGING) {
                // Not swiping
                mPager.setCurrentItem(mEmojiCategory.getTabIdFromCategoryId(
                        mEmojiCategory.getCurrentCategoryId()), !initial && !isAnimationsDisabled());
            }

            if (Settings.getValues().mSecondaryStripVisible) {
                final View old = mTabStrip.findViewWithTag((long) oldCategoryId);
                final View current = mTabStrip.findViewWithTag((long) categoryId);

                if (old instanceof ImageView)
                    Settings.getValues().mColors.setColor((ImageView) old, ColorType.EMOJI_CATEGORY);
                if (current instanceof ImageView)
                    Settings.getValues().mColors.setColor((ImageView) current, ColorType.EMOJI_CATEGORY_SELECTED);
            }
        }
    }

    private boolean isAnimationsDisabled() {
        return android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f) == 0.0f;
    }

    public void clearKeyboardCache() {
        if (!initialized) {
            return;
        }

        mEmojiCategory.clearKeyboardCache();
        mPager.getAdapter().notifyDataSetChanged();
        closeDictionaryFacilitator();
    }

    private void initDictionaryFacilitator() {
        // Always load dictionary for search functionality, regardless of
        // mShowEmojiDescriptions
        var locale = RichInputMethodManager.getInstance().getCurrentSubtype().getLocale();
        if (sDictionaryFacilitator == null || !sDictionaryFacilitator.isForLocale(locale)) {
            closeDictionaryFacilitator();
            var dictFile = DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI,
                    getContext());
            var dictionary = dictFile != null ? DictionaryFactory.getDictionary(dictFile, locale) : null;
            sDictionaryFacilitator = dictionary != null ? new SingleDictionaryFacilitator(dictionary) : null;
        }
    }

    public static void closeDictionaryFacilitator() {
        if (sDictionaryFacilitator != null) {
            sDictionaryFacilitator.closeDictionaries();
            sDictionaryFacilitator = null;
        }
    }

    @Override
    public void setVisibility(int visibility) {
        if (visibility != VISIBLE && mInSearchMode) {
            stopSearchMode();
        }
        super.setVisibility(visibility);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(mDownloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED);
        } else {
            getContext().registerReceiver(mDownloadReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mInSearchMode) {
            stopSearchMode();
        }
        try {
            getContext().unregisterReceiver(mDownloadReceiver);
        } catch (IllegalArgumentException e) {
            // Check if receiver not registered
        }
        super.onDetachedFromWindow();
    }

    private void downloadEmojiDictionary() {
        var locale = RichInputMethodManager.getInstance().getCurrentSubtype().getLocale();
        // Construct URL:
        // https://codeberg.org/Helium314/aosp-dictionaries/raw/branch/main/emoji_cldr_signal_dictionaries/emoji_<lang>.dict
        // We use CLDR as it's the standard for emoji
        String lang = locale.getLanguage();
        String url = Links.DICTIONARY_URL + Links.DICTIONARY_DOWNLOAD_SUFFIX + Links.DICTIONARY_EMOJI_CLDR_SUFFIX
                + "emoji_" + lang + ".dict";

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle("Downloading Emoji Dictionary (" + lang + ")");
        request.setDescription("Downloading emoji dictionary for search...");
        request.setDestinationInExternalFilesDir(getContext(), Environment.DIRECTORY_DOWNLOADS,
                "emoji_" + lang + ".dict");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);

        DownloadManager manager = (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager != null) {
            mDownloadId = manager.enqueue(request);
            Toast.makeText(getContext(), "Download started...", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleDownloadCompletion(Context context) {
        // Move file from external files to internal cache dicts
        // Because DownloadManager cannot write to internal storage directly
        var locale = RichInputMethodManager.getInstance().getCurrentSubtype().getLocale();
        String lang = locale.getLanguage();
        File externalFile = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "emoji_" + lang + ".dict");

        if (externalFile.exists()) {
            String cachePath = DictionaryInfoUtils.INSTANCE.getCacheDirectoryForLocale(locale, context);
            if (cachePath != null) {
                File targetFile = new File(cachePath, "emoji_" + lang + ".dict"); // Name doesn't matter much as long as
                                                                                  // it's in the folder? verify
                                                                                  // DictionaryInfoUtils
                // Actually DictionaryInfoUtils usually expects name format?
                // DictionaryInfoUtils.getCachedDictForLocaleAndType checks prefix "type_"
                // so "emoji_..." should be fine as type="emoji"

                try (java.io.FileInputStream fis = new java.io.FileInputStream(externalFile)) {
                    helium314.keyboard.latin.common.FileUtils.copyStreamToNewFile(fis, targetFile);
                    externalFile.delete(); // Cleanup

                    // Reload
                    initDictionaryFacilitator();

                    // Refresh UI if in search mode
                    if (mInSearchMode) {
                        // Restart search mode to show results list instead of button
                        // Need to run on UI thread? we are on UI thread in onReceive usually
                        stopSearchMode();
                        startSearchMode();
                        // Maybe restore text?
                    }
                    Toast.makeText(context, "Emoji dictionary installed!", Toast.LENGTH_SHORT).show();
                } catch (java.io.IOException e) {
                    android.util.Log.e("EmojiSearch", "Failed to move dictionary", e);
                    Toast.makeText(context, "Failed to install dictionary", Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            Toast.makeText(context, "Download failed or file not found.", Toast.LENGTH_SHORT).show();
        }
    }
}

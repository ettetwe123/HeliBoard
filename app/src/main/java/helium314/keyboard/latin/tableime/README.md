# HeliBoard Table IME Engine

This directory contains the core implementation of the **Table IME (Table-driven Input Method Engine)** for HeliBoard.

The Table IME engine is designed specifically for code-table-based input methods (e.g., **Cangjie / 倉頡**, **Quick / 速成 (簡易)**, **Wubi / 五筆**, **Dayi / 大易**, **Array / 行列**, **Zhengma / 鄭碼**, etc.) where keystroke sequences are mapped deterministically to candidate characters and words via translation tables.

---

## 1. Architecture & Design Principles

The engine is built around three core principles:
1. **Low Overhead & Memory Efficiency**: Tables and word prediction lists are loaded asynchronously on-demand (lazy-loaded) and stored in compact memory representations.
2. **Extensible Abstraction**: All engine components communicate through generic interfaces (`TableImeSpec`), decoupling specific table lookup logic from Android UI and HeliBoard internals.
3. **Strict Isolation**: Integrations with HeliBoard's `LatinIME`, `InputLogic`, and suggestion views are protected by strict guard conditions (`isTableIme`), guaranteeing zero regression or runtime impact on Latin and other language keyboards.

### Component Interaction Flow

```
                        [ Key Press / Touch Event ]
                                     │
                                     ▼
                             [ CombinerChain ]
                                     │
                          [ TableImeCombiner ]  <─── Manages raw composing code
                                     │               and radical visual feedback
                                     ▼
                              [ InputLogic ]
                                     │
           ┌─────────────────────────┴─────────────────────────┐
           ▼                                                   ▼
  [ TableImeHandler ]                                  [ Suggest.kt ]
   • Handles Space / Enter auto-commit                  • Delegates to TableImeRegistry
   • Tracks user selection frequency                           │
   • Triggers next-word predictions                            ▼
           │                                        [ TableImeRegistry ]
           ▼                                                   │
  [ TableImeCandidatesView ] ◀─── Candidate List ─── [ TableImeTable / Predictor ]
   • Fullscreen candidate grid view                    • Exact & prefix table lookup
   • Horizontal candidate strip                        • Next-word predictive lookup
```

### Key Components

| Class / Interface | Description |
| :--- | :--- |
| **`TableImeSpec`** | Specification interface. Defines IME metadata: identifier (`id`), combining rule identifier (`combiningRules`), maximum code length (`maxCodeLength`), asset paths (`baseTableAsset`, `extendedTableAsset`, `nextWordsAsset`), character-to-radical mapping (`codeToRadical`), and references to `fuzzyPolicy` and `rankingPolicy`. |
| **`TableImePolicy`** | Decoupled policy interfaces (`TableImeFuzzyPolicy`, `TableImeRankingPolicy`). Allows different table IMEs (Cangjie vs Quick vs Wubi) to configure their own rules for fuzzy tolerance, neighbor keys, prefix matching, and homocode stability without hardcoded logic. |
| **`TableImeRegistry`** | Central registry and singleton manager. Holds registered specs and manages background thread loading (`preloadAsync`, `ensureExtendedLoaded`) for `TableImeTable` and `TableImeWordPredictor`. |
| **`TableImeTable`** | TSV and binary table search engine. Supports exact matching, prefix matching, typo tolerance (neighbor keys and extra-key deletion), homocode stability, and dynamic inclusion of extended (rare) character tables. |
| **`TableImeCombiner`** | Implements HeliBoard's `Combiner` interface. Intercepts key presses, manages raw code state, and emits composing radical strings (e.g. `ab` $\to$ `日月`) for UI feedback. |
| **`TableImeHandler`** | Static controller interacting with `InputLogic`. Intercepts Space (synchronous commit of 1st candidate), Enter (commits raw composing or sends newline), Backspace, and updates user frequency history. |
| **`TableImeWordPredictor`** | Next-word predictor. When the composing buffer is empty, predicts next words based on the text committed immediately before the cursor. |
| **`TableImeUserHistory`** | Persistent frequency tracker backed by `SharedPreferences`. Tracks committed words and dynamically boosts candidate ranking over time. |
| **`TableImeCandidatesView`** | Expandable full-keyboard candidate picker view displayed when clicking the dropdown button on the suggestion strip. |
| **`TableImeSuggestionStripLayoutHelper`** | Layout helper for the candidate strip. Displays horizontally scrollable candidates (up to 60) with primary candidate bold highlighting. |

---

## 2. Guide: Adding a New Table IME (e.g. Quick / 速成)

To add support for a new table-based input method such as **Quick / 簡易 (速成)**, follow these steps:

### Step 1: Prepare Table Files (TSV)

Place table files in `app/src/main/assets/tableime/`. Each line must be tab-delimited (`code\tcandidate`):
```tsv
a	日
a	曰
ab	明
ab	萌
```
* **Base table**: `app/src/main/assets/tableime/quick_base.tsv`
* **Extended table (optional)**: `app/src/main/assets/tableime/quick_extended.tsv`

*(Note: Binary tables (.bin) can be compiled from TSV using `tools/tableime/convert_to_binary.py` for ultra-fast startup and zero-allocation lookups.)*

---

### Step 2: Implement `TableImeSpec`

Create a new specification file in this directory, for example `QuickSpec.kt`:

```kotlin
package helium314.keyboard.latin.tableime

object QuickSpec : TableImeSpec {
    override val id: String = "quick"
    override val combiningRules: String = "quick"
    override val maxCodeLength: Int = 2 // Quick takes at most 2 keys (first + last radical)
    override val baseTableAsset: String = "tableime/quick_base.tsv"
    override val extendedTableAsset: String = "tableime/quick_extended.tsv"
    override val nextWordsAsset: String = "tableime/cangjie_words.bin" // Optional: reuse Cantonese / Chinese word predictor

    // Quick shares the standard 24/25 Cangjie radical mapping (A-Z -> 日-重)
    private val RADICALS = mapOf(
        'a' to "日", 'b' to "月", 'c' to "金", 'd' to "木", 'e' to "水",
        'f' to "火", 'g' to "土", 'h' to "竹", 'i' to "戈", 'j' to "十",
        'k' to "大", 'l' to "中", 'm' to "一", 'n' to "弓", 'o' to "人",
        'p' to "心", 'q' to "手", 'r' to "口", 's' to "尸", 't' to "廿",
        'u' to "山", 'v' to "女", 'w' to "田", 'x' to "難", 'y' to "卜", 'z' to "重"
    )

    override fun codeToRadical(c: Char): String =
        RADICALS[c.lowercaseChar()] ?: c.toString()
}
```

---

### Step 3: Register in `TableImeRegistry`

In `TableImeRegistry.kt`, register `QuickSpec` in the `specs` map:

```kotlin
private val specs = ConcurrentHashMap<String, TableImeSpec>().apply {
    put(CangjieSpec.combiningRules, CangjieSpec)
    put(QuickSpec.combiningRules, QuickSpec) // <-- Add here
}
```

---

### Step 4: Configure Keyboard Layout & Subtype

1. **Keyboard Layout**:
   - If reusing Cangjie radicals, reference `assets/layouts/main/cangjie.json`.
   - Alternatively, create a custom layout in `assets/layouts/main/quick.json`.

2. **Add Subtype in `app/src/main/res/xml/method.xml`**:
   ```xml
   <subtype android:icon="@drawable/ic_ime_switcher"
       android:label="@string/subtype_generic_quick"
       android:subtypeId="0x7a680002"
       android:imeSubtypeLocale="zh_HK"
       android:languageTag="zh-HK"
       android:imeSubtypeMode="keyboard"
       android:imeSubtypeExtraValue="KeyboardLayoutSet=MAIN:cangjie,CombiningRules=quick,NoShiftKey,EmojiCapable"
       android:isAsciiCapable="false"
   />
   ```
   *Note: `CombiningRules=quick` must match `QuickSpec.combiningRules`.*

3. **Add Subtype Labels in String Resources**:
   - `res/values/strings.xml`
   - `res/values-zh-rHK/strings.xml`
   - `res/values-zh-rTW/strings.xml`
   ```xml
   <string name="subtype_generic_quick">Quick (Traditional Chinese)</string>
   ```

---

### Step 5: Subtype Settings (Automatic)

`SubtypeScreen.kt` dynamically inspects `TableImeRegistry.isTableIme(combiningRules)`.
Any registered Table IME automatically inherits:
- **Extended Table Switch** (`TableImeExtended`): Toggles rare characters on or off.
- **Frequent Words Prioritization Switch** (`TableImeFreqWords`): Toggles dynamic frequency-based candidate ranking.

No extra configuration is required in settings screens.

---

## 3. Candidate Ranking, Typo Tolerance & Commit Logic

### 3.1 Option A Candidate Ordering

Candidates are partitioned into tiers and displayed in strict order:
```
Base Exact Matches ──▶ Base Prefix Matches ──▶ Common Fuzzy Matches ──▶ Ext Exact ──▶ Ext Prefix ──▶ Remaining Fuzzy
```

- **Base entries always precede extended entries**: Common characters from the primary table take precedence over obscure Kangxi or Unicode extension characters.
- **Prefix matches precede fuzzy matches**: Zero-error direct prefix matches representing the word the user is actively typing always rank ahead of fuzzy typo matches.

### 3.2 Homocode Stability (Muscle Memory Protection)

When multiple characters share the exact same code (e.g., Cangjie code `kb` for both `有` and `冇`):
- **Problem**: Naive `count DESC` sorting causes "leapfrog oscillation" (每選一次就反超換位), where picking `冇` moves it to slot 1, then picking `有` moves `有` back to slot 1, destroying touch-typing muscle memory.
- **Solution**: Within exact matches (`isExact = true`), all frequently used characters (`count > 0`) retain their **original base table order** instead of sorting by usage count descending.
  - `有` (index 0 in table) and `冇` (index 1 in table) permanently stay as `[有, 冇]`.
  - Unused rare characters (`count == 0`) remain behind all used characters.
  - For prefix matches (`isExact = false`), characters have different codes and continue to be sorted by usage count descending to float frequent words to the front.

### 3.3 Typo Tolerance & Fuzzy Matching Rules

Typo tolerance handles neighbor key slips (spatial proximity) and accidental extra key insertions while preventing false positives:

1. **Trigger Threshold**:
   - Fuzzy matching only executes when total direct candidates (`baseExact + basePrefix + extExact + extPrefix`) are fewer than **10**, and no common exact match exists.
   - If direct suggestions are plentiful, fuzzy matching is bypassed entirely to save CPU and avoid candidate pollution.

2. **Extra-Key Deletion Guard**:
   - Dropping a key (e.g. `gnrmi` $\to$ `gnmi` $\to$ `壽`) is only permitted when `basePrefix.isEmpty()` (the typed code matches no word or prefix in the dictionary).
   - When `basePrefix` is non-empty (e.g. typing `hgh` towards `hghu` `先`), deletion is disabled so shorter code words (e.g. `hg` `壬`) can never hijack the candidates list.

3. **Neighbor Key Substitution**:
   - Inspects QWERTY adjacent keys (e.g. `cnd` $\to$ `vnd` $\to$ `好`).
   - If matched, common fuzzy words are appended after `basePrefix`, keeping legitimate in-progress prefix matches at the front.

### 3.4 Synchronous Commit on Space (Fast-Typing Protection)

In LatinIME, `getSuggestedWords()` runs asynchronously on a background worker thread (`mNonUIThreadHandler`).

- **Problem**: When typing at high speed, pressing Space can reach the UI thread before the background worker finishes computing candidates. If the handler falls back to LatinIME's default `SuggestedWords`, index 0 contains `mTypedWordInfo` (the raw composing code / radicals, e.g. `竹土` or `hgh`), causing the raw code to be committed instead of the candidate character.
- **Solution**: In `TableImeHandler.resolveCommit()`, pressing Space directly performs an in-memory synchronous lookup (`table.lookup(rawCode)`) using `wordComposer.rawComposingCode` on the UI thread.
  - Because `cangjie_base.bin` binary lookups take less than **20 µs**, synchronous resolution is instantaneous.
  - Guarantees 0ms race-condition-free commits and ensures the first candidate Chinese character is always committed, never the raw code.

---

## 4. Strict Latin Core Isolation Rules

When making modifications to the Table IME engine or its integration points, adhere to these isolation rules:

1. **Do not break non-space languages in `InputLogic`**:
   Any cursor inspection logic (`getTextBeforeCursor`) in `getNgramContextFromNthPreviousWordForSuggestion` must be guarded by `TableImeHandler.isTableIme(...)`. Non-space languages (e.g. Thai, Lao, Khmer) rely on `mLastComposedWord`.
2. **Respect user popup hint preferences**:
   In `KeyboardParser.kt`, removing `POPUP_KEYS_NUMBER` from the first row must strictly be restricted to `isTableIme`. Standard layouts must follow `params.mPopupKeyHintOrder`.
3. **Keep `SuggestedWords.MAX_SUGGESTIONS` untouched**:
   Leave `SuggestedWords.MAX_SUGGESTIONS = 18` for Latin dictionary operations and `MoreSuggestions`. Define larger candidate limits (e.g. 60) locally in `TableImeSuggestionStripLayoutHelper.MAX_SUGGESTIONS_FOR_TABLE_IME`.

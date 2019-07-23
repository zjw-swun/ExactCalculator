/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// TODO: Copy & more general paste in formula?  Note that this requires
//       great care: Currently the text version of a displayed formula
//       is not directly useful for re-evaluating the formula later, since
//       it contains ellipses representing subexpressions evaluated with
//       a different degree mode.  Rather than supporting copy from the
//       formula window, we may eventually want to support generation of a
//       more useful text version in a separate window.  It's not clear
//       this is worth the added (code and user) complexity.

package com.example.calculator2

import android.animation.*
import android.animation.Animator.AnimatorListener
import android.content.ClipData
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.Property
import android.view.*
import android.view.View.OnLongClickListener
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toolbar
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.viewpager.widget.ViewPager
import com.example.calculator2.CalculatorFormula.OnFormulaContextMenuClickListener
import com.example.calculator2.CalculatorFormula.OnTextSizeChangeListener
import java.io.*
import java.text.DecimalFormatSymbols
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

@RequiresApi(Build.VERSION_CODES.N)
@Suppress("MemberVisibilityCanBePrivate", "LocalVariableName")
class Calculator2 : FragmentActivity(), OnTextSizeChangeListener, OnLongClickListener,
        AlertDialogFragment.OnClickListener, Evaluator.EvaluationListener /* for main result */,
        DragLayout.CloseCallback, DragLayout.DragCallback {
    // Normal transition sequence is
    // INPUT -> EVALUATE -> ANIMATE -> RESULT (or ERROR) -> INPUT
    // A RESULT -> ERROR transition is possible in rare corner cases, in which
    // a higher precision evaluation exposes an error.  This is possible, since we
    // initially evaluate assuming we were given a well-defined problem.  If we
    // were actually asked to compute sqrt(<extremely tiny negative number>) we produce 0
    // unless we are asked for enough precision that we can distinguish the argument from zero.
    // ERROR and RESULT are translated to INIT or INIT_FOR_RESULT state if the application
    // is restarted in that state.  This leads us to recompute and redisplay the result
    // ASAP. We avoid saving the ANIMATE state or activating history in that state.
    // In INIT_FOR_RESULT, and RESULT state, a copy of the current
    // expression has been saved in the history db; in the other non-ANIMATE states,
    // it has not.
    // TODO: Possibly save a bit more information, e.g. its initial display string
    // or most significant digit position, to speed up restart.

    /**
     * [Property] that is used to animate the current color selected for normal text, it is used
     * by the [onResult] method to animate the "textColor" property of the [CalculatorResult] TextView
     * when displaying a result.
     */
    private val textColor = object : Property<TextView, Int>(Int::class.java, "textColor") {
        override fun get(textView: TextView): Int {
            return textView.currentTextColor
        }

        override fun set(textView: TextView, textColor: Int) {
            textView.setTextColor(textColor)
        }
    }

    /**
     * OnPreDrawListener that scrolls the [CalculatorScrollView] formula container (with resource id
     * R.id.formula_container) to the right side of the TextView holding the current formula as
     * characters are added to it.
     */
    private val mPreDrawListener = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            mFormulaContainer.scrollTo(mFormulaText.right, 0)
            val observer = mFormulaContainer.viewTreeObserver
            if (observer.isAlive) {
                observer.removeOnPreDrawListener(this)
            }
            return false
        }
    }

    /**
     * Callback used by the [Evaluator] when the history changes, or its AsyncTask is canceled, and
     * this class uses when the history has been cleared as well. The onMemoryStateChanged override
     * calls the onMemoryStateChanged method of the TextView holding our formula which enables/disables
     * its on long click behavior appropriately (used for copy/paste). The showMessageDialog override
     * displays an alert dialog when the [Evaluator] is cancelled or a computation times out.
     */
    private val mEvaluatorCallback = object : Evaluator.Callback {
        /**
         * This is called when the state of the "memory" register has changed. We just call the
         * `onMemoryStateChanged` method of the [TextView] holding our formula which enables/disables
         * its on long click behavior appropriately (used for copy/paste).
         */
        override fun onMemoryStateChanged() {
            mFormulaText.onMemoryStateChanged()
        }

        /**
         * This callback is called when the [Evaluator] needs to show an alert dialog. We just call
         * our [AlertDialogFragment.showMessageDialog] method with the parameters passed us.
         *
         * @param title resource id for the title string
         * @param message resource id for the displayed message string
         * @param positiveButtonLabel label for second button, if any. If non-null, activity must
         * implement *AlertDialogFragment.OnClickListener* to respond to that button being clicked.
         * @param tag tag for the *Fragment* that the *FragmentManager* will add.
         */
        override fun showMessageDialog(@StringRes title: Int, @StringRes message: Int,
                                       @StringRes positiveButtonLabel: Int, tag: String?) {
            AlertDialogFragment.showMessageDialog(this@Calculator2, title, message,
                    positiveButtonLabel, tag)

        }
    }

    /**
     * Tests that the [Evaluator] memory index into its expression Map (and history database) is
     * non-zero. It is set to zero temporarily when an expression is being evaluated, then updated
     * with the new index when it is done.
     */
    private val mOnDisplayMemoryOperationsListener = object : OnDisplayMemoryOperationsListener {
        override fun shouldDisplayMemory(): Boolean {
            return mEvaluator.memoryIndexGet() != 0L
        }
    }

    /**
     * Used when the formula TextView is long clicked and its context menu used to either paste from
     * the clipboard (our onPaste override) or recall the last expression from memory and append it
     * to the current one (our onMemoryRecall override).
     */
    private val mOnFormulaContextMenuClickListener = object : OnFormulaContextMenuClickListener {
        /**
         * Called when the "paste" menu item is clicked, it should do something with the [clip]'s
         * [ClipData] it is passed. We initialize our variable `item` to *null* if the item count
         * of [clip] is 0 or else to the [ClipData.Item] at index 0 and if the result is *null*
         * we return *false* to the caller. Otherwise we initialize our variable `uri` with the
         * raw `Uri` contained in `item`, and if it is not *null* and the `isLastSaved` method of
         * [mEvaluator] signals that it is the `Uri` of the last expression saved to the preference
         * data base we call our method [clearIfNotInputState] to clear the main expression, call
         * the `appendExpr` method of [mEvaluator] to have it append that saved result, and call
         * our [redisplayAfterFormulaChange] to redisplay the new formula. If `uri` is *null* or
         * not the last saved result we call our [addChars] method to add the text contents of
         * `item` to the end of the expression. Finally we return *true* to indicate that we used
         * the data.
         *
         * @param clip the primary [ClipData] contents.
         * @return *true* if the data was used, *false* if there was nothing to paste.
         */
        override fun onPaste(clip: ClipData): Boolean {
            val item = (if (clip.itemCount == 0) null else clip.getItemAt(0))
                    ?: // nothing to paste, bail early...
                    return false

            // Check if the item is a previously copied result, otherwise paste as raw text.
            val uri = item.uri
            if (uri != null && mEvaluator.isLastSaved(uri)) {
                clearIfNotInputState()
                mEvaluator.appendExpr(mEvaluator.savedIndexGet())
                redisplayAfterFormulaChange()
            } else {
                addChars(item.coerceToText(this@Calculator2).toString(), false)
            }
            return true
        }

        /**
         * Called when a "memory" menu item is clicked, it should do something with the contents of
         * the memory register. First we call our method [clearIfNotInputState] to clear the main
         * expression, initialize our variable `memoryIndex` with the index of the expression that
         * memory is currently holding, and if that is not 0 we call the `appendExpr` method of
         * [mEvaluator] to append that expression to the main expression then call our method
         * [redisplayAfterFormulaChange] to redisplay the new formula.
         */
        override fun onMemoryRecall() {
            clearIfNotInputState()
            val memoryIndex = mEvaluator.memoryIndexGet()
            if (memoryIndex != 0L) {
                mEvaluator.appendExpr(mEvaluator.memoryIndexGet())
                redisplayAfterFormulaChange()
            }
        }
    }

    /**
     * [TextWatcher] for the formula TextView, the `afterTextChanged` override adds our
     * `OnPreDrawListener` [mPreDrawListener] to the ViewTreeObserver of the [HorizontalScrollView]
     * holding [TextWatcher] which will scroll the scroll view as new characters are added to the
     * formula.
     */
    private val mFormulaTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(charSequence: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(charSequence: CharSequence, start: Int, count: Int, after: Int) {}

        /**
         * This method is called to notify you that, somewhere within [editable], the text has been
         * changed. We initialize our variable `observer` with the [ViewTreeObserver] for the
         * [mFormulaContainer] view's hierarchy. If `observer` is alive we remove [mPreDrawListener]
         * as a `OnPreDrawListener` (if it was already one), then add it back again.
         *
         * @param editable the [Editable] text which has been changed.
         */
        override fun afterTextChanged(editable: Editable) {
            val observer = mFormulaContainer.viewTreeObserver
            if (observer.isAlive) {
                observer.removeOnPreDrawListener(mPreDrawListener)
                observer.addOnPreDrawListener(mPreDrawListener)
            }
        }
    }

    /**
     * The current [CalculatorState] of the calculator.
     */
    private lateinit var mCurrentState: CalculatorState
    /**
     * The [Evaluator] instance we use to evaluate the formulas entered.
     */
    private lateinit var mEvaluator: Evaluator

    /**
     * The [CalculatorDisplay] in our ui with id R.id.display, it is a *LinearLayout* which contains
     * the toolbar, formula, and result.
     */
    private lateinit var mDisplayView: CalculatorDisplay
    /**
     * The [TextView] in our ui which displays whether we are in degree mode or radian mode
     */
    private lateinit var mModeView: TextView
    /**
     * The [CalculatorFormula] TextView displaying the current formula.
     */
    private lateinit var mFormulaText: CalculatorFormula
    /**
     * The [CalculatorResult] TextView displaying the results of our calculations.
     */
    private lateinit var mResultText: CalculatorResult
    /**
     * The [HorizontalScrollView] holding our infinite [CalculatorFormula] formula TextView
     */
    private lateinit var mFormulaContainer: HorizontalScrollView
    /**
     * The [DragLayout] with id R.id.drag_layout which holds the FrameLayout for our history
     */
    private lateinit var mDragLayout: DragLayout

    /**
     * The [CalculatorPadViewPager] ViewPager used only in portrait orientation for the extra keypad
     * (it is null when in landscape orientation).
     */
    private var mPadViewPager: ViewPager? = null
    /**
     * The DEL key (id R.id.del) deletes character from forumla on click, clears display on long click
     */
    private lateinit var mDeleteButton: View
    /**
     * The CLR key (id R.id.clr) clears display, only visible after pressing '=' key (replaces DEL)
     */
    private lateinit var mClearButton: View
    /**
     * The '=' key (id R.id.eq) starts evaluation of the current formual
     */
    private lateinit var mEqualButton: View
    /**
     * The layout file that is resolved from the "activity_calculator" value for the particular
     * screen size of our device, could be layout/activity_calculator_port.xml, or could be
     * layout/activity_calculator_land.xml or layout/activity_calculator_tablet_port.xml which
     * is then included in our main layout file layout/activity_calculator_main.xml. It appears
     * to be only used for accessibility
     */
    private lateinit var mMainCalculator: View

    /**
     * The INV button (id R.id.toggle_inv) toggles transcendental functions to/from their inverse
     */
    private lateinit var mInverseToggle: TextView
    /**
     * Button used to toggle the RAD/DEG trigonometric mode currently in effect
     */
    private lateinit var mModeToggle: TextView

    /**
     * Array containing references to all the transcendental function buttons: R.id.fun_sin,
     * R.id.fun_cos, R.id.fun_tan, R.id.fun_ln, R.id.fun_log, and R.id.op_sqrt (the visibility
     * of these buttons is toggled VISIBLE/GONE by the INV button)
     */
    private lateinit var mInvertibleButtons: Array<View>
    /**
     * Array containing references to all the inverse transcendental function buttons: R.id.fun_arcsin,
     * R.id.fun_arccos, R.id.fun_arctan, R.id.fun_exp, R.id.fun_10pow, and R.id.op_sqr (the visibility
     * of these buttons is toggled VISIBLE/GONE by the INV button)
     */
    private lateinit var mInverseButtons: Array<View>

    /**
     * Last button pressed before a call to [onError] or [onClear] methods, its location is used as
     * the center of the [reveal] method's animation which sweeps over the display and status bar.
     */
    private lateinit var mCurrentButton: View
    /**
     * [AnimatorSet] set in action by [reveal] or [onResult] methods (or null when they are done)
     */
    private var mCurrentAnimator: Animator? = null

    /**
     * Characters that were recently entered at the end of the display that have not yet been added
     * to the underlying expression. (TODO: get rid of use of !! not null assertion somehow)
     */
    private var mUnprocessedChars: String? = null

    /**
     * Color to highlight unprocessed characters from physical keyboard.
     * TODO: should probably match this to the error color?
     */
    private val mUnprocessedColorSpan = ForegroundColorSpan(Color.RED)

    /**
     * Whether the display is one line. If true the display layout file display_one_line.xml is being
     * used (it is used for the default as well as values-w520dp-h220dp-land, values-w375dp-h220dp
     * and values-w230dp-h220dp) In display_one_line.xml the result TextView starts out invisible and
     * swaps with the formula TextView when the calculation is finished.
     */
    var isOneLine: Boolean = false
        private set

    /**
     * Used by our [HistoryFragment] to determine whether our display contains an expression that is
     * in progress (false) in which case it displays that expression as the "Current Expression", or
     * if the display displays only the result (true) in which case it just displays the history.
     * Note that ERROR has INPUT, not RESULT layout.
     */
    val isResultLayout: Boolean
        get() = mCurrentState == CalculatorState.INIT_FOR_RESULT || mCurrentState == CalculatorState.RESULT

    /**
     * Our [HistoryFragment], it is added to the R.id.history_frame ViewGroup by our [showHistoryFragment]
     * method with the tag HistoryFragment.TAG ("HistoryFragment") either by the selection of the option
     * menu item with id R.id.menu_history ("History") or by our [onStartDraggingOpen] override when
     * the user drags it down from display View.
     */
    private val historyFragment: HistoryFragment?
        get() {
            val manager = supportFragmentManager
            if (manager.isDestroyed) {
                return null
            }
            val fragment: Fragment? = manager.findFragmentByTag(HistoryFragment.TAG)
            return if (fragment == null || fragment.isRemoving) null else fragment as HistoryFragment
        }

    /**
     * The character to be used for the decimal sign text in our decimal button. Since we only
     * support LTR format, using the RTL comma does not make sense.
     */
    private val decimalSeparator: String
        get() {
            val defaultSeparator = DecimalFormatSymbols.getInstance().decimalSeparator
            val rtlComma = '\u066b'
            return if (defaultSeparator == rtlComma) "," else defaultSeparator.toString()
        }

    /**
     * The state that the current calculation is in
     */
    private enum class CalculatorState {
        INPUT, // Result and formula both visible, no evaluation requested,
        // Though result may be visible on bottom line.
        EVALUATE, // Both visible, evaluation requested, evaluation/animation incomplete.
        // Not used for instant result evaluation.
        INIT, // Very temporary state used as alternative to EVALUATE
        // during reinitialization.  Do not animate on completion.
        INIT_FOR_RESULT, // Identical to INIT, but evaluation is known to terminate
        // with result, and current expression has been copied to history.
        ANIMATE, // Result computed, animation to enlarge result window in progress.
        RESULT, // Result displayed, formula invisible.
        // If we are in RESULT state, the formula was evaluated without
        // error to initial precision.
        // The current formula is now also the last history entry.
        ERROR // Error displayed: Formula visible, result shows error message.
        // Display similar to INPUT state.
    }

    /**
     * This is called from our [restoreDisplay] method which is called from our [onCreate] override
     * in order to convert any saved [CalculatorState] from before a possible orientation change to
     * an appropriate continuation state (Maps the old saved state to a new state reflecting requested
     * result reevaluation). We just branch based on the saved [CalculatorState] passed us, returning
     * INIT_FOR_RESULT for both RESULT and INIT_FOR_RESULT saved state, returning INIT for both
     * ERROR and INIT saved state, and returning the state unchanged for both EVALUATE and INPUT
     * state.
     *
     * @return the [CalculatorState] we should now use.
     */
    private fun mapFromSaved(savedState: CalculatorState): CalculatorState {
        return when (savedState) {
            CalculatorState.RESULT, CalculatorState.INIT_FOR_RESULT ->
                // Evaluation is expected to terminate normally.
                CalculatorState.INIT_FOR_RESULT
            CalculatorState.ERROR, CalculatorState.INIT -> CalculatorState.INIT
            CalculatorState.EVALUATE, CalculatorState.INPUT -> savedState
            else  // Includes ANIMATE state.
            -> throw AssertionError("Impossible saved state")
        }
    }

    /**
     * Restore Evaluator state and [mCurrentState] from [savedInstanceState], called from [onCreate].
     * First we retrieve the *indexOfEnum* into the [CalculatorState] enum's that was stored in
     * [savedInstanceState] by [onSaveInstanceState], then we retrieve the [CalculatorState] value
     * that corresponds to that index into *savedState* and call our method [setState] to configure
     * our UI appropriately for that [CalculatorState]. We retrieve the [CharSequence] that was
     * possibly stored in [savedInstanceState] under the key KEY_UNPROCESSED_CHARS ("_unprocessed_chars")
     * to *unprocessed*, and if that is not null we store the string value of that in our field
     * [mUnprocessedChars]. We retrieve the byte array stored in [savedInstanceState] under the key
     * KEY_EVAL_STATE ("_eval_state") into *state*, and if this is not null we wrap in a try block
     * whose catch block will default to a clean state ([mCurrentState] = INPUT, and a cleared
     * [mEvaluator]), a call to the *restoreInstanceState* method of [mEvaluator] with an
     * [ObjectInputStream] created from an [ByteArrayInputStream] created from *state*. We retrieve
     * the [Boolean] stored in [savedInstanceState] under the key KEY_SHOW_TOOLBAR ("_show_toolbar")
     * (defaulting to true) and if it is true we call our method [showAndMaybeHideToolbar] to show
     * or hide the tool bar depending on the value of [mCurrentState], and if it is false we call
     * the *hideToolbar* method of [mDisplayView] to hide the tool bar. We then call our method
     * [onInverseToggled] with the value in [savedInstanceState] stored under the key KEY_INVERSE_MODE
     * ("_inverse_mode") to have it restore the state of the inverse functions keys in our UI.
     */
    private fun restoreInstanceState(savedInstanceState: Bundle) {
        val indexOfEnum = savedInstanceState
                .getInt(KEY_DISPLAY_STATE, CalculatorState.INPUT.ordinal)
        val savedState = CalculatorState.values()[indexOfEnum]
        setState(savedState)
        val unprocessed = savedInstanceState.getCharSequence(KEY_UNPROCESSED_CHARS)
        if (unprocessed != null) {
            mUnprocessedChars = unprocessed.toString()
        }
        val state = savedInstanceState.getByteArray(KEY_EVAL_STATE)
        if (state != null) {
            try {
                ObjectInputStream(ByteArrayInputStream(state)).use {
                    stream -> mEvaluator.restoreInstanceState(stream)
                }
            } catch (ignored: Throwable) {
                // When in doubt, revert to clean state
                mCurrentState = CalculatorState.INPUT
                mEvaluator.clearMain()
            }

        }
        if (savedInstanceState.getBoolean(KEY_SHOW_TOOLBAR, true)) {
            showAndMaybeHideToolbar()
        } else {
            mDisplayView.hideToolbar()
        }
        onInverseToggled(savedInstanceState.getBoolean(KEY_INVERSE_MODE))
        // TODO: We're currently not saving and restoring scroll position.
        //       We probably should.  Details may require care to deal with:
        //         - new display size
        //         - slow re-computation if we've scrolled far.
    }

    /**
     * Called from [onCreate] to set the deg/rad mode to that of the formula that [mEvaluator] last
     * worked on, redisplay the formula if [mCurrentState] is not in a state where it is invisible
     * (RESULT, and INIT_FOR_RESULT should remain invisible) by calling [redisplayFormula] to display
     * the latest formula in [mFormulaText]. If our current state [mCurrentState] is INPUT we call
     * the *setShouldEvaluateResult* method of [mResultText] to have it evaluate result on layout
     * (SHOULD_EVALUATE), otherwise we call our [setState] method to set our state to that which
     * our method [mapFromSaved] determines to be appropriate given the value of [mCurrentState]
     * and then we call the *setShouldEvaluateResult* method of [mResultText] with the value
     * SHOULD_REQUIRE to have it call the *requireResult* method of Evaluator on layout so that it
     * will start the evaluation of the expression.
     */
    private fun restoreDisplay() {
        onModeChanged(mEvaluator.degreeModeGet(Evaluator.MAIN_INDEX))
        if (mCurrentState != CalculatorState.RESULT && mCurrentState != CalculatorState.INIT_FOR_RESULT) {
            redisplayFormula()
        }
        if (mCurrentState == CalculatorState.INPUT) {
            // This resultText will explicitly call evaluateAndNotify when ready.
            mResultText.setShouldEvaluateResult(CalculatorResult.SHOULD_EVALUATE, this)
        } else {
            // Just reevaluate.
            setState(mapFromSaved(mCurrentState))
            // Request evaluation when we know display width.
            mResultText.setShouldEvaluateResult(CalculatorResult.SHOULD_REQUIRE, this)
        }
    }

    /**
     * Called when our [FragmentActivity] is starting. First we call our super's implementation of
     * [onCreate], then we set our content view to our layout file R.layout.activity_calculator_main.
     * We set the action bar to the [Toolbar] with id R.id.toolbar (it is included by the display
     * layouts, both 'display_one_line.xml' and 'display_two_line.xml'). We set the *displayOptions*
     * property of the *actionBar* to 0 to hide all the default options, and add a lambda as an
     * *OnMenuVisibilityListener* that will respond to menu visibility change events by forcing
     * [mDisplayView] to have the tool bar visible while the options menu is displayed. We initialize
     * [mMainCalculator] by finding the view with id R.id.main_calculator (it is the *LinearLayout*
     * which contains our calculator UI and is important for accessibility), [mDisplayView] by finding
     * the view with id R.id.display (it contains both our current formula, and our result TextView's),
     * [mModeView] by finding the view with id R.id.mode (the [TextView] in our ui which displays
     * whether we are in degree mode or radian mode), [mFormulaText] by finding the view with id
     * R.id.formula (the [CalculatorFormula] TextView displaying the current formula), [mResultText]
     * by finding the view with id R.id.result (the [CalculatorResult] TextView displaying the results
     * of our calculations), and [mFormulaContainer] by finding the view with id R.id.formula_container
     * (the [HorizontalScrollView] holding our infinite [CalculatorFormula] formula TextView). We
     * initialize [mEvaluator] with a new instance of [Evaluator], set its [Evaluator.Callback] to
     * our [mEvaluatorCallback], then set the [Evaluator] of [mResultText] to [mEvaluator] with its
     * index the main expression MAIN_INDEX. We call the *setActivity* method of [KeyMaps] to have
     * it set the activity used for looking up button labels to *this*. We initialize [mPadViewPager]
     * by finding the view with id R.id.pad_pager, [mDeleteButton] by finding the view with id
     * R.id.del, and [mClearButton] by finding the view with id R.id.clr. We initialize our variable
     * *numberPad* by finding the view with id R.id.pad_numeric, and try to initialize our variable
     * *numberPadEquals* by finding the view in *numberPad* with id R.id.eq. We then initialize
     * [mEqualButton] to *numberPadEquals* if it is not null, and is VISIBLE, or to the view
     * inside the view with id R.id.pad_operator which has the id R.id.eq if *numberPadEquals* is
     * null or is not VISIBLE. We initialize our variable *decimalPointButton* by finding the view
     * with id R.id.dec_point and set its text to the decimal point string [decimalSeparator]. We
     * initialize [mInverseToggle] by finding the view with id R.id.toggle_inv and [mModeToggle] by
     * finding the view with id R.id.toggle_mode. We initialize [isOneLine] to true if [mResultText]
     * is INVISIBLE (it is invisible in layout/display_one_line.xml). We fill [mInvertibleButtons]
     * with the views with ids: R.id.fun_sin, R.id.fun_cos, R.id.fun_tan, R.id.fun_ln, R.id.fun_log,
     * and R.id.op_sqrt (these are the invertible transcendental functions) and fill [mInverseButtons]
     * with the views with ids: R.id.fun_arcsin, R.id.fun_arccos, R.id.fun_arctan, R.id.fun_exp,
     * R.id.fun_10pow, and R.id.op_sqr (these are the inverses of the functions in [mInvertibleButtons],
     * the visibility of the views in [mInvertibleButtons], and [mInverseButtons] are swapped when
     * [mInverseToggle] is used to toggle between them). We initialize [mDragLayout] by finding the
     * view with id R.id.drag_layout, call its *removeDragCallback* method to remove *this* as a
     * callback (just in case -- we don't want to be called twice), call its *addDragCallback* method
     * to add *this* as a callback, and call its *setCloseCallback* method to add *this* as a
     * *CloseCallback*. We call the *setOnContextMenuClickListener* method of [mFormulaText] to set
     * its [OnFormulaContextMenuClickListener] to [mOnFormulaContextMenuClickListener], call its
     * *setOnDisplayMemoryOperationsListener* method to set its [OnDisplayMemoryOperationsListener]
     * to *this* and call its *addTextChangedListener* to add [mFormulaTextWatcher] as a [TextWatcher].
     * We set the [OnLongClickListener] of [mDeleteButton] to *this* (a long click on it will clear
     * it). If [savedInstanceState] is not null (we are being restarted) we pass it to our method
     * [restoreInstanceState] to restore our state from the values stored by [onSaveInstanceState],
     * if it is null we are just being started so we call the *clearMain* method of [mEvaluator] to
     * have it initialize itself, call our [showAndMaybeHideToolbar] method to instruct [mDisplayView]
     * to show the tool bar when it is relevant, and call our [onInverseToggled] method with *false*
     * so that it starts out showing the views in [mInvertibleButtons] (instead of the inverse functions
     * in [mInverseButtons]. Finally we call our [restoreDisplay] method to update [mEvaluator],
     * [mCurrentState], [mFormulaText] and [mResultText] to reflect the initializations we have just
     * completed.
     *
     * @param savedInstanceState If the activity is being re-initialized after previously being shut
     * down then this Bundle contains the data it most recently supplied in [onSaveInstanceState].
     * Note: Otherwise it is null.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calculator_main)

        setActionBar(findViewById<View>(R.id.toolbar) as Toolbar)

        // Hide all default options in the ActionBar.
        actionBar?.displayOptions = 0

        // Ensure the toolbar stays visible while the options menu is displayed.
        actionBar?.addOnMenuVisibilityListener {
            isVisible -> mDisplayView.forceToolbarVisible = isVisible
        }

        mMainCalculator = findViewById(R.id.main_calculator)
        mDisplayView = findViewById(R.id.display)
        mModeView = findViewById(R.id.mode)
        mFormulaText = findViewById(R.id.formula)
        mResultText = findViewById(R.id.result)
        mFormulaContainer = findViewById(R.id.formula_container)

        mEvaluator = Evaluator.instanceGet(this)
        mEvaluator.setCallback(mEvaluatorCallback)
        mResultText.setEvaluator(mEvaluator, Evaluator.MAIN_INDEX)
        KeyMaps.setActivity(this)

        mPadViewPager = findViewById(R.id.pad_pager)
        mDeleteButton = findViewById(R.id.del)
        mClearButton = findViewById(R.id.clr)

        val numberPad = findViewById<View>(R.id.pad_numeric)
        val numberPadEquals: View? = numberPad.findViewById(R.id.eq)
        mEqualButton = if (numberPadEquals == null || numberPadEquals.visibility != View.VISIBLE) {
            findViewById<View>(R.id.pad_operator).findViewById(R.id.eq)
        } else {
            numberPadEquals
        }

        val decimalPointButton = numberPad.findViewById<TextView>(R.id.dec_point)
        decimalPointButton.text = decimalSeparator

        mInverseToggle = findViewById(R.id.toggle_inv)
        mModeToggle = findViewById(R.id.toggle_mode)

        isOneLine = mResultText.visibility == View.INVISIBLE

        mInvertibleButtons = arrayOf(findViewById(R.id.fun_sin), findViewById(R.id.fun_cos),
                findViewById(R.id.fun_tan), findViewById(R.id.fun_ln), findViewById(R.id.fun_log),
                findViewById(R.id.op_sqrt))
        mInverseButtons = arrayOf(findViewById(R.id.fun_arcsin), findViewById(R.id.fun_arccos),
                findViewById(R.id.fun_arctan), findViewById(R.id.fun_exp),
                findViewById(R.id.fun_10pow), findViewById(R.id.op_sqr))

        mDragLayout = findViewById(R.id.drag_layout)
        mDragLayout.removeDragCallback(this)
        mDragLayout.addDragCallback(this)
        mDragLayout.setCloseCallback(this)

        mFormulaText.setOnContextMenuClickListener(mOnFormulaContextMenuClickListener)
        mFormulaText.setOnDisplayMemoryOperationsListener(mOnDisplayMemoryOperationsListener)
        mFormulaText.setOnTextSizeChangeListener(this)
        mFormulaText.addTextChangedListener(mFormulaTextWatcher)

        mDeleteButton.setOnLongClickListener(this)

        mCurrentState = CalculatorState.INPUT
        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState)
        } else {
            mEvaluator.clearMain()
            showAndMaybeHideToolbar()
            onInverseToggled(false)
        }
        restoreDisplay()
    }

    /**
     * Called after [onRestoreInstanceState], [onRestart], or [onPause], for our activity to start
     * interacting with the user. First we call through to our super's implementation, then if the
     * tool bar is visible according to [mDisplayView], we call our [showAndMaybeHideToolbar] method
     * which shows the tool bar briefly, then automatically hides it again if it's not relevant to
     * the current formula. Then we set the *importantForAccessibility* property of [mMainCalculator]
     * depending on whether the [mDragLayout] (our [HistoryFragment]) is open/showing, if it is we
     * want to hide the main Calculator elements from accessibility and set it to the value
     * IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS (view is not important for accessibility, nor
     * for any of its descendant views), if it is not we set it to IMPORTANT_FOR_ACCESSIBILITY_AUTO
     * (automatically determine whether a view is important for accessibility).
     */
    override fun onResume() {
        super.onResume()
        if (mDisplayView.isToolbarVisible) {
            showAndMaybeHideToolbar()
        }
        // If HistoryFragment is showing, hide the main Calculator elements from accessibility.
        // This is because TalkBack does not use visibility as a cue for RelativeLayout elements,
        // and RelativeLayout is the base class of DragLayout.
        // If we did not do this, it would be possible to traverse to main Calculator elements from
        // HistoryFragment.
        mMainCalculator.importantForAccessibility = if (mDragLayout.isOpen)
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        else
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    /**
     * Called to retrieve per-instance state from an activity before being killed so that the state
     * can be restored in [onCreate] or [onRestoreInstanceState] (the [Bundle] populated by this
     * method will be passed to both). First we call the *cancelAll* method of [mEvaluator] with the
     * quiet flag *true* which causes it to cancel all of its [CalculatorExpr] in progress without a
     * cancellation message, and if [mCurrentAnimator] is not null we cancel the animation that was
     * in progress to ensure our state is up-to-date. Then we call our super's [onSaveInstanceState].
     * We store the ordinal int of the current [CalculatorState] of [mCurrentState] in [outState]
     * under the key KEY_DISPLAY_STATE ("_display_state"), and store [mUnprocessedChars] as a
     * [CharSequence] under the key KEY_UNPROCESSED_CHARS ("_unprocessed_chars"). We then initialize
     * our variable *byteArrayStream* with a new instance of [ByteArrayOutputStream], then wrapped
     * in a *try* block intended to catch and rethrow [IOException] as an [AssertionError] we create
     * an [ObjectOutputStream] that will write to *byteArrayStream* and pass it to a block lambda
     * using the [use] library function, and that lambda calls the *saveInstanceState* method of
     * [mEvaluator] to have it save the evaluator state, including the expression and any saved value
     * to the [DataOutput] interface of that [ObjectOutputStream], and then we save the byte array
     * version of *byteArrayStream* in [outState] under the key KEY_EVAL_STATE ("_eval_state"). Next
     * we store the *isSelected* state of [mInverseToggle] under the key KEY_INVERSE_MODE
     * ("_inverse_mode"), and the *isToolbarVisible* state of [mDisplayView] under the key
     * KEY_SHOW_TOOLBAR ("_show_toolbar"). Finally we call the *waitForWrites* method of [mEvaluator]
     * to have it wait for any asynchronous writes to the database to complete.
     * TODO: Figure out if this waiting is bad for orientation changes, might be why they are so slow.
     *
     * @param outState Bundle in which to place your saved state.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        mEvaluator.cancelAll(true)
        // If there's an animation in progress, cancel it first to ensure our state is up-to-date.
        mCurrentAnimator?.cancel()

        super.onSaveInstanceState(outState)

        outState.putInt(KEY_DISPLAY_STATE, mCurrentState.ordinal)
        outState.putCharSequence(KEY_UNPROCESSED_CHARS, mUnprocessedChars)

        val byteArrayStream = ByteArrayOutputStream()
        try {
            ObjectOutputStream(byteArrayStream).use { out -> mEvaluator.saveInstanceState(out) }
        } catch (e: IOException) {
            // Impossible; No IO involved.
            throw AssertionError("Impossible IO exception", e)
        }
        outState.putByteArray(KEY_EVAL_STATE, byteArrayStream.toByteArray())

        outState.putBoolean(KEY_INVERSE_MODE, mInverseToggle.isSelected)
        outState.putBoolean(KEY_SHOW_TOOLBAR, mDisplayView.isToolbarVisible)
        // We must wait for asynchronous writes to complete, since outState may contain
        // references to expressions being written.
        mEvaluator.waitForWrites()
    }

    /**
     * Set the state, updating delete label and display colors. This restores display positions on
     * moving to INPUT but movement/animation for moving to RESULT has already been done. If our
     * parameter [state] is the same as [mCurrentState] we do nothing. If [state] is INPUT we call
     * the *setShouldEvaluateResult* method of [mResultText] to instruct it to not evaluate the formula
     * in its *onLayout* override, setting its *EvaluationListener* to *null*, and then we call our
     * [restoreDisplayPositions] method to restore the positions of the formula and result displays
     * to their original pre-animation state. Next we set [mCurrentState] to [state]. If [mCurrentState]
     * is RESULT we set the visibility of [mDeleteButton] to GONE and the visibility of [mClearButton]
     * to VISIBLE, otherwise we we set the visibility of [mDeleteButton] to VISIBLE and the visibility
     * of [mClearButton] to GONE. If our [isOneLine] method reports *true* we need to determine the
     * appropriate visibility of [mFormulaText] and [mResultText]: when [mCurrentState] is RESULT,
     * EVALUATE, or ANIMATE we set the visibility of both [mFormulaText] and [mResultText] to VISIBLE,
     * when [mCurrentState] is ERROR we set the visibility of [mFormulaText] to INVISIBLE and the
     * visibility of [mResultText] to VISIBLE, and for all other states we set the visibility of
     * [mFormulaText] to VISIBLE and the visibility of [mResultText] to INVISIBLE. Now we need to set
     * the colors used for ERROR and RESULT states: when [mCurrentState] is ERROR we initialize our
     * variable *errorColor* with the color R.color.calculator_error_color (a bright red) and set the
     * text color of both [mFormulaText] and [mResultText] to it as well as setting the [Window] status
     * bar color to it; when [mCurrentState] is RESULT we set the text color of [mFormulaText] to the
     * color R.color.display_formula_text_color (BLACK), the color of [mResultText] to the color
     * R.color.display_result_text_color (a light gray) and the color of the [Window] status bar to
     * the color R.color.calculator_statusbar_color (a turquoise shade of blue). Finally we call the
     * method [invalidateOptionsMenu] to have the options menu recreated.
     *
     * @param state new [CalculatorState] to set
     */
    private fun setState(state: CalculatorState) {
        if (mCurrentState != state) {
            if (state == CalculatorState.INPUT) {
                // We'll explicitly request evaluation from now on.
                mResultText.setShouldEvaluateResult(CalculatorResult.SHOULD_NOT_EVALUATE, null)
                restoreDisplayPositions()
            }
            mCurrentState = state

            if (mCurrentState == CalculatorState.RESULT) {
                // No longer do this for ERROR; allow mistakes to be corrected.
                mDeleteButton.visibility = View.GONE
                mClearButton.visibility = View.VISIBLE
            } else {
                mDeleteButton.visibility = View.VISIBLE
                mClearButton.visibility = View.GONE
            }

            if (isOneLine) {
                when (mCurrentState) {
                    CalculatorState.RESULT, CalculatorState.EVALUATE, CalculatorState.ANIMATE -> {
                        mFormulaText.visibility = View.VISIBLE
                        mResultText.visibility = View.VISIBLE
                    }
                    CalculatorState.ERROR -> {
                        mFormulaText.visibility = View.INVISIBLE
                        mResultText.visibility = View.VISIBLE
                    }
                    else -> {
                        mFormulaText.visibility = View.VISIBLE
                        mResultText.visibility = View.INVISIBLE
                    }
                }
            }

            when {
                mCurrentState == CalculatorState.ERROR -> {
                    val errorColor = ContextCompat.getColor(this, R.color.calculator_error_color)
                    mFormulaText.setTextColor(errorColor)
                    mResultText.setTextColor(errorColor)
                    window.statusBarColor = errorColor
                }
                mCurrentState != CalculatorState.RESULT -> {
                    mFormulaText.setTextColor(
                            ContextCompat.getColor(this, R.color.display_formula_text_color))
                    mResultText.setTextColor(
                            ContextCompat.getColor(this, R.color.display_result_text_color))
                    window.statusBarColor = ContextCompat.getColor(this, R.color.calculator_statusbar_color)
                }
            }

            invalidateOptionsMenu()
        }
    }

    /**
     * Perform any final cleanup before our activity is destroyed. We call the *removeDragCallback*
     * method of [mDragLayout] to remove *this* as a *DragCallback* then call our super's implementation
     * of [onDestroy].
     */
    override fun onDestroy() {
        mDragLayout.removeDragCallback(this)
        super.onDestroy()
    }

    /**
     * Destroy the evaluator and close the underlying database. Just calls the *destroyEvaluator*
     * method of [mEvaluator]. Never used though.
     */
    @Suppress("unused")
    fun destroyEvaluator() {
        mEvaluator.destroyEvaluator()
    }

    /**
     * Notifies the Activity that an action mode has been started. [ActionMode] is used for paste
     * support on M (23) and higher. First we call our super's implementation of [onActionModeStarted]
     * then if the tag of [mode] is CalculatorFormula.TAG_ACTION_MODE we call the *scrollTo* method
     * of [mFormulaContainer] to have it scroll all the way to the right side of [mFormulaText].
     *
     * @param mode the [ActionMode] that has started.
     */
    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        if (mode.tag === CalculatorFormula.TAG_ACTION_MODE) {
            mFormulaContainer.scrollTo(mFormulaText.right, 0)
        }
    }

    /**
     * Stop any active [ActionMode] or [ContextMenu] being used for copy/paste actions. We return the
     * boolean *or* of the results returned by the *stopActionModeOrContextMenu* methods of either
     * [mResultText] or [mFormulaText]. Note that this is a short circuit *or* which is quite correct
     * because only one or the other can be active at one time.
     *
     * @return true if there was an active [ActionMode] or [ContextMenu]
     */
    private fun stopActionModeOrContextMenu(): Boolean {
        return mResultText.stopActionModeOrContextMenu() || mFormulaText.stopActionModeOrContextMenu()
    }

    /**
     * Called whenever a key, touch, or trackball event is dispatched to the activity. First we call
     * our super's implementation of [onUserInteraction], then if [mCurrentAnimator] is not null we
     * call its *end* method to end it immediately.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()

        // If there's an animation in progress, end it c, so the user interaction can
        // be handled.
        mCurrentAnimator?.end()
    }

    /**
     * Called to process touch screen events. If the masked off action of [e] is ACTION_DOWN, we
     * call our [stopActionModeOrContextMenu] method to stop any active [ActionMode] or [ContextMenu]
     * in progress, then we set our variable *historyFragment* to [historyFragment] and if
     * [mDragLayout] is open and *historyFragment* is not null we call the *stopActionModeOrContextMenu*
     * method of *historyFragment*. Finally we return the value returned by our super's implementation
     * of [stopActionModeOrContextMenu] to the caller.
     *
     * @param e The touch screen event.
     * @return true if this event was consumed.
     */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            stopActionModeOrContextMenu()

            val historyFragment = historyFragment
            if (mDragLayout.isOpen && historyFragment != null) {
                historyFragment.stopActionModeOrContextMenu()
            }
        }
        return super.dispatchTouchEvent(e)
    }

    /**
     * Called when the activity has detected the user's press of the back key. If our method
     * [stopActionModeOrContextMenu] returns *false* (no [ActionMode] or [ContextMenu] were in
     * progress) we set our variable *historyFragment* to [historyFragment], then when [mDragLayout]
     * is open and *historyFragment* is not null if the *stopActionModeOrContextMenu* method of
     * *historyFragment* returns *false* we call our method [removeHistoryFragment] to have the
     * [FragmentManager] pop the [HistoryFragment] off the stack, we then return. If [mPadViewPager]
     * is not *null* and its *currentItem* is not 0 we decrement its *currentItem*. Otherwise
     * we just call our super's implementation of [onBackPressed].
     */
    override fun onBackPressed() {
        if (!stopActionModeOrContextMenu()) {
            val historyFragment = historyFragment
            when {
                mDragLayout.isOpen && historyFragment != null -> {
                    if (!historyFragment.stopActionModeOrContextMenu()) {
                        removeHistoryFragment()
                    }
                    return
                }
                mPadViewPager != null && mPadViewPager!!.currentItem != 0 -> {
                    mPadViewPager!!.currentItem = mPadViewPager!!.currentItem - 1
                }
                else -> super.onBackPressed()
            }
        }
    }

    /**
     * Called when a key was released and not handled by any of the views inside of the activity. When
     * [keyCode] is one of KEYCODE_BACK, KEYCODE_ESCAPE, KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN,
     * KEYCODE_DPAD_LEFT, or KEYCODE_DPAD_RIGHT we just return the value returned by our super's
     * implementation of [onKeyUp]. Otherwise we first call our method [stopActionModeOrContextMenu]
     * to stop the action mode or context menu if it's showing, then call our [cancelUnrequested]
     * method to cancel unrequested in-progress evaluation of the main expression. We then branch
     * on the value of [keyCode]:
     *
     * KEYCODE_NUMPAD_ENTER, KEYCODE_ENTER, KEYCODE_DPAD_CENTER: we set [mCurrentButton] to
     * [mEqualButton], call our method [onEquals] and return true to the caller.
     *
     * KEYCODE_DEL: we set [mCurrentButton] to [mDeleteButton], call our method [onDelete] and
     * return true to the caller.
     *
     * KEYCODE_CLEAR: we set [mCurrentButton] to [mClearButton], call our method [onClear] and
     * return true to the caller.
     *
     * For all other characters we call our method [cancelIfEvaluating] with *false* for the *quiet*
     * flag to have it cancel any in-progress explicitly requested evaluations without suppressing
     * its pop-up message. We then initialize our variable *raw* with the unicode character which is
     * associated with [keyCode] and the state of the meta keys pressed in [event]. If *raw* has
     * the COMBINING_ACCENT bit set it means it is a dead key so we return *true* to just discard it.
     * If *raw* is not a character that is usable as part of a java identifier or is a white space
     * character we return *true* to just discard it. Otherwise we initialize our variable *c* to the
     * [Char] value of *raw*. If *c* is the character '=' we set [mCurrentButton] to [mEqualButton]
     * and call our [onEquals] method, otherwise we call our [addChars] method to add the the string
     * value of *c* to the expression with the *explicit* flag set to true to indicate it was typed
     * by the user not pasted, then call our [redisplayAfterFormulaChange] to have it redisplay the
     * formula. Finally we return *true* to the caller.
     *
     * @param keyCode The value in event.getKeyCode().
     * @param event Description of the key event.
     * @return true to consume the event here
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Allow the system to handle special key codes (e.g. "BACK" or "DPAD").
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
            -> return super.onKeyUp(keyCode, event)
        }

        // Stop the action mode or context menu if it's showing.
        stopActionModeOrContextMenu()

        // Always cancel unrequested in-progress evaluation of the main expression, so that
        // we don't have to worry about subsequent asynchronous completion.
        // Requested in-progress evaluations are handled below.
        cancelUnrequested()

        when (keyCode) {
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                mCurrentButton = mEqualButton
                onEquals()
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                mCurrentButton = mDeleteButton
                onDelete()
                return true
            }
            KeyEvent.KEYCODE_CLEAR -> {
                mCurrentButton = mClearButton
                onClear()
                return true
            }
            else -> {
                cancelIfEvaluating(false)
                val raw = event.keyCharacterMap.get(keyCode, event.metaState)
                if (raw and KeyCharacterMap.COMBINING_ACCENT != 0) {
                    return true // discard
                }
                // Try to discard non-printing characters and the like.
                // The user will have to explicitly delete other junk that gets past us.
                if (Character.isIdentifierIgnorable(raw) || Character.isWhitespace(raw)) {
                    return true
                }
                val c = raw.toChar()
                if (c == '=') {
                    mCurrentButton = mEqualButton
                    onEquals()
                } else {
                    addChars(c.toString(), true)
                    redisplayAfterFormulaChange()
                }
                return true
            }
        }
    }

    /**
     * Invoked whenever the inverse button is toggled to update the UI. First we set the selected
     * state of [mInverseToggle] to [showInverse]. Then we branch on the value of [showInverse]:
     *
     * *true*: We set the content description of [mInverseToggle] to the string "hide inverse
     * functions", then loop through all the *invertibleButton* buttons in [mInvertibleButtons]
     * setting their visibility to GONE, and loop through all the *inverseButton* in [mInverseButtons]
     * setting their visibility to VISIBLE.
     *
     * *false*: We set the content description of [mInverseToggle] to the string "show inverse
     * functions", then loop through all the *invertibleButton* buttons in [mInvertibleButtons]
     * setting their visibility to VISIBLE, and loop through all the *inverseButton* in
     * [mInverseButtons] setting their visibility to GONE.
     *
     * @param showInverse `true` if inverse functions should be shown
     */
    private fun onInverseToggled(showInverse: Boolean) {
        mInverseToggle.isSelected = showInverse
        if (showInverse) {
            mInverseToggle.contentDescription = getString(R.string.desc_inv_on)
            for (invertibleButton in mInvertibleButtons) {
                invertibleButton.visibility = View.GONE
            }
            for (inverseButton in mInverseButtons) {
                inverseButton.visibility = View.VISIBLE
            }
        } else {
            mInverseToggle.contentDescription = getString(R.string.desc_inv_off)
            for (invertibleButton in mInvertibleButtons) {
                invertibleButton.visibility = View.VISIBLE
            }
            for (inverseButton in mInverseButtons) {
                inverseButton.visibility = View.GONE
            }
        }
    }

    /**
     * Invoked whenever the deg/rad mode may have changed to update the UI. Note that the mode has
     * not necessarily actually changed where this is invoked. We branch on the value of [degreeMode]:
     *
     * *true*: We set the text of [mModeView] to the string "deg", and set its content description to
     * "degree mode". The we set the text of [mModeToggle] to the string "rad" and its content
     * description to "switch to radians".
     *
     * *false*: We set the text of [mModeView] to the string "rad", and set its content description to
     * "radian mode". The we set the text of [mModeToggle] to the string "deg" and its content
     * description to "switch to degrees".
     *
     * @param degreeMode `true` if in degree mode
     */
    private fun onModeChanged(degreeMode: Boolean) {
        if (degreeMode) {
            mModeView.setText(R.string.mode_deg)
            mModeView.contentDescription = getString(R.string.desc_mode_deg)

            mModeToggle.setText(R.string.mode_rad)
            mModeToggle.contentDescription = getString(R.string.desc_switch_rad)
        } else {
            mModeView.setText(R.string.mode_rad)
            mModeView.contentDescription = getString(R.string.desc_mode_rad)

            mModeToggle.setText(R.string.mode_deg)
            mModeToggle.contentDescription = getString(R.string.desc_switch_deg)
        }
    }

    /**
     * Called to have the [FragmentManager] remove the [HistoryFragment]. We initialize our variable
     * *manager* with the [FragmentManager] for interacting with fragments associated with this
     * activity. If *manager* has not been destroyed, we have it pop down to and including the
     * fragment with the tag "HistoryFragment". We then set the *importantForAccessibility* flag of
     * [mMainCalculator] to IMPORTANT_FOR_ACCESSIBILITY_AUTO (since when the [HistoryFragment] is
     * hidden, the main Calculator is important for accessibility again).
     */
    private fun removeHistoryFragment() {
        val manager = supportFragmentManager
        if (!manager.isDestroyed) {
            manager.popBackStack(HistoryFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        // When HistoryFragment is hidden, the main Calculator is important for accessibility again.
        mMainCalculator.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    /**
     * Switch to INPUT from RESULT state in response to input of the specified button_id.
     * View.NO_ID is treated as an incomplete function id. If the button pressed is for a
     * binary operator (R.id.op_pow, R.id.op_mul, R.id.op_div, R.id.op_add, or R.id.op_sub)
     * or is for a "suffix" operator (R.id.op_fact, R.id.op_pct, or R.id.op_sqr) we call the
     * *collapse* method of [mEvaluator] to have it abbreviate the most recent history entry
     * to a pre-evaluated expression node, and use that as the new main expression, otherwise
     * we call our [announceClearedForAccessibility] method to have it announce "cleared", and
     * call the *clearMain* method of [mEvaluator] to have it clear its *mMainExpr*. Finally we
     * set our [CalculatorState] to INPUT.
     *
     * @param button_id the resource id of the button that was pressed.
     */
    private fun switchToInput(button_id: Int) {
        if (KeyMaps.isBinary(button_id) || KeyMaps.isSuffix(button_id)) {
            mEvaluator.collapse(mEvaluator.maxIndexGet() /* Most recent history entry */)
        } else {
            announceClearedForAccessibility()
            mEvaluator.clearMain()
        }
        setState(CalculatorState.INPUT)
    }

    /**
     * Add the given button id to input expression. If appropriate, clear the expression before
     * doing so. When [mCurrentState] is:
     *
     * ERROR: We call our [setState] method to set the [CalculatorState] to INPUT.
     *
     * RESULT: We call our [switchToInput] method to have it react to the button, either continuing
     * the previous expression for a binary or suffix operator, or by clearing the current result and
     * formula to start a new one.
     *
     * Finally we call the *append* method of [mEvaluator] to have it append [id] to the expression
     * it is evaluating.
     *
     * @param id the resource id of the button we want to add to the expression.
     */
    private fun addKeyToExpr(id: Int) {

        when (mCurrentState) {
            CalculatorState.ERROR -> setState(CalculatorState.INPUT)
            CalculatorState.RESULT -> switchToInput(id)
            else -> {}
        }

        if (!mEvaluator.append(id)) {
            // TODO: Some user visible feedback?
        }
    }

    /**
     * Add the given button id to the input expression, assuming it was explicitly typed/touched.
     * We perform slightly more aggressive correction than in pasted expressions. If our current
     * state [mCurrentState] is INPUT and [id] is R.id.op_sub we retrieve the MAIN_INDEX expression
     * of [mEvaluator] and call its *removeTrailingAdditiveOperators* method to remove any additive
     * operators from the end of the expression. Then we call our [addKeyToExpr] to add [id] to the
     * end of the current expression that [mEvaluator] is evaluating.
     *
     * @param id resource id of the the key that was typed/touched.
     */
    private fun addExplicitKeyToExpr(id: Int) {
        if (mCurrentState == CalculatorState.INPUT && id == R.id.op_sub) {
            mEvaluator.exprGet(Evaluator.MAIN_INDEX).removeTrailingAdditiveOperators()
        }
        addKeyToExpr(id)
    }

    /**
     * Starts evaluation of the current expression if [mCurrentState] is INPUT and the current
     * expression has "interesting" operations in it (is worth evaluating).
     */
    fun evaluateInstantIfNecessary() {
        if (mCurrentState == CalculatorState.INPUT
                && mEvaluator.exprGet(Evaluator.MAIN_INDEX).hasInterestingOps()) {
            mEvaluator.evaluateAndNotify(Evaluator.MAIN_INDEX, this, mResultText)
        }
    }

    /**
     * Redisplays the formula after it is changed, clears our [mResultText] and causes [mEvaluator]
     * to refill [mResultText] with whatever is appropriate given the changed formula it is currently
     * evaluating. First we call our method [redisplayFormula] to have it update the text of our
     * formula TextView [mFormulaText], then we set our [CalculatorState] to INPUT and clear
     * [mResultText]. If our [haveUnprocessed] method reports that we have characters which have not
     * be processed yet in [mUnprocessedChars] we call the *touch* method of [mEvaluator] which marks
     * the expression as having changed which prevents the next evaluation request from being ignored,
     * otherwise we call our [evaluateInstantIfNecessary] which causes evaluation and result display
     * to occur if it is appropriate to do so.
     */
    private fun redisplayAfterFormulaChange() {
        // TODO: Could do this more incrementally.
        redisplayFormula()
        setState(CalculatorState.INPUT)
        mResultText.clear()
        if (haveUnprocessed()) {
            // Force reevaluation when text is deleted, even if expression is unchanged.
            mEvaluator.touch()
        } else {
            evaluateInstantIfNecessary()
        }
    }

    /**
     * Show the toolbar, automatically hide it again if it's not relevant to the current formula. We
     * initialize our variable *shouldBeVisible* to true if [mCurrentState] is INPUT and the
     * *hasTrigFuncs* method of [mEvaluator] reports that the current main expression contains trig
     * functions. Then we call the *showToolbar* method of [mDisplayView] with the inverse of
     * *shouldBeVisible* (if *shouldBeVisible* is true *showToolbar* will not auto hide the toolbar
     * after showing it).
     */
    private fun showAndMaybeHideToolbar() {
        val shouldBeVisible = mCurrentState == CalculatorState.INPUT && mEvaluator.hasTrigFuncs()
        mDisplayView.showToolbar(!shouldBeVisible)
    }

    /**
     * Display or hide the toolbar depending on calculator state. We initialize our variable
     * *shouldBeVisible* to true if [mCurrentState] is INPUT and the *hasTrigFuncs* method of
     * [mEvaluator] reports that the current main expression contains trig functions. If
     * *shouldBeVisible* is *true* we call the *showToolbar* method of [mDisplayView] with *false*
     * as the *autoHide* parameters to have it show the tool bar without auto-hiding it after a 3
     * second delay, otherwise we call the *hideToolbar* method of [mDisplayView] to have it hide
     * the tool bar immediately.
     */
    private fun showOrHideToolbar() {
        val shouldBeVisible = mCurrentState == CalculatorState.INPUT && mEvaluator.hasTrigFuncs()
        if (shouldBeVisible) {
            mDisplayView.showToolbar(false)
        } else {
            mDisplayView.hideToolbar()
        }
    }

    /**
     * This is specified as the "android:onClick" *OnClickListener* for the style "PadButtonStyle"
     * and "PadButtonStyle" is extended by "dot notation" to create a bunch of different styles for
     * the different kinds of keys and then used by all of the keys of the calculator. The file
     * values/styles.xml contains the definition of "PadButtonStyle", and default definitions for the
     * different kinds of children of "PadButtonStyle" with these children overridden depending on the
     * screen size and orientation. First we set [mCurrentButton] to [view], then we call our method
     * [stopActionModeOrContextMenu] to cancel the copy/paste context menu if it is being displayed,
     * and call our method [cancelUnrequested] to cancel any current background task that might be in
     * progress for the main expression. We next branch on the resource id *id* of the view that was
     * clicked:
     *
     * R.id.eq: we call our method [onEquals] which causes the current expression to be evaluated if
     * it is in an state where that is possible, or signals an error if it is incomplete.
     *
     * R.id.del: we call our method [onDelete] to remove the last character or operator from the
     * expression.
     *
     * R.id.clr: we call our method [onClear] which clears the expression if there is one in
     * progress, then we return leaving the animation which [onClear] starts to show or hide the
     * tool bar.
     *
     * R.id.toggle_inv: we initialize our variable *selected* to the inverse of the selection state
     * of [mInverseToggle], set the selected state of [mInverseToggle] to *selected*, and call our
     * method [onInverseToggled] with that value to invert the state of the invertible keys. Then if
     * [mCurrentState] is RESULT, we call the *redisplay* method of [mResultText] just in case the
     * reevaluation was canceled by our above call to [cancelUnrequested].
     *
     * R.id.toggle_mode: we call our method [cancelIfEvaluating] with the *quiet* flag *false* to
     * cancel any evaluation in progress without suppressing a possible error pop-up message. We
     * then initialize our variable *mode* to the inverse of the current degree mode of the main
     * expression (true is degrees, false is radians). Then if [mCurrentState] is RESULT and there
     * are trig functions in the main expression we call the *collapse* method of [mEvaluator] to
     * "collapse" the main expression so that it will forever be bound to the old degree mode (unless
     * we are in INPUT mode), and then call our method [redisplayFormula] to redisplay the formula.
     * We now call the *setDegreeMode* method of [mEvaluator] to have it change to the new *mode*,
     * and call our method [onModeChanged] to have it change the UI to reflect the new *mode*. We
     * call our method [showAndMaybeHideToolbar] to have it show the tool bar (and not auto-hide it
     * again if there are trig functions being evaluated), we call our method [setState] to set our
     * [CalculatorState] to INPUT, and call the *clear* method of [mResultText] to clear it. Then if
     * our [haveUnprocessed] method reports there are no unprocessed characters we call our method
     * [evaluateInstantIfNecessary] to start the evaluation of the current expression if [mCurrentState]
     * is INPUT and the current expression has "interesting" operations in it (is worth evaluating).
     * Finally we return to the caller.
     *
     * For all other keys we call our [cancelIfEvaluating] method with the *quiet* flag *false* (not
     * suppressing any error pop-up), and if our [haveUnprocessed] method reports we have unprocessed
     * characters we call our [addChars] method to add the character represented by the key with id
     * *id* to the end of our current expression as an uninterpreted character, otherwise we call our
     * method [addExplicitKeyToExpr] to add the key's character, deleting trailing additive operators
     * from the expression if the key id is R.id.op_sub, and call our method [redisplayAfterFormulaChange]
     * to redisplay our changed formula.
     *
     * For all keys which fall through the *when* block without returning we call our method
     * [showOrHideToolbar] to show our tool bar, and auto-hide it if that is appropriate to do so.
     *
     * @param view [View] that was clicked
     */
    @Suppress("unused") // This is actually used by values/styles.xml as "android:onClick"
    fun onButtonClick(view: View) {
        // Any animation is ended before we get here.
        mCurrentButton = view
        stopActionModeOrContextMenu()

        // See onKey above for the rationale behind some of the behavior below:
        cancelUnrequested()

        when (val id = view.id) {
            R.id.eq -> onEquals()
            R.id.del -> onDelete()
            R.id.clr -> {
                onClear()
                return   // Toolbar visibility adjusted at end of animation.
            }
            R.id.toggle_inv -> {
                val selected = !mInverseToggle.isSelected
                mInverseToggle.isSelected = selected
                onInverseToggled(selected)
                if (mCurrentState == CalculatorState.RESULT) {
                    mResultText.redisplay()   // In case we cancelled reevaluation.
                }
            }
            R.id.toggle_mode -> {
                cancelIfEvaluating(false)
                val mode = !mEvaluator.degreeModeGet(Evaluator.MAIN_INDEX)
                if (mCurrentState == CalculatorState.RESULT
                        && mEvaluator.exprGet(Evaluator.MAIN_INDEX).hasTrigFuncs()) {
                    // Capture current result evaluated in old mode.
                    mEvaluator.collapse(mEvaluator.maxIndexGet())
                    redisplayFormula()
                }
                // In input mode, we reinterpret already entered trig functions.
                mEvaluator.setDegreeMode(mode)
                onModeChanged(mode)
                // Show the toolbar to highlight the mode change.
                showAndMaybeHideToolbar()
                setState(CalculatorState.INPUT)
                mResultText.clear()
                if (!haveUnprocessed()) {
                    evaluateInstantIfNecessary()
                }
                return
            }
            else -> {
                cancelIfEvaluating(false)
                if (haveUnprocessed()) {
                    // For consistency, append as uninterpreted characters.
                    // This may actually be useful for a left parenthesis.
                    addChars(KeyMaps.toString(this, id), true)
                } else {
                    addExplicitKeyToExpr(id)
                    redisplayAfterFormulaChange()
                }
            }
        }
        showOrHideToolbar()
    }

    /**
     * Called to redisplay the current main expression in [mFormulaText]. We initialize our variable
     * *formula* with a *SpannableStringBuilder* created from the main expression of [mEvaluator].
     * If [mUnprocessedChars] is not *null* we append them to *formula* using [mUnprocessedColorSpan]
     * as their color and using the SPAN_EXCLUSIVE_EXCLUSIVE so that only these characters will be
     * colored with that color. We then call the *changeTextTo* method of [mFormulaText] to have it
     * change the text it is displaying to *formula* announcing the new text for accessibility. Then
     * we set the content description of [mFormulaText] to the string "No formula" if *formula* is
     * empty or to null if it is not empty.
     */
    fun redisplayFormula() {
        val formula = mEvaluator.exprGet(Evaluator.MAIN_INDEX).toSpannableStringBuilder(this)
        if (mUnprocessedChars != null) {
            // Add and highlight characters we couldn't process.
            formula.append(mUnprocessedChars, mUnprocessedColorSpan,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        mFormulaText.changeTextTo(formula)
        mFormulaText.contentDescription = if (TextUtils.isEmpty(formula))
            getString(R.string.desc_formula)
        else
            null
    }

    /**
     * This is specified as the [OnLongClickListener] for the button with id R.id.del (delete button).
     * We set [mCurrentButton] to [view], then if the resource id of [view] is R.id.del we call our
     * [onClear] method to clear the display and return *true* to the caller, otherwise we return
     * *false*.
     *
     * @param view [View] that was long clicked.
     * @return true if the callback consumed the long click, false otherwise.
     */
    override fun onLongClick(view: View): Boolean {
        mCurrentButton = view

        if (view.id == R.id.del) {
            onClear()
            return true
        }
        return false
    }

    /**
     * Initial evaluation completed successfully. Initiate display. If [index] is not MAIN_INDEX we
     * throw an AssertionError. Otherwise we call [invalidateOptionsMenu] to signal that the options
     * menu has changed and should be recreated. We then call the *onEvaluate* method of [mResultText]
     * with our parameters which initiates the display of the new result. If [mCurrentState] is not
     * INPUT we call our method [onResult] with the *animate* flag *true* if [mCurrentState] is
     * EVALUATE (in which case the result will be animated into place), and the *resultWasPreserved*
     * flag true if [mCurrentState] is INIT_FOR_RESULT or RESULT (if true [onResult] will call the
     * *represerve* method of [mEvaluator] which will preserve the main expression as the most recent
     * cached history entry without rewriting it to the database, if *false* it calls the *preserve*
     * method of [mEvaluator] to add the current result to both the cache and database).
     *
     * @param index Index of the expression which has been evaluated (always MAIN_INDEX)
     * @param initPrecOffset Initial display precision computed by evaluator. (1 = tenths digit)
     * @param msdIndex Position of most significant digit. Offset from left of string.
     * @param lsdOffset Position of least significant digit (1 = tenths digit) or Integer.MAX_VALUE.
     * @param truncatedWholePart the integer part of the result
     */
    override fun onEvaluate(index: Long, initPrecOffset: Int, msdIndex: Int, lsdOffset: Int,
                            truncatedWholePart: String) {
        if (index != Evaluator.MAIN_INDEX) {
            throw AssertionError("Unexpected evaluation result index\n")
        }

        // Invalidate any options that may depend on the current result.
        invalidateOptionsMenu()

        mResultText.onEvaluate(index, initPrecOffset, msdIndex, lsdOffset, truncatedWholePart)
        if (mCurrentState != CalculatorState.INPUT) {
            // In EVALUATE, INIT, RESULT, or INIT_FOR_RESULT state.
            onResult(mCurrentState == CalculatorState.EVALUATE /* animate */,
                    mCurrentState == CalculatorState.INIT_FOR_RESULT
                            || mCurrentState == CalculatorState.RESULT /* previously preserved */)
        }
    }

    /**
     * Reset state to reflect evaluator cancellation. Invoked by evaluator. We set our [CalculatorState]
     * to INPUT, then call the *onCancelled* method of [mResultText] to have it clear its text, and
     * set its state to one reflecting its "emptiness".
     *
     * @param index index of the expression being canceled (always MAIN_INDEX)
     */
    override fun onCancelled(index: Long) {
        // Index is Evaluator.MAIN_INDEX. We should be in EVALUATE state.
        setState(CalculatorState.INPUT)
        mResultText.onCancelled(index)
    }

    /**
     * Reevaluation completed; ask result to redisplay current value. We just call the *onReevaluate*
     * method of [mResultText] to have it do its stuff.
     *
     * @param index Index of the expression that was reevaluated (always MAIN_INDEX).
     */
    override fun onReevaluate(index: Long) {
        // Index is Evaluator.MAIN_INDEX.
        mResultText.onReevaluate(index)
    }

    /**
     * Called because we are a [OnTextSizeChangeListener] for [mFormulaText] and the text size of
     * that [TextView] has changed. If [mCurrentState] is not INPUT the change did not occur because
     * of user input so we do not want to animate the change in text size so we just return. Otherwise
     * we calculate the values needed to perform the scale and translation animations between the
     * [oldSize] text size and the next *textSize* text size property of [textView]:
     *
     * *textScale*: the ratio of [oldSize] to the new *textSize* property of [textView]
     *
     * *translationX*: the X translation caused by the change in size.
     *
     * *translationY*: the Y translation caused by the change in size.
     *
     * We initialize our variable *animatorSet* with a new instance of [AnimatorSet], set it to
     * play together [ObjectAnimator]'s which scale [textView] from *textScale* to 1.0 in both X
     * and Y directions, and translate it from *translationX* to 0.0 and *translationY* to 0.0 in X
     * and Y coordinates. We set the duration of *animatorSet* to the android system constant
     * config_mediumAnimTime (400ms currently), and set its [TimeInterpolator] to an instance of
     * [AccelerateDecelerateInterpolator]. Finally we start *animatorSet* running.
     *
     * @param textView [TextView] whose text size has changed.
     * @param oldSize old text size.
     */
    override fun onTextSizeChanged(textView: TextView, oldSize: Float) {
        if (mCurrentState != CalculatorState.INPUT) {
            // Only animate text changes that occur from user input.
            return
        }

        // Calculate the values needed to perform the scale and translation animations,
        // maintaining the same apparent baseline for the displayed text.
        val textScale = oldSize / textView.textSize
        val translationX = (1.0f - textScale) * (textView.width / 2.0f - textView.paddingEnd)
        val translationY = (1.0f - textScale) * (textView.height / 2.0f - textView.paddingBottom)

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(
                ObjectAnimator.ofFloat(textView, View.SCALE_X, textScale, 1.0f),
                ObjectAnimator.ofFloat(textView, View.SCALE_Y, textScale, 1.0f),
                ObjectAnimator.ofFloat(textView, View.TRANSLATION_X, translationX, 0.0f),
                ObjectAnimator.ofFloat(textView, View.TRANSLATION_Y, translationY, 0.0f))
        animatorSet.duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
        animatorSet.interpolator = AccelerateDecelerateInterpolator()
        animatorSet.start()
    }

    /**
     * Cancel any in-progress explicitly requested evaluations. If [mCurrentState] is EVALUATE we
     * call the *cancel* method of [mEvaluator] to have it cancel the expression of the MAIN_INDEX
     * formula passing it our parameter [quiet] to have it suppress (or not) the error pop-up, and
     * we return *true* to the caller. Otherwise we return *false*.
     *
     * @param quiet suppress pop-up message. Explicit evaluation can change the expression
     * value, and certainly changes the display, so it seems reasonable to warn.
     * @return      true if there was such an evaluation
     */
    private fun cancelIfEvaluating(quiet: Boolean): Boolean {
        return if (mCurrentState == CalculatorState.EVALUATE) {
            mEvaluator.cancel(Evaluator.MAIN_INDEX, quiet)
            true
        } else {
            false
        }
    }

    /**
     * Cancels unrequested evaluation of the MAIN_INDEX formula of [mEvaluator] suppressing the error
     * pop-up.
     */
    private fun cancelUnrequested() {
        if (mCurrentState == CalculatorState.INPUT) {
            mEvaluator.cancel(Evaluator.MAIN_INDEX, true)
        }
    }

    /**
     * If [mUnprocessedChars] is not *null* and contains characters, we return *true*, otherwise we
     * return *false*.
     *
     * @return true if there are unprocessed characters in [mUnprocessedChars]
     */
    private fun haveUnprocessed(): Boolean {
        return mUnprocessedChars != null && mUnprocessedChars!!.isNotEmpty()
    }

    /**
     * Called when the "equals" button (or key) is pressed. We iqnore it if [mCurrentState] is not
     * INPUT. If it is INPUT we check to see if our [haveUnprocessed] method reports there are
     * unprocessed characters first and if so we set our state to EVALUATE and call our method
     * [onError] to have it report a "Bad expression" to the user. If there are no unprocessed
     * characters we set the state to EVALUATE and call the *requireResult* method of [mEvaluator]
     * to have it start the required evaluation of the MAIN_INDEX expression, passing it *this* as
     * the *EvaluationListener* (our overrides of the various methods of the interface will be
     * called when appropriate to do so) and [mResultText] as the *CharMetricsInfo* to use (its
     * override of the various methods of the interface will be called when information about the
     * text it can display is needed).
     */
    private fun onEquals() {
        // Ignore if in non-INPUT state, or if there are no operators.
        if (mCurrentState == CalculatorState.INPUT) {
            if (haveUnprocessed()) {
                setState(CalculatorState.EVALUATE)
                onError(Evaluator.MAIN_INDEX, R.string.error_syntax)
            } else if (mEvaluator.exprGet(Evaluator.MAIN_INDEX).hasInterestingOps()) {
                setState(CalculatorState.EVALUATE)
                mEvaluator.requireResult(Evaluator.MAIN_INDEX, this, mResultText)
            }
        }
    }

    /**
     * Called when the users has touched the delete button or key. First we call our method
     * [cancelIfEvaluating] to cancel any in-progress explicit evaluation with the *quiet* flag
     * *false* to so as to not suppress any error pop-up, and if it reports that there was an
     * evaluation in progress we just return. Otherwise we set our [CalculatorState] to INPUT,
     * then if [haveUnprocessed] reports that there are unprocessed characters in [mUnprocessedChars]
     * we remove the last character from [mUnprocessedChars], otherwise we call the *delete* method
     * of [mEvaluator] which deletes the last token from the main expression. If the MAIN_INDEX
     * expression of [mEvaluator] is now empty and there are no unprocessed characters we call our
     * [announceClearedForAccessibility] method to have accessibility announce that the formula has
     * been cleared. Finally we call our [redisplayAfterFormulaChange] method to have it redisplay
     * the formula.
     */
    private fun onDelete() {
        // Delete works like backspace; remove the last character or operator from the expression.
        // Note that we handle keyboard delete exactly like the delete button. For example the
        // delete button can be used to delete a character from an incomplete function name typed
        // on a physical keyboard. This should be impossible in RESULT state.
        // If there is an in-progress explicit evaluation, just cancel it and return.
        if (cancelIfEvaluating(false)) return
        setState(CalculatorState.INPUT)
        if (haveUnprocessed()) {
            mUnprocessedChars = mUnprocessedChars?.substring(0, mUnprocessedChars!!.length - 1)
        } else {
            mEvaluator.delete()
        }
        if (mEvaluator.exprGet(Evaluator.MAIN_INDEX).isEmpty && !haveUnprocessed()) {
            // Resulting formula won't be announced, since it's empty.
            announceClearedForAccessibility()
        }
        redisplayAfterFormulaChange()
    }

    /**
     * Produces an animation when the display is cleared or an error is detected which looks like it
     * starts from the center of the [sourceView] button which caused the event and sweeps across the
     * result/formula display.
     *
     * We initialize our variable *groupOverlay* by retrieving the top-level window decor view from
     * the current Window of the activity and fetching the overlay for this view, creating it if it
     * does not yet exist (a [ViewGroupOverlay] is an extra layer that sits on top of a [ViewGroup]
     * (the "host view") which is drawn after all other content in that view (including the view
     * group's children). Interaction with the overlay layer is done by adding and removing views
     * and drawables). We initialize our variable *displayRect* with a new instance of [Rect] and
     * use the *getGlobalVisibleRect* method of [mDisplayView] to set *displayRect* to encompass the
     * region that it occupies on the screen. We initialize our variable *revealView* with a new
     * instance of [View], set its *bottom* to the *bottom* of *displayRect*, its *left* to the
     * *left* of *displayRect*, and its *right* to the *right* of *displayRect* (its *top* defaults
     * to 0). We then set the background color of *revealView* to the color with resource id
     * [colorRes] and add it to *groupOverlay*. We initialize our variable *clearLocation* with a
     * new instance of [IntArray](2), use the *getLocationInWindow* method of [sourceView] to fill
     * it with its (x,y) coordinates then add half the width of [sourceView] to its x coordinate
     * and half the height to its y coordinate (*clearLocation* now contains the coordinates of the
     * center of [sourceView]). We initialize our variable *revealCenterX* with the difference between
     * the X coordinate in *clearLocation* and the *left* side of *revealView*, and our variable
     * *revealCenterY* with the difference between the Y coordinate in *clearLocation* and the *top*
     * side of *revealView*. We then calculate our variable *revealRadius* to be the maximum of the
     * distance from the center of the button that was clicked and the top corners of *revealView*.
     * We initialize our variable *revealAnimator* with an [Animator] which can animate a clipping
     * circle for *revealView* centered at *revealCenterX* and *revealCenterY* with a starting radius
     * of 0.0 and an end radius of *revealRadius*, we set the duration to the system constant
     * config_mediumAnimTime (500ms), and add [listener] as an [AnimatorListener] to it. We initialize
     * our variable *alphaAnimator* with an [ObjectAnimator] which will animate the ALPHA property of
     * *revealView* to 0.0 and set its duration to config_mediumAnimTime also. We initialize our
     * variable *animatorSet* with a new instance of [AnimatorSet], configure it to play *revealAnimator*
     * before *alphaAnimator*, set its [TimeInterpolator] to an [AccelerateDecelerateInterpolator],
     * and add an anonymous [AnimatorListenerAdapter] whose *onAnimationEnd* override will remove
     * *revealView* from *groupOverlay* and set [mCurrentAnimator] to null. Having fully configured
     * *animatorSet* we set [mCurrentAnimator] to it and start it running.
     *
     * @param sourceView button which triggered the animation we are producing, its center will be
     * used as the center of the animation.
     * @param colorRes resource id of the color we are to use.
     * @param listener [AnimatorListener] whose *onAnimationEnd* override will be called at the end
     * of the animation in order to perform whatever cleanup is appropriate.
     */
    private fun reveal(sourceView: View, colorRes: Int, listener: AnimatorListener) {
        val groupOverlay = window.decorView.overlay as ViewGroupOverlay

        val displayRect = Rect()
        mDisplayView.getGlobalVisibleRect(displayRect)

        // Make reveal cover the display and status bar.
        val revealView = View(this)
        revealView.bottom = displayRect.bottom
        revealView.left = displayRect.left
        revealView.right = displayRect.right
        revealView.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        groupOverlay.add(revealView)

        val clearLocation = IntArray(2)
        sourceView.getLocationInWindow(clearLocation)
        clearLocation[0] += sourceView.width / 2
        clearLocation[1] += sourceView.height / 2

        val revealCenterX = clearLocation[0] - revealView.left
        val revealCenterY = clearLocation[1] - revealView.top

        val x1_2 = (revealView.left - revealCenterX).toDouble().pow(2.0)
        val x2_2 = (revealView.right - revealCenterX).toDouble().pow(2.0)
        val y_2 = (revealView.top - revealCenterY).toDouble().pow(2.0)
        val revealRadius = max(sqrt(x1_2 + y_2), sqrt(x2_2 + y_2)).toFloat()

        val revealAnimator = ViewAnimationUtils.createCircularReveal(revealView,
                revealCenterX, revealCenterY, 0.0f, revealRadius)
        revealAnimator.duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong()
        revealAnimator.addListener(listener)

        val alphaAnimator = ObjectAnimator.ofFloat(revealView, View.ALPHA, 0.0f)
        alphaAnimator.duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()

        val animatorSet = AnimatorSet()
        animatorSet.play(revealAnimator).before(alphaAnimator)
        animatorSet.interpolator = AccelerateDecelerateInterpolator()
        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) {
                groupOverlay.remove(revealView)
                mCurrentAnimator = null
            }
        })

        mCurrentAnimator = animatorSet
        animatorSet.start()
    }

    /**
     * Called to have [mResultText] announce "cleared" to the accessibility system service.
     */
    private fun announceClearedForAccessibility() {
        mResultText.announceForAccessibility(resources.getString(R.string.cleared))
    }

    /**
     * Called from the *onAnimationEnd* override of the [AnimatorListenerAdapter] which is added
     * to the animation which our [onClear] method has our [reveal] method run, it finishes the
     * steps needed to clear our display, and called from our [onClick] method which the
     * [HistoryFragment] calls when its clear history button is clicked it does likewise.
     *
     * We set [mUnprocessedChars] to null, call the *clear* method of [mResultText] have it clear its
     * text and update its state variables, call the *clearMain* method of [mEvaluator] to have it
     * clear its main expression, and set our [CalculatorState] to INPUT. Finally we call our
     * [redisplayFormula] to have it redisplay the (now empty) formula.
     */
    fun onClearAnimationEnd() {
        mUnprocessedChars = null
        mResultText.clear()
        mEvaluator.clearMain()
        setState(CalculatorState.INPUT)
        redisplayFormula()
    }

    /**
     * Called when the button with id R.id.clr (clear) is clicked, when the KEYCODE_CLEAR (clear)
     * key is clicked, or when the button with id R.id.del (delete) is long clicked it clears the
     * formula and result with a turquoise circular "reveal" animation which sweeps across the
     * formula/result display with the center of the button responsible for the clearing as its
     * starting point. The *onClearAnimationEnd* override of the [AnimatorListenerAdapter] it passes
     * to the [reveal] method calls our method [onClearAnimationEnd] then finishes up the steps
     * needed to actually clear the display and program state variables to a "clear" state.
     *
     * If the MAIN_INDEX expression of [mEvaluator] is empty and we have no unprocessed characters
     * in [mUnprocessedChars] we just return having done nothing. We call our [cancelIfEvaluating]
     * method to have it "quietly" cancel any explicit expression evaluation which might be in
     * progress, and call our [announceClearedForAccessibility] to have it announce "cleared" to the
     * accessibility system service. Finally we call our [reveal] method to have it perform a
     * circular reveal animation across our formula/result display with its center at the center of
     * [mCurrentButton], its color the color with resource id R.color.calculator_primary_color
     * (a turquoise shade of blue) with an anonymous [AnimatorListenerAdapter] whose *onAnimationEnd*
     * override calls our methods [onClearAnimationEnd] (finishes clearing the calculator state) and
     * [showOrHideToolbar] (shows the tool bar, then auto-hides it after a delay) after the animation
     * is completed.
     */
    private fun onClear() {
        if (mEvaluator.exprGet(Evaluator.MAIN_INDEX).isEmpty && !haveUnprocessed()) {
            return
        }
        cancelIfEvaluating(true)
        announceClearedForAccessibility()
        reveal(mCurrentButton, R.color.calculator_primary_color, object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                onClearAnimationEnd()
                showOrHideToolbar()
            }
        })
    }

    /**
     * Evaluation encountered en error. Display the error. If our parameter [index] is not MAIN_INDEX
     * we throw an AssertionError. Otherwise, when our [CalculatorState] is EVALUATE, we set our
     * [CalculatorState] to ANIMATE, call the *announceForAccessibility* method of [mResultText] to
     * have it use ask accessibility service to announce the string with resource id [errorId],
     * then call our [reveal] method to have it perform its circular reveal animation of the display
     * centered about [mCurrentButton] using the calculator_error_color (a red) with an anonymous
     * [AnimatorListenerAdapter] whose *onAnimationEnd* override sets our [CalculatorState] to
     * ERROR, and calls the *onError* method of [mResultText] to have it display the string with
     * resource id [errorId] as its message. When our [CalculatorState] is INIT or
     * INIT_FOR_RESULT (very unlikely) we set our [CalculatorState] to ERROR, and call the *onError*
     * method of [mResultText] to have it display the string with resource id [errorId] as
     * its message. For all other [CalculatorState] we just call the *clear* method of [mResultText]
     * to have it clear its text.
     *
     * @param index index of the expression causing the error, should always be MAIN_INDEX
     * @param errorId resource id of the string describing the type of error that occurred
     */
    override fun onError(index: Long, errorId: Int) {
        if (index != Evaluator.MAIN_INDEX) {
            throw AssertionError("Unexpected error source")
        }
        when (mCurrentState) {
            CalculatorState.EVALUATE -> {
                setState(CalculatorState.ANIMATE)
                mResultText.announceForAccessibility(resources.getString(errorId))
                reveal(mCurrentButton, R.color.calculator_error_color,
                        object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                setState(CalculatorState.ERROR)
                                mResultText.onError(index, errorId)
                            }
                        })
            }
            CalculatorState.INIT, CalculatorState.INIT_FOR_RESULT -> {  /* very unlikely */
                setState(CalculatorState.ERROR)
                mResultText.onError(index, errorId)
            }
            else -> mResultText.clear()
        }
    }

    /**
     * Animate movement of result into the top formula slot. Result window now remains translated in
     * the top slot while the result is displayed. (We convert it back to formula use only when the
     * user provides new input.) Historical note: In the Lollipop version, this invisibly and
     * instantaneously moved formula and result displays back at the end of the animation. We no
     * longer do that so that we can continue to properly support scrolling of the result. We assume
     * the result already contains the text to be expanded.
     *
     * We initialize our variable *textSize* to the minimum text size of [mFormulaText], and if the
     * *isScrollable* method of [mResultText] reports that is is scrollable we set *textSize* to
     * the text size computed by the *getVariableTextSize* method of [mFormulaText] for the string
     * value of the *text* of [mResultText]. We calculate our variable *resultScale* to be the ratio
     * of *textSize* to the *textSize* of [mResultText] (this is the value that will be used to scale
     * [mResultText] when it moves into the position formerly occupied by [mFormulaText]). We set
     * the *pivotX* of [mResultText] to its width minus its right padding, and its *pivotY* to its
     * height minus its bottom padding (these are the x and y locations of the point around which
     * the view is rotated and scaled). We then calculate the necessary translations so that the
     * result takes the place of the formula and the formula moves off the top of the screen: our
     * variables *resultTranslationY*, and *formulaTranslationY* (both simple calculations using the
     * current *bottom* Y coordinates of [mFormulaContainer] and [mResultText]). If our display if
     * one line ([isOneLine] is *true*) we need to position the top corner of [mResultText] to its
     * current bottom (it starts out invisible in one line mode, and in the same position as the
     * [mFormulaContainer] so we move it down so that it has some distance to move) and recalculate
     * *formulaTranslationY* to also subtract off the bottom of the tool bar view (id R.id.toolbar).
     * We initialize our variable *formulaTextColor* to the current text color of [mFormulaText].
     * If our parameter [resultWasPreserved] is *true* the result was previously added to the history
     * database so we just call the *represerve* method of [mEvaluator] to have it make sure that the
     * in memory cache is up to date, if it has not yet been added ([resultWasPreserved] is *false*)
     * we call the *preserve* method of [mEvaluator] to have it add the current result to the history
     * database. Next we branch on the value of our parameter [animate]:
     *
     * *true*: We need to animate the movements. First we call the *announceForAccessibility* method
     * of [mResultText] to have it use the accessibility to announce the string "equals", then call
     * it to have it announce the text of [mResultText]. We set our [CalculatorState] to ANIMATE. We
     * initialize our variable *animatorSet* with a new instance of [AnimatorSet] and configure it to
     * play together [ObjectAnimator]s which scale [mResultText] by *resultScale* in both X and Y
     * directions, translate it in the Y direction by *resultTranslationY*, animate its *textColor*
     * to *formulaTextColor*, and translate [mFormulaContainer] by *formulaTranslationY* in the Y
     * direction. We set its duration to the system resource constant config_longAnimTime (500ms) and
     * add an anonymous [AnimatorListenerAdapter] whose *onAnimationEnd* override sets our state to
     * RESULT and sets [mCurrentAnimator] to null. We then set [mCurrentAnimator] to *animatorSet*
     * and start it running.
     *
     * *false*: No animation desired. We just set the *scaleX* and *scaleY* of [mResultText] to
     * *resultScale*, set its translation in the Y direction to *resultTranslationY* set its text
     * color to *formulaTextColor*, and set the translation in the Y direction of [mFormulaContainer]
     * to *formulaTranslationY*. Finally we set our [CalculatorState] to RESULT.
     *
     * @param animate if true we are to animate the movement of the result, if false move it fast
     * @param resultWasPreserved if true the result was previously preserved to the database, if
     * false we need to have [mEvaluator] preserve it to the database.
     */
    private fun onResult(animate: Boolean, resultWasPreserved: Boolean) {
        // Calculate the textSize that would be used to display the result in the formula.
        // For scrollable results just use the minimum textSize to maximize the number of digits
        // that are visible on screen.
        var textSize = mFormulaText.minimumTextSize
        if (!mResultText.isScrollable) {
            textSize = mFormulaText.getVariableTextSize(mResultText.text.toString())
        }

        // Scale the result to match the calculated textSize, minimizing the jump-cut transition
        // when a result is reused in a subsequent expression.
        val resultScale = textSize / mResultText.textSize

        // Set the result's pivot to match its gravity.
        mResultText.pivotX = (mResultText.width - mResultText.paddingRight).toFloat()
        mResultText.pivotY = (mResultText.height - mResultText.paddingBottom).toFloat()

        // Calculate the necessary translations so the result takes the place of the formula and
        // the formula moves off the top of the screen.
        val resultTranslationY = (mFormulaContainer.bottom - mResultText.bottom
                - (mFormulaText.paddingBottom - mResultText.paddingBottom)).toFloat()
        var formulaTranslationY = (-mFormulaContainer.bottom).toFloat()
        if (isOneLine) {
            // Position the result text.
            mResultText.y = mResultText.bottom.toFloat()
            val tBarBot = findViewById<View>(R.id.toolbar).bottom
            formulaTranslationY = (-(tBarBot + mFormulaContainer.bottom)).toFloat()
        }

        // Change the result's textColor to match the formula.
        val formulaTextColor = mFormulaText.currentTextColor

        if (resultWasPreserved) {
            // Result was previously added to history.
            mEvaluator.represerve()
        } else {
            // Add current result to history.
            mEvaluator.preserve(Evaluator.MAIN_INDEX, true)
        }

        if (animate) {
            mResultText.announceForAccessibility(resources.getString(R.string.desc_eq))
            mResultText.announceForAccessibility(mResultText.text)
            setState(CalculatorState.ANIMATE)
            val animatorSet = AnimatorSet()
            animatorSet.playTogether(
                    ObjectAnimator.ofPropertyValuesHolder(mResultText,
                            PropertyValuesHolder.ofFloat(View.SCALE_X, resultScale),
                            PropertyValuesHolder.ofFloat(View.SCALE_Y, resultScale),
                            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, resultTranslationY)),
                    ObjectAnimator.ofArgb(mResultText, textColor, formulaTextColor),
                    ObjectAnimator.ofFloat(mFormulaContainer, View.TRANSLATION_Y, formulaTranslationY))
            animatorSet.duration = resources
                    .getInteger(android.R.integer.config_longAnimTime)
                    .toLong()
            animatorSet.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setState(CalculatorState.RESULT)
                    mCurrentAnimator = null
                }
            })

            mCurrentAnimator = animatorSet
            animatorSet.start()
        } else
        /* No animation desired; get there fast when restarting */ {
            mResultText.scaleX = resultScale
            mResultText.scaleY = resultScale
            mResultText.translationY = resultTranslationY
            mResultText.setTextColor(formulaTextColor)
            mFormulaContainer.translationY = formulaTranslationY
            setState(CalculatorState.RESULT)
        }
    }

    /**
     * Restore positions of the formula and result displays back to their original, pre-animation
     * state. First we set the text of [mResultText] to the empty string. Then we set its scaling
     * in X and Y direction to 1.0, and its translation in the X and Y direction to 0.0. We set the
     * translation in the Y direction of [mFormulaContainer] to 0.0, and then call the *requestFocus*
     * method of [mFormulaText] to have it acquire keyboard focus.
     */
    private fun restoreDisplayPositions() {
        // Clear result.
        mResultText.text = ""
        // Reset all of the values modified during the animation.
        mResultText.scaleX = 1.0f
        mResultText.scaleY = 1.0f
        mResultText.translationX = 0.0f
        mResultText.translationY = 0.0f
        mFormulaContainer.translationY = 0.0f

        mFormulaText.requestFocus()
    }

    /**
     * Called when an [AlertDialogFragment] button is clicked. If the button id [which] is not the
     * BUTTON_POSITIVE button we ignore it, otherwise we branch on the *tag* of the [fragment]:
     *
     * CLEAR_DIALOG_TAG: The user wants to clear the history. We call the *clearEverything* method
     * of [mEvaluator] to erase the database and reinitialize its memory state to an empty one. We
     * call our [onClearAnimationEnd] method to clean up our own state to a starting state. Then we
     * call the *onMemoryStateChanged* method of [mEvaluatorCallback] to notify [mFormulaText] that
     * the memory state has changed. Finally we call our [onBackPressed] method to have it remove the
     * the [HistoryFragment].
     *
     * TIMEOUT_DIALOG_TAG: The [Evaluator] has timed out and asked the user if he wanted to use a
     * longer timeout, and the user clicked the "Use longer timeouts" button of the dialog. We call
     * the *setLongTimeout* method of [mEvaluator] to have use the long timeout.
     *
     * For all other *tag* values we just log the string "Unknown AlertDialogFragment click:".
     *
     * @param fragment the [AlertDialogFragment] which has had a button clicked.
     * @param which the identifier of the button that was clicked.
     */
    override fun onClick(fragment: AlertDialogFragment, which: Int) {
        if (which == DialogInterface.BUTTON_POSITIVE) {
            when {
                HistoryFragment.CLEAR_DIALOG_TAG == fragment.tag -> {
                    // TODO: Try to preserve the current, saved, and memory expressions. How should we
                    // handle expressions to which they refer?
                    mEvaluator.clearEverything()
                    // TODO: It's not clear what we should really do here. This is an initial hack.
                    // May want to make onClearAnimationEnd() private if/when we fix this.
                    onClearAnimationEnd()
                    mEvaluatorCallback.onMemoryStateChanged()
                    onBackPressed()
                }
                Evaluator.TIMEOUT_DIALOG_TAG == fragment.tag -> // Timeout extension request.
                    mEvaluator.setLongTimeout()
                else -> Log.e(TAG, "Unknown AlertDialogFragment click:" + fragment.tag)
            }
        }
    }

    /**
     * Initialize the contents of the Activity's standard options [menu]. First we call our super's
     * implementation of [onCreateOptionsMenu] then we use a [MenuInflater] instance with our
     * activities context to inflate our menu layout file R.menu.activity_calculator into [menu].
     * Finally we return *true* so that the menu will be displayed.
     *
     * @param menu The options menu in which you place your items.
     * @return You must return true for the menu to be displayed
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)

        menuInflater.inflate(R.menu.activity_calculator, menu)
        return true
    }

    /**
     * Prepare the Screen's standard options menu to be displayed. First we call our super's
     * implementation of [onPrepareOptionsMenu]. We initialize our variable *visible* to *true* if
     * [mCurrentState] is RESULT, then find the item in [menu] with id R.id.menu_leading and set it
     * to *visible*. We initialize our variable *mainResult* with the [UnifiedReal] of the result of
     * the main expression of [mEvaluator], then set *visible* to *true* if it is already *true* and
     * *mainResult* is not null and *mainResult* is exactly displayable as a rational number (ie. it
     * is true if [mCurrentState] is RESULT, *mainResult* is not null and *mainResult* is exactly
     * displayable as a rational number). We then find the menu item with id R.id.menu_fraction and
     * set its visibility to *visible*. Finally we return true so that the menu will be displayed.
     *
     * @param menu The options menu as last shown or first initialized by [onCreateOptionsMenu]
     * @return You must return true for the menu to be displayed
     */
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)

        // Show the leading option when displaying a result.
        var visible = mCurrentState == CalculatorState.RESULT
        menu.findItem(R.id.menu_leading).isVisible = visible

        // Show the fraction option when displaying a rational result.
        val mainResult = mEvaluator.resultGet(Evaluator.MAIN_INDEX)
        // mainResult should never be null, but it happens. Check as a workaround to protect
        // against crashes until we find the root cause (b/34763650).
        visible = visible and (mainResult != null && mainResult.exactlyDisplayable())
        menu.findItem(R.id.menu_fraction).isVisible = visible

        return true
    }

    /**
     * This hook is called whenever an item in your options menu is selected. When the item id
     * of [item] is:
     *
     * R.id.menu_history: we call our [showHistoryFragment] method to show the [HistoryFragment],
     * and return true to consume the event here.
     *
     * R.id.menu_leading: we call our [displayFull] method to have it show an [AlertDialogFragment]
     * displaying the entire result up to current displayed precision of [mResultText] using digit
     * separators, and return true to consume the event here.
     *
     * R.id.menu_fraction: we call our [displayFraction] method to have it show an
     * [AlertDialogFragment] displaying the result as a fraction (this item is only visible if it
     * is possible to do so), and return true to consume the event here.
     *
     * R.id.menu_licenses: we start the activity with the class [Licenses], and return true to
     * consume the event here.
     *
     * All other item id's we just return the value returned by our super's implementation of
     * [onOptionsItemSelected].
     *
     * @param item The menu item that was selected.
     * @return Return false to allow normal menu processing to proceed, true to consume it here.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_history -> {
                showHistoryFragment()
                return true
            }
            R.id.menu_leading -> {
                displayFull()
                return true
            }
            R.id.menu_fraction -> {
                displayFraction()
                return true
            }
            R.id.menu_licenses -> {
                startActivity(Intent(this, Licenses::class.java))
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    /*
     * Begin override CloseCallback method. We set *this* to be the *CloseCallback*
     * of *mDragLayout* in *onCreate*.
     */

    /**
     * Callback when the layout is closed, used to pop the [HistoryFragment] off the backstack. We
     * just call our method [removeHistoryFragment] to have the [FragmentManager] remove the
     * [HistoryFragment].
     */
    override fun onClose() {
        removeHistoryFragment()
    }

    /* End override CloseCallback method. */

    /*
     * Begin override DragCallback methods. We add *this* to be a *DragCallback*
     * of *mDragLayout* in *onCreate*. *HistoryFragment* does the same thing.
     */

    /**
     * Callback when a drag to open begins. We call the *hideToolbar* method of [mDisplayView] to
     * have it hide the tool bar, then call our method [showHistoryFragment] to add a new instance
     * of [HistoryFragment] to the R.id.history_frame container if it does not already exist.
     */
    override fun onStartDraggingOpen() {
        mDisplayView.hideToolbar()
        showHistoryFragment()
    }

    /**
     * Callback called from the [onRestoreInstanceState] override of [mDragLayout]. We ignore.
     *
     * @param isOpen true if the [DragLayout] was open
     */
    override fun onInstanceStateRestored(isOpen: Boolean) {}

    /**
     * Called to animate the *RecyclerView* text of [HistoryFragment]. We ignore.
     *
     * @param yFraction Fraction of the dragged [View] that is visible (0.0 - 1.0) 0.0 is closed.
     */
    override fun whileDragging(yFraction: Float) {}

    /**
     * Determines whether we should allow the [view] to be dragged. The [HistoryFragment] overrides
     * this too and returns *true* if its *RecylerView* is scrollable (both need to return *true* in
     * order for the [view] to be captured). We return *true* is the id of [view] is R.id.history_frame
     * (the *FrameLayout* that contains the [HistoryFragment]) and either the [mDragLayout] reports
     * that it is moving (being dragged) or [mDragLayout] is the [View] under ([x],[y]).
     *
     * @param view Child view of the [DragLayout] that the user is attempting to capture.
     * @param x X coordinate of the touch that is doing the dragging
     * @param y Y coordinate of the touch that is doing the dragging
     * @return *true* to have [DragLayout] capture the [view].
     */
    override fun shouldCaptureView(view: View, x: Int, y: Int): Boolean =
            (view.id == R.id.history_frame
                    && (mDragLayout.isMoving || mDragLayout.isViewUnder(view, x, y)))

    /**
     * Called from the *onLayout* override of [DragLayout] to get the height of our [mDisplayView],
     * we just return the raw measured height of [mDisplayView].
     *
     * @return the raw measured height of [mDisplayView].
     */
    override fun displayHeightFetch(): Int {
        return mDisplayView.measuredHeight
    }

    /* End override DragCallback methods */

    /**
     * Change evaluation state to one that's friendly to the history fragment. When [mCurrentState]
     * is ANIMATE we end [mCurrentAnimator] if it is not null and return false to signal that
     * preparation has failed. When [mCurrentState] is EVALUATE we call our method [cancelIfEvaluating]
     * to cancel the current evaluation suppressing any error pop-up, set our [CalculatorState] state
     * to INPUT and return *true*. When [mCurrentState] is INIT we return *false*, and for all other
     * states we just return *true*.
     *
     * @return false if it was not easily possible to change the evaluation state
     */
    private fun prepareForHistory(): Boolean {
        when (mCurrentState) {
            CalculatorState.ANIMATE -> {
                // End the current animation and signal that preparation has failed.
                // onUserInteraction is unreliable and onAnimationEnd() is asynchronous, so we
                // aren't guaranteed to be out of the ANIMATE state by the time prepareForHistory is
                // called.
                mCurrentAnimator?.end()
                return false
            }
            CalculatorState.EVALUATE -> {
                // Cancel current evaluation
                cancelIfEvaluating(true /* quiet */)
                setState(CalculatorState.INPUT)
                return true
            }
            else -> return mCurrentState != CalculatorState.INIT
            // We just return *true* in INPUT, INIT_FOR_RESULT, RESULT, or ERROR state.
            // For INIT we return *false* because it is easiest to just refuse. Otherwise we can
            // see a state change while in history mode, which causes all sorts of problems.
            // TODO: Consider other alternatives. If we're just doing the decimal conversion
            // TODO: at the end of an evaluation, we could treat this as RESULT state.
        }
    }

    /**
     * Adds a new instance of [HistoryFragment] to the R.id.history_frame container if it does not
     * already exist. If [historyFragment] is not null the fragment already exists, so we just return
     * having done nothing. Otherwise we initialize our variable *manager* with a handle to the
     * [FragmentManager] for interacting with fragments associated with this activity. If *manager*
     * has been destroyed or our method [prepareForHistory] signals that it can't easily change to a
     * state that is "friendly to the history fragment" we call the *setClosed* method of [mDragLayout]
     * to close the [DragLayout] and return. Otherwise we call our [stopActionModeOrContextMenu] method
     * to cancel any copy/paste context menu that might be open, then use *manager* to begin a
     * [FragmentTransaction] which we then use to add a new instance of [HistoryFragment] to the
     * container with resource id R.id.history_frame, with the tag HistoryFragment.TAG ("HistoryFragment"),
     * set its transition animation to TRANSIT_NONE, add it to the back stack with the state name
     * HistoryFragment.TAG, and then we commit the [FragmentTransaction]. We set [mMainCalculator]'s
     * flag *importantForAccessibility* to IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS (when the
     * [HistoryFragment] is visible, we want to hide all descendants of the main Calculator view),
     * and return to the caller.
     */
    private fun showHistoryFragment() {
        if (historyFragment != null) {
            // If the fragment already exists, do nothing.
            return
        }

        val manager = supportFragmentManager
        if (manager.isDestroyed || !prepareForHistory()) {
            // If the history fragment can not be shown, close the DragLayout.
            mDragLayout.setClosed()
            return
        }

        stopActionModeOrContextMenu()
        manager.beginTransaction()
                .replace(R.id.history_frame, HistoryFragment() as Fragment, HistoryFragment.TAG)
                .setTransition(FragmentTransaction.TRANSIT_NONE)
                .addToBackStack(HistoryFragment.TAG)
                .commit()

        // When HistoryFragment is visible, hide all descendants of the main Calculator view.
        mMainCalculator.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // TODO: pass current scroll position of result
    }

    /**
     * Called to pop up an [AlertDialogFragment] which just displays the given [title] and [message].
     *
     * @param title the title of the [AlertDialogFragment] we are to show
     * @param message the message of the [AlertDialogFragment] we are to show
     */
    private fun displayMessage(title: String, message: String) {
        AlertDialogFragment.showMessageDialog(this, title, message, null, null)
    }

    /**
     * This is called when the user clicks the option menu item with id R.id.menu_fraction ("Answer
     * as fraction"). It displays an [AlertDialogFragment] which shows the result as a fraction (the
     * menu item is only visibile in the menu if the current result is a rational number). We initialize
     * our variable *result* with the [UnifiedReal] value of the main expression of [mEvaluator] then
     * call our method [displayMessage] to display *result* as a fraction in an [AlertDialogFragment].
     */
    private fun displayFraction() {
        val result = mEvaluator.resultGet(Evaluator.MAIN_INDEX)
        displayMessage(getString(R.string.menu_fraction),
                KeyMaps.translateResult(result!!.toNiceString()))
    }

    /**
     * Display full result to currently evaluated precision. This is called when the user clicks the
     * option menu item with id R.id.menu_leading ("Answer with leading digits"). We initialize our
     * variable *res* to a *Resources* instance for the application's package, and initialize our
     * variable *msg* to the string containing the entire result (within reason) up to current
     * displayed precision that is returned by the *getFullText* method of [mResultText] (with comma
     * separators every 3 digits in the integral part if appropriate). The if the *fullTextIsExact*
     * method of [mResultText] is true (the result is exact) we append the string "(exact)" to *msg*,
     * otherwise we append the string "(±1 in last digit)". Then we call our [displayMessage] method
     * to display an [AlertDialogFragment] with the title "Answer with leading digits" and the text
     * *msg*.
     */
    private fun displayFull() {
        val res = resources
        var msg = mResultText.getFullText(true /* withSeparators */) + " "
        msg += if (mResultText.fullTextIsExact()) {
            res.getString(R.string.exact)
        } else {
            res.getString(R.string.approximate)
        }
        displayMessage(getString(R.string.menu_leading), msg)
    }

    /**
     * Add input characters to the end of the expression. Map them to the appropriate button pushes
     * when possible. Leftover characters are added to [mUnprocessedChars], which is presumed to
     * immediately precede the newly added characters. This function is called when a keyboard has
     * been used to enter text or when pasting a clip that another app has copied to the clipboard,
     * it is not used when a history entry is pasted.
     *
     * First we initialize our variable *myMoreChars* to [moreChars]. If [mUnprocessedChars] is not
     * null we prepend it so *myMoreChars*. We initialize our variable *current* to 0, initialize our
     * variable *len* to the length of *myMoreChars*, and initialize our variable *lastWasDigit* to
     * *false*. If [mCurrentState] is RESULT and *len* is not equal to 0 we call our method
     * *switchToInput* with the *current* (currently 0, aka the first) character of *myMoreChars*
     * translated by the *keyForChar* method of [KeyMaps] to a keypad resource id (*switchToInput*
     * will switch to INPUT state after having [mEvaluator] modify the main expression appropriately
     * given the button id passed it and the present contents of the main expression).
     *
     * We initialize our variable *groupingSeparator* with the 0'th (first) character returned by
     * the *translateResult* method of [KeyMaps] for the string "," (which is ',' aka the grouping
     * separator). Then while *current* is less than *len* we loop:
     *
     * We initialize our variable *c* with the character at index *current* in *myMoreChars*. If *c*
     * is a space character or is equal to *groupingSeparator* we just increment *current* and skip
     * the character by continuing the loop. Otherwise we initialize our variable *k* with the button
     * id of the keypad button that the *keyForChar* method of [KeyMaps] finds for *c* (it returns
     * View.NO_ID if there is no such button). If our parameter [explicit] is false (the text was
     * pasted, not typed) we initialize our variable *expEnd* with the index of the character after
     * the exponent in *myMoreChars* starting at *current*. If *lastWasDigit* is true and *current*
     * is not equal to *expEnd* we need to process scientific notation with 'E' when pasting so we
     * call the *addExponent* method of [mEvaluator] to have it add the characters *current* to
     * *expEnd* of *myMoreChars* as an exponend of the current expression, we then set *current* to
     * *expEnd*, set *lastWasDigit to *false* and continue the while loop. Otherwise we initialize
     * our variable *isDigit* to *true* if the *digVal* of [KeyMaps] says *k* is one of the decimal
     * keys on the keypad (it returns NOT_DIGIT if it is not). Then if *current* is 0, and *isDigit*
     * is *true* or *k* is R.id.dec_point and the main expression of [mEvaluator] has a trailing
     * *Constant* we refuse to add *k* to it, adding the key R.id.op_mul (multiply) before dealing
     * with *k* later on in the loop. In either case we set *lastWasDigit* to *isDigit* or the
     * previous value of *lastWasDigit* when *k* is R.id.dec_point.
     *
     * Then if *k* is not View.NO_ID, we set [mCurrentButton] to the view with id *k*. If our parameter
     * [explicit] is *true* we call our [addExplicitKeyToExpr] to add *k* to the expression, otherwise
     * we call our method [addKeyToExpr] to add *k* to the expression. If *c* is a Unicode surrogate
     * code unit we add 2 to *current*, otherwise we add 1 to it, and then we continue the while loop.
     *
     * If we have got this far in the loop we know that *k* is NO_ID, so we need to see if *myMoreChars*
     * contains a function name so we initialize our variable *f* with the value that the *funForString*
     * method returns after examining *myMoreChars* to see if a function name starts at *current* (it
     * returns View.NO_ID if a key is not found for the string, or the resource id of the key if it
     * finds one). Then if *f* is not View.NO_ID, we set [mCurrentButton] to the view with id *f*.
     * If our parameter [explicit] is *true* we call our method [addExplicitKeyToExpr] to add *f* to
     * the expression, otherwise we call our method [addKeyToExpr] to add *f* to the expression. If
     * *f* is R.id.op_sqrt we call our method [addKeyToExpr] to add a R.id.lparen to the expression.
     * We then set *current* to one plus the index in *myMoreChars* of the '(' character of the
     * function invocation, and continue the loop.
     *
     * At this point in the loop there are characters left, but we can't convert them to button presses
     * so we set [mUnprocessedChars] to the characters from *current* to the end of *myMoreChars*, call
     * our method [redisplayAfterFormulaChange] to redisplay the formula, call our method
     * [showOrHideToolbar] to show the tool bar and auto-hide it when appropriate, then return to the
     * caller.
     *
     * If we manage to finish all the characters and finish the while loop we set [mUnprocessedChars]
     * to null, call our method [redisplayAfterFormulaChange] to redisplay the formula, call our method
     * [showOrHideToolbar] to show the tool bar and auto-hide it when appropriate, then return to the
     * caller.
     *
     * @param moreChars characters to be added
     * @param explicit these characters were explicitly typed by the user, not pasted
     */
    private fun addChars(moreChars: String, explicit: Boolean) {
        var myMoreChars = moreChars
        if (mUnprocessedChars != null) {
            myMoreChars = mUnprocessedChars + myMoreChars
        }
        var current = 0
        val len = myMoreChars.length
        var lastWasDigit = false
        if (mCurrentState == CalculatorState.RESULT && len != 0) {
            // Clear display immediately for incomplete function name.
            switchToInput(KeyMaps.keyForChar(myMoreChars[current]))
        }
        val groupingSeparator = KeyMaps.translateResult(",")[0]
        while (current < len) {
            val c = myMoreChars[current]
            if (Character.isSpaceChar(c) || c == groupingSeparator) {
                ++current
                continue
            }
            val k = KeyMaps.keyForChar(c)
            if (!explicit) {
                val expEnd: Int = Evaluator.exponentEnd(myMoreChars, current)
                if (lastWasDigit && (current != expEnd)) {
                    // Process scientific notation with 'E' when pasting, in spite of ambiguity
                    // with base of natural log. 'e' is not recognized as equivalent!
                    // Otherwise the 10^x key is the user's friend.
                    mEvaluator.addExponent(myMoreChars, current, expEnd)
                    current = expEnd
                    lastWasDigit = false
                    continue
                } else {
                    val isDigit = KeyMaps.digVal(k) != KeyMaps.NOT_DIGIT
                    if (current == 0 && (isDigit || k == R.id.dec_point)
                            && mEvaluator.exprGet(Evaluator.MAIN_INDEX).hasTrailingConstant()) {
                        // Refuse to concatenate pasted content to trailing constant.
                        // This makes pasting of calculator results more consistent, whether or
                        // not the old calculator instance is still around.
                        addKeyToExpr(R.id.op_mul)
                    }
                    lastWasDigit = isDigit || lastWasDigit && k == R.id.dec_point
                }
            }
            if (k != View.NO_ID) {
                mCurrentButton = findViewById(k)
                if (explicit) {
                    addExplicitKeyToExpr(k)
                } else {
                    addKeyToExpr(k)
                }
                if (Character.isSurrogate(c)) {
                    current += 2
                } else {
                    ++current
                }
                continue
            }
            val f = KeyMaps.funForString(myMoreChars, current)
            if (f != View.NO_ID) {
                mCurrentButton = findViewById(f)
                if (explicit) {
                    addExplicitKeyToExpr(f)
                } else {
                    addKeyToExpr(f)
                }
                if (f == R.id.op_sqrt) {
                    // Square root entered as function; don't lose the parenthesis.
                    addKeyToExpr(R.id.lparen)
                }
                current = myMoreChars.indexOf('(', current) + 1
                continue
            }
            // There are characters left, but we can't convert them to button presses.
            mUnprocessedChars = myMoreChars.substring(current)
            redisplayAfterFormulaChange()
            showOrHideToolbar()
            return
        }
        mUnprocessedChars = null
        redisplayAfterFormulaChange()
        showOrHideToolbar()
    }

    /**
     * If [mCurrentState] is ERROR or RESULT we set our state to INPUT and clear the main expression
     * of [mEvaluator].
     */
    private fun clearIfNotInputState() {
        if (mCurrentState == CalculatorState.ERROR || mCurrentState == CalculatorState.RESULT) {
            setState(CalculatorState.INPUT)
            mEvaluator.clearMain()
        }
    }

    /**
     * This hook is called whenever the context menu is being closed (either by the user canceling
     * the menu with the back/menu button, or when an item is selected). We just call our method
     * [stopActionModeOrContextMenu] to stop any active [ActionMode] or [ContextMenu] being used for
     * copy/paste actions.
     *
     * @param menu The context menu that is being closed.
     */
    override fun onContextMenuClosed(menu: Menu) {
        stopActionModeOrContextMenu()
    }

    /**
     * This interface is used to determine if it is appropriate given the current execution state for
     * the memory option to be displayed in the [ActionMode] or [ContextMenu] that is launched by
     * a long click.
     */
    interface OnDisplayMemoryOperationsListener {
        /**
         * Returns true if the memory options should be displayed in the [ActionMode] or [ContextMenu]
         * that is launched by long clicking.
         */
        fun shouldDisplayMemory(): Boolean
    }

    /**
     * Contains all our constants.
     */
    companion object {

        /**
         * TAG used for logging.
         */
        private const val TAG = "Calculator"

        /**
         * Constant for an invalid resource id.
         */
        const val INVALID_RES_ID = -1

        /**
         * Namespace we use for the keys of values saved by [onSaveInstanceState] in the [Bundle] it
         * is passed, and which are restored by [restoreInstanceState].
         */
        private const val NAME = "Calculator"
        /**
         * Key under which the Int *ordinal* of [mCurrentState] is saved.
         */
        private const val KEY_DISPLAY_STATE = NAME + "_display_state"
        /**
         * Key under which the [CharSequence] of [mUnprocessedChars] is stored.
         */
        private const val KEY_UNPROCESSED_CHARS = NAME + "_unprocessed_chars"
        /**
         * Associated value is a byte array holding the evaluator state of [mEvaluator].
         */
        private const val KEY_EVAL_STATE = NAME + "_eval_state"
        /**
         *  Key under which the boolean *isSelected* state of [mInverseToggle] is stored.
         */
        private const val KEY_INVERSE_MODE = NAME + "_inverse_mode"
        /**
         * Associated value is an boolean holding the visibility state of the toolbar.
         */
        private const val KEY_SHOW_TOOLBAR = NAME + "_show_toolbar"
    }
}

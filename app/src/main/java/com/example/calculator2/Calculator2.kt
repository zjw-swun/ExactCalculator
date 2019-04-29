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
        override fun onMemoryStateChanged() {
            mFormulaText.onMemoryStateChanged()
        }

        override fun showMessageDialog(@StringRes title: Int, @StringRes message: Int,
                                       @StringRes positiveButtonLabel: Int, tag: String) {
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
            return mEvaluator.memoryIndex != 0L
        }
    }

    /**
     * Used when the formula TextView is long clicked and its context menu used to either paste from
     * the clipboard (our onPaste override) or recall the last expression from memory and append it
     * to the current one (our onMemoryRecall override).
     */
    private val mOnFormulaContextMenuClickListener = object : OnFormulaContextMenuClickListener {
        override fun onPaste(clip: ClipData): Boolean {
            val item = (if (clip.itemCount == 0) null else clip.getItemAt(0))
                    ?: // nothing to paste, bail early...
                    return false

            // Check if the item is a previously copied result, otherwise paste as raw text.
            val uri = item.uri
            if (uri != null && mEvaluator.isLastSaved(uri)) {
                clearIfNotInputState()
                mEvaluator.appendExpr(mEvaluator.savedIndex)
                redisplayAfterFormulaChange()
            } else {
                addChars(item.coerceToText(this@Calculator2).toString(), false)
            }
            return true
        }

        override fun onMemoryRecall() {
            clearIfNotInputState()
            val memoryIndex = mEvaluator.memoryIndex
            if (memoryIndex != 0L) {
                mEvaluator.appendExpr(mEvaluator.memoryIndex)
                redisplayAfterFormulaChange()
            }
        }
    }

    /**
     * [TextWatcher] for the formula TextView, the afterTextChanged override adds our OnPreDrawListener
     * [mPreDrawListener] to the ViewTreeObserver of the HorizontalScrollView holding [TextWatcher]
     * which will scroll the scroll view as new characters are added to the formula.
     */
    private val mFormulaTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(charSequence: CharSequence, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(charSequence: CharSequence, start: Int, count: Int, after: Int) {}

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
     * The [CalculatorDisplay] in our ui with id R.id.main_calculator, it is a LinearLayout which
     * contains the logic to control the widgets that make up the ui
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
     * First we retrieve the 'indexOfEnum' into the [CalculatorState] enum's that was stored in
     * [savedInstanceState] by [onSaveInstanceState], then we retrieve the [CalculatorState] value
     * that corresponds to that index into 'savedState' and call our method [setState] to configure
     * our UI appropriately for that [CalculatorState]. We retrieve the [CharSequence] that was
     * possibly stored in [savedInstanceState] under the key KEY_UNPROCESSED_CHARS ("_unprocessed_chars")
     * to 'unprocessed', and if that is not null we store the string value of that in our field
     * [mUnprocessedChars]. We retrieve the byte array stored in [savedInstanceState] under the key
     * KEY_EVAL_STATE ("_eval_state") into 'state', and if this is not null we wrap in a try block
     * whose catch block will default to a clean state ([mCurrentState] = INPUT, and a cleared
     * [mEvaluator]), a call to the 'restoreInstanceState' method of [mEvaluator] with an
     * [ObjectInputStream] created from an [ByteArrayInputStream] created from 'state'. We retrieve
     * the [Boolean] stored in [savedInstanceState] under the key KEY_SHOW_TOOLBAR ("_show_toolbar")
     * (defaulting to true) and if it is true we call our method [showAndMaybeHideToolbar] to show
     * or hide the tool bar depending on the value of [mCurrentState], and if it is false we call
     * the 'hideToolbar' method of [mDisplayView] to hiae the tool bar. We then call our method
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
                    `in` -> mEvaluator.restoreInstanceState(`in`)
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
     * the 'setShouldEvaluateResult' method of [mResultText] to have it evaluate result on layout
     * (SHOULD_EVALUATE), otherwise we call our [setState] method to set our state to that which
     * our method [mapFromSaved] determines to be appropriate given the value of [mCurrentState]
     * and then we call the 'setShouldEvaluateResult' method of [mResultText] with the value
     * SHOULD_REQUIRE to have it call the 'requireResult' method of Evaluator on layout so that it
     * will start the evaluation of the expression.
     */
    private fun restoreDisplay() {
        onModeChanged(mEvaluator.getDegreeMode(Evaluator.MAIN_INDEX))
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
     * layouts, both 'display_one_line.xml' and 'display_two_line.xml'). We set the 'displayOptions'
     * property of the 'actionBar' to 0 to hide all the default options, and add a lambda as an
     * 'OnMenuVisibilityListener' that will respond to menu visibility change events by forcing
     * [mDisplayView] to have the tool bar visible while the options menu is displayed. We initialize
     * [mMainCalculator] by finding the view with id R.id.main_calculator (it is the 'LinearLayout'
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
     * index the main expression MAIN_INDEX. We call the 'setActivity' method of [KeyMaps] to have
     * it set the activity used for looking up button labels to 'this'.
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

        mEvaluator = Evaluator.getInstance(this)
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

    // Set the state, updating delete label and display colors.
    // This restores display positions on moving to INPUT.
    // But movement/animation for moving to RESULT has already been done.
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
                if (mCurrentState == CalculatorState.RESULT
                        || mCurrentState == CalculatorState.EVALUATE
                        || mCurrentState == CalculatorState.ANIMATE) {
                    mFormulaText.visibility = View.VISIBLE
                    mResultText.visibility = View.VISIBLE
                } else if (mCurrentState == CalculatorState.ERROR) {
                    mFormulaText.visibility = View.INVISIBLE
                    mResultText.visibility = View.VISIBLE
                } else {
                    mFormulaText.visibility = View.VISIBLE
                    mResultText.visibility = View.INVISIBLE
                }
            }

            if (mCurrentState == CalculatorState.ERROR) {
                val errorColor = ContextCompat.getColor(this, R.color.calculator_error_color)
                mFormulaText.setTextColor(errorColor)
                mResultText.setTextColor(errorColor)
                window.statusBarColor = errorColor
            } else if (mCurrentState != CalculatorState.RESULT) {
                mFormulaText.setTextColor(
                        ContextCompat.getColor(this, R.color.display_formula_text_color))
                mResultText.setTextColor(
                        ContextCompat.getColor(this, R.color.display_result_text_color))
                window.statusBarColor = ContextCompat.getColor(this, R.color.calculator_statusbar_color)
            }

            invalidateOptionsMenu()
        }
    }

    override fun onDestroy() {
        mDragLayout.removeDragCallback(this)
        super.onDestroy()
    }

    /**
     * Destroy the evaluator and close the underlying database.
     */
    @Suppress("unused")
    fun destroyEvaluator() {
        mEvaluator.destroyEvaluator()
    }

    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        if (mode.tag === CalculatorFormula.TAG_ACTION_MODE) {
            mFormulaContainer.scrollTo(mFormulaText.right, 0)
        }
    }

    /**
     * Stop any active ActionMode or ContextMenu for copy/paste actions.
     * Return true if there was one.
     */
    private fun stopActionModeOrContextMenu(): Boolean {
        return mResultText.stopActionModeOrContextMenu() || mFormulaText.stopActionModeOrContextMenu()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()

        // If there's an animation in progress, end it immediately, so the user interaction can
        // be handled.
        mCurrentAnimator?.end()
    }

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

    override fun onBackPressed() {
        if (!stopActionModeOrContextMenu()) {
            val historyFragment = historyFragment
            if (mDragLayout.isOpen && historyFragment != null) {
                if (!historyFragment.stopActionModeOrContextMenu()) {
                    removeHistoryFragment()
                }
                return
            }
            if (mPadViewPager != null && mPadViewPager!!.currentItem != 0) {
                // Select the previous pad.
                mPadViewPager!!.currentItem = mPadViewPager!!.currentItem - 1
            } else {
                // If the user is currently looking at the first pad (or the pad is not paged),
                // allow the system to handle the Back button.
                super.onBackPressed()
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Allow the system to handle special key codes (e.g. "BACK" or "DPAD").
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return super.onKeyUp(keyCode, event)
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
     * Invoked whenever the inverse button is toggled to update the UI.
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
     * not necessarily actually changed where this is invoked.
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
     * View.NO_ID is treated as an incomplete function id.
     */
    private fun switchToInput(button_id: Int) {
        if (KeyMaps.isBinary(button_id) || KeyMaps.isSuffix(button_id)) {
            mEvaluator.collapse(mEvaluator.maxIndex /* Most recent history entry */)
        } else {
            announceClearedForAccessibility()
            mEvaluator.clearMain()
        }
        setState(CalculatorState.INPUT)
    }

    // Add the given button id to input expression.
    // If appropriate, clear the expression before doing so.
    private fun addKeyToExpr(id: Int) {
        if (mCurrentState == CalculatorState.ERROR) {
            setState(CalculatorState.INPUT)
        } else if (mCurrentState == CalculatorState.RESULT) {
            switchToInput(id)
        }

        if (!mEvaluator.append(id)) {
            // TODO: Some user visible feedback?
        }
    }

    /**
     * Add the given button id to input expression, assuming it was explicitly
     * typed/touched.
     * We perform slightly more aggressive correction than in pasted expressions.
     */
    private fun addExplicitKeyToExpr(id: Int) {
        if (mCurrentState == CalculatorState.INPUT && id == R.id.op_sub) {
            mEvaluator.getExpr(Evaluator.MAIN_INDEX).removeTrailingAdditiveOperators()
        }
        addKeyToExpr(id)
    }

    fun evaluateInstantIfNecessary() {
        if (mCurrentState == CalculatorState.INPUT && mEvaluator.getExpr(Evaluator.MAIN_INDEX).hasInterestingOps()) {
            mEvaluator.evaluateAndNotify(Evaluator.MAIN_INDEX, this, mResultText)
        }
    }

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
     * Show the toolbar.
     * Automatically hide it again if it's not relevant to current formula.
     */
    private fun showAndMaybeHideToolbar() {
        val shouldBeVisible = mCurrentState == CalculatorState.INPUT && mEvaluator.hasTrigFuncs()
        mDisplayView.showToolbar(!shouldBeVisible)
    }

    /**
     * Display or hide the toolbar depending on calculator state.
     */
    private fun showOrHideToolbar() {
        val shouldBeVisible = mCurrentState == CalculatorState.INPUT && mEvaluator.hasTrigFuncs()
        if (shouldBeVisible) {
            mDisplayView.showToolbar(false)
        } else {
            mDisplayView.hideToolbar()
        }
    }

    @Suppress("unused")
    fun onButtonClick(view: View) {
        // Any animation is ended before we get here.
        mCurrentButton = view
        stopActionModeOrContextMenu()

        // See onKey above for the rationale behind some of the behavior below:
        cancelUnrequested()

        @Suppress("MoveVariableDeclarationIntoWhen")
        val id = view.id
        when (id) {
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
                val mode = !mEvaluator.getDegreeMode(Evaluator.MAIN_INDEX)
                if (mCurrentState == CalculatorState.RESULT && mEvaluator.getExpr(Evaluator.MAIN_INDEX).hasTrigFuncs()) {
                    // Capture current result evaluated in old mode.
                    mEvaluator.collapse(mEvaluator.maxIndex)
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

    fun redisplayFormula() {
        val formula = mEvaluator.getExpr(Evaluator.MAIN_INDEX).toSpannableStringBuilder(this)
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

    override fun onLongClick(view: View): Boolean {
        mCurrentButton = view

        if (view.id == R.id.del) {
            onClear()
            return true
        }
        return false
    }

    // Initial evaluation completed successfully.  Initiate display.
    override fun onEvaluate(index: Long, initDisplayPrec: Int, msd: Int, leastDigPos: Int,
                            truncatedWholeNumber: String) {
        if (index != Evaluator.MAIN_INDEX) {
            throw AssertionError("Unexpected evaluation result index\n")
        }

        // Invalidate any options that may depend on the current result.
        invalidateOptionsMenu()

        mResultText.onEvaluate(index, initDisplayPrec, msd, leastDigPos, truncatedWholeNumber)
        if (mCurrentState != CalculatorState.INPUT) {
            // In EVALUATE, INIT, RESULT, or INIT_FOR_RESULT state.
            onResult(mCurrentState == CalculatorState.EVALUATE /* animate */,
                    mCurrentState == CalculatorState.INIT_FOR_RESULT || mCurrentState == CalculatorState.RESULT /* previously preserved */)
        }
    }

    // Reset state to reflect evaluator cancellation.  Invoked by evaluator.
    override fun onCancelled(index: Long) {
        // Index is Evaluator.MAIN_INDEX. We should be in EVALUATE state.
        setState(CalculatorState.INPUT)
        mResultText.onCancelled(index)
    }

    // Reevaluation completed; ask result to redisplay current value.
    override fun onReevaluate(index: Long) {
        // Index is Evaluator.MAIN_INDEX.
        mResultText.onReevaluate(index)
    }

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
     * Cancel any in-progress explicitly requested evaluations.
     * @param quiet suppress pop-up message.  Explicit evaluation can change the expression
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


    private fun cancelUnrequested() {
        if (mCurrentState == CalculatorState.INPUT) {
            mEvaluator.cancel(Evaluator.MAIN_INDEX, true)
        }
    }

    private fun haveUnprocessed(): Boolean {
        return mUnprocessedChars != null && mUnprocessedChars!!.isNotEmpty()
    }

    private fun onEquals() {
        // Ignore if in non-INPUT state, or if there are no operators.
        if (mCurrentState == CalculatorState.INPUT) {
            if (haveUnprocessed()) {
                setState(CalculatorState.EVALUATE)
                onError(Evaluator.MAIN_INDEX, R.string.error_syntax)
            } else if (mEvaluator.getExpr(Evaluator.MAIN_INDEX).hasInterestingOps()) {
                setState(CalculatorState.EVALUATE)
                mEvaluator.requireResult(Evaluator.MAIN_INDEX, this, mResultText)
            }
        }
    }

    private fun onDelete() {
        // Delete works like backspace; remove the last character or operator from the expression.
        // Note that we handle keyboard delete exactly like the delete button.  For
        // example the delete button can be used to delete a character from an incomplete
        // function name typed on a physical keyboard.
        // This should be impossible in RESULT state.
        // If there is an in-progress explicit evaluation, just cancel it and return.
        if (cancelIfEvaluating(false)) return
        setState(CalculatorState.INPUT)
        if (haveUnprocessed()) {
            mUnprocessedChars = mUnprocessedChars?.substring(0, mUnprocessedChars!!.length - 1)
        } else {
            mEvaluator.delete()
        }
        if (mEvaluator.getExpr(Evaluator.MAIN_INDEX).isEmpty && !haveUnprocessed()) {
            // Resulting formula won't be announced, since it's empty.
            announceClearedForAccessibility()
        }
        redisplayAfterFormulaChange()
    }

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

        val x1_2 = Math.pow((revealView.left - revealCenterX).toDouble(), 2.0)
        val x2_2 = Math.pow((revealView.right - revealCenterX).toDouble(), 2.0)
        val y_2 = Math.pow((revealView.top - revealCenterY).toDouble(), 2.0)
        val revealRadius = Math.max(Math.sqrt(x1_2 + y_2), Math.sqrt(x2_2 + y_2)).toFloat()

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

    private fun announceClearedForAccessibility() {
        mResultText.announceForAccessibility(resources.getString(R.string.cleared))
    }

    fun onClearAnimationEnd() {
        mUnprocessedChars = null
        mResultText.clear()
        mEvaluator.clearMain()
        setState(CalculatorState.INPUT)
        redisplayFormula()
    }

    private fun onClear() {
        if (mEvaluator.getExpr(Evaluator.MAIN_INDEX).isEmpty && !haveUnprocessed()) {
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

    // Evaluation encountered en error.  Display the error.
    override fun onError(index: Long, errorResourceId: Int) {
        if (index != Evaluator.MAIN_INDEX) {
            throw AssertionError("Unexpected error source")
        }
        if (mCurrentState == CalculatorState.EVALUATE) {
            setState(CalculatorState.ANIMATE)
            mResultText.announceForAccessibility(resources.getString(errorResourceId))
            reveal(mCurrentButton, R.color.calculator_error_color,
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            setState(CalculatorState.ERROR)
                            mResultText.onError(index, errorResourceId)
                        }
                    })
        } else if (mCurrentState == CalculatorState.INIT ||
                mCurrentState == CalculatorState.INIT_FOR_RESULT /* very unlikely */) {
            setState(CalculatorState.ERROR)
            mResultText.onError(index, errorResourceId)
        } else {
            mResultText.clear()
        }
    }

    // Animate movement of result into the top formula slot.
    // Result window now remains translated in the top slot while the result is displayed.
    // (We convert it back to formula use only when the user provides new input.)
    // Historical note: In the Lollipop version, this invisibly and instantaneously moved
    // formula and result displays back at the end of the animation.  We no longer do that,
    // so that we can continue to properly support scrolling of the result.
    // We assume the result already contains the text to be expanded.
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
        val resultTranslationY = (mFormulaContainer.bottom - mResultText.bottom - (mFormulaText.paddingBottom - mResultText.paddingBottom)).toFloat()
        var formulaTranslationY = (-mFormulaContainer.bottom).toFloat()
        if (isOneLine) {
            // Position the result text.
            mResultText.y = mResultText.bottom.toFloat()
            formulaTranslationY = (-(findViewById<View>(R.id.toolbar).bottom + mFormulaContainer.bottom)).toFloat()
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
                    ObjectAnimator.ofFloat(mFormulaContainer, View.TRANSLATION_Y,
                            formulaTranslationY))
            animatorSet.duration = resources.getInteger(
                    android.R.integer.config_longAnimTime).toLong()
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

    // Restore positions of the formula and result displays back to their original,
    // pre-animation state.
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)

        menuInflater.inflate(R.menu.activity_calculator, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)

        // Show the leading option when displaying a result.
        menu.findItem(R.id.menu_leading).isVisible = mCurrentState == CalculatorState.RESULT

        // Show the fraction option when displaying a rational result.
        var visible = mCurrentState == CalculatorState.RESULT
        val mainResult = mEvaluator.getResult(Evaluator.MAIN_INDEX)
        // mainResult should never be null, but it happens. Check as a workaround to protect
        // against crashes until we find the root cause (b/34763650).
        visible = visible and (mainResult != null && mainResult.exactlyDisplayable())
        menu.findItem(R.id.menu_fraction).isVisible = visible

        return true
    }

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

    /* Begin override CloseCallback method. */

    override fun onClose() {
        removeHistoryFragment()
    }

    /* End override CloseCallback method. */

    /* Begin override DragCallback methods */

    override fun onStartDraggingOpen() {
        mDisplayView.hideToolbar()
        showHistoryFragment()
    }

    override fun onInstanceStateRestored(isOpen: Boolean) {}

    override fun whileDragging(yFraction: Float) {}

    override fun shouldCaptureView(view: View, x: Int, y: Int): Boolean {
        return view.id == R.id.history_frame && (mDragLayout.isMoving || mDragLayout.isViewUnder(view, x, y))
    }

    override fun getDisplayHeight(): Int {
        return mDisplayView.measuredHeight
    }

    /* End override DragCallback methods */

    /**
     * Change evaluation state to one that's friendly to the history fragment.
     * Return false if that was not easily possible.
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
            } // Easiest to just refuse.  Otherwise we can see a state change
            // while in history mode, which causes all sorts of problems.
            // TODO: Consider other alternatives. If we're just doing the decimal conversion
            // at the end of an evaluation, we could treat this as RESULT state.
            CalculatorState.EVALUATE -> {
                // Cancel current evaluation
                cancelIfEvaluating(true /* quiet */)
                setState(CalculatorState.INPUT)
                return true
            }
            else -> return mCurrentState != CalculatorState.INIT
        }
        // We should be in INPUT, INIT_FOR_RESULT, RESULT, or ERROR state.
    }

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

    private fun displayMessage(title: String, message: String) {
        AlertDialogFragment.showMessageDialog(this, title, message, null, null/* tag */)
    }

    private fun displayFraction() {
        val result = mEvaluator.getResult(Evaluator.MAIN_INDEX)
        displayMessage(getString(R.string.menu_fraction),
                KeyMaps.translateResult(result.toNiceString()))
    }

    // Display full result to currently evaluated precision
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
     * Add input characters to the end of the expression.
     * Map them to the appropriate button pushes when possible.  Leftover characters
     * are added to mUnprocessedChars, which is presumed to immediately precede the newly
     * added characters.
     * @param moreChars characters to be added
     * @param explicit these characters were explicitly typed by the user, not pasted
     */
    private fun addChars(moreChars: String, explicit: Boolean) {
        var myMoreChars = moreChars
        if (mUnprocessedChars != null) {
            myMoreChars = mUnprocessedChars!! + myMoreChars
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
                if (lastWasDigit && current != (expEnd)) {
                    // Process scientific notation with 'E' when pasting, in spite of ambiguity
                    // with base of natural log.
                    // Otherwise the 10^x key is the user's friend.
                    mEvaluator.addExponent(myMoreChars, current, expEnd)
                    current = expEnd
                    lastWasDigit = false
                    continue
                } else {
                    val isDigit = KeyMaps.digVal(k) != KeyMaps.NOT_DIGIT
                    if (current == 0 && (isDigit || k == R.id.dec_point)
                            && mEvaluator.getExpr(Evaluator.MAIN_INDEX).hasTrailingConstant()) {
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

    private fun clearIfNotInputState() {
        if (mCurrentState == CalculatorState.ERROR || mCurrentState == CalculatorState.RESULT) {
            setState(CalculatorState.INPUT)
            mEvaluator.clearMain()
        }
    }

    /**
     * Clean up animation for context menu.
     */
    override fun onContextMenuClosed(menu: Menu) {
        stopActionModeOrContextMenu()
    }

    interface OnDisplayMemoryOperationsListener {
        fun shouldDisplayMemory(): Boolean
    }

    companion object {

        private const val TAG = "Calculator"
        /**
         * Constant for an invalid resource id.
         */
        const val INVALID_RES_ID = -1

        private const val NAME = "Calculator"
        private const val KEY_DISPLAY_STATE = NAME + "_display_state"
        private const val KEY_UNPROCESSED_CHARS = NAME + "_unprocessed_chars"
        /**
         * Associated value is a byte array holding the evaluator state.
         */
        private const val KEY_EVAL_STATE = NAME + "_eval_state"
        private const val KEY_INVERSE_MODE = NAME + "_inverse_mode"
        /**
         * Associated value is an boolean holding the visibility state of the toolbar.
         */
        private const val KEY_SHOW_TOOLBAR = NAME + "_show_toolbar"
    }
}

/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.calculator2

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.ActionMode
import android.view.ContextMenu
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import android.widget.Toast

import androidx.annotation.IntDef
import androidx.core.content.ContextCompat

import kotlin.annotation.Retention

/**
 * A text widget that is "infinitely" scrollable to the right, and obtains the text to display via
 * a callback to the program Logic.
 */
@Suppress("MemberVisibilityCanBePrivate")
class CalculatorResult(context: Context, attrs: AttributeSet)
    : AlignedTextView(context, attrs), MenuItem.OnMenuItemClickListener, Evaluator.EvaluationListener,
        Evaluator.CharMetricsInfo {
    /**
     * [OverScroller] that we use to calculate our current position [mCurrentPos] when the user is
     * scrolling or flinging our [AlignedTextView]
     */
    internal val mScroller: OverScroller = OverScroller(context)
    /**
     * The [GestureDetector] that we use to handle `onFling`, `onScroll`, and `onLongPress` gestures
     * that our view's `OnTouchListener` receives.
     */
    internal val mGestureDetector: GestureDetector
    /**
     * Index of expression we are displaying.
     */
    private var mIndex: Long = 0
    /**
     * The [Evaluator] we should use to evaluate the expression whose result we are showing.
     */
    private var mEvaluator: Evaluator? = null
    /**
     * A scrollable result is currently displayed.
     *
     * @return `true` if the currently displayed result is scrollable
     */
    var isScrollable = false
        private set
    /**
     * The result holds a valid number (not an error message).
     */
    private var mValid = false
    /**
     * Position of right of display relative to decimal point, in pixels. A suffix of "Pos" denotes
     * a pixel offset. Zero represents a scroll position in which the decimal point is just barely
     * visible on the right of the display. Large positive values mean the decimal point is scrolled
     * off the left of the display.
     */
    private var mCurrentPos: Int = 0
    /**
     * Position already reflected in display. Pixels.
     */
    private var mLastPos: Int = 0
    /**
     * Minimum position to avoid unnecessary blanks on the left. Pixels.
     */
    private var mMinPos: Int = 0
    /**
     * Maximum position before we start displaying the infinite sequence of trailing zeroes
     * on the right. Pixels.
     */
    private var mMaxPos: Int = 0
    /**
     * Length of the whole part of current result.
     */
    private var mWholeLen: Int = 0

    // In the following, we use a suffix of Offset to denote a character position in a numeric
    // string relative to the decimal point. Positive is to the right and negative is to the left.
    // 1 = tenths position, -1 = units. Integer.MAX_VALUE is sometimes used for the offset of the
    // last digit in an a non-terminating decimal expansion. We use the suffix "Index" to denote
    // a zero-based index into a string representing a result.
    /**
     * Character offset from decimal point of rightmost digit that should be displayed, plus the
     * length of any exponent needed to display that digit. Limited to MAX_RIGHT_SCROLL. Often
     * the same as [mLsdOffset]
     */
    private var mMaxCharOffset: Int = 0
    /**
     * Position of least-significant digit in result
     */
    private var mLsdOffset: Int = 0
    /**
     * Offset of last digit actually displayed after adding exponent.
     */
    private var mLastDisplayedOffset: Int = 0
    /**
     * Scientific notation not needed for initial display.
     */
    private var mWholePartFits: Boolean = false
    /**
     * Fraction of digit width saved by avoiding scientific notation. Only accessed from UI thread.
      */
    private var mNoExponentCredit: Float = 0.toFloat()
    /**
     * The result fits entirely in the display, even with an exponent, but not with grouping
     * separators. Since the result is not scrollable, and we do not add the exponent to max.
     * scroll position, append an exponent instead of replacing trailing digits.
     */
    private var mAppendExponent: Boolean = false

    /**
     * Protects the next five fields. These fields are only updated by the UI thread, and read
     * accesses by the UI thread sometimes do not acquire the lock.
     */
    private val mWidthLock = Any()
    /**
     * Our total width in pixels minus space for ellipsis. 0 ==> uninitialized.
     */
    private var mWidthConstraint = 0
    /**
     * Maximum character width. For now we pretend that all characters have this width. We're not
     * really using a fixed width font. But it appears to be close enough for the characters we use
     * that the difference is not noticeable.
     */
    private var mCharWidth = 1f
    /**
     * Fraction of digit width occupied by a digit separator.
     */
    private var mGroupingSeparatorWidthRatio: Float = 0.toFloat()
    /**
     * Fraction of digit width saved by replacing digit with decimal point.
     */
    private var mDecimalCredit: Float = 0.toFloat()
    /**
     * Fraction of digit width saved by both replacing ellipsis with digit and avoiding
     * scientific notation.
     */
    private var mNoEllipsisCredit: Float = 0.toFloat()

    /**
     * This annotation limits the values assigned to a field to the valid enum choices
     */
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(SHOULD_REQUIRE, SHOULD_EVALUATE, SHOULD_NOT_EVALUATE)
    annotation class EvaluationRequest

    /**
     * Should we evaluate when layout completes, and how?
     */
    @EvaluationRequest
    private var mEvaluationRequest = SHOULD_REQUIRE
    /**
     * Listener to use if/when evaluation is requested.
     */
    private var mEvaluationListener: Evaluator.EvaluationListener? = this

    /**
     * Lighter color for exponent while scrolling when the exponent is used as a position indicator
     */
    private val mExponentColorSpan: ForegroundColorSpan = ForegroundColorSpan(
            ContextCompat.getColor(context, R.color.display_result_exponent_text_color))

    /**
     * Background color to use to highlight the result when the context menu has been launched by
     * long clicking it.
     */
    private val mHighlightSpan: BackgroundColorSpan = BackgroundColorSpan(highlightColor)

    /**
     * The [ActionMode] that has been started by long clicking us (Android M and above).
     */
    private var mActionMode: ActionMode? = null
    /**
     * The [ActionMode.Callback2] that interprets the selection of action menu item clicks
     */
    private var mCopyActionModeCallback: ActionMode.Callback? = null
    /**
     * The [ContextMenu] that has been started by long clicking us (Android L and lower).
     */
    private var mContextMenu: ContextMenu? = null

    /**
     * The user requested that the result currently being evaluated should be stored to "memory".
     */
    private var mStoreToMemoryRequested = false

    /**
     * Get entire result up to current displayed precision, or up to MAX_COPY_EXTRA additional
     * digits, if it will lead to an exact result.
     */
    val fullCopyText: String
        get() {
            if (!mValid
                    || mLsdOffset == Integer.MAX_VALUE
                    || fullTextIsExact()
                    || mWholeLen > MAX_RECOMPUTE_DIGITS
                    || mWholeLen + mLsdOffset > MAX_RECOMPUTE_DIGITS
                    || mLsdOffset - mLastDisplayedOffset > MAX_COPY_EXTRA) {
                return getFullText(false)
            }
            // It's reasonable to compute and copy the exact result instead.
            var fractionLsdOffset = Math.max(0, mLsdOffset)
            var rawResult = mEvaluator!!.getResult(mIndex)!!.toStringTruncated(fractionLsdOffset)
            if (mLsdOffset <= -1) {
                // Result has trailing decimal point. Remove it.
                rawResult = rawResult.substring(0, rawResult.length - 1)
                fractionLsdOffset = -1
            }
            val formattedResult = formatResult(rawResult, fractionLsdOffset, MAX_COPY_SIZE,
                    false, rawResult[0] == '-', null,
                    forcePrecision = true, forceSciNotation = false, insertCommas = false)
            return KeyMaps.translateResult(formattedResult)
        }

    /**
     * Our init block. First we initialize `mGestureDetector` with an anonymous `SimpleOnGestureListener`
     * which overrides `onDown`, `onFling`, `onScroll`, and `onLongPress` in order to provide these
     * gestures with behavior specific to our use case. We initialize our variable `slop` to the
     * Distance in pixels a touch can wander before we think the user is scrolling (`scaledTouchSlop`
     * for the `ViewConfiguration` of the `Context` `context` we were constructed in). We then set
     * our `OnTouchListener` to an anonymous class whose `onTouch` override extracts values from the
     * `MotionEvent` it is called for then passes the event on to the `onTouchEvent` override of our
     * `GestureDetector` `mGestureDetector`. Then is the build version of the device we are running
     * on is greater than or equal to M we call our `setupActionMode` method to set up the action mode
     * menu, and for older versions we call our `setupContextMenu` method to set up a context menu.
     * We set our `isCursorVisible` property to *false*, our `isLongClickable` property to *false*
     * and set our `contentDescription` to the string "No result".
     */
    init {
        mGestureDetector = GestureDetector(context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean {
                        return true
                    }

                    override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float,
                                         velocityY: Float): Boolean {
                        if (!mScroller.isFinished) {
                            mCurrentPos = mScroller.finalX
                        }
                        mScroller.forceFinished(true)
                        stopActionModeOrContextMenu()
                        this@CalculatorResult.cancelLongPress()
                        // Ignore scrolls of error string, etc.
                        if (!isScrollable) return true
                        mScroller.fling(mCurrentPos, 0, -velocityX.toInt(), 0  /* horizontal only */,
                                mMinPos, mMaxPos, 0, 0)
                        postInvalidateOnAnimation()
                        return true
                    }

                    override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float,
                                          distanceY: Float): Boolean {
                        var distance = distanceX.toInt()
                        if (!mScroller.isFinished) {
                            mCurrentPos = mScroller.finalX
                        }
                        mScroller.forceFinished(true)
                        stopActionModeOrContextMenu()
                        this@CalculatorResult.cancelLongPress()
                        if (!isScrollable) return true
                        if (mCurrentPos + distance < mMinPos) {
                            distance = mMinPos - mCurrentPos
                        } else if (mCurrentPos + distance > mMaxPos) {
                            distance = mMaxPos - mCurrentPos
                        }
                        var duration = (e2.eventTime - e1.eventTime).toInt()
                        if (duration < 1 || duration > 100) duration = 10
                        mScroller.startScroll(mCurrentPos, 0, distance, 0, duration)
                        postInvalidateOnAnimation()
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        if (mValid) {
                            performLongClick()
                        }
                    }
                })

        val slop = ViewConfiguration.get(context).scaledTouchSlop
        setOnTouchListener(object : OnTouchListener {

            // Used to determine whether a touch event should be intercepted.
            private var mInitialDownX: Float = 0.toFloat()
            private var mInitialDownY: Float = 0.toFloat()

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val action = event.actionMasked

                val x = event.x
                val y = event.y
                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        mInitialDownX = x
                        mInitialDownY = y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = Math.abs(x - mInitialDownX)
                        val deltaY = Math.abs(y - mInitialDownY)
                        if (deltaX > slop && deltaX > deltaY) {
                            // Prevent the DragLayout from intercepting horizontal scrolls.
                            parent.requestDisallowInterceptTouchEvent(true)
                        }
                    }
                }
                return mGestureDetector.onTouchEvent(event)
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setupActionMode()
        } else {
            setupContextMenu()
        }

        isCursorVisible = false
        isLongClickable = false
        contentDescription = context.getString(R.string.desc_result)
    }

    /**
     * Called to initialize our fields [mEvaluator] and [mIndex], and then call the [requestLayout]
     * method to schedule a layout pass of the view tree.
     *
     * @param evaluator The [Evaluator] we should use.
     * @param index The index of the expression whose result we are showing.
     */
    fun setEvaluator(evaluator: Evaluator, index: Long) {
        mEvaluator = evaluator
        mIndex = index
        requestLayout()
    }

    /**
     * Measure the view and its content to determine the measured width and the measured height. If
     * our view has *not* been through at least one layout since it was last attached to or detached
     * from a window we call our super's implementation of `onMeasure` then set the minimum height
     * of our view to the sum of the vertical distance between lines of text plus the bottom padding
     * of our view plus the top padding of our view.
     *
     * We then initialize our variable `paint` to the [TextPaint] of our view, our variable `context`
     * to the context our view is running in, and our variable `newCharWidth` to the maximum digit
     * width calculated by our method [getMaxDigitWidth] for `paint`. We then perform a bunch of
     * calculations which determine the widths contributed by non-digit characters we might display
     * depending on the format we need to use to display our result. Then synchronized on our field
     * [mWidthLock] we initialize our fields [mWidthConstraint], [mCharWidth], [mNoEllipsisCredit],
     * [mDecimalCredit], and [mGroupingSeparatorWidthRatio] to values consistent with the size our
     * view is allowed and the text size of its [TextPaint]. Finally we call our super's implementation
     * of `onMeasure` with the recalculated [widthMeasureSpec] and the original [heightMeasureSpec]
     * parameter.
     *
     * @param widthMeasureSpec horizontal space requirements as imposed by the parent.
     * @param heightMeasureSpec vertical space requirements as imposed by the parent.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!isLaidOut) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            // Set a minimum height so scaled error messages won't affect our layout.
            minimumHeight = (lineHeight + compoundPaddingBottom
                    + compoundPaddingTop)
        }

        val paint = paint
        val context = context
        val newCharWidth = getMaxDigitWidth(paint)
        // Digits are presumed to have no more than newCharWidth.
        // There are two instances when we know that the result is otherwise narrower than
        // expected:
        // 1. For standard scientific notation (our type 1), we know that we have a narrow decimal
        // point and no (usually wide) ellipsis symbol. We allow one extra digit
        // (SCI_NOTATION_EXTRA) to compensate, and consider that in determining available width.
        // 2. If we are using digit grouping separators and a decimal point, we give ourselves
        // a fractional extra space for those separators, the value of which depends on whether
        // there is also an ellipsis.
        //
        // Maximum extra space we need in various cases:
        // Type 1 scientific notation, assuming ellipsis, minus sign and E are wider than a digit:
        //    Two minus signs + "E" + "." - 3 digits.
        // Type 2 scientific notation:
        //    Ellipsis + "E" + "-" - 3 digits.
        // In the absence of scientific notation, we may need a little less space.
        // We give ourselves a bit of extra credit towards comma insertion and give
        // ourselves more if we have either
        //    No ellipsis, or
        //    A decimal separator.

        // Calculate extra space we need to reserve, in addition to character count.
        val decimalSeparatorWidth = Layout.getDesiredWidth(context.getString(R.string.dec_point), paint)
        val minusWidth = Layout.getDesiredWidth(context.getString(R.string.op_sub), paint)
        val minusExtraWidth = Math.max(minusWidth - newCharWidth, 0.0f)
        val ellipsisWidth = Layout.getDesiredWidth(KeyMaps.ELLIPSIS, paint)
        val ellipsisExtraWidth = Math.max(ellipsisWidth - newCharWidth, 0.0f)
        val expWidth = Layout.getDesiredWidth(KeyMaps.translateResult("e"), paint)
        val expExtraWidth = Math.max(expWidth - newCharWidth, 0.0f)
        val type1Extra = 2 * minusExtraWidth + expExtraWidth + decimalSeparatorWidth
        val type2Extra = ellipsisExtraWidth + expExtraWidth + minusExtraWidth
        val extraWidth = Math.max(type1Extra, type2Extra)
        val intExtraWidth = Math.ceil(extraWidth.toDouble()).toInt() + 1 /* to cover rounding sins */
        val newWidthConstraint = (MeasureSpec.getSize(widthMeasureSpec)
                - (paddingLeft + paddingRight) - intExtraWidth)

        // Calculate other width constants we need to handle grouping separators.
        val groupingSeparatorW = Layout.getDesiredWidth(KeyMaps.translateResult(","), paint)
        // Credits in the absence of any scientific notation:
        val noExponentCredit = extraWidth - Math.max(ellipsisExtraWidth, minusExtraWidth)
        val noEllipsisCredit = extraWidth - minusExtraWidth  // includes noExponentCredit.
        val decimalCredit = Math.max(newCharWidth - decimalSeparatorWidth, 0.0f)

        mNoExponentCredit = noExponentCredit / newCharWidth
        synchronized(mWidthLock) {
            mWidthConstraint = newWidthConstraint
            mCharWidth = newCharWidth
            mNoEllipsisCredit = noEllipsisCredit / newCharWidth
            mDecimalCredit = decimalCredit / newCharWidth
            mGroupingSeparatorWidthRatio = groupingSeparatorW / newCharWidth
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * Called from layout when this view should assign a size and position to each of its children.
     * First we call our super's implementation of `onLayout`. Then if [mEvaluator] is not *null*,
     * and [mEvaluationRequest] is not SHOULD_NOT_EVALUATE we:
     * - Initialize our variable `expr` with the [CalculatorExpr] found by the `getExpr` method
     * of [mEvaluator] for the expression index [mIndex].
     * - If the `hasInterestingOps` method of `expr` determines that the expression is worth evaluating
     * We branch on the value of [mEvaluationRequest]:
     *     - SHOULD_REQUIRE: we call the `requireResult` method of `mEvaluator` start the required
     *     evaluation of the expression at the index `mIndex` and call back `mEvaluationListener`
     *     when ready.
     *     - SHOULD_EVALUATE: we call the `evaluateAndNotify` method of `mEvaluator` to start the
     *     optional evaluation of the same expression and display when ready.
     *
     * @param changed This is a new size or position for this view
     * @param left Left position, relative to parent
     * @param top Top position, relative to parent
     * @param right Right position, relative to parent
     * @param bottom Bottom position, relative to parent
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        if (mEvaluator != null && mEvaluationRequest != mEvaluationRequest) {
            val expr = mEvaluator!!.getExpr(mIndex)
            if (expr.hasInterestingOps()) {
                when (mEvaluationRequest) {
                    SHOULD_REQUIRE -> mEvaluator?.requireResult(mIndex, mEvaluationListener, this)
                    else -> mEvaluator?.evaluateAndNotify(mIndex, mEvaluationListener, this)
                }
            }
        }
    }

    /**
     * Specify whether we should evaluate result on layout. We just set our fields [mEvaluationListener]
     * and [mEvaluationRequest] to our parameters.
     *
     * @param request one of SHOULD_REQUIRE, SHOULD_EVALUATE, SHOULD_NOT_EVALUATE
     * @param listener the `EvaluationListener` whose callback should be called.
     */
    fun setShouldEvaluateResult(@EvaluationRequest request: Int,
                                listener: Evaluator.EvaluationListener?) {
        mEvaluationListener = listener
        mEvaluationRequest = request
    }

    // From Evaluator.CharMetricsInfo.
    override fun separatorChars(s: String, len: Int): Float {
        var start = 0
        while (start < len && !Character.isDigit(s[start])) {
            ++start
        }
        // We assume the rest consists of digits, and for consistency with the rest
        // of the code, we assume all digits have width mCharWidth.
        val nDigits = len - start
        // We currently insert a digit separator every three digits.
        val nSeparators = (nDigits - 1) / 3
        synchronized(mWidthLock) {
            // Always return an upper bound, even in the presence of rounding errors.
            return nSeparators * mGroupingSeparatorWidthRatio
        }
    }

    // From Evaluator.CharMetricsInfo.
    override fun getNoEllipsisCredit(): Float {
        synchronized(mWidthLock) {
            return mNoEllipsisCredit
        }
    }

    // From Evaluator.CharMetricsInfo.
    override fun getDecimalCredit(): Float {
        synchronized(mWidthLock) {
            return mDecimalCredit
        }
    }

    // Return the length of the exponent representation for the given exponent, in
    // characters.
    private fun expLen(exp: Int): Int {
        if (exp == 0) return 0
        val absExpDigits = Math.ceil(Math.log10(Math.abs(exp.toDouble())) + 0.0000000001 /* Round whole numbers to next integer */).toInt()
        return absExpDigits + if (exp >= 0) 1 else 2
    }

    /**
     * Initiate display of a new result.
     * Only called from UI thread.
     * The parameters specify various properties of the result.
     * @param index Index of expression that was just evaluated. Currently ignored, since we only
     * expect notification for the expression result being displayed.
     * @param initPrec Initial display precision computed by evaluator. (1 = tenths digit)
     * @param msd Position of most significant digit.  Offset from left of string.
     * Evaluator.INVALID_MSD if unknown.
     * @param leastDigPos Position of least significant digit (1 = tenths digit)
     * or Integer.MAX_VALUE.
     * @param truncatedWholePart Result up to but not including decimal point.
     * Currently we only use the length.
     */
    override fun onEvaluate(index: Long, initPrec: Int, msd: Int, leastDigPos: Int,
                            truncatedWholePart: String) {
        initPositions(initPrec, msd, leastDigPos, truncatedWholePart)

        if (mStoreToMemoryRequested) {
            mEvaluator!!.copyToMemory(index)
            mStoreToMemoryRequested = false
        }
        redisplay()
    }

    /**
     * Store the result for this index if it is available.
     * If it is unavailable, set mStoreToMemoryRequested to indicate that we should store
     * when evaluation is complete.
     */
    fun onMemoryStore() {
        if (mEvaluator!!.hasResult(mIndex)) {
            mEvaluator!!.copyToMemory(mIndex)
        } else {
            mStoreToMemoryRequested = true
            mEvaluator!!.requireResult(mIndex, this /* listener */, this /* CharMetricsInfo */)
        }
    }

    /**
     * Add the result to the value currently in memory.
     */
    fun onMemoryAdd() {
        mEvaluator!!.addToMemory(mIndex)
    }

    /**
     * Subtract the result from the value currently in memory.
     */
    fun onMemorySubtract() {
        mEvaluator!!.subtractFromMemory(mIndex)
    }

    /**
     * Set up scroll bounds (mMinPos, mMaxPos, etc.) and determine whether the result is
     * scrollable, based on the supplied information about the result.
     * This is unfortunately complicated because we need to predict whether trailing digits
     * will eventually be replaced by an exponent.
     * Just appending the exponent during formatting would be simpler, but would produce
     * jumpier results during transitions.
     * Only called from UI thread.
     */
    private fun initPositions(initPrecOffset: Int, msdIndex: Int, lsdOffset: Int,
                              truncatedWholePart: String) {
        var msdIndexLocal = msdIndex
        val maxChars = maxChars
        mWholeLen = truncatedWholePart.length
        // Allow a tiny amount of slop for associativity/rounding differences in length
        // calculation.  If getPreferredPrec() decided it should fit, we want to make it fit, too.
        // We reserved one extra pixel, so the extra length is OK.
        val nSeparatorChars = Math.ceil(
                (separatorChars(truncatedWholePart, truncatedWholePart.length)
                        - noEllipsisCredit - 0.0001f).toDouble()).toInt()
        mWholePartFits = mWholeLen + nSeparatorChars <= maxChars
        mLastPos = INVALID
        mLsdOffset = lsdOffset
        mAppendExponent = false
        // Prevent scrolling past initial position, which is calculated to show leading digits.
        mMinPos = Math.round(initPrecOffset * mCharWidth)
        mCurrentPos = mMinPos
        if (msdIndexLocal == Evaluator.INVALID_MSD) {
            // Possible zero value
            if (lsdOffset == Integer.MIN_VALUE) {
                // Definite zero value.
                mMaxPos = mMinPos
                mMaxCharOffset = Math.round(mMaxPos / mCharWidth)
                isScrollable = false
            } else {
                // May be very small nonzero value.  Allow user to find out.
                mMaxCharOffset = MAX_RIGHT_SCROLL
                mMaxPos = mMaxCharOffset
                mMinPos -= mCharWidth.toInt()  // Allow for future minus sign.
                isScrollable = true
            }
            return
        }
        val negative = if (truncatedWholePart[0] == '-') 1 else 0
        if (msdIndexLocal > mWholeLen && msdIndexLocal <= mWholeLen + 3) {
            // Avoid tiny negative exponent; pretend msdIndex is just to the right of decimal point.
            msdIndexLocal = mWholeLen - 1
        }
        // Set to position of leftmost significant digit relative to dec. point. Usually negative.
        var minCharOffset = msdIndexLocal - mWholeLen
        if (minCharOffset > -1 && minCharOffset < MAX_LEADING_ZEROES + 2) {
            // Small number of leading zeroes, avoid scientific notation.
            minCharOffset = -1
        }
        if (lsdOffset < MAX_RIGHT_SCROLL) {
            mMaxCharOffset = lsdOffset
            if (mMaxCharOffset < -1 && mMaxCharOffset > -(MAX_TRAILING_ZEROES + 2)) {
                mMaxCharOffset = -1
            }
            // lsdOffset is positive or negative, never 0.
            var currentExpLen = 0  // Length of required standard scientific notation exponent.
            if (mMaxCharOffset < -1) {
                currentExpLen = expLen(-minCharOffset - 1)
            } else if (minCharOffset > -1 || mMaxCharOffset >= maxChars) {
                // Number is either entirely to the right of decimal point, or decimal point is
                // not visible when scrolled to the right.
                currentExpLen = expLen(-minCharOffset)
            }
            // Exponent length does not included added decimal point.  But whenever we add a
            // decimal point, we allow an extra character (SCI_NOTATION_EXTRA).
            val separatorLength = if (mWholePartFits && minCharOffset < -3) nSeparatorChars else 0
            isScrollable = mMaxCharOffset + currentExpLen + separatorLength - minCharOffset + negative >= maxChars
            // Now adjust mMaxCharOffset for any required exponent.
            val newMaxCharOffset: Int
            if (currentExpLen > 0) {
                newMaxCharOffset = if (isScrollable) {
                    // We'll use exponent corresponding to leastDigPos when scrolled to right.
                    mMaxCharOffset + expLen(-lsdOffset)
                } else {
                    mMaxCharOffset + currentExpLen
                }
                mMaxCharOffset = if (-1 in mMaxCharOffset until newMaxCharOffset) {
                    // Very unlikely; just drop exponent.
                    -1
                } else {
                    Math.min(newMaxCharOffset, MAX_RIGHT_SCROLL)
                }
                mMaxPos = Math.min(Math.round(mMaxCharOffset * mCharWidth),
                        MAX_RIGHT_SCROLL)
            } else if (!mWholePartFits && !isScrollable) {
                // Corner case in which entire number fits, but not with grouping separators.  We
                // will use an exponent in un-scrolled position, which may hide digits.  Scrolling
                // by one character will remove the exponent and reveal the last digits.  Note
                // that in the forced scientific notation case, the exponent length is not
                // factored into mMaxCharOffset, since we do not want such an increase to impact
                // scrolling behavior.  In the un-scrollable case, we thus have to append the
                // exponent at the end using the forcePrecision argument to formatResult, in order
                // to ensure that we get the entire result.
                isScrollable = mMaxCharOffset + expLen(-minCharOffset - 1) - minCharOffset + negative >= maxChars
                if (isScrollable) {
                    mMaxPos = Math.ceil((mMinPos + mCharWidth).toDouble()).toInt()
                    // Single character scroll will remove exponent and show remaining piece.
                } else {
                    mMaxPos = mMinPos
                    mAppendExponent = true
                }
            } else {
                mMaxPos = Math.min(Math.round(mMaxCharOffset * mCharWidth),
                        MAX_RIGHT_SCROLL)
            }
            if (!isScrollable) {
                // Position the number consistently with our assumptions to make sure it
                // actually fits.
                mCurrentPos = mMaxPos
            }
        } else {
            mMaxCharOffset = MAX_RIGHT_SCROLL
            mMaxPos = mMaxCharOffset
            isScrollable = true
        }
    }

    /**
     * Display error message indicated by resourceId.
     * UI thread only.
     */
    override fun onError(index: Long, resourceId: Int) {
        mStoreToMemoryRequested = false
        mValid = false
        isLongClickable = false
        isScrollable = false
        val msg = context.getString(resourceId)
        val measuredWidth = Layout.getDesiredWidth(msg, paint)
        if (measuredWidth > mWidthConstraint) {
            // Multiply by .99 to avoid rounding effects.
            val scaleFactor = 0.99f * mWidthConstraint / measuredWidth
            val smallTextSpan = RelativeSizeSpan(scaleFactor)
            val scaledMsg = SpannableString(msg)
            scaledMsg.setSpan(smallTextSpan, 0, msg.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = scaledMsg
        } else {
            text = msg
        }
    }

    /**
     * Format a result returned by Evaluator.getString() into a single line containing ellipses
     * (if appropriate) and an exponent (if appropriate).
     * We add two distinct kinds of exponents:
     * (1) If the final result contains the leading digit we use standard scientific notation.
     * (2) If not, we add an exponent corresponding to an interpretation of the final result as
     * an integer.
     * We add an ellipsis on the left if the result was truncated.
     * We add ellipses and exponents in a way that leaves most digits in the position they
     * would have been in had we not done so. This minimizes jumps as a result of scrolling.
     * Result is NOT internationalized, uses "E" for exponent.
     * Called only from UI thread; We sometimes omit locking for fields.
     * @param precOffset The value that was passed to getString. Identifies the significance of
     * the rightmost digit. A value of 1 means the rightmost digits corresponds to tenths.
     * @param maxDigs The maximum number of characters in the result
     * @param truncated The in parameter was already truncated, beyond possibly removing the
     * minus sign.
     * @param negative The in parameter represents a negative result. (Minus sign may be removed
     * without setting truncated.)
     * @param lastDisplayedOffset  If not null, we set lastDisplayedOffset[0] to the offset of
     * the last digit actually appearing in the display.
     * @param forcePrecision If true, we make sure that the last displayed digit corresponds to
     * precOffset, and allow maxDigs to be exceeded in adding the exponent and commas.
     * @param forceSciNotation Force scientific notation. May be set because we don't have
     * space for grouping separators, but whole number otherwise fits.
     * @param insertCommas Insert commas (literally, not internationalized) as digit separators.
     * We only ever do this for the integral part of a number, and only when no
     * exponent is displayed in the initial position. The combination of which means
     * that we only do it when no exponent is displayed.
     * We insert commas in a way that does consider the width of the actual localized digit
     * separator. Commas count towards maxDigs as the appropriate fraction of a digit.
     */
    private fun formatResult(`in`: String, precOffset: Int, maxDigs: Int, truncated: Boolean,
                             negative: Boolean, lastDisplayedOffset: IntArray?, forcePrecision: Boolean,
                             forceSciNotation: Boolean, insertCommas: Boolean): String {
        val minusSpace = if (negative) 1 else 0
        val msdIndex = if (truncated) -1 else getNaiveMsdIndexOf(`in`)  // INVALID_MSD is OK.
        var result = `in`
        var needEllipsis = false
        if (truncated || negative && result[0] != '-') {
            needEllipsis = true
            result = KeyMaps.ELLIPSIS + result.substring(1)
            // Ellipsis may be removed again in the type(1) scientific notation case.
        }
        val decIndex = result.indexOf('.')
        if (lastDisplayedOffset != null) {
            lastDisplayedOffset[0] = precOffset
        }
        if (forceSciNotation || (decIndex == -1 || msdIndex != Evaluator.INVALID_MSD && msdIndex - decIndex > MAX_LEADING_ZEROES + 1) && precOffset != -1) {
            // Either:
            // 1) No decimal point displayed, and it's not just to the right of the last digit, or
            // 2) we are at the front of a number whose integral part is too large to allow
            // comma insertion, or
            // 3) we should suppress leading zeroes.
            // Add an exponent to let the user track which digits are currently displayed.
            // Start with type (2) exponent if we dropped no digits. -1 accounts for decimal point.
            // We currently never show digit separators together with an exponent.
            val initExponent = if (precOffset > 0) -precOffset else -precOffset - 1
            var exponent = initExponent
            var hasPoint = false
            if (!truncated && msdIndex < maxDigs - 1
                    && result.length - msdIndex + 1 + minusSpace <= maxDigs + SCI_NOTATION_EXTRA) {
                // Type (1) exponent computation and transformation:
                // Leading digit is in display window. Use standard calculator scientific notation
                // with one digit to the left of the decimal point. Insert decimal point and
                // delete leading zeroes.
                // We try to keep leading digits roughly in position, and never
                // lengthen the result by more than SCI_NOTATION_EXTRA.
                if (decIndex > msdIndex) {
                    // In the forceSciNotation, we can have a decimal point in the relevant digit
                    // range. Remove it.
                    result = result.substring(0, decIndex) + result.substring(decIndex + 1)
                    // msdIndex and precOffset unaffected.
                }
                val resLen = result.length
                val fraction = result.substring(msdIndex + 1, resLen)
                result = ((if (negative) "-" else "") + result.substring(msdIndex, msdIndex + 1)
                        + "." + fraction)
                // Original exp was correct for decimal point at right of fraction.
                // Adjust by length of fraction.
                exponent = initExponent + resLen - msdIndex - 1
                hasPoint = true
            }
            // Exponent can't be zero.
            // Actually add the exponent of either type:
            if (!forcePrecision) {
                var dropDigits: Int  // Digits to drop to make room for exponent.
                if (hasPoint) {
                    // Type (1) exponent.
                    // Drop digits even if there is room. Otherwise the scrolling gets jumpy.
                    dropDigits = expLen(exponent)
                    if (dropDigits >= result.length - 1) {
                        // Jumpy is better than no mantissa.  Probably impossible anyway.
                        dropDigits = Math.max(result.length - 2, 0)
                    }
                } else {
                    // Type (2) exponent.
                    // Exponent depends on the number of digits we drop, which depends on
                    // exponent ...

                    dropDigits = 2
                    while (expLen(initExponent + dropDigits) > dropDigits) {
                        ++dropDigits
                    }
                    exponent = initExponent + dropDigits
                    if (precOffset - dropDigits > mLsdOffset) {
                        // This can happen if e.g. result = 10^40 + 10^10
                        // It turns out we would otherwise display ...10e9 because it takes
                        // the same amount of space as ...1e10 but shows one more digit.
                        // But we don't want to display a trailing zero, even if it's free.
                        ++dropDigits
                        ++exponent
                    }
                }
                if (dropDigits >= result.length - 1) {
                    // Display too small to show meaningful result.
                    return KeyMaps.ELLIPSIS + "E" + KeyMaps.ELLIPSIS
                }
                result = result.substring(0, result.length - dropDigits)
                if (lastDisplayedOffset != null) {
                    lastDisplayedOffset[0] -= dropDigits
                }
            }
            result = result + "E" + exponent
        } else if (insertCommas) {
            // Add commas to the whole number section, and then truncate on left to fit,
            // counting commas as a fractional digit.
            val wholeStart = if (needEllipsis) 1 else 0
            var origLength = result.length
            val nCommaChars: Float
            if (decIndex != -1) {
                nCommaChars = separatorChars(result, decIndex)
                result = StringUtils.addCommas(result, wholeStart, decIndex) + result.substring(decIndex, origLength)
            } else {
                nCommaChars = separatorChars(result, origLength)
                result = StringUtils.addCommas(result, wholeStart, origLength)
            }
            if (needEllipsis) {
                origLength -= 1  // Exclude ellipsis.
            }
            val len = origLength + nCommaChars
            var deletedChars = 0
            @Suppress("UNUSED_VARIABLE") val ellipsisCredit = noEllipsisCredit
            @Suppress("UNUSED_VARIABLE") val decimalCredit = decimalCredit
            val effectiveLen = len - if (decIndex == -1) 0.0F else getDecimalCredit()
            val ellipsisAdjustment = if (needEllipsis) mNoExponentCredit else noEllipsisCredit
            // As above, we allow for a tiny amount of extra length here, for consistency with
            // getPreferredPrec().
            if (effectiveLen - ellipsisAdjustment > (maxDigs - wholeStart).toFloat() + 0.0001f && !forcePrecision) {
                var deletedWidth = 0.0f
                while (effectiveLen - mNoExponentCredit - deletedWidth > (maxDigs - 1 /* for ellipsis */).toFloat()) {
                    deletedWidth += if (result[deletedChars] == ',') {
                        mGroupingSeparatorWidthRatio
                    } else {
                        1.0f
                    }
                    deletedChars++
                }
            }
            if (deletedChars > 0) {
                result = KeyMaps.ELLIPSIS + result.substring(deletedChars)
            } else if (needEllipsis) {
                result = KeyMaps.ELLIPSIS + result
            }
        }
        return result
    }

    /**
     * Get formatted, but not internationalized, result from mEvaluator.
     * @param precOffset requested position (1 = tenths) of last included digit
     * @param maxSize maximum number of characters (more or less) in result
     * @param lastDisplayedOffset zeroth entry is set to actual offset of last included digit,
     * after adjusting for exponent, etc.  May be null.
     * @param forcePrecision Ensure that last included digit is at nextPos, at the expense
     * of treating maxSize as a soft limit.
     * @param forceSciNotation Force scientific notation, even if not required by maxSize.
     * @param insertCommas Insert commas as digit separators.
     */
    private fun getFormattedResult(precOffset: Int, maxSize: Int, lastDisplayedOffset: IntArray?,
                                   forcePrecision: Boolean, forceSciNotation: Boolean,
                                   insertCommas: Boolean): String {
        val truncated = BooleanArray(1)
        val negative = BooleanArray(1)
        val requestedPrecOffset = intArrayOf(precOffset)
        val rawResult = mEvaluator!!.getString(mIndex, requestedPrecOffset, mMaxCharOffset,
                maxSize, truncated, negative, this)
        return formatResult(rawResult, requestedPrecOffset[0], maxSize, truncated[0], negative[0],
                lastDisplayedOffset, forcePrecision, forceSciNotation, insertCommas)
    }

    /**
     * Return entire result (within reason) up to current displayed precision.
     * @param withSeparators  Add digit separators
     */
    fun getFullText(withSeparators: Boolean): String {
        if (!mValid) return ""
        return if (!isScrollable) text.toString() else KeyMaps.translateResult(getFormattedResult(mLastDisplayedOffset, MAX_COPY_SIZE, null, true, false, withSeparators))
    }

    /**
     * Did the above produce a correct result?
     * UI thread only.
     */
    fun fullTextIsExact(): Boolean {
        return !isScrollable || getCharOffset(mMaxPos) == getCharOffset(mCurrentPos) && mMaxCharOffset != MAX_RIGHT_SCROLL
    }

    /**
     * Return the maximum number of characters that will fit in the result display.
     * May be called asynchronously from non-UI thread. From Evaluator.CharMetricsInfo.
     * Returns zero if measurement hasn't completed.
     */
    override fun getMaxChars(): Int {
        synchronized(mWidthLock) {
            return Math.floor((mWidthConstraint / mCharWidth).toDouble()).toInt()
        }
    }

    /**
     * Map pixel position to digit offset.
     * UI thread only.
     */
    internal fun getCharOffset(pos: Int): Int {
        return Math.round(pos / mCharWidth)  // Lock not needed.
    }

    internal fun clear() {
        mValid = false
        isScrollable = false
        text = ""
        isLongClickable = false
    }

    override fun onCancelled(index: Long) {
        clear()
        mStoreToMemoryRequested = false
    }

    /**
     * Refresh display.
     * Only called in UI thread. Index argument is currently ignored.
     */
    override fun onReevaluate(index: Long) {
        redisplay()
    }

    fun redisplay() {
        val maxChars = maxChars
        if (maxChars < 4) {
            // Display currently too small to display a reasonable result. Punt to avoid crash.
            return
        }
        if (mScroller.isFinished && length() > 0) {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val currentCharOffset = getCharOffset(mCurrentPos)
        val lastDisplayedOffset = IntArray(1)
        var result = getFormattedResult(currentCharOffset, maxChars, lastDisplayedOffset,
                mAppendExponent /* forcePrecision; preserve entire result */,
                !mWholePartFits && currentCharOffset == getCharOffset(mMinPos) /* forceSciNotation */,
                mWholePartFits /* insertCommas */)
        val expIndex = result.indexOf('E')
        result = KeyMaps.translateResult(result)
        if (expIndex > 0 && result.indexOf('.') == -1) {
            // Gray out exponent if used as position indicator
            val formattedResult = SpannableString(result)
            formattedResult.setSpan(mExponentColorSpan, expIndex, result.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = formattedResult
        } else {
            text = result
        }
        mLastDisplayedOffset = lastDisplayedOffset[0]
        mValid = true
        isLongClickable = true
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)

        if (!isScrollable || mScroller.isFinished) {
            if (lengthBefore == 0 && lengthAfter > 0) {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                contentDescription = null
            } else if (lengthBefore > 0 && lengthAfter == 0) {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
                contentDescription = context.getString(R.string.desc_result)
            }
        }
    }

    override fun computeScroll() {
        if (!isScrollable) {
            return
        }

        if (mScroller.computeScrollOffset()) {
            mCurrentPos = mScroller.currX
            if (getCharOffset(mCurrentPos) != getCharOffset(mLastPos)) {
                mLastPos = mCurrentPos
                redisplay()
            }
        }

        if (!mScroller.isFinished) {
            postInvalidateOnAnimation()
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        } else if (length() > 0) {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    }

    /**
     * Use ActionMode for copy/memory support on M and higher.
     */
    @TargetApi(Build.VERSION_CODES.M)
    private fun setupActionMode() {
        mCopyActionModeCallback = object : ActionMode.Callback2() {

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val inflater = mode.menuInflater
                return createContextMenu(inflater, menu)
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                return false // Return false if nothing is done
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return if (onMenuItemClick(item)) {
                    mode.finish()
                    true
                } else {
                    false
                }
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                unhighlightResult()
                mActionMode = null
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                super.onGetContentRect(mode, view, outRect)

                outRect.left += view.paddingLeft
                outRect.top += view.paddingTop
                outRect.right -= view.paddingRight
                outRect.bottom -= view.paddingBottom
                val width = Layout.getDesiredWidth(text, paint).toInt()
                if (width < outRect.width()) {
                    outRect.left = outRect.right - width
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    // The CAB (prior to N) only takes the translation of a view into account, so
                    // if a scale is applied to the view then the offset outRect will end up being
                    // positioned incorrectly. We workaround that limitation by manually applying
                    // the scale to the outRect, which the CAB will then offset to the correct
                    // position.
                    val scaleX = view.scaleX
                    val scaleY = view.scaleY
                    outRect.left *= scaleX.toInt()
                    outRect.right *= scaleX.toInt()
                    outRect.top *= scaleY.toInt()
                    outRect.bottom *= scaleY.toInt()
                }
            }
        }
        setOnLongClickListener(OnLongClickListener {
            if (mValid) {
                mActionMode = startActionMode(mCopyActionModeCallback, ActionMode.TYPE_FLOATING)
                return@OnLongClickListener true
            }
            false
        })
    }

    /**
     * Use ContextMenu for copy/memory support on L and lower.
     */
    @Suppress("UNUSED_ANONYMOUS_PARAMETER")
    private fun setupContextMenu() {
        setOnCreateContextMenuListener { contextMenu, view, contextMenuInfo ->
            val inflater = MenuInflater(context)
            createContextMenu(inflater, contextMenu)
            mContextMenu = contextMenu
            for (i in 0 until contextMenu.size()) {
                contextMenu.getItem(i).setOnMenuItemClickListener(this@CalculatorResult)
            }
        }
        setOnLongClickListener {
            if (mValid) {
                showContextMenu()
            } else false
        }
    }

    private fun createContextMenu(inflater: MenuInflater, menu: Menu): Boolean {
        inflater.inflate(R.menu.menu_result, menu)
        val displayMemory = mEvaluator!!.memoryIndex != 0L
        val memoryAddItem = menu.findItem(R.id.memory_add)
        val memorySubtractItem = menu.findItem(R.id.memory_subtract)
        memoryAddItem.isEnabled = displayMemory
        memorySubtractItem.isEnabled = displayMemory
        highlightResult()
        return true
    }

    fun stopActionModeOrContextMenu(): Boolean {
        if (mActionMode != null) {
            mActionMode!!.finish()
            return true
        }
        if (mContextMenu != null) {
            unhighlightResult()
            mContextMenu!!.close()
            return true
        }
        return false
    }

    private fun highlightResult() {
        val text = text as Spannable
        text.setSpan(mHighlightSpan, 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun unhighlightResult() {
        val text = text as Spannable
        text.removeSpan(mHighlightSpan)
    }

    @Suppress("unused")
    private fun setPrimaryClip(clip: ClipData) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip = clip
    }

    private fun copyContent() {
        val text = fullCopyText
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // We include a tag URI, to allow us to recognize our own results and handle them
        // specially.
        val newItem = ClipData.Item(text, null, mEvaluator!!.capture(mIndex))
        val mimeTypes = arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)
        val cd = ClipData("calculator result", mimeTypes, newItem)
        clipboard.primaryClip = cd
        Toast.makeText(context, R.string.text_copied_toast, Toast.LENGTH_SHORT).show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.memory_add -> {
                onMemoryAdd()
                return true
            }
            R.id.memory_subtract -> {
                onMemorySubtract()
                return true
            }
            R.id.memory_store -> {
                onMemoryStore()
                return true
            }
            R.id.menu_copy -> return if (mEvaluator!!.evaluationInProgress(mIndex)) {
                // Refuse to copy placeholder characters.
                false
            } else {
                copyContent()
                unhighlightResult()
                true
            }
            else -> return false
        }
    }

    override fun onDetachedFromWindow() {
        stopActionModeOrContextMenu()
        super.onDetachedFromWindow()
    }

    companion object {
        internal const val MAX_RIGHT_SCROLL = 10_000_000
        /**
         * A larger value is unlikely to avoid running out of space
         */
        internal const val INVALID = MAX_RIGHT_SCROLL + 10_000
        const val SHOULD_REQUIRE = 2
        const val SHOULD_EVALUATE = 1
        const val SHOULD_NOT_EVALUATE = 0
        const val MAX_LEADING_ZEROES = 6
        // Maximum number of leading zeroes after decimal point before we
        // switch to scientific notation with negative exponent.
        const val MAX_TRAILING_ZEROES = 6
        // Maximum number of trailing zeroes before the decimal point before
        // we switch to scientific notation with positive exponent.
        private const val SCI_NOTATION_EXTRA = 1
        // Extra digits for standard scientific notation.  In this case we
        // have a decimal point and no ellipsis.
        // We assume that we do not drop digits to make room for the decimal
        // point in ordinary scientific notation. Thus >= 1.
        private const val MAX_COPY_EXTRA = 100
        // The number of extra digits we are willing to compute to copy
        // a result as an exact number.

        /**
         * The maximum number of digits we're willing to recompute in the UI thread. We only do
         * this for known rational results, where we can bound the computation cost.
         */
        private const val MAX_RECOMPUTE_DIGITS = 2_000

        private const val MAX_COPY_SIZE = 1_000_000

        // Compute maximum digit width the hard way.
        private fun getMaxDigitWidth(paint: TextPaint): Float {
            // Compute the maximum advance width for each digit, thus accounting for between-character
            // spaces. If we ever support other kinds of digits, we may have to avoid kerning effects
            // that could reduce the advance width within this particular string.
            val allDigits = "0123456789"
            val widths = FloatArray(allDigits.length)
            paint.getTextWidths(allDigits, widths)
            var maxWidth = 0f
            for (x in widths) {
                maxWidth = Math.max(x, maxWidth)
            }
            return maxWidth
        }

        /*
     * Return the most significant digit position in the given string or Evaluator.INVALID_MSD.
     * Unlike Evaluator.getMsdIndexOf, we treat a final 1 as significant.
     * Pure function; callable from anywhere.
     */
        fun getNaiveMsdIndexOf(s: String): Int {
            val len = s.length
            for (i in 0 until len) {
                val c = s[i]
                if (c != '-' && c != '.' && c != '0') {
                    return i
                }
            }
            return Evaluator.INVALID_MSD
        }
    }
}

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
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

import kotlin.annotation.Retention
import kotlin.math.*

/**
 * A text widget that is "infinitely" scrollable to the right, and obtains the text to display via
 * a callback to the program Logic.
 */
@RequiresApi(Build.VERSION_CODES.N)
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
     * This annotation limits the values assigned to a field to the valid [Int] choices
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
            var fractionLsdOffset = max(0, mLsdOffset)
            var rawResult = mEvaluator!!.resultGet(mIndex)!!.toStringTruncated(fractionLsdOffset)
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
                    /**
                     * Notified when a tap occurs with the down [MotionEvent] that triggered it. This
                     * will be triggered immediately for every down event. All other events should be
                     * preceded by this. We just return *true* so that we continue to recieve events.
                     *
                     * @param e The down motion event.
                     * @return *true* if you wish to receive the following events.
                     */
                    override fun onDown(e: MotionEvent): Boolean {
                        return true
                    }

                    /**
                     * Notified of a fling event when it occurs with the initial on down [MotionEvent]
                     * and the matching up [MotionEvent]. The calculated velocity is supplied along
                     * the x and y axis in pixels per second. If the [mScroller] `OverScroller` has
                     * not yet finished scrolling we set our field [mCurrentPos] (position of right
                     * of our display relative to decimal point) to the end position (`finalX`) that
                     * the scroll would reach. Finished or not we then call the `forceFinished` method
                     * of [mScroller] to force its finished field to *true*. We then call our
                     * [stopActionModeOrContextMenu] to close a possible action mode or context menu,
                     * and call our [cancelLongPress] to cancel any pending long press. If our result
                     * is not scrollable (text which fits our display is not scrolled) we just return
                     * *true* to consume the event. Otherwise we call the `fling` method of [mScroller]
                     * to have it start to fling the text it contains starting from [mCurrentPos] with
                     * a velocity of minus [velocityX], with its minimum X value [mMinPos], and maximum
                     * X value [mMaxPos] (we do not scroll vertically so all the Y values are 0). Then
                     * we call the [postInvalidateOnAnimation] method to cause an invalidate to happen
                     * on the next animation time step. Finally we return *true* to consume the event.
                     *
                     * @param e1 The first down motion event that started the fling.
                     * @param e2 The move motion event that triggered the current onFling.
                     * @param velocityX The velocity of this fling measured in pixels per second
                     *              along the x axis.
                     * @param velocityY The velocity of this fling measured in pixels per second
                     *              along the y axis.
                     * @return *true* if the event is consumed, else *false*
                     */
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

                    /**
                     * Notified when a scroll occurs with the initial on down [MotionEvent] and the
                     * current move [MotionEvent]. The distance in x and y is also supplied for
                     * convenience. We initialize our variable `distance` with the [Int] value of
                     * [distanceX]. If the [mScroller] `OverScroller` has not yet finished scrolling
                     * we set our field [mCurrentPos] (position of right of our display relative to
                     * decimal point) to the end position (`finalX`) that the scroll would reach.
                     * Finished or not we then call the `forceFinished` method of [mScroller] to force
                     * its finished field to *true*. We then call our [stopActionModeOrContextMenu]
                     * to close a possible action mode or context menu, and call our [cancelLongPress]
                     * to cancel any pending long press. If our result is not scrollable (text which
                     * fits our display is not scrolled) we just return *true* to consume the event.
                     * If the end position of the scroll ([mCurrentPos] plus `distance`) is less than
                     * [mMinPos] we set `distance` to [mMinPos] minus [mCurrentPos], and if the end
                     * position is greater than [mMaxPos] we set `distance` to [mMaxPos] minus
                     * [mCurrentPos]. We initialize our variable `duration` to the [Int] value of
                     * the `eventTime` field of [e2] minus the `eventTime` field of [e1]. If `duration`
                     * is less than 1 or greater than 100 we set it to 10. We then call the `startScroll`
                     * method of [mScroller] to have it scroll its text contents starting from the
                     * X position [mCurrentPos] and moving `distance` pixels with a duration of
                     * `duration` milliseconds (the Y coordinates are 0 since we do not scroll verically).
                     * Then we call the [postInvalidateOnAnimation] method to cause an invalidate to
                     * happen on the next animation time step, and return *true* to consume the event.
                     *
                     * @param e1 The first down motion event that started the scrolling.
                     * @param e2 The move motion event that triggered the current [onScroll].
                     * @param distanceX The distance along the X axis that has been scrolled since
                     * the last call to [onScroll]. This is NOT the distance between [e1] and [e2].
                     * @param distanceY The distance along the Y axis that has been scrolled since
                     * the last call to [onScroll]. This is NOT the distance between [e1] and [e2].
                     * @return *true* if the event is consumed, else *false*
                     */
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

                    /**
                     * Notified when a long press occurs with the initial on down [MotionEvent] that
                     * triggered it. If our contents represents a valid result we call our
                     * [performLongClick] method to handle the event.
                     *
                     * @param e The initial on down motion event that started the longpress.
                     */
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
                        val deltaX = abs(x - mInitialDownX)
                        val deltaY = abs(y - mInitialDownY)
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
        val minusExtraWidth = max(minusWidth - newCharWidth, 0.0f)
        val ellipsisWidth = Layout.getDesiredWidth(KeyMaps.ELLIPSIS, paint)
        val ellipsisExtraWidth = max(ellipsisWidth - newCharWidth, 0.0f)
        val expWidth = Layout.getDesiredWidth(KeyMaps.translateResult("e"), paint)
        val expExtraWidth = max(expWidth - newCharWidth, 0.0f)
        val type1Extra = 2 * minusExtraWidth + expExtraWidth + decimalSeparatorWidth
        val type2Extra = ellipsisExtraWidth + expExtraWidth + minusExtraWidth
        val extraWidth = max(type1Extra, type2Extra)
        val intExtraWidth = ceil(extraWidth.toDouble()).toInt() + 1 /* to cover rounding sins */
        val newWidthConstraint = (MeasureSpec.getSize(widthMeasureSpec)
                - (paddingLeft + paddingRight) - intExtraWidth)

        // Calculate other width constants we need to handle grouping separators.
        val groupingSeparatorW = Layout.getDesiredWidth(KeyMaps.translateResult(","), paint)
        // Credits in the absence of any scientific notation:
        val noExponentCredit = extraWidth - max(ellipsisExtraWidth, minusExtraWidth)
        val noEllipsisCredit = extraWidth - minusExtraWidth  // includes noExponentCredit.
        val decimalCredit = max(newCharWidth - decimalSeparatorWidth, 0.0f)

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
     * - Initialize our variable `expr` with the [CalculatorExpr] found by the `exprGet` method
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

        if (mEvaluator != null && mEvaluationRequest != SHOULD_NOT_EVALUATE) {
            val expr = mEvaluator!!.exprGet(mIndex)
            if (expr.hasInterestingOps()) {
                if (mEvaluationRequest == SHOULD_REQUIRE) {
                    mEvaluator!!.requireResult(mIndex, mEvaluationListener, this)
                } else {
                    mEvaluator!!.evaluateAndNotify(mIndex, mEvaluationListener, this)
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

    /**
     * Part of the `CharMetricsInfo` interface. Return the number of additional digit widths required
     * to add digit separators to the supplied string prefix. The prefix consists of the first [len]
     * characters of string [s], which is presumed to represent a whole number. Callable from non-UI
     * thread. Returns zero if metrics information is not yet available.
     *
     * We initialize our variable `start` to 0, then loop incrementing `start` looking for the index
     * of the first digit character (also stopping if `start` reaches [len]). We assume the rest of
     * the prefix consists of digits, so we initialize our variable `nDigits` to the number of digits
     * remaining in the prefix (which is [len] minus `start`). The number of separators required is
     * calculated to be `nDigits` minus 1 divided by 3 which we use to initialize `nSeparators`.
     * Synchronized on our lock [mWidthLock] we return the digit space occupied by the separators:
     * `nSeparators` times [mGroupingSeparatorWidthRatio].
     *
     * @param s the string we are to insert digit separators into.
     * @param len the length of the prefix string in [s] we are to consider.
     * @return the number of additional digit widths required to add digit separators to the
     * supplied string prefix (the first [len] characters of the string [s] is the prefix).
     */
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

    /**
     * Part of the `CharMetricsInfo` interface. Return extra width credit for absence of ellipsis, as
     * fraction of a digit width. May be called by non-UI thread. Synchronized on our lock [mWidthLock]
     * we return our field [mNoEllipsisCredit].
     *
     * @return the faction of a digit width available when there is no ellipsis in the display.
     */
    override fun noEllipsisCreditGet(): Float {
        synchronized(mWidthLock) {
            return mNoEllipsisCredit
        }
    }

    /**
     * Part of the `CharMetricsInfo` interface. Return extra width credit for presence of a decimal
     * point, as fraction of a digit width. May be called by non-UI thread. Synchronized on our lock
     * [mWidthLock] we return our field [mDecimalCredit].
     *
     * @return fraction of a digit width saved by lack of a decimal point in the display
     */
    override fun decimalCreditGet(): Float {
        synchronized(mWidthLock) {
            return mDecimalCredit
        }
    }

    /**
     * Return the length of the exponent representation for the given exponent, in characters. If
     * [exp] is 0, we return 0. Otherwise we initialize our variable `absExpDigits` to the number
     * of digits needed to represent the absolute value of [exp] then return `absExpDigits` plus
     * 1 if [exp] is greater than 0 or `absExpDigits` plus 2 if it is a negative exponent.
     *
     * @param exp the exponent the size of whose representation we wish to determine.
     * @return the number of characters that the given exponent will occupy when displayed.
     */
    private fun expLen(exp: Int): Int {
        if (exp == 0) return 0
        val absExpDigits = ceil(log10(abs(exp.toDouble()))
                + 0.0000000001).toInt()
        return absExpDigits + if (exp >= 0) 1 else 2
    }

    /**
     * Part of the `EvaluationListener` interface. Initiate display of a new result. Only called from
     * UI thread. The parameters specify various properties of the result. First we call our method
     * [initPositions] to set up scroll bounds ([mMinPos], [mMaxPos], etc.) and determine whether the
     * result is scrollable. If our field [mStoreToMemoryRequested] is true (the user requested that
     * the result currently being evaluated should be stored to "memory") we call the `copyToMemory`
     * method of [mEvaluator] to copy an immutable version of the expression index [index] as the
     * "memory" value, and set [mStoreToMemoryRequested] to *false*. In any case we call our [redisplay]
     * method to refresh the display.
     *
     * @param index Index of expression that was just evaluated. Currently ignored, since we only
     * expect notification for the expression result being displayed.
     * @param initPrecOffset Initial display precision computed by evaluator. (1 = tenths digit)
     * @param msdIndex Position of most significant digit.  Offset from left of string.
     * Evaluator.INVALID_MSD if unknown.
     * @param lsdOffset Position of least significant digit (1 = tenths digit)
     * or Integer.MAX_VALUE.
     * @param truncatedWholePart Result up to but not including decimal point.
     * Currently we only use the length.
     */
    override fun onEvaluate(index: Long, initPrecOffset: Int, msdIndex: Int, lsdOffset: Int,
                            truncatedWholePart: String) {
        initPositions(initPrecOffset, msdIndex, lsdOffset, truncatedWholePart)

        if (mStoreToMemoryRequested) {
            mEvaluator?.copyToMemory(index)
            mStoreToMemoryRequested = false
        }
        redisplay()
    }

    /**
     * Store the result for this index if it is available. If it is unavailable, set our field
     * [mStoreToMemoryRequested] to *true* to indicate that we should store when evaluation is
     * complete. If the `hasResult` method of [mEvaluator] returns true to indicate that the
     * expression at index [mIndex] has explicitly been evaluated (User pressed "=") we call the
     * `copyToMemory` method of [mEvaluator] to copy an immutable version of the expression index
     * [mIndex] as the "memory" value, otherwise we set our field [mStoreToMemoryRequested] to
     * *true* and call the `requireResult` method of [mEvaluator] to start the required evaluation
     * of expression index [mIndex] using *this* as the `CharMetricsInfo` and call back *this*
     * `EvaluationListener` when ready.
     */
    fun onMemoryStore() {
        if (mEvaluator!!.hasResult(mIndex)) {
            mEvaluator?.copyToMemory(mIndex)
        } else {
            mStoreToMemoryRequested = true
            mEvaluator?.requireResult(mIndex, this /* listener */, this /* CharMetricsInfo */)
        }
    }

    /**
     * Add the result to the value currently in memory. We just call the `addToMemory` method of
     * [mEvaluator] to add the expression at index [mIndex] to the current contents of "memory".
     */
    fun onMemoryAdd() {
        mEvaluator?.addToMemory(mIndex)
    }

    /**
     * Subtract the result from the value currently in memory. We just call the `subtractFromMemory`
     * method of [mEvaluator] to subtract the expression at index [mIndex] from the current contents
     * of "memory".
     */
    fun onMemorySubtract() {
        mEvaluator?.subtractFromMemory(mIndex)
    }

    /**
     * Set up scroll bounds ([mMinPos], [mMaxPos], etc.) and determine whether the result is scrollable,
     * based on the supplied information about the result. This is unfortunately complicated because
     * we need to predict whether trailing digits will eventually be replaced by an exponent. Just
     * appending the exponent during formatting would be simpler, but would produce jumpier results
     * during transitions. Only called from UI thread.
     *
     * We initialize our variable `msdIndexLocal` to our parameter [msdIndex], and our variable
     * `maxCharsLocal` to our property `maxChars`. We then set our field [mWholeLen] to the length
     * of our parameter [truncatedWholePart]. We initialize our variable `nSeparatorChars` by adding
     * some slop to the number of separator characters returned by our [separatorChars] method for
     * our parameter [truncatedWholePart], then initialize our [Boolean] field [mWholePartFits] (the
     * flag for signaling that scientific notation is not needed for initial display) to *true* if
     * [mWholeLen] plus `nSeparatorChars` is less than `maxCharsLocal`. We then set [mLastPos] to
     * an INVALID value, [mLsdOffset] to our parameter [lsdOffset], [mAppendExponent] to *false*
     * (signaling that the result does not fit entirely in the display), [mMinPos] to our parameter
     * [initPrecOffset] times [mCharWidth] (this will prevent scrolling past initial position), and
     * [mCurrentPos] (Position of right of display relative to decimal point) to [mMinPos].
     *
     * If the position of the most significant digit `msdIndexLocal` is INVALID_MSD (unknown) it might
     * be a zero value, so we check if [lsdOffset] is [Integer.MIN_VALUE] and if it is it is a zero
     * value so we set [mMaxPos] to [mMinPos], [mMaxCharOffset] to the rounded off value of [mMaxPos]
     * divided by [mCharWidth] and [isScrollable] to *false*. Otherwise it might be a very small
     * nonzero value so we set up our scroll bounds to allow the user to scroll the result to see:
     * setting [mMaxCharOffset] to MAX_RIGHT_SCROLL, [mMaxPos] to [mMaxCharOffset], subtracting
     * [mCharWidth] from [mMinPos] to allow room for a future minus sign, and set [isScrollable] to
     * *true*. Then whether zero or almost zero we return.
     *
     * Now that we have dealt with zero and near zero values and returned we move on, initializing our
     * varaible `negative` to 1 if the first (zeroth) character of [truncatedWholePart] is a '-'
     * character or to 0 if it is not. If `msdIndexLocal` is greater than [mWholeLen] but less than
     * or equal to [mWholeLen] plus 3 we want to avoid the use of a tiny negative exponent so we
     * pretend [msdIndex] is just to the right of decimal point by setting it to [mWholeLen] minus 1.
     *
     * Now we want to initialize our variable `minCharOffset` to the position of the leftmost
     * significant digit relative to decimal point which is `msdIndexLocal` minus [mWholeLen]. If
     * `minCharOffset` is greater than minus 1, and less than 2 more than MAX_LEADING_ZEROES (6) there
     * are just a small number of leading zeroes, so to avoid scientific notation we set `minCharOffset`
     * to -1.
     *
     * Now we branch on where [lsdOffset] is less than MAX_RIGHT_SCROLL (10,000,000), and if it is
     * greater than or equal instead we just set [mMaxCharOffset] to MAX_RIGHT_SCROLL, [mMaxPos] to
     * [mMaxCharOffset] and [isScrollable] to *true*, otherwise we have some complex logic to perform
     * to determine the scroll values:
     * - We set [mMaxCharOffset] to [lsdOffset], and if it is less than minus 1 but greater than minus
     * MAX_TRAILING_ZEROES (6) minus 2 we set it to minus 1.
     * - We initialize `currentExpLen` (the length of required standard scientific notation exponent)
     * to 0, and if [mMaxCharOffset] is less than -1 we set it to the value returned by our [expLen]
     * method for minus `minCharOffset` minus 1, otherwise if `minCharOffset` is greater than -1
     * or [mMaxCharOffset] is greater than or equal to `maxCharsLocal` the number is  either entirely
     * to the right of decimal point, or decimal point is not visible when scrolled to the right so
     * we set `currentExpLen` to the value returned by our [expLen] method for minus `minCharOffset`.
     * - We initialize our variable `separatorLength` to `nSeparatorChars` if [mWholePartFits] is
     * *true* and `minCharOffset` is less than minus 3, otherwise we set it to 0.
     * - We initialize our variable `charReq` to [mMaxCharOffset] plus `currentExpLen` plus
     * `separatorLength` minus `minCharOffset` plus `negative` then set [isScrollable] to *true* if
     * `charReq` is greater than or equal to `maxCharsLocal`.
     *     - Now we need to adjust [mMaxCharOffset] for any required exponent, so we declare our variable
     *     `newMaxCharOffset`, and if `currentExpLen` is greater than 0, we add the value returned by
     *      our method [expLen] for minus [lsdOffset] to [mMaxCharOffset] to set `newMaxCharOffset` (using
     *      the exponent corresponding to leastDigPos when scrolled to right) if [isScrollable] is *true*
     *      of set it to [mMaxCharOffset] plus `currentExpLen` if it is false.
     *      - We set [mMaxCharOffset] to minus 1 if -1 is in the range [mMaxCharOffset] until
     *      `newMaxCharOffset` (while unlikely we just drop the exponent), otherwise we set it the minimum
     *      of `newMaxCharOffset` and MAX_RIGHT_SCROLL. We then set [mMaxPos] to the minimum of
     *      [mMaxCharOffset] times [mCharWidth] and MAX_RIGHT_SCROLL.
     *      - If `currentExpLen` is less than or equal to 0 on the other hand, we branch on whether
     *      both [mWholePartFits] and [isScrollable] are *false* in which case we have a corner case
     *      in which entire number fits, but not with grouping separators, so we initialize our
     *      variable `chrReq` to `mMaxCharOffset` plus the value returned by our  [expLen] method for
     *      minus `minCharOffset` minus 1, minus `minCharOffset` plus `negative`, and set `isScrollable`
     *      to *true* if that is greater than or equal to `maxCharsLocal`. Then is `isScrollable` is
     *      *true* we set `mMaxPos` to the ceiling of `mMinPos` plus `mCharWidth` (a single character
     *      scroll will remove exponent and show remaining piece), otherwise we set `mMaxPos` to
     *      `mMinPos` and set `mAppendExponent` to *true*. If neither of the above code paths are used
     *      we set `mMaxPos` to the minimum of `mMaxCharOffset` times `mCharWidth` and MAX_RIGHT_SCROLL.
     *      - Then if `isScrollable` is *false* we set `mCurrentPos` to `mMaxPos` to position the
     *      number consistently with our assumptions to make sure it actually fits.
     *
     * @param initPrecOffset Initial display precision computed by evaluator. (1 = tenths digit)
     * @param msdIndex Position of most significant digit.  Offset from left of string.
     * Evaluator.INVALID_MSD if unknown.
     * @param lsdOffset Position of least significant digit (1 = tenths digit)
     * or Integer.MAX_VALUE.
     * @param truncatedWholePart Result up to but not including decimal point.
     * Currently we only use the length.
     */
    private fun initPositions(initPrecOffset: Int,
                              msdIndex: Int, lsdOffset: Int,
                              truncatedWholePart: String) {

        var msdIndexLocal = msdIndex
        val maxCharsLocal = maxCharsGet()
        mWholeLen = truncatedWholePart.length
        // Allow a tiny amount of slop for associativity/rounding differences in length
        // calculation.  If getPreferredPrec() decided it should fit, we want to make it fit, too.
        // We reserved one extra pixel, so the extra length is OK.
        val nSeparatorChars = ceil((separatorChars(truncatedWholePart, truncatedWholePart.length)
                        - noEllipsisCreditGet() - 0.0001f).toDouble()).toInt()
        mWholePartFits = mWholeLen + nSeparatorChars <= maxCharsLocal
        mLastPos = INVALID
        mLsdOffset = lsdOffset
        mAppendExponent = false
        // Prevent scrolling past initial position, which is calculated to show leading digits.
        mMinPos = (initPrecOffset * mCharWidth).roundToInt()
        mCurrentPos = mMinPos
        if (msdIndexLocal == Evaluator.INVALID_MSD) {
            // Possible zero value
            if (lsdOffset == Integer.MIN_VALUE) {
                // Definite zero value.
                mMaxPos = mMinPos
                mMaxCharOffset = (mMaxPos / mCharWidth).roundToInt()
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
            } else if (minCharOffset > -1 || mMaxCharOffset >= maxCharsLocal) {
                // Number is either entirely to the right of decimal point, or decimal point is
                // not visible when scrolled to the right.
                currentExpLen = expLen(-minCharOffset)
            }
            // Exponent length does not included added decimal point.  But whenever we add a
            // decimal point, we allow an extra character (SCI_NOTATION_EXTRA).
            val separatorLength = if (mWholePartFits && minCharOffset < -3) nSeparatorChars else 0
            val charReq = mMaxCharOffset + currentExpLen + separatorLength - minCharOffset + negative
            isScrollable = charReq >= maxCharsLocal
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
                    min(newMaxCharOffset, MAX_RIGHT_SCROLL)
                }
                mMaxPos = min((mMaxCharOffset * mCharWidth).roundToInt(), MAX_RIGHT_SCROLL)
            } else if (!mWholePartFits && !isScrollable) {
                // Corner case in which entire number fits, but not with grouping separators.  We
                // will use an exponent in un-scrolled position, which may hide digits.  Scrolling
                // by one character will remove the exponent and reveal the last digits.  Note
                // that in the forced scientific notation case, the exponent length is not
                // factored into mMaxCharOffset, since we do not want such an increase to impact
                // scrolling behavior.  In the un-scrollable case, we thus have to append the
                // exponent at the end using the forcePrecision argument to formatResult, in order
                // to ensure that we get the entire result.
                val chrReq = mMaxCharOffset + expLen(-minCharOffset - 1) - minCharOffset + negative
                isScrollable = chrReq >= maxCharsLocal
                if (isScrollable) {
                    mMaxPos = ceil((mMinPos + mCharWidth).toDouble()).toInt()
                    // Single character scroll will remove exponent and show remaining piece.
                } else {
                    mMaxPos = mMinPos
                    mAppendExponent = true
                }
            } else {
                mMaxPos = min((mMaxCharOffset * mCharWidth).roundToInt(), MAX_RIGHT_SCROLL)
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
     * Part of the `EvaluationListener` interface. Display error message indicated by [errorId].
     * UI thread only. First we set our field [mStoreToMemoryRequested] to *false* (we do not want
     * to store an error into "memory" even if the user requested us to do so), set our field [mValid]
     * to *false* (to indicate that the result does not hold a valid number), set our long-clickable
     * property flag to *false*, and set our [isScrollable] property to *false*. We initialize our
     * variable `msg` to the string with resource id [errorId], and our variable `measuredWidth`
     * to the width that a layout must be in order to display `msg` using our [TextPaint] with one
     * line per paragraph. If `measuredWidth` is greater than [mWidthConstraint] we need to scale the
     * text of `msg`, so we initialize our variable `scaleFactor` to 0.99 times [mWidthConstraint]
     * divided by `measuredWidth` (to avoid rounding effects), initialize our variable `smallTextSpan`
     * to an instance of [RelativeSizeSpan] which will scale its text by `scaleFactor`, initialize
     * our variable `scaledMsg` to an instance of [SpannableString] constructed from `msg`, and attach
     * the markup of `smallTextSpan` as a span from 0 to the length of `msg` using the flag
     * SPAN_EXCLUSIVE_EXCLUSIVE (do not expand to include text inserted at either the starting or
     * ending point) to `scaledMsg`. We then set our `text` to `scaledMsg`. If on the other hand
     * the `msg` does fit we just set our `text` to `msg`.
     *
     * @param index index of the expression which contains an error. UNUSED
     * @param errorId resource ID of the error string we are the display.
     */
    override fun onError(index: Long, errorId: Int) {
        mStoreToMemoryRequested = false
        mValid = false
        isLongClickable = false
        isScrollable = false
        val msg = context.getString(errorId)
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
     * Format a result returned by Evaluator.stringGet() into a single line containing ellipses
     * (if appropriate) and an exponent (if appropriate).
     *
     * We add two distinct kinds of exponents:
     * 1. If the final result contains the leading digit we use standard scientific notation.
     * 2. If not, we add an exponent corresponding to an interpretation of the final result as
     * an integer.
     *
     * We add an ellipsis on the left if the result was truncated. We add ellipses and exponents in
     * a way that leaves most digits in the position they would have been in had we not done so.
     * This minimizes jumps as a result of scrolling. Result is NOT internationalized, uses "E" for
     * exponent. Called only from UI thread; We sometimes omit locking for fields.
     *
     * @param str result returned by Evaluator.stringGet()
     * @param precOffset The value that was passed to stringGet. Identifies the significance of
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
     * @return the properly formatted result.
     */
    private fun formatResult(str: String, precOffset: Int, maxDigs: Int,
                             truncated: Boolean, negative: Boolean,
                             lastDisplayedOffset: IntArray?,
                             forcePrecision: Boolean,
                             forceSciNotation: Boolean,
                             insertCommas: Boolean): String {
        val minusSpace = if (negative) 1 else 0
        val msdIndex = if (truncated) -1 else getNaiveMsdIndexOf(str)  // INVALID_MSD is OK.
        var result = str
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
                        dropDigits = max(result.length - 2, 0)
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
            @Suppress("UNUSED_VARIABLE") val ellipsisCredit = noEllipsisCreditGet()
            @Suppress("UNUSED_VARIABLE") val decimalCredit = decimalCreditGet()
            val effectiveLen = len - if (decIndex == -1) 0.0F else decimalCreditGet()
            val ellipsisAdjustment = if (needEllipsis) mNoExponentCredit else noEllipsisCreditGet()
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
     * Get formatted, but not internationalized, result from [mEvaluator]. We initialize our variable
     * `truncated` with a [BooleanArray] of size 1, `negative` with a [BooleanArray] of size 1, and
     * `requestedPrecOffset` with an [IntArray] containing [precOffset]. We then initialize our
     * variable `rawResult` to the string returned by [mEvaluator] after it evaluates the expression
     * at index [mIndex] using the precision specified by the zeroth element of `requestedPrecOffset`,
     * [mMaxCharOffset] as the maximum adjusted precision offset, [maxSize] as the maximum length of
     * the result, `truncated` as the array whose zeroth element is set if leading nonzero digits
     * were dropped, `negative` as the array whose zeroth element is set if the result is negative,
     * and *this* as the `EvaluationListener` to notify when reevaluation is complete. Finally we
     * return the string that our method [formatResult] creates after formatting `rawResult` properly.
     *
     * @param precOffset requested position (1 = tenths) of last included digit
     * @param maxSize maximum number of characters (more or less) in result
     * @param lastDisplayedOffset zeroth entry is set to actual offset of last included digit,
     * after adjusting for exponent, etc.  May be null.
     * @param forcePrecision Ensure that last included digit is at nextPos, at the expense
     * of treating maxSize as a soft limit.
     * @param forceSciNotation Force scientific notation, even if not required by maxSize.
     * @param insertCommas Insert commas as digit separators.
     * @return the result returned by [mEvaluator] formatted by our method [formatResult]
     */
    private fun getFormattedResult(precOffset: Int, maxSize: Int, lastDisplayedOffset: IntArray?,
                                   forcePrecision: Boolean, forceSciNotation: Boolean,
                                   insertCommas: Boolean): String {
        val truncated = BooleanArray(1)
        val negative = BooleanArray(1)
        val requestedPrecOffset = intArrayOf(precOffset)
        val rawResult = mEvaluator!!.stringGet(mIndex, requestedPrecOffset, mMaxCharOffset,
                maxSize, truncated, negative, this)
        return formatResult(rawResult, requestedPrecOffset[0], maxSize, truncated[0], negative[0],
                lastDisplayedOffset, forcePrecision, forceSciNotation, insertCommas)
    }

    /**
     * Return entire result (within reason) up to current displayed precision. If our field [mValid]
     * is *false* (indicating our result does not hold a valid number) we return the empty string "".
     * Otherwise if our [isScrollable] field is *false* (indicating that our result is short enough
     * to fit without scrolling) we just return the string value of our `text` property. Otherwise
     * we return the string returned by our [KeyMaps.translateResult] returns after localizing the
     * string returned by our [getFormattedResult] method when it formats our [mIndex] expression
     * using [mLastDisplayedOffset] as the requested position of last included digit, MAX_COPY_SIZE
     * (1,000,000) as the maximum number of characters in the result, *null* for the offset of last
     * included digit (ie. none specified), *true* for the `forcePrecision` parameter to forse the
     * last included digit is at `nextPos`, *false* to not force scientific notation, and our parameter
     * [withSeparators] to have it insert commas as digit separators if our caller wanted them.
     *
     * @param withSeparators  Add digit separators
     * @return string representing the entire result up to the current displayed precision.
     */
    fun getFullText(withSeparators: Boolean): String {
        if (!mValid) return ""
        return if (!isScrollable) {
            text.toString()
        } else {
            KeyMaps.translateResult(getFormattedResult(mLastDisplayedOffset, MAX_COPY_SIZE,
                    null, forcePrecision = true,
                    forceSciNotation = false, insertCommas = withSeparators))
        }
    }

    /**
     * Did the above produce an exact result? UI thread only. We initialize our variable `terminating`
     * to *true* if the character offset of the maximum position before we start displaying the
     * infinite sequence of trailing zeroes on the right ([mMaxPos]) is equal to the character offset
     * of the current position ([mCurrentPos]) and the character offset from decimal point of rightmost
     * digit that should be displayed ([mMaxCharOffset]) is not equal to MAX_RIGHT_SCROLL. We then
     * return *true* if [isScrollable] is *false* (our number fits our display without scrolling)
     * or `terminating` is *true*.
     *
     * @return *true* if the result is exact.
     */
    fun fullTextIsExact(): Boolean {
        val terminating = getCharOffset(mMaxPos) == getCharOffset(mCurrentPos)
                && mMaxCharOffset != MAX_RIGHT_SCROLL
        return !isScrollable || terminating
    }

    /**
     * Part of the `CharMetricsInfo` interface. Return the maximum number of characters that will
     * fit in the result display. May be called asynchronously from non-UI thread. Returns zero if
     * measurement hasn't completed. In a block synchronized on [mWidthLock] we return the rounded
     * down [Int] value of [mWidthConstraint] (our total width in pixels minus space for ellipsis)
     * divided by [mCharWidth] (maximum character width).
     *
     * @return the maximum number of characters that will fit in the result display.
     */
    override fun maxCharsGet(): Int {
        synchronized(mWidthLock) {
            return floor((mWidthConstraint / mCharWidth).toDouble()).toInt()
        }
    }

    /**
     * Map pixel position to digit offset. UI thread only. We just return the rounded division of
     * [pos] by [mCharWidth].
     *
     * @param pos pixel position we are interested in.
     * @return the character position which occupies pixel position [pos]
     */
    internal fun getCharOffset(pos: Int): Int {
        return (pos / mCharWidth).roundToInt()  // Lock not needed.
    }

    /**
     * Clears our text contents and state variables to reflect a cleared state. We set our field
     * [mValid] to *false* (we do not hold a valid number), set [isScrollable] to *false* (we do not
     * hold a scrollable number), set our text to the empty string, and set our long clickable state
     * to *false*.
     */
    internal fun clear() {
        mValid = false
        isScrollable = false
        text = ""
        isLongClickable = false
    }

    /**
     * Part of the `EvaluationListener` interface. Called if evaluation was explicitly cancelled or
     * evaluation timed out. We call our [clear] method to "clear" our contents, then set our
     * [mStoreToMemoryRequested] field to *false* (cancelling the possible request by the user to
     * store our result to "memory").
     *
     * @param index Index of expression that has been cancelled (UNUSED)
     */
    override fun onCancelled(index: Long) {
        clear()
        mStoreToMemoryRequested = false
    }

    /**
     * Part of the `EvaluationListener` interface. Refresh display. Only called in UI thread. Index
     * [index] argument is currently ignored. We just call our [redisplay] method to redisplay the
     * current value of the expression we hold.
     *
     * @param index the index of the expression (UNUSED)
     */
    override fun onReevaluate(index: Long) {
        redisplay()
    }

    /**
     * Redisplay the result of evaluating our current expression. We initialize our variable
     * `maxCharsLocal` to the maximum number of characters that will fit in the result display.
     * If this is less than 4 we just return to avoid crashing. If [mScroller] is finished scrolling
     * and the length of the text managed by this `TextView` is greater than 0 we set the live region
     * mode for this view to ACCESSIBILITY_LIVE_REGION_POLITE (the accessibility services should
     * announce changes to this view). We initialize our variable `currentCharOffset` to the
     * character offset occupied by [mCurrentPos] (Position of right of display relative to decimal
     * point). We initialize our variable `lastDisplayedOffset` to an [Int] array of size 1. Then
     * we initialize our variable `result` to the string returned by our method [getFormattedResult]
     * when it has our [mEvaluator] evaluate our expression using `currentCharOffset` as the position
     * of the last included digit, using `maxCharsLocal` as the maximum number of characters, and
     * `lastDisplayedOffset` to hold the actual offset of last included digit. We also pass it
     * [mAppendExponent] to have it preserve entire the result if it is *true*, if [mWholePartFits]
     * is *false* (the whole result does not fit our display) and `currentCharOffset` is equal to the
     * character offset of [mMinPos] we have it force scientific notation, and we have it insert
     * commas as digit separators if [mWholePartFits] is *true*. We initialize our variable `expIndex`
     * to the index of the 'E' scientific notation character in `result` (or -1 if none is found),
     * then set `result` to the the string that the [KeyMaps.translateResult] method returns when it
     * localizes `result`. If `expIndex` is greater than 0, and there is no '.' character in `result`
     * the exponent is being used as a position indicator in a result that needs to be scrolled to
     * view the less significant digits so we want to gray it out. To do this we initialize our
     * variable `formattedResult` to a [SpannableString] constructed from `result`, then set the span
     * from `expIndex` to the end of `result` to use [mExponentColorSpan] as its [ForegroundColorSpan].
     * We then set our `text` to `formattedResult`. If it was not necessary to gray our the exponent
     * we just set our `text` to `result`.
     *
     * To finish up we set our field [mLastDisplayedOffset] to the zeroth entry in `lastDisplayedOffset`
     * that was determined by [getFormattedResult], set [mValid] to *true* and `isLongClickable` to
     * *true*.
     */
    fun redisplay() {
        val maxCharsLocal = maxCharsGet()
        if (maxCharsLocal < 4) {
            // Display currently too small to display a reasonable result. Punt to avoid crash.
            return
        }
        if (mScroller.isFinished && length() > 0) {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val currentCharOffset = getCharOffset(mCurrentPos)
        val lastDisplayedOffset = IntArray(1)
        var result = getFormattedResult(currentCharOffset, maxCharsLocal, lastDisplayedOffset,
                mAppendExponent /* forcePrecision; preserve entire result */,
                !mWholePartFits && currentCharOffset == getCharOffset(mMinPos) /* forceSciNotation */,
                mWholePartFits /* insertCommas */)
        val expIndex = result.indexOf('E')
        result = KeyMaps.translateResult(result)
        if (expIndex > 0 && result.indexOf('.') == -1) {
            // Gray out exponent if used as position indicator
            val formattedResult = SpannableString(result)
            formattedResult.setSpan(mExponentColorSpan,
                    expIndex, result.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = formattedResult
        } else {
            text = result
        }
        mLastDisplayedOffset = lastDisplayedOffset[0]
        mValid = true
        isLongClickable = true
    }

    /**
     * This method is called when the text is changed, in case any subclasses would like to know
     * Within [text], the [lengthAfter] characters beginning at [start] have just replaced old text
     * that had length [lengthBefore]. First we call our super's implementation of `onTextChanged`.
     * Then if [isScrollable] is *false* (result fits without scrolling) or [mScroller] has finished
     * scrolling we branch on whether [lengthBefore] was 0, and [lengthAfter] is greater than 0:
     * - The text has changed from empty to non-empty: we set our `accessibilityLiveRegion` property
     * (live region mode for this view) to ACCESSIBILITY_LIVE_REGION_POLITE (accessibility services
     * should announce changes to this view), and set our `contentDescription` to *null*
     * - The text has changed from non-empty to empty: we set our `accessibilityLiveRegion` property
     * to ACCESSIBILITY_LIVE_REGION_NONE (accessibility services should not automatically announce
     * changes to this view), and set our `contentDescription` to the string "No result".
     *
     * @param text The text the TextView is displaying
     * @param start The offset of the start of the range of the text that was modified
     * @param lengthBefore The length of the former text that has been replaced
     * @param lengthAfter The length of the replacement modified text
     */
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

    /**
     * Called by a parent to request that a child update its values for `mScrollX` and `mScrollY` if
     * necessary. If [isScrollable] is *false* (our text fits the display without scrolling) we just
     * return having done nothing. If the `computeScrollOffset` method of [mScroller] returns *true*
     * (the animation is not yet finished), we set [mCurrentPos] to the `currX` field of [mScroller],
     * and if the character offset of [mCurrentPos] is not equal to the character offset of [mLastPos]
     * (the new position is different than that already reflected in the display), we set [mLastPos]
     * to [mCurrentPos] and call our [redisplay] method to update our display. If [mScroller] has not
     * finished scrolling we call the [postInvalidateOnAnimation] method to cause an invalidate to
     * happen on the next animation time step and set our `accessibilityLiveRegion` property (live
     * region mode for this view) to ACCESSIBILITY_LIVE_REGION_NONE (accessibility services should
     * not automatically announce changes to this view), otherwise if the length of our text is greater
     * than 0 we set our `accessibilityLiveRegion` property to ACCESSIBILITY_LIVE_REGION_POLITE
     * (accessibility services should announce changes to this view).
     */
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
     * Sets up ActionMode for copy/memory support on M and higher, this is called from our init block
     * instead of [setupContextMenu] which is called for Android versions less than M. We just
     * initialize [mCopyActionModeCallback] with an anonymous [ActionMode.Callback2] whose overrides
     * of `onCreateActionMode`, `onPrepareActionMode`, `onActionItemClicked`, `onDestroyActionMode`,
     * and `onGetContentRect` do what needs to be done for copy/memory actions performed on our
     * contents, then set our `OnLongClickListener` to a lambda which starts the action mode.
     */
    @TargetApi(Build.VERSION_CODES.N)
    private fun setupActionMode() {
        mCopyActionModeCallback = object : ActionMode.Callback2() {

            /**
             * Called when action mode is first created. The menu supplied will be used to generate
             * action buttons for the action mode. We initialize our variable `inflater` with a
             * [MenuInflater] with the `ActionMode` [mode]'s context, then return the value returned
             * by our [createContextMenu] method when it uses `inflater` to inflate our menu layout
             * into `menu`.
             *
             * @param mode ActionMode being created
             * @param menu Menu used to populate action buttons
             * @return true if the action mode should be created, false if entering this mode should
             * be aborted.
             */
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                val inflater = mode.menuInflater
                return createContextMenu(inflater, menu)
            }

            /**
             * Called to refresh an action mode's action menu whenever it is invalidated. We just
             * return *false* to indicate that we did nothing to the `menu`.
             *
             * @param mode ActionMode being prepared
             * @param menu Menu used to populate action buttons
             * @return true if the menu or action mode was updated, false otherwise.
             */
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                return false // Return false if nothing is done
            }

            /**
             * Called to report a user click on an action button. If our method [onMenuItemClick]
             * returns *true* to indicate that the [item] clicked was one that it handled (one of
             * R.id.memory_add, R.id.memory_subtract, R.id.memory_store, or a R.id.menu_copy while
             * our [mEvaluator] is not currently reevaluating our expression) we call the `finish`
             * method of [mode] to finish it and return *true* to the caller to indicate that we
             * handled the event, otherwise we just return *false* to allow it to deal with the
             * event as it sees fit.
             *
             * @param mode The current ActionMode
             * @param item The item that was clicked
             * @return *true* if this callback handled the event, *false* if the standard [MenuItem]
             * invocation should continue.
             */
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return if (onMenuItemClick(item)) {
                    mode.finish()
                    true
                } else {
                    false
                }
            }

            /**
             * Called when an action mode is about to be exited and destroyed. We call our method
             * [unhighlightResult] to remove the highlighting span from our [Spannable] text, and
             * set our field [mActionMode] to *null*.
             *
             * @param mode The current ActionMode being destroyed
             */
            override fun onDestroyActionMode(mode: ActionMode) {
                unhighlightResult()
                mActionMode = null
            }

            /**
             * Called when an ActionMode needs to be positioned on screen, potentially occluding view
             * content. Note this may be called on a per-frame basis. First we call our super's
             * implementation of [onGetContentRect] (this sets the left and top of [outRect] to 0,
             * the right to the width of [view] and the bottom to the height of [view], ie. [outRect]
             * starts out surrounding the entire [view]). We add the left padding of [view] to the
             * left side of [outRect], the top padding to the top side, and subtract the right padding
             * of [view] from the right side of [outRect] and the bottom padding from the bottom side
             * (narrowing in [outRect] to the area which is not occupied by empty padding). We then
             * initialize our variable `width` to the width that our layout must be in order to
             * display our `text` using our [TextPaint] `paint` (using one line per paragraph). Then
             * if `width` is less than the width of [outRect], we set the left size of [outRect] to
             * the right side of [outRect] minus `width` (this tends to center our text underneath
             * the `view`0.
             *
             * If the device we are running on is older than Android N we have to compensate for the
             * fact that it does not take scaling into account when positioning the CAB. To do this
             * we initialize our variable `scaleX` to the `scaleX` of [view], and `scaleY` to the
             * `scaleY` of [view]. We then multiply the left and right coordinates of [outRect] by
             * `scaleX`, and the top and bottom coordiantes by `scaleY`.
             *
             * @param mode The [ActionMode] that requires positioning.
             * @param view The [View] that originated the [ActionMode], in whose coordinates the
             * [Rect] should be provided.
             * @param outRect The [Rect] to be populated with the content position. Use this to
             * specify where the content in your app lives within the given view. This will be used
             * to avoid occluding the given content [Rect] with the created [ActionMode].
             */
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
        // The lambda of this `OnLongClickListener` will if `mValid` is *true* set `mActionMode` to
        // the `ActionMode` that is returned by the `startActionMode` method for the `Callback`
        // `mCopyActionModeCallback` with the type TYPE_FLOATING, then return *true* to the caller
        // to consume the long click. If `mValid` is *false* it just returns *false*
        setOnLongClickListener(OnLongClickListener {
            if (mValid) {
                mActionMode = startActionMode(mCopyActionModeCallback, ActionMode.TYPE_FLOATING)
                return@OnLongClickListener true
            }
            false
        })
    }

    /**
     * Sets up [ContextMenu] for copy/memory support on L and lower. First we register a lambda as a
     * `OnCreateContextMenuListener` which initializes a variable `inflater` with a [MenuInflater]
     * constructed for our view's context when calling our [createContextMenu] method to inflate
     * and configure the `contextMenu` menu that is passed it as a parameter. It then sets our field
     * [mContextMenu] to this `contextMenu`, then loops through all the items in `contextMenu` setting
     * their `OnMenuItemClickListener` to *this* [CalculatorResult].
     *
     * Finally we set our `OnLongClickListener` to a lambda which calls the [showContextMenu] method
     * to show our context menu (returning the [Boolean] it returns) or returns *false*.
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

    /**
     * Inflate our menu xml file into the [Menu] passed it and enables or disables items in it if
     * need be. We use our parameter [inflater] to inflate our menu R.menu.menu_result into our
     * parameter [menu]. We initialize our variable `displayMemory` to *true* if the index of the
     * expression in our "memory" is not 0 (0 denotes an empty memory). We initialize our variable
     * `memoryAddItem` with the [MenuItem] with id R.id.memory_add, and `memorySubtractItem` with
     * the [MenuItem] with id R.id.memory_subtract. We enable `memoryAddItem` and `memorySubtractItem`
     * only if `displayMemory` is true. We then call our method [highlightResult] to highlight the
     * text of our result and return *true* to the caller.
     *
     * @param inflater [MenuInflater] to use to inflate our menu xml file.
     * @param menu [Menu] to fill and configure for use as a context menu.
     * @return always returns *true*, which is returned to the caller of `onCreateActionMode`
     */
    private fun createContextMenu(inflater: MenuInflater, menu: Menu): Boolean {
        inflater.inflate(R.menu.menu_result, menu)
        val displayMemory = mEvaluator!!.memoryIndexGet() != 0L
        val memoryAddItem = menu.findItem(R.id.memory_add)
        val memorySubtractItem = menu.findItem(R.id.memory_subtract)
        memoryAddItem.isEnabled = displayMemory
        memorySubtractItem.isEnabled = displayMemory
        highlightResult()
        return true
    }

    /**
     * Stops any action mode or context menu which might be in progress. If our field [mActionMode]
     * is not *null* we call its `finish` method to finish and close the action mode (the action
     * [ActionMode.Callback] will have its `onDestroyActionMode(ActionMode)` method called) then
     * return *true* to the caller. If our field [mContextMenu] is not *null* we call our method
     * [unhighlightResult] to remove the highlighting from our text, all the `close` method of
     * [mContextMenu] to close the menu if it is open, then return *true* to the caller. If both
     * of these fields are *null* we return false to the caller.
     *
     * @return *true* if an action mode or context menu has been stopped, *false* if there was none
     * to stop.
     */
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

    /**
     * Highlights our text when it has been selected for copying. We initialize our variable `text`
     * by casting the `text` of our `TextView` to a [Spannable]. We then attach the [mHighlightSpan]
     * markup object to `text` starting from the zeroth character to the length of `text` using the
     * flag SPAN_EXCLUSIVE_EXCLUSIVE (do not expand to include text inserted at either their starting
     * or ending point).
     */
    private fun highlightResult() {
        val text = text as Spannable
        text.setSpan(mHighlightSpan, 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * Removes the highlighting from our `text`. We initialize our variable `text` by casting the
     * `text` of our `TextView` to a [Spannable], then call its `removeSpan` method to remove the
     * [mHighlightSpan] markup object from the range of text to which it was attached, if any.
     */
    private fun unhighlightResult() {
        val text = text as Spannable
        text.removeSpan(mHighlightSpan)
    }

    /**
     * Sets current primary clip property of the clipboard to [clip] (this is the clip that is
     * involved in normal cut and paste operations). We initialize our variable `clipboard` to the
     * system level service [ClipboardManager], then set its `primaryClip` property to [clip].
     */
    @Suppress("unused")
    private fun setPrimaryClip(clip: ClipData) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip = clip
    }

    /**
     * Copies our expression to the clipboard. First we initialize our variable `text` to the string
     * returned when we access our [fullCopyText] property for our expression (entire result up to the
     * current displayed precision, or up to MAX_COPY_EXTRA additional digits, if it will lead to an
     * exact result). Then we initialize our variable `clipboard` to the system level [ClipboardManager]
     * service. We initialize our variable `newItem` to a [ClipData.Item] constructed from `text`
     * with a *null* `Intent`, and the `Uri` created for the expression with index [mIndex] by the
     * `capture` method of [mEvaluator]. We initialize our variable `mimeTypes` to an array containing
     * the single entry [ClipDescription.MIMETYPE_TEXT_PLAIN] (MIME type for a clip holding plain text).
     * We then initialize our variable `cd` to a [ClipData] constructed using the label "calculator
     * result" for display to the user, the MIME type `mimeTypes` and `newItem` as the [ClipData.Item]
     * contents for the first item in the clip. We then set the `primaryClip` property of `clipboard`
     * to `cd`. Finally we toast the message "Text copied".
     */
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

    /**
     * Called when a menu item has been invoked. This is the first code that is executed; if it
     * returns true, no other callbacks will be executed. We branch on the `itemId` of [item]:
     * - R.id.memory_add: we call our method [onMemoryAdd] to add the result to the value currently
     * in memory and return true to the caller.
     * - R.id.memory_subtract: we call our method [onMemorySubtract] to subtract the result from the
     * value currently in memory and return true to the caller.
     * - R.id.memory_store: we call our method [onMemoryStore] to store the result for our expression
     * if it is available and return true to the caller.
     * - R.id.menu_copy: If the `evaluationInProgress` method of [mEvaluator] returns *true* to
     * indicate that a reevaluation is still in progress we return *false* to refuse to copy
     * placeholder characters to the clipboard, otherwise we call our method [copyContent] to copy
     * our expression to the clipboard, call our method [unhighlightResult] to remove the highlight
     * from our text and return *true* to the caller.
     * - For all other menu item id's we return *false* to the caller.
     *
     * @param item The menu item that was invoked.
     * @return Return true to consume this click and prevent others from executing.
     */
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

    /**
     * This is called when the view is detached from a window. At this point it no longer has a
     * surface for drawing. We call our [stopActionModeOrContextMenu] to cancel any action mode or
     * context menu which might be displayed, then call our super's implementation of
     * [onDetachedFromWindow].
     */
    override fun onDetachedFromWindow() {
        stopActionModeOrContextMenu()
        super.onDetachedFromWindow()
    }

    /**
     * Our static constants and static methods.
     */
    companion object {
        /**
         * Maximum value our result can be scrolled to the right.
         */
        internal const val MAX_RIGHT_SCROLL = 10_000_000
        /**
         * Value for the scroll position that indicates that the current result is invalid.
         * A larger value is unlikely to avoid running out of space
         */
        internal const val INVALID = MAX_RIGHT_SCROLL + 10_000
        /**
         * Require the evaluation of our expression by our [Evaluator] (our [mEvaluator] field).
         */
        const val SHOULD_REQUIRE = 2
        /**
         * Explicitly call `evaluateAndNotify` when ready.
         */
        const val SHOULD_EVALUATE = 1
        /**
         * Explicitly request evaluation.
         */
        const val SHOULD_NOT_EVALUATE = 0

        /**
         * Maximum number of leading zeroes after decimal point before we switch to scientific
         * notation with negative exponent.
         */
        const val MAX_LEADING_ZEROES = 6
        /**
         * Maximum number of trailing zeroes before the decimal point before we switch to scientific
         * notation with positive exponent.
         */
        const val MAX_TRAILING_ZEROES = 6
        /**
         * Extra digits for standard scientific notation. In this case we have a decimal point and
         * no ellipsis. We assume that we do not drop digits to make room for the decimal point in
         * ordinary scientific notation. Thus >= 1.
         */
        private const val SCI_NOTATION_EXTRA = 1

        /**
         * The number of extra digits we are willing to compute to copy a result as an exact number.
         */
        private const val MAX_COPY_EXTRA = 100
        /**
         * The maximum number of digits we're willing to recompute in the UI thread. We only do
         * this for known rational results, where we can bound the computation cost.
         */
        private const val MAX_RECOMPUTE_DIGITS = 2_000
        /**
         * Maximum number of characters we are willing to copy for an exact result.
         */
        private const val MAX_COPY_SIZE = 1_000_000

        /**
         * Compute maximum digit width the hard way. We initialize our variable `allDigits` to a
         * string consisting of the 10 digits, then initialize our variable `widths` to a [Float]
         * array allocated to hold an entry for each of these digits. We call the `getTextWidths`
         * method of [paint] to fill `widths` with the width of each of the characters in `allDigits`.
         * Then we initalize our variable `maxWidth` to 0f, and loop through all the `x` values in
         * `widths` setting `maxWidth` to the maximum of `x`, and `maxWidth`. Finally we return
         * `maxWidth` to the caller.
         *
         * @param paint the [TextPaint] our text is being painted by.
         * @return the pixels required to paint the maximum width digit.
         */
        private fun getMaxDigitWidth(paint: TextPaint): Float {
            // Compute the maximum advance width for each digit, thus accounting for between-character
            // spaces. If we ever support other kinds of digits, we may have to avoid kerning effects
            // that could reduce the advance width within this particular string.
            val allDigits = "0123456789"
            val widths = FloatArray(allDigits.length)
            paint.getTextWidths(allDigits, widths)
            var maxWidth = 0f
            for (x in widths) {
                maxWidth = max(x, maxWidth)
            }
            return maxWidth
        }

        /**
         * Return the most significant digit position in the given string or Evaluator.INVALID_MSD.
         * Unlike Evaluator.msdIndexOfGet, we treat a final 1 as significant. Pure function; callable
         * from anywhere. We initialize our variable `len` to the length of [s], then we loop over
         * `i` from 0 until `len` setting `c` to character at index `i` in [s] and if `c` is not
         * equal to '-' and not equal to '.' and not equal to '0' we return the index `i` where we
         * found `c` to the caller. If no character in [s] passes that test we return INVALID_MSD
         * to the caller.
         *
         * @param s string containing a number whose most significant digit we are to find.
         * @return the index within [s] of the first non-zero digit character.
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

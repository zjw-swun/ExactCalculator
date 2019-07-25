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

package com.example.calculator2

import android.animation.ArgbEvaluator
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView

/**
 * Contains the logic for animating the recyclerview elements when the [HistoryFragment]
 * is dragged down onto the screen.
 */
@RequiresApi(Build.VERSION_CODES.N)
class DragController {

    // References to views from the Calculator Display.
    /**
     * The [CalculatorFormula] containing the current expression
     */
    private var mDisplayFormula: CalculatorFormula? = null
    /**
     * The [CalculatorResult] containing the current result of our current expression
     */
    private var mDisplayResult: CalculatorResult? = null
    /**
     * The `ToolBar` of the calculator.
     */
    private var mToolbar: View? = null

    /**
     * Translation in the Y direction of the formula whose drag we are animating
     */
    private var mFormulaTranslationY: Int = 0
    /**
     * Translation in the X direction of the formula whose drag we are animating
     */
    private var mFormulaTranslationX: Int = 0
    /**
     * Scale of the formula whose drag we are animating
     */
    private var mFormulaScale: Float = 0f
    /**
     * Scale of the result whose drag we are animating
     */
    private var mResultScale: Float = 0f

    /**
     * Translation in the Y direction of the result whose drag we are animating
     */
    private var mResultTranslationY: Float = 0f
    /**
     * Translation in the X direction of the result whose drag we are animating
     */
    private var mResultTranslationX: Int = 0

    /**
     * Total height of the calculator display -- that is the sum of the heights of the [mToolbar],
     * [mDisplayResult], and [mDisplayFormula]
     */
    private var mDisplayHeight: Int = 0

    /**
     * Starting color for the color change animation of the formula text
     */
    private var mFormulaStartColor: Int = 0
    /**
     * Ending color for the color change animation of the formula text
     */
    private var mFormulaEndColor: Int = 0

    /**
     * Starting color for the color change animation of the result text
     */
    private var mResultStartColor: Int = 0
    /**
     * Ending color for the color change animation of the result text
     */
    private var mResultEndColor: Int = 0

    /**
     * The padding at the bottom of the RecyclerView itself.
     */
    private var mBottomPaddingHeight: Int = 0

    /**
     * Set to *true* after all our animators have been initialized.
     */
    private var mAnimationInitialized: Boolean = false

    /**
     * Are we running on a device which uses a single line display?
     */
    private var mOneLine: Boolean = false
    /**
     * Are both of the formula and result text views empty.
     */
    private var mIsDisplayEmpty: Boolean = false

    /**
     * The [AnimationController] which is animating the calculator display, it is an instance of
     * [EmptyAnimationController] if the display is empty, [ResultAnimationController] if the
     * display contains a result, or just an [AnimationController] if there is something in the
     * formula field (but not necessarily a result).
     */
    private var mAnimationController: AnimationController? = null

    /**
     * The [Evaluator] that the [HistoryFragment] is using (this does not seem to be used by us?)
     */
    private var mEvaluator: Evaluator? = null

    /**
     * Setter for our [mEvaluator] field.
     *
     * @param evaluator the [Evaluator] that the [HistoryFragment] is using
     */
    fun setEvaluator(evaluator: Evaluator) {
        mEvaluator = evaluator
    }

    /**
     * Called from the [HistoryFragment] when it is created (drug down onto the screen) to initialize
     * the animations we are to control, also called from our [initializeAnimation] method to reset
     * all initialized values. First we save our parameter [oneLine] in our field [mOneLine], and
     * our parameter [isDisplayEmpty] in our field [mIsDisplayEmpty]. Then when [mIsDisplayEmpty]
     * is *true* we initialize our field [mAnimationController] with a new instance of
     * [EmptyAnimationController], and if [isResult] is *true* we initialize it with a new instance
     * of [ResultAnimationController]. Otherwise we know that there is something in the formula field
     * (although there may not be a result) so we initialize [mAnimationController] to a new instance
     * of [AnimationController].
     *
     * @param isResult *true* if the display is in the RESULT state.
     * @param oneLine *true* if the device needs to use the one line layout.
     * @param isDisplayEmpty *true* if the calculator display is cleared (no result or formula)
     */
    fun initializeController(isResult: Boolean, oneLine: Boolean, isDisplayEmpty: Boolean) {
        mOneLine = oneLine
        mIsDisplayEmpty = isDisplayEmpty
        when {
            mIsDisplayEmpty -> // Empty display
                mAnimationController = EmptyAnimationController()
            isResult -> // Result
                mAnimationController = ResultAnimationController()
            else -> // There is something in the formula field. There may or may not be
                // a quick result.
                mAnimationController = AnimationController()
        }
    }

    /**
     * Setter for our [mDisplayFormula] field.
     *
     * @param formula the [CalculatorFormula] `AlignedTextView` the calculator is displaying.
     */
    fun setDisplayFormula(formula: CalculatorFormula) {
        mDisplayFormula = formula
    }

    /**
     * Setter for our [mDisplayResult] field.
     *
     * @param result the [CalculatorResult] `AlignedTextView` the calculator is displaying.
     */
    fun setDisplayResult(result: CalculatorResult) {
        mDisplayResult = result
    }

    /**
     * Setter for our [mToolbar] field.
     *
     * @param toolbar the `Toolbar` of the calculator display.
     */
    fun setToolbar(toolbar: View) {
        mToolbar = toolbar
    }

    /**
     * Called to animate the [recyclerView] `RecyclerView` to the state it should be in when
     * [yFraction] of the view is visible. If any of our fields [mDisplayFormula], [mDisplayResult],
     * [mToolbar], or [mEvaluator] are still *null* we have not yet been initialized so we just
     * return having done nothing. Otherwise we initialize our variable `vh` to the `ViewHolder`
     * occupying position 0 in [recyclerView], and if [yFraction] is greater than 0, and `vh` is
     * not *null* we set the visibility of [recyclerView] to VISIBLE. Then if `vh` is not *null*
     * and [mIsDisplayEmpty] is *false* (the calculator is displaying something) and the item view
     * type of `vh` is HISTORY_VIEW_TYPE we want to animate the calculator display contents into
     * [recyclerView] and to do this we:
     * - Initialize our variable `formula` to the `formula` field of `vh`, `result` to the `result`
     * field, `date` to the `date` field and `divider` to the `divider` field.
     * - If our [mAnimationInitialized] field is *false* (this is the first time we have been called
     * to animate [recyclerView] onto the screen) we set our field [mBottomPaddingHeight] to the
     * bottom padding of [recyclerView], call all the appropriate initialization methods of our
     * [mAnimationController] field and set [mAnimationInitialized] to *true*.
     * - We then update all the properties that we are animating for the current value of [yFraction]
     * for each of the views `result`, `formula`, `date` and `divider`.
     *
     * On the otherhand is [mIsDisplayEmpty] is *true* (there is no current expression) we still need
     * to collect information to translate the other `ViewHolder`'s so if [mAnimationInitialized] is
     * *false* we call the `initializeDisplayHeight` method of [mAnimationController] to have it
     * initialize itself for the height of the calculator display and set [mAnimationInitialized] to
     * *true*.
     *
     * Having dealt with the possible animation of the calculator display contents, we now need to
     * move up all the `ViewHolder`'s above the current expression (if there is no current expression,
     * we're translating all the `ViewHolder`'s). To do this we loop over `i` from the last child
     * of [recyclerView] to the lowest index of the first ViewHolder to be translated upwards (which
     * is 1 for both an [ResultAnimationController] and [AnimationController], and 0 for an
     * [EmptyAnimationController]):
     * - We initialize our variable `vh2` to the view holder of the child view at index `i` in
     * [recyclerView]
     * - Then if `vh2` is not *null* we initialize our variable `view` to the `itemView` field of
     * `vh2` and update its `translationY` property to the translation determined for [yFraction]
     * by the `getHistoryElementTranslationY` method of [mAnimationController].
     *
     * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
     * @param recyclerView the [RecyclerView] whose animation we are controlling.
     */
    fun animateViews(yFraction: Float, recyclerView: RecyclerView) {
        if (mDisplayFormula == null
                || mDisplayResult == null
                || mToolbar == null
                || mEvaluator == null) {
            // Bail if we aren't yet initialized.
            return
        }

        val vh = recyclerView.findViewHolderForAdapterPosition(0) as HistoryAdapter.ViewHolder?
        if (yFraction > 0 && vh != null) {
            recyclerView.visibility = View.VISIBLE
        }
        if (vh != null && !mIsDisplayEmpty
                && vh.itemViewType == HistoryAdapter.HISTORY_VIEW_TYPE) {
            val formula = vh.formula!!
            val result = vh.result!!
            val date = vh.date!!
            val divider = vh.divider!!

            if (!mAnimationInitialized) {
                mBottomPaddingHeight = recyclerView.paddingBottom

                mAnimationController!!.initializeScales(formula, result)

                mAnimationController!!.initializeColorAnimators(formula, result)

                mAnimationController!!.initializeFormulaTranslationX(formula)

                mAnimationController!!.initializeFormulaTranslationY(formula, result)

                mAnimationController!!.initializeResultTranslationX(result)

                mAnimationController!!.initializeResultTranslationY(result)

                mAnimationInitialized = true
            }

            result.scaleX = mAnimationController!!.getResultScale(yFraction)
            result.scaleY = mAnimationController!!.getResultScale(yFraction)

            formula.scaleX = mAnimationController!!.getFormulaScale(yFraction)
            formula.scaleY = mAnimationController!!.getFormulaScale(yFraction)

            formula.pivotX = (formula.width - formula.paddingEnd).toFloat()
            formula.pivotY = (formula.height - formula.paddingBottom).toFloat()

            result.pivotX = (result.width - result.paddingEnd).toFloat()
            result.pivotY = (result.height - result.paddingBottom).toFloat()

            formula.translationX = mAnimationController!!.getFormulaTranslationX(yFraction)
            formula.translationY = mAnimationController!!.getFormulaTranslationY(yFraction)

            result.translationX = mAnimationController!!.getResultTranslationX(yFraction)
            result.translationY = mAnimationController!!.getResultTranslationY(yFraction)

            formula.setTextColor(mColorEvaluator.evaluate(yFraction, mFormulaStartColor,
                    mFormulaEndColor) as Int)

            result.setTextColor(mColorEvaluator.evaluate(yFraction, mResultStartColor,
                    mResultEndColor) as Int)

            date.translationY = mAnimationController!!.getDateTranslationY(yFraction)
            divider.translationY = mAnimationController!!.getDateTranslationY(yFraction)
        } else if (mIsDisplayEmpty) {
            // There is no current expression but we still need to collect information
            // to translate the other ViewHolder's.
            if (!mAnimationInitialized) {
                mAnimationController!!.initializeDisplayHeight()
                mAnimationInitialized = true
            }
        }

        // Move up all ViewHolders above the current expression; if there is no current expression,
        // we're translating all the ViewHolder's.
        for (i in recyclerView.childCount - 1 downTo mAnimationController!!.firstTranslatedViewHolderIndex) {
            val vh2 = recyclerView.getChildViewHolder(recyclerView.getChildAt(i))
            if (vh2 != null) {
                val view = vh2.itemView

                view.translationY = mAnimationController!!.getHistoryElementTranslationY(yFraction)
            }
        }
    }

    /**
     * Reset all initialized values, called from the `onStart` override of [HistoryFragment] to
     * initialize us to the state we should be in when the [HistoryFragment] is first visible to
     * the user. We set our field [mAnimationInitialized] to *false* then call our method
     * [initializeController] with our parameters to have it initialize the animations we are to
     * control to their starting state.
     *
     * @param isResult if *true* the calculator is displaying only a result ('=' has been pressed).
     * @param oneLine if *true* the display is just one line.
     * @param isDisplayEmpty if *true* the display is cleared of both formula and result.
     */
    fun initializeAnimation(isResult: Boolean, oneLine: Boolean, isDisplayEmpty: Boolean) {
        mAnimationInitialized = false
        initializeController(isResult, oneLine, isDisplayEmpty)
    }

    /**
     * Interface that each of our classes [AnimationController], [EmptyAnimationController], and
     * [ResultAnimationController] implement so that our [mAnimationController] field can be used to
     * call the methods needed for our animations.
     */
    interface AnimateTextInterface {

        /**
         * Return the lowest index of the first ViewHolder to be translated upwards. If there is no
         * current expression, we translate all the ViewHolder's starting at 0, otherwise we start
         * at index 1 (ie. [AnimationController], and [ResultAnimationController] override this to
         * return 1, and [EmptyAnimationController] overrides it to return 0).
         */
        val firstTranslatedViewHolderIndex: Int

        /**
         * Implement this if your implementation of this interface needs to do something special to
         * initialize the [mDisplayHeight] field. It is called from our [animateViews] method iff
         * [mIsDisplayEmpty] is *true* (the calculator display has been cleared).
         */
        fun initializeDisplayHeight()

        /**
         * This is called to initialize the color animation values for the formula view of the view
         * holder that needs animating ([mFormulaStartColor] and [mFormulaEndColor]) and for the
         * result view of that same view holder ([mResultStartColor] and [mResultEndColor]). It is
         * implemented by the [AnimationController] class, and is called from the [animateViews]
         * method.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         * @param result the [CalculatorResult] containing the result to be animated
         */
        fun initializeColorAnimators(formula: AlignedTextView, result: CalculatorResult)

        /**
         * Implement this to initialize the fields [mFormulaScale] and [mResultScale] which are used
         * to animate the scaling of the calculator display as it is "moved" into the history view.
         * It is implemented by the [AnimationController] class which only scales [mFormulaScale],
         * the [ResultAnimationController] class which scales both text views, and is a no-op in
         * the [EmptyAnimationController] class. It is called from the [animateViews] method.
         * method.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         * @param result the [CalculatorResult] containing the result to be animated
         */
        fun initializeScales(formula: AlignedTextView, result: CalculatorResult)

        /**
         * Implement this to initialize the field [mFormulaTranslationX]. It is implemented by the
         * [AnimationController] class where it moves the right border of the formula by the
         * difference in formula end padding, by the [ResultAnimationController] class where is does
         * the same, and is a no-op in the [EmptyAnimationController] class. It is called from the
         * [animateViews] method.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         */
        fun initializeFormulaTranslationX(formula: AlignedTextView)

        /**
         * Implement this to initialize the field [mFormulaTranslationY]. It is implemented by the
         * [AnimationController] class where it handles the case when the calculator display is in
         * the INPUT state, by the [ResultAnimationController] class when the calculator display is
         * in the RESULT state and the baseline of the formula moves by the difference in formula
         * bottom padding and the difference in the result height, and is a no-op in the
         * [EmptyAnimationController] class. It is called from the [animateViews] method.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         * @param result the [CalculatorResult] containing the result to be animated
         */
        fun initializeFormulaTranslationY(formula: AlignedTextView, result: CalculatorResult)

        /**
         * Implement this to initialize the field [mResultTranslationX]. It is implemented by the
         * [AnimationController] class where it handles the case when the calculator display is in
         * the INPUT state, by the [ResultAnimationController] class when the calculator display is
         * in the RESULT state, and is a no-op in the [EmptyAnimationController] class. It is called
         * from the [animateViews] method.
         *
         * @param result the [CalculatorResult] containing the result to be animated
         */
        fun initializeResultTranslationX(result: CalculatorResult)

        /**
         * Implement this to initialize the field [mResultTranslationY]. It is implemented by the
         * [AnimationController] class where it handles the case when the calculator display is in
         * the INPUT state, by the [ResultAnimationController] class when the calculator display is
         * in the RESULT state, and is a no-op in the [EmptyAnimationController] class. It is called
         * from the [animateViews] method.
         *
         * @param result the [CalculatorResult] containing the result to be animated
         */
        fun initializeResultTranslationY(result: CalculatorResult)

        /**
         * Implement this to return a X translation value for the result view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. It is
         * implemented by the [AnimationController] class where it handles the case when the
         * calculator display is in the INPUT state, by the [ResultAnimationController] class when
         * the calculator display is in the RESULT state, and returns 0.0f in the
         * [EmptyAnimationController] class. It is called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to X translate the result view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        fun getResultTranslationX(yFraction: Float): Float

        /**
         * Implement this to return a Y translation value for the result view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. It is
         * implemented by the [AnimationController] class where it handles the case when the
         * calculator display is in the INPUT state, by the [ResultAnimationController] class when
         * the calculator display is in the RESULT state, and returns 0.0f in the
         * [EmptyAnimationController] class. It is called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the result view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        fun getResultTranslationY(yFraction: Float): Float

        /**
         * Implement this to return a X and Y scale value for the result view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. It is
         * implemented by the [AnimationController] class where it handles the case when the
         * calculator display is in the INPUT state (returns 1.0f), by the [ResultAnimationController]
         * class when the calculator display is in the RESULT state, and is not overridden by the
         * [EmptyAnimationController] class. It is called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return amount to scale the result view as it moves up into the history pulldown given
         * the fraction of the history pulldown that is visible.
         */
        fun getResultScale(yFraction: Float): Float

        /**
         * Implement this to return a X and Y scale value for the formula view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. It is
         * implemented by the [AnimationController] class where it handles the case when the
         * calculator display is in the INPUT state, by the [ResultAnimationController] class when
         * the calculator display is in the RESULT state, and returns 1.0f in the
         * [EmptyAnimationController] class. It is called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return amount to scale the formula view as it moves up into the history pulldown given
         * the fraction of the history pulldown that is visible.
         */
        fun getFormulaScale(yFraction: Float): Float

        /**
         * Implement this to return a X translation value for the formula view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. It is
         * implemented by the [AnimationController] class where it handles the case when the
         * calculator display is in the INPUT state, by the [ResultAnimationController] class when
         * the calculator display is in the RESULT state, and is not overridden by the
         * [EmptyAnimationController] class. It is called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to X translate the formula view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        fun getFormulaTranslationX(yFraction: Float): Float

        /**
         * Implement this to return a Y translation value for the formula view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. It is
         * implemented by the [AnimationController] class where it handles the case when the
         * calculator display is in the INPUT state, by the [ResultAnimationController] class when
         * the calculator display is in the RESULT state, and is not overridden by
         * [EmptyAnimationController] class. It is called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the formula view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        fun getFormulaTranslationY(yFraction: Float): Float

        /**
         * Implement this to return a Y translation value for the date view of the view holder
         * given the fraction [yFraction] of the history pull down that is visible. It is
         * implemented by the [AnimationController] class where it handles the case when the
         * calculator display is in the INPUT state, by the [ResultAnimationController] class when
         * the calculator display is in the RESULT state, and returns 0.0f
         * [EmptyAnimationController] class. It is called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the date view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        fun getDateTranslationY(yFraction: Float): Float

        /**
         * Implement this to return a Y translation value for the `itemView` view of a view holder
         * held by the history [RecyclerView] given the fraction [yFraction] of the history pull
         * down that is visible. It is implemented by the [AnimationController] class where it handles
         * the case when the calculator display is in the INPUT state, is not overridden by the
         * [ResultAnimationController] class, and is implemented by the [EmptyAnimationController]
         * when the display is completely empty. It is called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the history view holders given the fraction
         * [yFraction] of the history pulldown that is visible.
         */
        fun getHistoryElementTranslationY(yFraction: Float): Float
    }

    /**
     * The default [AnimationController] when the calculator Display is in the INPUT state and
     * the formula view is not empty. There may or may not be a quick result.
     */
    open inner class AnimationController : AnimateTextInterface {

        /**
         * Returns the lowest index of the first ViewHolder to be translated upwards, 1 in our case
         * since there is a current expression in index 0 that is identical to the calculator display
         * contents which is animated separately. Our [animateViews] method uses this value as a
         * limit for the *for* loop which translates the view holders.
         */
        override val firstTranslatedViewHolderIndex: Int
            get() = 1

        /**
         * This is called from the [animateViews] method just in case it is necessary to initialize
         * the [mDisplayHeight] field, and it is only necessary for the [EmptyAnimationController]
         * class which overrides this no-op implementation.
         */
        override fun initializeDisplayHeight() {
            // no-op
        }

        /**
         * This is called to initialize the color animation values for the formula view of the view
         * holder that needs animating ([mFormulaStartColor] and [mFormulaEndColor]) and for the
         * result view of that same view holder ([mResultStartColor] and [mResultEndColor]). We just
         * set [mFormulaStartColor] to the current text color of [mDisplayFormula], [mFormulaEndColor]
         * to the current text color of [formula], [mResultStartColor] to the current text color of
         * [mDisplayFormula], and [mResultEndColor] to the current text color of [result]. We are
         * called by the [animateViews] method.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         * @param result the [CalculatorResult] containing the result to be animated
         */
        override fun initializeColorAnimators(formula: AlignedTextView, result: CalculatorResult) {
            mFormulaStartColor = mDisplayFormula!!.currentTextColor
            mFormulaEndColor = formula.currentTextColor

            mResultStartColor = mDisplayResult!!.currentTextColor
            mResultEndColor = result.currentTextColor
        }

        /**
         * This is called to initialize the fields [mFormulaScale] and [mResultScale] which are used
         * to animate the scaling of the calculator display as it is "moved" into the history view.
         * We just initialize [mFormulaScale] to the text size of [mDisplayFormula] divided by the
         * text size of [formula]. We are called by the [animateViews] method.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         * @param result the [CalculatorResult] containing the result to be animated
         */
        override fun initializeScales(formula: AlignedTextView, result: CalculatorResult) {
            // Calculate the scale for the text
            mFormulaScale = mDisplayFormula!!.textSize / formula.textSize
        }

        /**
         * This is called to initialize the field [mFormulaTranslationY]. If [mOneLine] is *true*
         * (the calculator display has only one line) we initialize [mFormulaTranslationY] by
         * subtracting the bottom padding of [formula] and [mBottomPaddingHeight] from the
         * bottom padding of [mDisplayFormula], otherwise we also add the height of [mDisplayResult]
         * and subtract the height of [result] from the same value. We are called by the
         * [animateViews] method.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         * @param result the [CalculatorResult] containing the result to be animated
         */
        override fun initializeFormulaTranslationY(formula: AlignedTextView,
                                                   result: CalculatorResult) {
            mFormulaTranslationY = if (mOneLine) {
                // Disregard result since we set it to GONE in the one-line case.
                (mDisplayFormula!!.paddingBottom - formula.paddingBottom
                        - mBottomPaddingHeight)
            } else {
                // Baseline of formula moves by the difference in formula bottom padding and the
                // difference in result height.
                (mDisplayFormula!!.paddingBottom - formula.paddingBottom
                        + mDisplayResult!!.height - result.height - mBottomPaddingHeight)
            }
        }

        /**
         * This is called to initialize the field [mFormulaTranslationX]. We just set
         * [mFormulaTranslationX] to the padding end of [mDisplayFormula] minus the padding end
         * of [formula]. We are called by the [animateViews] method.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         */
        override fun initializeFormulaTranslationX(formula: AlignedTextView) {
            // Right border of formula moves by the difference in formula end padding.
            mFormulaTranslationX = mDisplayFormula!!.paddingEnd - formula.paddingEnd
        }

        /**
         * This is called to initialize the field [mFormulaTranslationY].  We just set
         * [mResultTranslationY] to the padding bottom of [mDisplayFormula] minus the padding
         * bottom of [result] minus the field [mBottomPaddingHeight]. We are called by the
         * [animateViews] method.
         *
         * @param result the [CalculatorResult] containing the result to be animated
         */
        override fun initializeResultTranslationY(result: CalculatorResult) {
            // Baseline of result moves by the difference in result bottom padding.
            mResultTranslationY = (mDisplayResult!!.paddingBottom - result.paddingBottom
                    - mBottomPaddingHeight).toFloat()
        }

        /**
         * This is called to initialize the field [mFormulaTranslationX].  We just set
         * [mResultTranslationX] to the padding end of [mDisplayFormula] minus the padding end
         * of [result]. We are called by the [animateViews] method.
         *
         * @param result the [CalculatorResult] containing the result to be animated
         */
        override fun initializeResultTranslationX(result: CalculatorResult) {
            mResultTranslationX = mDisplayResult!!.paddingEnd - result.paddingEnd
        }

        /**
         * This is called to calculate an X translation value for the result view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We
         * return [mResultTranslationX] times the result of subtracting 1f from [yFraction]. We are
         * called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to X translate the result view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getResultTranslationX(yFraction: Float): Float {
            return mResultTranslationX * (yFraction - 1f)
        }

        /**
         * This is called to calculate a Y translation value for the result view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We
         * return [mResultTranslationY] times the result of subtracting 1f from [yFraction]. We are
         * called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the result view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getResultTranslationY(yFraction: Float): Float {
            return mResultTranslationY * (yFraction - 1f)
        }

        /**
         * This is called to calculate a X and Y scale value for the result view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We just
         * return 1f. We are called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return amount to scale the result view as it moves up into the history pulldown given
         * the fraction of the history pulldown that is visible.
         */
        override fun getResultScale(yFraction: Float): Float {
            return 1f
        }

        /**
         * This is called to calculate a X and Y scale value for the formula view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We
         * return [mFormulaScale] plus the quantity 1f minus [mFormulaScale] times [yFraction]. We
         * are called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return amount to scale the formula view as it moves up into the history pulldown given
         * the fraction of the history pulldown that is visible.
         */
        override fun getFormulaScale(yFraction: Float): Float {
            return mFormulaScale + (1f - mFormulaScale) * yFraction
        }

        /**
         * This is called to calculate a X translation value for the formula view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We
         * return [mFormulaTranslationX] times the quantity [yFraction] minus 1f. We are called
         * from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to X translate the formula view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getFormulaTranslationX(yFraction: Float): Float {
            return mFormulaTranslationX * (yFraction - 1f)
        }

        /**
         * This is called to calculate a Y translation value for the formula view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We
         * return [mFormulaTranslationY] times the quantity [yFraction] minus 1f. We are called
         * from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the formula view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getFormulaTranslationY(yFraction: Float): Float {
            // Scale linearly between -FormulaTranslationY and 0.
            return mFormulaTranslationY * (yFraction - 1f)
        }

        /**
         * This is called to calculate a Y translation value for the date view of the view holder
         * given the fraction [yFraction] of the history pull down that is visible. We return
         * minus the height of [mToolbar] times the quantity 1f minus [yFraction] plus the value
         * returned by the [getFormulaTranslationY] method for [yFraction] minus the height of
         * [mDisplayFormula] divided by the value returned by the [getFormulaScale] method for
         * [yFraction] times the quantity 1f minus [yFraction]. We are called from the [animateViews]
         * method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the date view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getDateTranslationY(yFraction: Float): Float {
            // We also want the date to start out above the visible screen with
            // this distance decreasing as it's pulled down.
            // Account for the scaled formula height.
            return (-mToolbar!!.height * (1f - yFraction)
                    + getFormulaTranslationY(yFraction)
                    - mDisplayFormula!!.height / getFormulaScale(yFraction) * (1f - yFraction))
        }

        /**
         * This is called to calculate a Y translation value for the `itemView` view of a view holder
         * held by the history [RecyclerView] given the fraction [yFraction] of the history pull
         * down that is visible. We just return the value returned by the [getDateTranslationY]
         * method for [yFraction]. We are called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the history view holders given the fraction
         * [yFraction] of the history pulldown that is visible.
         */
        override fun getHistoryElementTranslationY(yFraction: Float): Float {
            return getDateTranslationY(yFraction)
        }
    }

    /**
     * The default [AnimationController] when the Display is in the RESULT state.
     */
    inner class ResultAnimationController : AnimationController(), AnimateTextInterface {

        /**
         * Returns the lowest index of the first ViewHolder to be translated upwards which in our
         * case is 1. (The current result in the calculator display is at position 0 which is
         * animated from the display into the [RecyclerView] separately from the view holder
         * translation).
         */
        override val firstTranslatedViewHolderIndex: Int
            get() = 1

        /**
         * This is called to initialize the fields [mFormulaScale] and [mResultScale] which are used
         * to animate the scaling of the calculator display as it is "moved" into the history view.
         * First we initialize our variable `textSize` to the text size of [mDisplayResult] times
         * the scale X of [mDisplayResult], then we set [mResultScale] to `textSize` times the
         * text size of [result]. We are called from the [animateViews] method.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         * @param result the [CalculatorResult] containing the result to be animated
         */
        override fun initializeScales(formula: AlignedTextView, result: CalculatorResult) {
            val textSize = mDisplayResult!!.textSize * mDisplayResult!!.scaleX
            mResultScale = textSize / result.textSize
            mFormulaScale = 1f
        }

        /**
         * This is called to initialize the field [mFormulaTranslationY]. We set [mFormulaTranslationY]
         * to the bottom padding of [mDisplayFormula] minus the bottom padding of [formula] plus
         * the height of [mDisplayResult] minus the height of [result] minus the [mBottomPaddingHeight]
         * field. We are called from the [animateViews] method.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         * @param result the [CalculatorResult] containing the result to be animated
         */
        override fun initializeFormulaTranslationY(formula: AlignedTextView, result: CalculatorResult) {
            // Baseline of formula moves by the difference in formula bottom padding and the
            // difference in the result height.
            mFormulaTranslationY = (mDisplayFormula!!.paddingBottom - formula.paddingBottom
                    + mDisplayResult!!.height - result.height - mBottomPaddingHeight)
        }

        /**
         * This is called to initialize the field [mFormulaTranslationX]. We just set is to the
         * padding end of [mDisplayFormula] minus the padding end of [formula]. We are called from
         * the [animateViews] method.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         */
        override fun initializeFormulaTranslationX(formula: AlignedTextView) {
            // Right border of formula moves by the difference in formula end padding.
            mFormulaTranslationX = mDisplayFormula!!.paddingEnd - formula.paddingEnd
        }

        /**
         * This is called to initialize the field [mResultTranslationY]. We set [mResultTranslationY]
         * to the padding bottom of [mDisplayResult] minus the padding bottom of [result] minus the
         * translation Y of [mDisplayResult] minus the field [mBottomPaddingHeight]. We are called
         * from the [animateViews] method.
         */
        override fun initializeResultTranslationY(result: CalculatorResult) {
            // Baseline of result moves by the difference in result bottom padding.
            mResultTranslationY = (mDisplayResult!!.paddingBottom.toFloat()
                    - result.paddingBottom.toFloat()
                    - mDisplayResult!!.translationY
                    - mBottomPaddingHeight.toFloat())
        }

        /**
         * This is called to initialize the field [mResultTranslationX]. We set [mResultTranslationX]
         * to the padding end of [mDisplayResult] minus the padding end of [result]. We are called
         * from the [animateViews] method.
         *
         * @param result the [CalculatorResult] containing the result to be animated
         */
        override fun initializeResultTranslationX(result: CalculatorResult) {
            mResultTranslationX = mDisplayResult!!.paddingEnd - result.paddingEnd
        }

        /**
         * This is called to calculate a X translation value for the result view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We
         * return [mResultTranslationX] times [yFraction] minus [mResultTranslationX]. We are called
         * from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to X translate the result view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getResultTranslationX(yFraction: Float): Float {
            return mResultTranslationX * yFraction - mResultTranslationX
        }

        /**
         * This is called to calculate a Y translation value for the result view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We
         * return [mResultTranslationY] times [yFraction] minus [mResultTranslationY]. We are called
         * from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the result view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getResultTranslationY(yFraction: Float): Float {
            return mResultTranslationY * yFraction - mResultTranslationY
        }

        /**
         * This is called to calculate a X translation value for the formula view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We
         * return [mFormulaTranslationX] times [yFraction] minus [mFormulaTranslationX]. We are
         * called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to X translate the formula view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getFormulaTranslationX(yFraction: Float): Float {
            return mFormulaTranslationX * yFraction - mFormulaTranslationX
        }

        /**
         * This is called to calculate a Y translation value for the formula view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We
         * return the value returned by the [getDateTranslationY] method for [yFraction]. We are
         * called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the formula view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getFormulaTranslationY(yFraction: Float): Float {
            return getDateTranslationY(yFraction)
        }

        /**
         * This is called to calculate a X and Y scale value for the result view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We
         * return [mResultScale] minus [mResultScale] times [yFraction] plus [yFraction]. We are
         * called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return amount to scale the result view as it moves up into the history pulldown given
         * the fraction of the history pulldown that is visible.
         */
        override fun getResultScale(yFraction: Float): Float {
            return mResultScale - mResultScale * yFraction + yFraction
        }

        /**
         * This is called to calculate a X and Y scale value for the formula view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We just
         * return 1f. We are called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return amount to scale the formula view as it moves up into the history pulldown given
         * the fraction of the history pulldown that is visible.
         */
        override fun getFormulaScale(yFraction: Float): Float {
            return 1f
        }

        /**
         * This is called to calculate a Y translation value for the date view of the view holder
         * given the fraction [yFraction] of the history pull down that is visible. We return minus
         * the height of [mToolbar] times 1f minus [yFraction] plus [mResultTranslationY] times
         * [yFraction] minus [mResultTranslationY] minus the padding top of [mDisplayFormula] plus
         * the padding top of [mDisplayFormula] times [yFraction]. We are called from the
         * [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the date view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getDateTranslationY(yFraction: Float): Float {
            // We also want the date to start out above the visible screen with
            // this distance decreasing as it's pulled down.
            return ((-mToolbar!!.height * (1f - yFraction) + mResultTranslationY * yFraction
                    - mResultTranslationY - mDisplayFormula!!.paddingTop.toFloat())
                    + mDisplayFormula!!.paddingTop * yFraction)
        }
    }

    /**
     * The default AnimationController when Display is completely empty.
     */
    inner class EmptyAnimationController : AnimationController(), AnimateTextInterface {

        /**
         * Returns the lowest index of the first ViewHolder to be translated upwards, 0 in our case
         * since there is no a current expression. Our [animateViews] method uses this value as a
         * limit for the *for* loop which translates the view holders.
         */
        override val firstTranslatedViewHolderIndex: Int
            get() = 0

        /**
         * This is called to initialize the [mDisplayHeight] field. We set [mDisplayHeight] to the
         * height of [mToolbar] plus the height of [mDisplayResult] plus the height of
         * [mDisplayFormula]. We are called from the [animateViews] method.
         */
        override fun initializeDisplayHeight() {
            mDisplayHeight = (mToolbar!!.height + mDisplayResult!!.height + mDisplayFormula!!.height)
        }

        /**
         * This is called from the [animateViews] method to initialize the fields [mFormulaScale]
         * and [mResultScale] which are used to animate the scaling of the calculator display as it
         * is "moved" into the history view. Since the calculator display is empty in our case it
         * is just a no-op.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         * @param result the [CalculatorResult] containing the result to be animated
         */
        override fun initializeScales(formula: AlignedTextView, result: CalculatorResult) {
            // no-op
        }

        /**
         * This is called to initialize the field [mFormulaTranslationY] from the [animateViews]
         * method. Since the formula view is empty it is a no-op in our case.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         * @param result the [CalculatorResult] containing the result to be animated
         */
        override fun initializeFormulaTranslationY(formula: AlignedTextView, result: CalculatorResult) {
            // no-op
        }

        /**
         * This is called to initialize the field [mFormulaTranslationX] from the [animateViews]
         * method. Since the formula view is empty it is a no-op in our case.
         *
         * @param formula the [AlignedTextView] containing the formula to be animated
         */
        override fun initializeFormulaTranslationX(formula: AlignedTextView) {
            // no-op
        }

        /**
         * This is called to initialize the field [mResultTranslationY] from the [animateViews]
         * method. Since the result view is empty it is a no-op in our case.
         *
         * @param result the [CalculatorResult] containing the result to be animated
         */
        override fun initializeResultTranslationY(result: CalculatorResult) {
            // no-op
        }

        /**
         * This is called to initialize the field [mResultTranslationX] from the [animateViews]
         * method. Since the result view is empty it is a no-op in our case.
         *
         * @param result the [CalculatorResult] containing the result to be animated
         */
        override fun initializeResultTranslationX(result: CalculatorResult) {
            // no-op
        }

        /**
         * This is called to calculate a X translation value for the result view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We just
         * return 0f. We are called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to X translate the result view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getResultTranslationX(yFraction: Float): Float {
            return 0f
        }

        /**
         * This is called to calculate a Y translation value for the result view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We just
         * return 0f. We are called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to X translate the result view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getResultTranslationY(yFraction: Float): Float {
            return 0f
        }

        /**
         * This is called to calculate a X and Y scale value for the formula view of the calculator
         * display given the fraction [yFraction] of the history pull down that is visible. We just
         * return 1f. We are called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return amount to scale the formula view as it moves up into the history pulldown given
         * the fraction of the history pulldown that is visible.
         */
        override fun getFormulaScale(yFraction: Float): Float {
            return 1f
        }

        /**
         * This is called to calculate a Y translation value for the date view of the view holder
         * given the fraction [yFraction] of the history pull down that is visible. We just return
         * 0f. We are called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the date view given the fraction [yFraction] of
         * the history pulldown that is visible.
         */
        override fun getDateTranslationY(yFraction: Float): Float {
            return 0f
        }

        /**
         * This is called to calculate a Y translation value for the `itemView` view of a view holder
         * held by the history [RecyclerView] given the fraction [yFraction] of the history pull
         * down that is visible. We return minus [mDisplayHeight] times the quantity 1f minus
         * [yFraction] all minus [mBottomPaddingHeight]. We are called from the [animateViews] method.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         * @return value in pixels to Y translate the history view holders given the fraction
         * [yFraction] of the history pulldown that is visible.
         */
        override fun getHistoryElementTranslationY(yFraction: Float): Float {
            return -mDisplayHeight * (1f - yFraction) - mBottomPaddingHeight
        }
    }

    /**
     * Our static constants.
     */
    companion object {

        /**
         * The TAG to use for logging (just in case).
         */
        @Suppress("unused")
        private const val TAG = "DragController"

        /**
         * The [ArgbEvaluator] we use to perform type interpolation between integer
         * values that represent ARGB colors.
         */
        private val mColorEvaluator = ArgbEvaluator()
    }
}

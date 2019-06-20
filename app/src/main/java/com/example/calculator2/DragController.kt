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
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Contains the logic for animating the recyclerview elements on drag.
 */
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

    private var mFormulaStartColor: Int = 0
    private var mFormulaEndColor: Int = 0

    private var mResultStartColor: Int = 0
    private var mResultEndColor: Int = 0

    // The padding at the bottom of the RecyclerView itself.
    private var mBottomPaddingHeight: Int = 0

    private var mAnimationInitialized: Boolean = false

    private var mOneLine: Boolean = false
    private var mIsDisplayEmpty: Boolean = false

    private var mAnimationController: AnimationController? = null

    private var mEvaluator: Evaluator? = null

    fun setEvaluator(evaluator: Evaluator) {
        mEvaluator = evaluator
    }

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

    fun setDisplayFormula(formula: CalculatorFormula) {
        mDisplayFormula = formula
    }

    fun setDisplayResult(result: CalculatorResult) {
        mDisplayResult = result
    }

    fun setToolbar(toolbar: View) {
        mToolbar = toolbar
    }

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
            val formula = vh.formula
            val result = vh.result
            val date = vh.date
            val divider = vh.divider

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
     * Reset all initialized values.
     */
    fun initializeAnimation(isResult: Boolean, oneLine: Boolean, isDisplayEmpty: Boolean) {
        mAnimationInitialized = false
        initializeController(isResult, oneLine, isDisplayEmpty)
    }

    interface AnimateTextInterface {

        // Return the lowest index of the first ViewHolder to be translated upwards.
        // If there is no current expression, we translate all the ViewHolder's otherwise,
        // we start at index 1.
        val firstTranslatedViewHolderIndex: Int

        fun initializeDisplayHeight()

        fun initializeColorAnimators(formula: AlignedTextView, result: CalculatorResult)

        fun initializeScales(formula: AlignedTextView, result: CalculatorResult)

        fun initializeFormulaTranslationX(formula: AlignedTextView)

        fun initializeFormulaTranslationY(formula: AlignedTextView, result: CalculatorResult)

        fun initializeResultTranslationX(result: CalculatorResult)

        fun initializeResultTranslationY(result: CalculatorResult)

        fun getResultTranslationX(yFraction: Float): Float

        fun getResultTranslationY(yFraction: Float): Float

        fun getResultScale(yFraction: Float): Float

        fun getFormulaScale(yFraction: Float): Float

        fun getFormulaTranslationX(yFraction: Float): Float

        fun getFormulaTranslationY(yFraction: Float): Float

        fun getDateTranslationY(yFraction: Float): Float

        fun getHistoryElementTranslationY(yFraction: Float): Float
    }

    // The default AnimationController when Display is in INPUT state and DisplayFormula is not
    // empty. There may or may not be a quick result.
    open inner class AnimationController : AnimateTextInterface {

        override val firstTranslatedViewHolderIndex: Int
            get() = 1

        override fun initializeDisplayHeight() {
            // no-op
        }

        override fun initializeColorAnimators(formula: AlignedTextView, result: CalculatorResult) {
            mFormulaStartColor = mDisplayFormula!!.currentTextColor
            mFormulaEndColor = formula.currentTextColor

            mResultStartColor = mDisplayResult!!.currentTextColor
            mResultEndColor = result.currentTextColor
        }

        override fun initializeScales(formula: AlignedTextView, result: CalculatorResult) {
            // Calculate the scale for the text
            mFormulaScale = mDisplayFormula!!.textSize / formula.textSize
        }

        override fun initializeFormulaTranslationY(formula: AlignedTextView,
                                                   result: CalculatorResult) {
            mFormulaTranslationY = if (mOneLine) {
                // Disregard result since we set it to GONE in the one-line case.
                (mDisplayFormula!!.paddingBottom - formula.paddingBottom
                        - mBottomPaddingHeight)
            } else {
                // Baseline of formula moves by the difference in formula bottom padding and the
                // difference in result height.
                (mDisplayFormula!!.paddingBottom - formula.paddingBottom + mDisplayResult!!.height - result.height
                        - mBottomPaddingHeight)
            }
        }

        override fun initializeFormulaTranslationX(formula: AlignedTextView) {
            // Right border of formula moves by the difference in formula end padding.
            mFormulaTranslationX = mDisplayFormula!!.paddingEnd - formula.paddingEnd
        }

        override fun initializeResultTranslationY(result: CalculatorResult) {
            // Baseline of result moves by the difference in result bottom padding.
            mResultTranslationY = (mDisplayResult!!.paddingBottom - result.paddingBottom
                    - mBottomPaddingHeight).toFloat()
        }

        override fun initializeResultTranslationX(result: CalculatorResult) {
            mResultTranslationX = mDisplayResult!!.paddingEnd - result.paddingEnd
        }

        override fun getResultTranslationX(yFraction: Float): Float {
            return mResultTranslationX * (yFraction - 1f)
        }

        override fun getResultTranslationY(yFraction: Float): Float {
            return mResultTranslationY * (yFraction - 1f)
        }

        override fun getResultScale(yFraction: Float): Float {
            return 1f
        }

        override fun getFormulaScale(yFraction: Float): Float {
            return mFormulaScale + (1f - mFormulaScale) * yFraction
        }

        override fun getFormulaTranslationX(yFraction: Float): Float {
            return mFormulaTranslationX * (yFraction - 1f)
        }

        override fun getFormulaTranslationY(yFraction: Float): Float {
            // Scale linearly between -FormulaTranslationY and 0.
            return mFormulaTranslationY * (yFraction - 1f)
        }

        override fun getDateTranslationY(yFraction: Float): Float {
            // We also want the date to start out above the visible screen with
            // this distance decreasing as it's pulled down.
            // Account for the scaled formula height.
            return -mToolbar!!.height * (1f - yFraction) + getFormulaTranslationY(yFraction) - mDisplayFormula!!.height / getFormulaScale(yFraction) * (1f - yFraction)
        }

        override fun getHistoryElementTranslationY(yFraction: Float): Float {
            return getDateTranslationY(yFraction)
        }
    }

    // The default AnimationController when Display is in RESULT state.
    inner class ResultAnimationController : AnimationController(), AnimateTextInterface {

        override val firstTranslatedViewHolderIndex: Int
            get() = 1

        override fun initializeScales(formula: AlignedTextView, result: CalculatorResult) {
            val textSize = mDisplayResult!!.textSize * mDisplayResult!!.scaleX
            mResultScale = textSize / result.textSize
            mFormulaScale = 1f
        }

        override fun initializeFormulaTranslationY(formula: AlignedTextView,
                                                   result: CalculatorResult) {
            // Baseline of formula moves by the difference in formula bottom padding and the
            // difference in the result height.
            mFormulaTranslationY = (mDisplayFormula!!.paddingBottom - formula.paddingBottom + mDisplayResult!!.height - result.height
                    - mBottomPaddingHeight)
        }

        override fun initializeFormulaTranslationX(formula: AlignedTextView) {
            // Right border of formula moves by the difference in formula end padding.
            mFormulaTranslationX = mDisplayFormula!!.paddingEnd - formula.paddingEnd
        }

        override fun initializeResultTranslationY(result: CalculatorResult) {
            // Baseline of result moves by the difference in result bottom padding.
            mResultTranslationY = (mDisplayResult!!.paddingBottom.toFloat() - result.paddingBottom.toFloat()
                    - mDisplayResult!!.translationY
                    - mBottomPaddingHeight.toFloat())
        }

        override fun initializeResultTranslationX(result: CalculatorResult) {
            mResultTranslationX = mDisplayResult!!.paddingEnd - result.paddingEnd
        }

        override fun getResultTranslationX(yFraction: Float): Float {
            return mResultTranslationX * yFraction - mResultTranslationX
        }

        override fun getResultTranslationY(yFraction: Float): Float {
            return mResultTranslationY * yFraction - mResultTranslationY
        }

        override fun getFormulaTranslationX(yFraction: Float): Float {
            return mFormulaTranslationX * yFraction - mFormulaTranslationX
        }

        override fun getFormulaTranslationY(yFraction: Float): Float {
            return getDateTranslationY(yFraction)
        }

        override fun getResultScale(yFraction: Float): Float {
            return mResultScale - mResultScale * yFraction + yFraction
        }

        override fun getFormulaScale(yFraction: Float): Float {
            return 1f
        }

        override fun getDateTranslationY(yFraction: Float): Float {
            // We also want the date to start out above the visible screen with
            // this distance decreasing as it's pulled down.
            return (-mToolbar!!.height * (1f - yFraction) + mResultTranslationY * yFraction - mResultTranslationY
                    - mDisplayFormula!!.paddingTop.toFloat()) + mDisplayFormula!!.paddingTop * yFraction
        }
    }

    // The default AnimationController when Display is completely empty.
    inner class EmptyAnimationController : AnimationController(), AnimateTextInterface {

        override val firstTranslatedViewHolderIndex: Int
            get() = 0

        override fun initializeDisplayHeight() {
            mDisplayHeight = (mToolbar!!.height + mDisplayResult!!.height
                    + mDisplayFormula!!.height)
        }

        override fun initializeScales(formula: AlignedTextView, result: CalculatorResult) {
            // no-op
        }

        override fun initializeFormulaTranslationY(formula: AlignedTextView,
                                                   result: CalculatorResult) {
            // no-op
        }

        override fun initializeFormulaTranslationX(formula: AlignedTextView) {
            // no-op
        }

        override fun initializeResultTranslationY(result: CalculatorResult) {
            // no-op
        }

        override fun initializeResultTranslationX(result: CalculatorResult) {
            // no-op
        }

        override fun getResultTranslationX(yFraction: Float): Float {
            return 0f
        }

        override fun getResultTranslationY(yFraction: Float): Float {
            return 0f
        }

        override fun getFormulaScale(yFraction: Float): Float {
            return 1f
        }

        override fun getDateTranslationY(yFraction: Float): Float {
            return 0f
        }

        override fun getHistoryElementTranslationY(yFraction: Float): Float {
            return -mDisplayHeight * (1f - yFraction) - mBottomPaddingHeight
        }
    }

    companion object {

        @Suppress("unused")
        private const val TAG = "DragController"

        private val mColorEvaluator = ArgbEvaluator()
    }
}

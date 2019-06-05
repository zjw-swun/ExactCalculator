/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView

/**
 * Extended [TextView] that supports ascent/baseline alignment.
 */
open class AlignedTextView
/**
 * Our constructor, the JvmOverloads annotation causes the Kotlin compiler to generate overloads
 * that substitute default parameter values.
 *
 * @param context The Context the view is running in, through which it can access the current theme,
 * resources, etc.
 * @param attrs The attributes of the XML tag that is inflating the view.
 * @param defStyleAttr An attribute in the current theme that contains a reference to a style
 * resource that supplies default values for the view. Can be 0 to not look for defaults.
 */
@JvmOverloads constructor(context: Context,
                          attrs: AttributeSet? = null,
                          defStyleAttr: Int = android.R.attr.textViewStyle)
    : AppCompatTextView(context, attrs, defStyleAttr) {

    /**
     * temporary rect for use during layout
     */
    private val mTempRect = Rect()

    /**
     * Padding offset that we subract from the normal top padding of our view for
     * ascent alignment.
     */
    private var mTopPaddingOffset: Int = 0
    /**
     * Padding offset that we subract from the normal bottom padding of our view for
     * descent (baseline) alignment.
     */
    private var mBottomPaddingOffset: Int = 0

    /**
     * Our init block, we just set whether the *TextView* includes extra top and bottom padding to
     * make room for accents that go above the normal ascent and descent to *false*.
     */
    init {
        // Disable any included font padding by default.
        includeFontPadding = false
    }

    /**
     * Measure the view and its content to determine the measured width and the measured height. We
     * initialize our variable *paint* to the *TextPaint* base paint used for the text of our
     * *TextView*, then set [mTempRect] to the smallest rectangle that encloses the letter "H". We
     * set [mTopPaddingOffset] to the minimum of the top padding of our view, and the integer result
     * of subtracting the ascent of *paint* (a negative value) from the *top* of [mTempRect]. We set
     * [mBottomPaddingOffset] to the minimum of the bottom padding of our view and the integer value
     * of the descent of *paint* (a positive value). We then call our super's implementation of
     * [onMeasure] without changing [widthMeasureSpec] or [heightMeasureSpec] at all.
     *
     * @param widthMeasureSpec horizontal space requirements as imposed by the parent.
     * @param heightMeasureSpec vertical space requirements as imposed by the parent.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val paint = paint

        // Always align text to the default capital letter height.
        paint.getTextBounds(LATIN_CAPITAL_LETTER, 0, 1, mTempRect)

        mTopPaddingOffset = Math.min(paddingTop,
                Math.ceil((mTempRect.top - paint.ascent()).toDouble()).toInt())
        mBottomPaddingOffset = Math.min(paddingBottom,
                Math.ceil(paint.descent().toDouble()).toInt())

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * Subtracts off [mTopPaddingOffset] from the value our super's implementation of
     * [getCompoundPaddingTop] returns.
     *
     * @return the top padding of the view after subracting [mTopPaddingOffset] from the value that
     * our super's implementation of [getCompoundPaddingTop] returns.
     */
    override fun getCompoundPaddingTop(): Int {
        return super.getCompoundPaddingTop() - mTopPaddingOffset
    }

    /**
     * Subtracts off [mBottomPaddingOffset] from the value our super's implementation of
     * [getCompoundPaddingBottom] returns.
     *
     * @return the bottom padding of the view after subracting [mBottomPaddingOffset] from the value
     * that our super's implementation of [getCompoundPaddingBottom] returns.
     */
    override fun getCompoundPaddingBottom(): Int {
        return super.getCompoundPaddingBottom() - mBottomPaddingOffset
    }

    /**
     * Contains our only constant
     */
    companion object {
        private const val LATIN_CAPITAL_LETTER = "H"
    }
}

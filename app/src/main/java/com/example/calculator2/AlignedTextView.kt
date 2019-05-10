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
 * Our constructor, the JvmOverloads annotation causes the Kotlin compiler to generate overloads for
 * this function that substitute default parameter values.
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

    // temporary rect for use during layout
    private val mTempRect = Rect()

    private var mTopPaddingOffset: Int = 0
    private var mBottomPaddingOffset: Int = 0

    init {

        // Disable any included font padding by default.
        includeFontPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val paint = paint

        // Always align text to the default capital letter height.
        paint.getTextBounds(LATIN_CAPITAL_LETTER, 0, 1, mTempRect)

        mTopPaddingOffset = Math.min(paddingTop,
                Math.ceil((mTempRect.top - paint.ascent()).toDouble()).toInt())
        mBottomPaddingOffset = Math.min(paddingBottom, Math.ceil(paint.descent().toDouble()).toInt())

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun getCompoundPaddingTop(): Int {
        return super.getCompoundPaddingTop() - mTopPaddingOffset
    }

    override fun getCompoundPaddingBottom(): Int {
        return super.getCompoundPaddingBottom() - mBottomPaddingOffset
    }

    companion object {

        private const val LATIN_CAPITAL_LETTER = "H"
    }
}

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

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView

import android.view.View.MeasureSpec.UNSPECIFIED
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * The custom [HorizontalScrollView] we use to hold our [CalculatorFormula] in our layout files for
 * our [CalculatorDisplay] and our history items.
 */
class CalculatorScrollView
/**
 * Our constructors, the JvmOverloads annotation causes the Kotlin compiler to generate overloads
 * that substitute default parameter values.
 *
 * @param context The Context the view is running in, through which it can access the current theme,
 * resources, etc.
 * @param attrs The attributes of the XML tag that is inflating the view.
 * @param defStyleAttr An attribute in the current theme that contains a reference to a style
 * resource that supplies default values for the view. Can be 0 to not look for defaults.
 */
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : HorizontalScrollView(context, attrs, defStyleAttr) {

    /**
     * @param spec measure specification we are to adjust for the padding of our parent.
     * @param padding the padding in pixels of our parent view.
     */
    private fun getChildMeasureSpecCompat(spec: Int, padding: Int, childDimension: Int): Int {
        if (MeasureSpec.getMode(spec) == UNSPECIFIED
                && (childDimension == MATCH_PARENT || childDimension == WRAP_CONTENT)) {
            val size = Math.max(0, MeasureSpec.getSize(spec) - padding)
            return MeasureSpec.makeMeasureSpec(size, UNSPECIFIED)
        }
        return ViewGroup.getChildMeasureSpec(spec, padding, childDimension)
    }

    /**
     * Ask one of the children of this view to measure itself, taking into account both the
     * MeasureSpec requirements for this view and its padding.
     *
     * @param child The child to measure
     * @param parentWidthMeasureSpec The width requirements for this view
     * @param parentHeightMeasureSpec The height requirements for this view
     */
    override fun measureChild(child: View, parentWidthMeasureSpec: Int,
                              parentHeightMeasureSpec: Int) {
        var widthMeasureSpec = parentWidthMeasureSpec
        // Allow child to be as wide as they want.
        widthMeasureSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), UNSPECIFIED)

        val lp = child.layoutParams
        val childWidthMeasureSpec = getChildMeasureSpecCompat(widthMeasureSpec,
                0 /* padding */, lp.width)
        val childHeightMeasureSpec = getChildMeasureSpecCompat(parentHeightMeasureSpec,
                paddingTop + paddingBottom, lp.height)

        child.measure(childWidthMeasureSpec, childHeightMeasureSpec)
    }

    override fun measureChildWithMargins(child: View, parentWidthMeasureSpec: Int, widthUsed: Int,
                                         parentHeightMeasureSpec: Int, heightUsed: Int) {
        var widthMeasureSpec = parentWidthMeasureSpec
        // Allow child to be as wide as they want.
        widthMeasureSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), UNSPECIFIED)

        val lp = child.layoutParams as MarginLayoutParams
        val childWidthMeasureSpec = getChildMeasureSpecCompat(widthMeasureSpec,
                lp.leftMargin + lp.rightMargin, lp.width)
        val childHeightMeasureSpec = getChildMeasureSpecCompat(parentHeightMeasureSpec,
                paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin, lp.height)

        child.measure(childWidthMeasureSpec, childHeightMeasureSpec)
    }
}

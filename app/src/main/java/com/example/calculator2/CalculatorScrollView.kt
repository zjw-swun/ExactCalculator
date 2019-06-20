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
import kotlin.math.max

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
     * Figures out the `MeasureSpec` to pass to our child. This method figures out the right
     * `MeasureSpec` for one dimension (height or width) for our one child view. If the mode
     * of our `MeasureSpec` parameter [spec] is UNSPECIFIED (the parent has not imposed any
     * constraint on the child, it can be whatever size it wants) and our child's layout parameter
     * dimension [childDimension] is either MATCH_PARENT, or WRAP_CONTENT we initialize our variable
     * `size` to the maximum of 0 and the size of [spec] minus our [padding] parameter, then return
     * the `MeasureSpec` constructed by the `makeMeasureSpec` method of `MeasureSpec` from `size`
     * and the UNSPECIFIED mode. Otherwise we return the `MeasureSpec` constructed by the
     * `getChildMeasureSpec` method of `ViewGroup` from [spec], [padding], and [childDimension].
     *
     * @param spec measure specification we are to adjust for the padding of our parent.
     * @param padding the padding in pixels of our parent view.
     * @param childDimension the width or height specified by the layout parameters of our child.
     * @return the measure specification integer for the child.
     */
    private fun getChildMeasureSpecCompat(spec: Int, padding: Int, childDimension: Int): Int {
        if (MeasureSpec.getMode(spec) == UNSPECIFIED
                && (childDimension == MATCH_PARENT || childDimension == WRAP_CONTENT)) {
            val size = max(0, MeasureSpec.getSize(spec) - padding)
            return MeasureSpec.makeMeasureSpec(size, UNSPECIFIED)
        }
        return ViewGroup.getChildMeasureSpec(spec, padding, childDimension)
    }

    /**
     * Ask one of the children of this view to measure itself, taking into account both the
     * `MeasureSpec` requirements for this view and its padding. We initialize our variable
     * `widthMeasureSpec` to our parameter [parentWidthMeasureSpec], then set it to the
     * `MeasureSpec` returned by the `makeMeasureSpec` method of `MeasureSpec` for the size of
     * `widthMeasureSpec` and the mode UNSPECIFIED (this will allow our child to be as wide
     * as they want). We then initialize our variable `lp` to the `LayoutParams` of [child],
     * and initialize our variable `childWidthMeasureSpec` to the `MeasureSpec` constructed by
     * our [getChildMeasureSpecCompat] method from `widthMeasureSpec`, a padding of 0, and the
     * `width` of `lp`. We then initialize our variable `childHeightMeasureSpec` to the `MeasureSpec`
     * constructed by our [getChildMeasureSpecCompat] method from [parentHeightMeasureSpec], a
     * padding of the top padding plus the bottom padding of our view plus the top margin of `lp`
     * plus the bottom margin of `lp`, and the `height` of `lp`. Finally we call the `measure`
     * method of [child] to find out how big it wants to be given `childWidthMeasureSpec` and
     * `childHeightMeasureSpec` as its constraints.
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

    /**
     * Ask one of the children of this view to measure itself, taking into account both the
     * `MeasureSpec` requirements for this view and its padding and margins. The child must
     * have MarginLayoutParams The heavy lifting is done in `getChildMeasureSpec`. We intialize
     * our variable `widthMeasureSpec` to our parameter [parentWidthMeasureSpec], then set it
     * to the `MeasureSpec` returned by the `makeMeasureSpec` method of `MeasureSpec` for the size of
     * `widthMeasureSpec` and the mode UNSPECIFIED (this will allow our child to be as wide
     * as they want). We initialize our variable `lp` by casting the `LayoutParams` of [child] to
     * a `MarginLayoutParams`. We then initialize our variable `childWidthMeasureSpec` to the
     * `MeasureSpec` constructed by our [getChildMeasureSpecCompat] method from `widthMeasureSpec`,
     * a padding of the left margin of `lp` plus its right margin, a `MeasureSpec` constructed by
     * our [getChildMeasureSpecCompat] method from [parentHeightMeasureSpec], a padding of the top
     * padding of this view plus the bottom padding of this view plus the left margin of `lp` plus
     * its right margin, and the height of `lp`. Finally we call the `measure` method of [child] to
     * find out how big it wants to be given `childWidthMeasureSpec` and `childHeightMeasureSpec` as
     * its constraints.
     *
     * @param child The child to measure
     * @param parentWidthMeasureSpec The width requirements for this view
     * @param widthUsed Extra space that has been used up by the parent
     *        horizontally (possibly by other children of the parent)
     * @param parentHeightMeasureSpec The height requirements for this view
     * @param heightUsed Extra space that has been used up by the parent
     *        vertically (possibly by other children of the parent)
     */
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

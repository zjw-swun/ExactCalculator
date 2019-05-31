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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

class CalculatorPadViewPager @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewPager(context, attrs) {

    private val mStaticPagerAdapter = object : PagerAdapter() {
        override fun getCount(): Int {
            return childCount
        }

        override fun instantiateItem(container: ViewGroup, position: Int): View {
            val child = getChildAt(position)

            // Set a OnClickListener to scroll to item's position when it isn't the current item.
            child.setOnClickListener { setCurrentItem(position, true /* smoothScroll */) }
            // Set an OnTouchListener to always return true for onTouch events so that a touch
            // sequence cannot pass through the item to the item below.
            child.setOnTouchListener { v, event ->
                v.onTouchEvent(event)
                true
            }

            // Set an OnHoverListener to always return true for onHover events so that focus cannot
            // pass through the item to the item below.
            child.setOnHoverListener { v, event ->
                v.onHoverEvent(event)
                true
            }
            // Make the item focusable so it can be selected via a11y.
            child.isFocusable = true
            // Set the content description of the item which will be used by a11y to identify it.
            child.contentDescription = getPageTitle(position)

            return child
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            removeViewAt(position)
        }

        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view === `object`
        }

        override fun getPageWidth(position: Int): Float {
            return if (position == 1) 7.0f / 9.0f else 1.0f
        }

        override fun getPageTitle(position: Int): CharSequence? {
            val pageDescriptions = getContext().resources
                    .getStringArray(R.array.desc_pad_pages)
            return pageDescriptions[position]
        }
    }

    private val mOnPageChangeListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            for (i in childCount - 1 downTo 0) {
                val child = getChildAt(i)
                // Only the "peeking" or covered page should be clickable.
                child.isClickable = i != position

                // Prevent clicks and accessibility focus from going through to descendants of
                // other pages which are covered by the current page.
                if (child is ViewGroup) {
                    for (j in child.childCount - 1 downTo 0) {
                        child.getChildAt(j).importantForAccessibility = if (i == position)
                            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        else
                            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    }
                }
            }
        }
    }

    private val mPageTransformer = PageTransformer { view, position ->
        if (position < 0.0f) {
            // Pin the left page to the left side.
            view.translationX = width * -position
            view.alpha = Math.max(1.0f + position, 0.0f)
        } else {
            // Use the default slide transition when moving to the next page.
            view.translationX = 0.0f
            view.alpha = 1.0f
        }
    }

    private val mGestureWatcher = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            // Return true so calls to onSingleTapUp are not blocked.
            return true
        }

        override fun onSingleTapUp(ev: MotionEvent): Boolean {
            if (mClickedItemIndex != -1) {
                getChildAt(mClickedItemIndex).performClick()
                mClickedItemIndex = -1
                return true
            }
            return super.onSingleTapUp(ev)
        }
    }

    private val mGestureDetector: GestureDetector

    private var mClickedItemIndex = -1

    init {

        mGestureDetector = GestureDetector(context, mGestureWatcher)
        mGestureDetector.setIsLongpressEnabled(false)

        adapter = mStaticPagerAdapter
        setBackgroundColor(Color.BLACK)
        pageMargin = -resources.getDimensionPixelSize(R.dimen.pad_page_margin)
        setPageTransformer(false, mPageTransformer)
        addOnPageChangeListener(mOnPageChangeListener)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()

        // Invalidate the adapter's data set since children may have been added during inflation.

        adapter!!.notifyDataSetChanged()

        // Let page change listener know about our initial position.
        mOnPageChangeListener.onPageSelected(currentItem)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        try {
            // Always intercept touch events when a11y focused since otherwise they will be
            // incorrectly offset by a11y before being dispatched to children.
            if (isAccessibilityFocused || super.onInterceptTouchEvent(ev)) {
                return true
            }

            // Only allow the current item to receive touch events.
            val action = ev.actionMasked
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                // If a child is a11y focused then we must always intercept the touch event
                // since it will be incorrectly offset by a11y.
                val childCount = childCount
                for (childIndex in childCount - 1 downTo 0) {
                    if (getChildAt(childIndex).isAccessibilityFocused) {
                        mClickedItemIndex = childIndex
                        return true
                    }
                }

                if (action == MotionEvent.ACTION_DOWN) {
                    mClickedItemIndex = -1
                }

                // Otherwise if touch is on a non-current item then intercept.
                val actionIndex = ev.actionIndex
                val x = ev.getX(actionIndex) + scrollX
                val y = ev.getY(actionIndex) + scrollY
                for (i in childCount - 1 downTo 0) {
                    val childIndex = getChildDrawingOrder(childCount, i)
                    val child = getChildAt(childIndex)
                    if (child.visibility == View.VISIBLE
                            && x >= child.left && x < child.right
                            && y >= child.top && y < child.bottom) {
                        if (action == MotionEvent.ACTION_DOWN) {
                            mClickedItemIndex = childIndex
                        }
                        return childIndex != currentItem
                    }
                }
            }

            return false
        } catch (e: IllegalArgumentException) {
            Log.e("Calculator", "Error intercepting touch event", e)
            return false
        }

    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        try {
            // Allow both the gesture detector and super to handle the touch event so they both see
            // the full sequence of events. This should be safe since the gesture detector only
            // handle clicks and super only handles swipes.
            mGestureDetector.onTouchEvent(ev)
            return super.onTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            Log.e("Calculator", "Error processing touch event", e)
            return false
        }

    }
}/* attrs */

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

/**
 * This custom [ViewPager] is used only in portrait mode to allow "swiping" in the advanced keypad
 * (it is in layout file layout/activity_calculator_port.xml). It has 2 children: a horizontal
 * `LinearLayout` holding the numeric keypad (layout/pad_numeric.xml) and the operator keypad
 * (layout/pad_operator.xml) as well as the advanced keypad (which is one of the display size
 * specific /pad_advanced*.xml layouts).
 */
class CalculatorPadViewPager
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : ViewPager(context, attrs) {

    /**
     * The [PagerAdapter] we use to "swipe" in the advanced keypad.
     */
    private val mStaticPagerAdapter = object : PagerAdapter() {
        /**
         * Return the number of views available, we just return our `childCount` property.
         *
         * @return the number of children we hold.
         */
        override fun getCount(): Int {
            return childCount
        }

        /**
         * Create the page for the given position. The adapter is responsible for adding the view to
         * the container given here, although it only must ensure this is done by the time it returns
         * from [finishUpdate]. We initialize our variable `child` with the child [View] at position
         * [position]. We then set its `OnClickListener` to a lambda which calls the [setCurrentItem]
         * method to scroll smoothly to it's position when it isn't the current item. We set its
         * `OnTouchListener` to a lambda which calls the [View]'s `onTouchEvent` with the event then
         * returns *true* so that the touch sequence cannot pass through the item to the item below.
         * We set its `OnHoverListener` to a lambda which calls the [View]'s `onHoverEvent` method
         * with the event then returns *true* so that focus cannot pass through the item to the item
         * below. We then set the `isFocusable` property of `child` to *true* and set its
         * `contentDescription` to the [CharSequence] returned by our [getPageTitle] override for
         * the use of the a11y (accessibility) service. Finally we return `child` to the caller.
         *
         * @param container The containing View in which the page will be shown.
         * @param position The page position to be instantiated.
         * @return Returns an Object representing the new page. This does not need to be a View, but
         * can be some other container of the page.
         */
        override fun instantiateItem(container: ViewGroup, position: Int): View {
            val child = getChildAt(position)

            // Set a OnClickListener to scroll to item's position when it isn't the current item.
            child.setOnClickListener {
                setCurrentItem(position, true)
            }
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

        /**
         * Remove a page for the given position. The adapter is responsible for removing the view
         * from its container, although it only must ensure this is done by the time it returns
         * from [finishUpdate]. We just call the [removeViewAt] method of [ViewGroup] to have it
         * remove the child at position [position]
         *
         * @param container The containing View from which the page will be removed.
         * @param position The page position to be removed.
         * @param object The same object that was returned by [instantiateItem].
         */
        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            removeViewAt(position)
        }

        /**
         * Determines whether a page View is associated with a specific key object as returned by
         * [instantiateItem]. This method is required for a [PagerAdapter] to function properly.
         * We just return *true* is [view] points to the same instance as [object] does (referential
         * equality).
         *
         * @param view Page [View] to check for association with [object]
         * @param object Object to check for association with [view]
         * @return *true* if [view] is associated with the key object [object]
         */
        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view === `object`
        }

        /**
         * Returns the proportional width of a given page as a percentage of the ViewPager's measured
         * width from (0.f-1.f]. If [position] is 1 (the advanced keypad) we return 7/9, otherwise we
         * return 1.0
         *
         * @param position The position of the page requested
         * @return Proportional width for the given page position
         */
        override fun getPageWidth(position: Int): Float {
            return if (position == 1) 7.0f / 9.0f else 1.0f
        }

        /**
         * This method may be called by the [ViewPager] to obtain a title string to describe the
         * specified page. This method may return null indicating no title for this page. We
         * initialize our variable `pageDescriptions` with the string array R.array.desc_pad_pages
         * from our resources, then return the string at index [position] to the caller.
         *
         * @param position The position of the title requested
         * @return A title for the requested page
         */
        override fun getPageTitle(position: Int): CharSequence? {
            val pageDescriptions = getContext().resources.getStringArray(R.array.desc_pad_pages)
            return pageDescriptions[position]
        }
    }

    /**
     * The `SimpleOnPageChangeListener` for our [ViewPager], its `onPageSelected` override will be
     * called whenever a new page is selected.
     */
    private val mOnPageChangeListener = object : ViewPager.SimpleOnPageChangeListener() {
        /**
         * This method will be invoked when a new page becomes selected. Animation is not
         * necessarily complete. We loop over `i` from our last child down to our first (zeroth):
         * - We initialize our variable `child` to our child [View] at position `i`.
         * - If `i` is not equal to [position] we set its clickable property to *true*, and if they
         * are equal we set it to *false* (only the "peeking" or covered page should be clickable).
         * - If `child` is a [ViewGroup] we loop down over `j` from the last to the zeroth child
         * setting each child's `importantForAccessibility` to IMPORTANT_FOR_ACCESSIBILITY_AUTO
         * if `i` is equal to [position], or to IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS if
         * it is not (this will prevent clicks and accessibility focus from going through to
         * descendants of other pages which are covered by the current page).
         *
         * @param position Position index of the new selected page.
         */
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

    /**
     * Our `PageTransformer`, it is invoked whenever a visible/attached page is scrolled. This
     * offers an opportunity for the application to apply a custom transformation to the page views
     * using animation properties.
     */
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
        return try {
            // Allow both the gesture detector and super to handle the touch event so they both see
            // the full sequence of events. This should be safe since the gesture detector only
            // handle clicks and super only handles swipes.
            mGestureDetector.onTouchEvent(ev)
            super.onTouchEvent(ev)
        } catch (e: IllegalArgumentException) {
            Log.e("Calculator", "Error processing touch event", e)
            false
        }

    }
}

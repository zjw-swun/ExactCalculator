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
     *
     * The lambda is for the function `transformPage`:
     * - `view` Apply the transformation to this page
     * - `position` Position of page relative to the current front-and-center position of the pager.
     * 0 is front and center. 1 is one full page position to the right, and -1 is one page position
     * to the left.
     *
     * If `position` is less than 0 (left page) we translate `view` by our width times minus `position`
     * and set its alpha to the maximum of 1.0 plus `position` and 0. Otherwise we translate the right
     * page using the default slide transition by setting the translation of `view` to 0.0 and its
     * alpha to 1.0
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

    /**
     * Our [GestureDetector.SimpleOnGestureListener]. Its super implements all the methods in the
     * `OnGestureListener`, `OnDoubleTapListener`, and `OnContextClickListener` but does nothing
     * and returns *false* for all applicable methods, so we can override only the methods we are
     * interested in (which is `onSingleTapUp`).
     */
    private val mGestureWatcher = object : GestureDetector.SimpleOnGestureListener() {
        /**
         * Notified when a tap occurs with the down [MotionEvent] that triggered it. This will be
         * triggered immediately for every down event. All other events should be preceded by this.
         * We just return *true* so that calls to [onSingleTapUp] are not blocked.
         *
         * @param e The down motion event.
         * @return *true* to continue getting events for the rest of the gesture, if you return *false*
         * the system assumes that you want to ignore the rest of the gesture, and the other methods
         * of [GestureDetector.OnGestureListener] never get called.
         */
        override fun onDown(e: MotionEvent): Boolean {
            // Return true so calls to onSingleTapUp are not blocked.
            return true
        }

        /**
         * Notified when a tap occurs with the up [MotionEvent] that triggered it. If [mClickedItemIndex]
         * is not equal to -1 our [onInterceptTouchEvent] method has intercepted a touch event intended
         * for our child at index [mClickedItemIndex] so we fetch that child and call its `performClick`
         * method to pass the click on to it, set [mClickedItemIndex] to -1 and return *true* to consume
         * the event. Otherwise we return the value returned by our super's implementation of
         * `onSingleTapUp`.
         *
         * @param ev The up motion event that completed the first tap
         * @return *true* if the event is consumed, else false
         */
        override fun onSingleTapUp(ev: MotionEvent): Boolean {
            if (mClickedItemIndex != -1) {
                getChildAt(mClickedItemIndex).performClick()
                mClickedItemIndex = -1
                return true
            }
            return super.onSingleTapUp(ev)
        }
    }

    /**
     * The [GestureDetector] whose `onTouchEvent` method is called by our [onTouchEvent] override.
     * It is used to selectively pass touch events to our children (or not).
     */
    private val mGestureDetector: GestureDetector

    /**
     * Index of the child that [mGestureDetector] should pass touch events to, -1 does not pass them.
     */
    private var mClickedItemIndex = -1

    /**
     * The init block for our constructor. We initialize `mGestureDetector` with a new instance of
     * `GestureDetector` constructed to use `mGestureWatcher` as its `OnGestureListener` and call
     * its `setIsLongpressEnabled` method with *false* to disable long press events (so that we
     * scroll better). We set our `adapter` property to `mStaticPagerAdapter`, set out background
     * color to BLACK, set our `pageMargin` property to minus our R.dimen.pad_page_margin resource,
     * set our `PageTransformer` to `mPageTransformer` with the reverse drawing order option *false*
     * so that it draws first to last, and finally add `mOnPageChangeListener` as an
     * `OnPageChangeListener`.
     */
    init {

        mGestureDetector = GestureDetector(context, mGestureWatcher)
        mGestureDetector.setIsLongpressEnabled(false)

        adapter = mStaticPagerAdapter
        setBackgroundColor(Color.BLACK)
        pageMargin = -resources.getDimensionPixelSize(R.dimen.pad_page_margin)
        setPageTransformer(false, mPageTransformer)
        addOnPageChangeListener(mOnPageChangeListener)
    }

    /**
     * Finalize inflating a view from XML. This is called as the last phase of inflation, after all
     * child views have been added. First we call our super's implementation of `onFinishInflate`,
     * then we call the `notifyDataSetChanged` method of `adapter` to invalidate the adapter's data
     * set since children may have been added during inflation. Finally we call the `onPageSelected`
     * override of [mOnPageChangeListener] with our `currentItem` property to let it know about our
     * initial position.
     */
    override fun onFinishInflate() {
        super.onFinishInflate()

        // Invalidate the adapter's data set since children may have been added during inflation.

        adapter!!.notifyDataSetChanged()

        // Let page change listener know about our initial position.
        mOnPageChangeListener.onPageSelected(currentItem)
    }

    /**
     * Implement this method to intercept all touch screen motion events. This allows you to watch
     * events as they are dispatched to your children, and take ownership of the current gesture at
     * any point.
     *
     * Using this function takes some care, as it has a fairly complicated interaction with
     * [onTouchEvent], and using it requires implementing that method as well as this one in
     * the correct way. Events will be received in the following order:
     * - You will receive the down event here.
     * - The down event will be handled either by a child of this view group, or given to your own
     * [onTouchEvent] method to handle; this means you should implement [onTouchEvent] to return
     * *true*, so you will continue to see the rest of the gesture (instead of looking for a parent
     * view to handle it). Also, by returning *true* from [onTouchEvent], you will not receive any
     * following events in [onInterceptTouchEvent] and all touch processing must happen in
     * onTouchEvent like normal.
     * - For as long as you return *false* from this function, each following event (up to and
     * including the final up) will be delivered first here and then to the target's [onTouchEvent].
     * - If you return true from here, you will not receive any following events: the target view
     * will receive the same event but with the action ACTION_CANCEL, and all further events will be
     * delivered to your [onTouchEvent] method and no longer appear here.
     *
     * Wrapped in a try block intended to catch and log IllegalArgumentException, we first check
     * whether we are accessibility focused or our super's implementation of `onInterceptTouchEvent`
     * returns *true* and if either are *true* we return *true* to intercept the touch event (we do
     * this because otherwise they will be incorrectly offset by a11y before being dispatched to
     * children, and the call to our super's implementation is to give it a chance to intercept the
     * event).
     *
     * Otherwise we initialize our variable `action` with the masked off action of [ev]. If `action`
     * is ACTION_DOWN (a pressed gesture has started) or ACTION_POINTER_DOWN (a non-primary pointer
     * has gone down) we need to check if any of our children are a11y focused:
     * - We initialize our variable `childCount` to our `childCount` property.
     * - We loop over `childIndex` from our last child down to our first and if any of these children
     * are accessibility focused we set our [mClickedItemIndex] field to that `childIndex` and return
     * *true* to intercept the event (the `onSingleTapUp` override of our [GestureDetector] instance
     * [mGestureWatcher] will pass the event to the child's `performClick` method).
     *
     * If none of our children are a11y focused we need to check if the touch is on a non-current
     * item that we want to intercept:
     * - We initialize our variable `actionIndex` with the `actionIndex` field of [ev].
     * - We initialize our variable `x` with the X coordinate of the `actionIndex` pointer index of
     * [ev] plus our `scrollX` property (the scrolled left position of this view: the left edge of
     * the displayed part of our view) and our variable `y` with the Y coordinate of the `actionIndex`
     * pointer index of [ev] plus our `scrollY` property (the top edge of the displayed part of our
     * view).
     * - We loop over `i` from our last child down to our first:
     *     - We initialize our variable `childIndex` to the drawing order of the child to draw for
     *     iteration `i`, and initialize our variable `child` to the child at `childIndex`.
     *     - If the `child` is visible and `x` and `y` are within the coordinates of `child` we
     *     check if `action` is ACTION_DOWN and if it is we set `mClickedItemIndex` to `childIndex`.
     *     Then we return *true* if `childIndex` is not equal to the `currentItem` property of our
     *     `ViewPager` (the currently selected page) otherwise we return *false*
     *
     * If we did not steal the touch event in the above code, we return *false* to continue the
     * normal touch event handling.
     *
     * @param ev The motion event being dispatched down the hierarchy.
     * @return Return true to steal motion events from the children and have them dispatched to this
     * [ViewGroup] through [onTouchEvent]. The current target will receive an ACTION_CANCEL event,
     * and no further messages will be delivered here.
     */
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

    /**
     * Implement this method to handle touch screen motion events. We return the value returned by
     * a try block that is intended to catch and log IllegalArgumentException:
     * - We call the `onTouchEvent` override of [mGestureDetector] with the event [ev], then return
     * the value returned by our super's implementation of `onTouchEvent`.
     * - The catch block logs: "Error processing touch event" and returns *false*
     *
     * @param ev The motion event.
     * @return True if the event was handled, false otherwise.
     */
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

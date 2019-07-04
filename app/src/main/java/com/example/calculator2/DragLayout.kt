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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import androidx.core.view.ViewCompat
import androidx.customview.widget.ViewDragHelper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

import java.util.HashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

/**
 * This is the [ViewGroup] used in our layout file layout/activity_calculator_main.xml to hold both
 * of our layout/activity_calculator.xml layout for the calculator (id R.id.main_calculator) and the
 * [FrameLayout] which holds the drug down [HistoryFragment] (id R.id.history_frame, starts out
 * invisible until the user drags it down or selects it using the option menu).
 */
class DragLayout(context: Context, attrs: AttributeSet) : ViewGroup(context, attrs) {

    /**
     * The [FrameLayout] which holds the drug down [HistoryFragment] (id R.id.history_frame).
     */
    private lateinit var mHistoryFrame: FrameLayout
    /**
     * The [ViewDragHelper] we use for its useful operations and state tracking callbacks which
     * allow the user to drag and reposition views within our [ViewGroup].
     */
    private lateinit var mDragHelper: ViewDragHelper

    /**
     * The list of [DragCallback] implementations whose callbacks are to be called when things
     * happen which they need to be informed about. Currently only [Calculator2] and [HistoryFragment]
     * add themselves as [DragCallback] implementations.
     */
    private val mDragCallbacks = CopyOnWriteArrayList<DragCallback>()
    /**
     * [CloseCallback] whose `onClose` callback should be called when the [DragLayout] needs to be
     * closed for some reason (in our case either because the user has drug it closed (the method
     * `onViewDragStateChanged` of our [DragHelperCallback] has been called to indicate that the
     * view has stopped moving and its top is below half of [mVerticalRange]) or the [Calculator2]
     * method `showHistoryFragment` has determined that the history fragment can not be shown)
     */
    private var mCloseCallback: CloseCallback? = null

    /**
     * XY coordinates of touch events we have received indexed by the pointer ID of that touch event.
     */
    @SuppressLint("UseSparseArrays")
    private val mLastMotionPoints = HashMap<Int, PointF>()
    /**
     * Hit rectangle in our coordinates of pointer movement that the `tryCaptureView` method of
     * [ViewDragHelper.Callback] needs to evaluate in order to determine if the user can drag the
     * [HistoryFragment] down.
     */
    private val mHitRect = Rect()

    /**
     * How far can the [HistoryFragment] be drug down.
     */
    private var mVerticalRange: Int = 0

    /**
     * Is the [HistoryFragment] drag down open at the moment?
     */
    var isOpen: Boolean = false
        private set

    /**
     * Is the user moving the [HistoryFragment] drag down at the moment? Our getter initializes its
     * variable `draggingState` to the current drag state of the helper [mDragHelper] (our
     * [ViewDragHelper]). Then if `draggingState` is STATE_DRAGGING or STATE_SETTLING we return
     * *true* to the caller (if it is STATE_IDLE we return *false*).
     */
    val isMoving: Boolean
        get() {
            val draggingState = mDragHelper.viewDragState
            return (draggingState == ViewDragHelper.STATE_DRAGGING)
                    || (draggingState == ViewDragHelper.STATE_SETTLING)
        }

    /**
     * Finalize inflating a view from XML. This is called as the last phase of inflation, after all
     * child views have been added. We initialize our field [mDragHelper] with the [ViewDragHelper]
     * created by the [ViewDragHelper.create] method for *this*, with a normal 1.0 sensitivity and
     * a new instance of [DragHelperCallback] as its callback. We initialize our field [mHistoryFrame]
     * by finding the view with id R.id.history_frame. Finally we call our super's implementation of
     * `onFinishInflate`.
     */
    override fun onFinishInflate() {
        mDragHelper = ViewDragHelper.create(this, 1.0f, DragHelperCallback())
        mHistoryFrame = findViewById(R.id.history_frame)
        super.onFinishInflate()
    }

    /**
     * Measure the view and its content to determine the measured width and the measured height.
     * This method is invoked by `measure(int, int)` and should be overridden by subclasses to
     * provide accurate and efficient measurement of their contents. First we call our super's
     * implementation of `onMeasure`, then we call the [measureChildren] method to have each of
     * our children measure themselves, taking into account both the `MeasureSpec` requirements
     * passed to our view and our padding.
     *
     * @param widthMeasureSpec horizontal space requirements as imposed by the parent. The
     * requirements are encoded with [android.view.View.MeasureSpec].
     * @param heightMeasureSpec vertical space requirements as imposed by the parent. The
     * requirements are encoded with [android.view.View.MeasureSpec].
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        measureChildren(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * Called from layout when this view should assign a size and position to each of its children.
     * We initialize our [Int] variable `displayHeight` to 0. Then for each of the `c` [DragCallback]
     * instances in [mDragCallbacks] we set `displayHeight` to the maximum of `displayHeight` and
     * the height returned by the `displayHeightFetch` method of `c`. Having done with all the
     * callbacks we set our field [mVerticalRange] to the height of our view minus `displayHeight`.
     * We initialize our variable `childCount` to the number of children in our group, then loop over
     * `i` from 0 until `childCount`:
     * - Initializing our variable `child` to the view at position `i` in the group.
     * - Initializing our variable `top` to 0.
     * - If the current child points to our [mHistoryFrame] we then set `top` to the `top` of `child`
     * if the `capturedView` of [mDragHelper] is [mHistoryFrame] and the `viewDragState` of
     * [mDragHelper] is not STATE_IDLE, or else if the [HistoryFragment] drag down is open to 0, if
     * closed then to minus [mVerticalRange]
     * - We then call the `layout` method of `child` to assign it to have its left side at 0, its
     * top at `top`, its right side at the `child`'s `measuredWidth`, and its bottom at `top` plus
     * the `measuredHeight` of `child`
     *
     * @param changed This is a new size or position for this view
     * @param l Left position, relative to parent
     * @param t Top position, relative to parent
     * @param r Right position, relative to parent
     * @param b Bottom position, relative to parent
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var displayHeight = 0
        for (c in mDragCallbacks) {
            displayHeight = max(displayHeight, c.displayHeightFetch())
        }
        mVerticalRange = height - displayHeight

        val childCount = childCount
        for (i in 0 until childCount) {
            val child = getChildAt(i)

            var top = 0
            if (child === mHistoryFrame) {
                top = if ((mDragHelper.capturedView === mHistoryFrame)
                        && (mDragHelper.viewDragState != ViewDragHelper.STATE_IDLE)) {
                    child.getTop()
                } else {
                    if (isOpen) 0 else -mVerticalRange
                }
            }
            child.layout(0, top, child.measuredWidth, top + child.measuredHeight)
        }
    }

    /**
     * Hook allowing a [View] to generate a representation of its internal state that can later be
     * used to create a new instance with that same state. This state should only contain information
     * that is not persistent or can not be reconstructed later. We initialize our variable `bundle`
     * with a new instance of [Bundle], store the [Parcelable] returned by our super's implementation
     * of [onSaveInstanceState] under the key KEY_SUPER_STATE ("SUPER_STATE") in it, then store the
     * [Boolean] value returned by our method [isOpen] in it under the key KEY_IS_OPEN ("IS_OPEN"),
     * and finally return `bundle` to our caller.
     *
     * @return Returns a [Parcelable] object containing the view's current dynamic state, or null if
     * there is nothing interesting to save.
     */
    override fun onSaveInstanceState(): Parcelable? {
        val bundle = Bundle()
        bundle.putParcelable(KEY_SUPER_STATE, super.onSaveInstanceState())
        bundle.putBoolean(KEY_IS_OPEN, isOpen)
        return bundle
    }

    /**
     * Hook allowing a [View] to re-apply a representation of its internal state that had previously
     * been generated by [onSaveInstanceState]. This function will never be called with a *null*
     * state. We initialize our variable `stateLocal` to our parameter [state]. If `stateLocal` is
     * a [Bundle] we initialize our variable `bundle` by casting `stateLocal` to a [Bundle], then
     * set our field [isOpen] to the [Boolean] stored in `bundle` under the key KEY_IS_OPEN. We
     * set the visibility of [mHistoryFrame] to VISIBLE if [isOpen] is *true* or to INVISIBLE if it
     * is *false*. Then for each of the `c` [DragCallback] in [mDragCallbacks] we call its method
     * `onInstanceStateRestored` method with the value of [isOpen]. We then set `stateLocal` to
     * the [Parcelable] stored under the key KEY_SUPER_STATE ("SUPER_STATE") in `bundle`. Finally we
     * call our super's implementation of [onRestoreInstanceState] with `stateLocal` whether [state]
     * was a [Bundle] or not.
     *
     * @param state The frozen state that had previously been returned by [onSaveInstanceState].
     */
    override fun onRestoreInstanceState(state: Parcelable) {
        var stateLocal = state
        if (stateLocal is Bundle) {
            val bundle = stateLocal as Bundle?
            isOpen = bundle!!.getBoolean(KEY_IS_OPEN)
            mHistoryFrame.visibility = if (isOpen) View.VISIBLE else View.INVISIBLE
            for (c in mDragCallbacks) {
                c.onInstanceStateRestored(isOpen)
            }

            stateLocal = bundle.getParcelable(KEY_SUPER_STATE)
        }
        super.onRestoreInstanceState(stateLocal)
    }

    /**
     * Stores the XY coordinates of the [MotionEvent] passed it in [mLastMotionPoints] indexed by
     * the pointer ID of the [MotionEvent]. The points stored in [mLastMotionPoints] are then
     * tested by the `tryCaptureView` method of our [DragHelperCallback] to see if the capture of
     * the history drag down should be allowed. We branch on the masked off action of [event]:
     * - ACTION_DOWN, ACTION_POINTER_DOWN: We initialize our variable `actionIndex` to the pointer
     * index of [event], initialize our variable `pointerId` to the pointer ID of that pointer data
     * index, and initialize our variable `point` to a [PointF] constructed to contain the X and
     * Y coordiantes of the [event] for pointer index `actionIndex`. We then store `point` under
     * the key `pointerId` in [mLastMotionPoints].
     * - ACTION_MOVE: We loop over `i` for the number of pointers in [event] down to 0, setting our
     * variable `pointerId` to the pointer identifier associated with pointer data index `i` in
     * [event], then setting our variable `point` to the [PointF] whose key is `pointerId`, and
     * then setting the X coordinate of `point` to the X coordinate of the event for the pointer
     * index `i`, and the Y coordinate of `point` to the Y coordinate of the event for the pointer
     * index `i`.
     * - ACTION_POINTER_UP: (A non-primary pointer has gone up) We set our variable `actionIndex`
     * to the pointer index associated with the [event], and our variable `pointerId` to the pointer
     * identifier associated with the pointer data index `actionIndex` in [event]. We then remove
     * the [PointF] stored under the key `pointerId` in [mLastMotionPoints].
     * - ACTION_UP or ACTION_CANCEL: (pressed gesture has finished or has been aborted) we clear the
     * entire contents of [mLastMotionPoints].
     *
     * @param event the [MotionEvent] we are to save.
     */
    private fun saveLastMotion(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                val point = PointF(event.getX(actionIndex), event.getY(actionIndex))
                mLastMotionPoints[pointerId] = point
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in event.pointerCount - 1 downTo 0) {
                    val pointerId = event.getPointerId(i)
                    val point = mLastMotionPoints[pointerId]
                    point?.set(event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                mLastMotionPoints.remove(pointerId)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mLastMotionPoints.clear()
            }
        }
    }

    /**
     * Implement this method to intercept all touch screen motion events. We call our [saveLastMotion]
     * method to process [event] and use it to update [mLastMotionPoints] appropriately. Then we
     * return the value returned by the `shouldInterceptTouchEvent` method of [mDragHelper] for
     * [event] (it analyses [event] and returns *true* if this view should return *true* from this
     * method so that we can intercept the rest of the touch event stream).
     *
     * @param event The motion event being dispatched down the hierarchy.
     * @return Return *true* to steal motion events from the children and have them dispatched to
     * this [ViewGroup] through [onTouchEvent]. The current target will receive an ACTION_CANCEL
     * event, and no further messages will be delivered here. For as long as you return *false* from
     * this function, each following event (up to and including the final up) will be delivered
     * first here and then to the target's [onTouchEvent].
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        saveLastMotion(event)
        return mDragHelper.shouldInterceptTouchEvent(event)
    }

    /**
     * Implement this method to handle touch screen motion events. We check first whether [event] is
     * a case where multi-touch would cause a crash, ie. the masked off action of [event] is
     * ACTION_MOVE and the current drag state of [mDragHelper] is STATE_DRAGGING, and the active
     * pointer ID of [mDragHelper] is not INVALID_POINTER, the `findPointerIndex` method of [event]
     * can not find that active pointer in the event. If all that is *true* we call the `cancel`
     * method of [mDragHelper] to cancel the drag and return *false* to indicate that we did not
     * handle the event. Otherwise we call our [saveLastMotion] method to process [event] and use it
     * to update [mLastMotionPoints] appropriately, call the `processTouchEvent` method of
     * [mDragHelper] to have it process [event] and return *true* to indicate that we have handled
     * the event.
     *
     * @param event The motion event.
     * @return *true* if the event was handled, *false* otherwise.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Workaround: do not process the error case where multi-touch would cause a crash.
        if (event.actionMasked == MotionEvent.ACTION_MOVE
                && mDragHelper.viewDragState == ViewDragHelper.STATE_DRAGGING
                && mDragHelper.activePointerId != ViewDragHelper.INVALID_POINTER
                && event.findPointerIndex(mDragHelper.activePointerId) == -1) {
            mDragHelper.cancel()
            return false
        }

        saveLastMotion(event)

        mDragHelper.processTouchEvent(event)
        return true
    }

    /**
     * Called by a parent to request that a child update its values for `mScrollX` and `mScrollY` if
     * necessary. If the `continueSettling` method of [mDragHelper] returns *true* for the defer
     * callbacks value of *true* (indicating the its drag state is STATE_SETTLING) we call the
     * `postInvalidateOnAnimation` method of [ViewCompat] to have it cause an invalidate to happen
     * for *this* [View] on the next animation time step.
     */
    override fun computeScroll() {
        if (mDragHelper.continueSettling(true)) {
            ViewCompat.postInvalidateOnAnimation(this)
        }
    }

    /**
     * Called from the `onViewCaptured` override of the [DragHelperCallback] class we construct when
     * we create the [ViewDragHelper] to initialize [mDragHelper]. We loop over `c` for all of the
     * [DragCallback] instances in [mDragCallbacks] and call their `onStartDraggingOpen` method.
     * Then we set the visibility of [mHistoryFrame] to VISIBLE.
     */
    private fun onStartDragging() {
        for (c in mDragCallbacks) {
            c.onStartDraggingOpen()
        }
        mHistoryFrame.visibility = View.VISIBLE
    }

    /**
     * Determines whether the point ([x],[y]) is within the hit rectangle of [view]. First we fill
     * [mHitRect] with the hit rectangle of [view] in its parent's coordinates. Then we call the
     * [offsetDescendantRectToMyCoords] method to offset [mHitRect] from its parent's coordinate
     * space to our coordinate space (this does nothing on my Pixel test device, but might on a
     * device which uses different layouts). Finally we return the value that the `contains` method
     * of [mHitRect] returns when it checks whether the point ([x],[y]) is within its bounds.
     *
     * @param view the child [View] which needs to know if it is being dragged.
     * @param x the X coordinate of the event
     * @param y the Y coordinate of the event
     * @return *true* if the point ([x],[y]) is within the hit rectangle of [view]
     */
    fun isViewUnder(view: View, x: Int, y: Int): Boolean {
        view.getHitRect(mHitRect)
        offsetDescendantRectToMyCoords(view.parent as View, mHitRect)
        return mHitRect.contains(x, y)
    }

    /**
     * Closes this [DragLayout]. We set our [isOpen] property to *false*, and set the visibility of
     * [mHistoryFrame] to INVISIBLE. If our field [mCloseCallback] is not *null* we call its `onClose`
     * callback.
     */
    fun setClosed() {
        isOpen = false
        mHistoryFrame.visibility = View.INVISIBLE
        if (mCloseCallback != null) {
            mCloseCallback?.onClose()
        }
    }

    /**
     * Called from the [HistoryFragment] override of `onCreateAnimator` in order to attach an
     * [AnimatorListenerAdapter] to the [Animator] that the fragment loads when the fragment is
     * added/attached/shown or removed/detached/hidden. If our [isOpen] property is the same as
     * our parameter [toOpen] we return a do nothing [ValueAnimator] that animates from 0f to 1f
     * with a 0L millisecond duration. Otherwise we set our [isOpen] property to [toOpen], and the
     * visibility of [mHistoryFrame] to VISIBLE. We then create a [ValueAnimator] `animator` that
     * animates from 0f to 1f, and add an anonymous [AnimatorListenerAdapter] to it whose
     * [onAnimationStart] override cancels any [mDragHelper] motion in progress, then calls its
     * `smoothSlideViewTo` method to animate the [mHistoryFrame] view to a left position of 0, and
     * a top position of 0 if [isOpen] is *true* or minus [mVerticalRange] if it is *false*. Finally
     * we return `animator` to the caller.
     *
     * @param toOpen *true* if the [HistoryFragment] is being added/attached/shown
     * @return an [Animator] to which an [AnimatorListenerAdapter] is added iff the current [isOpen]
     * state is different from the parameter [toOpen] (if it is the same state we just return a do
     * nothing [Animator] in order to keep the compiler happy).
     */
    fun createAnimator(toOpen: Boolean): Animator {
        if (isOpen == toOpen) {
            return ValueAnimator.ofFloat(0f, 1f).setDuration(0L)
        }

        isOpen = toOpen
        mHistoryFrame.visibility = View.VISIBLE

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.addListener(object : AnimatorListenerAdapter() {
            /**
             * Notifies the start of the animation. First we cancel any [mDragHelper] motion in
             * progress, then we call its `smoothSlideViewTo` method to animate the [mHistoryFrame]
             * view to a left position of 0, and a top position of 0 if [isOpen] is *true* or minus
             * [mVerticalRange] if it is *false*.
             *
             * @param animation The started animation.
             */
            override fun onAnimationStart(animation: Animator) {
                mDragHelper.cancel()
                mDragHelper.smoothSlideViewTo(mHistoryFrame, 0, if (isOpen) 0 else -mVerticalRange)
            }
        })

        return animator
    }

    /**
     * Setter for our [mCloseCallback] field.
     *
     * @param callback the [CloseCallback] that [mCloseCallback] should use
     */
    fun setCloseCallback(callback: CloseCallback) {
        mCloseCallback = callback
    }

    /**
     * Adds the [DragCallback] passed in [callback] to our field [mDragCallbacks].
     *
     * @param callback the [DragCallback] to add to [mDragCallbacks]
     */
    fun addDragCallback(callback: DragCallback) {
        mDragCallbacks.add(callback)
    }

    /**
     * Removes the [DragCallback] passed in [callback] from our field [mDragCallbacks].
     *
     * @param callback the [DragCallback] to remove from [mDragCallbacks]
     */
    fun removeDragCallback(callback: DragCallback) {
        mDragCallbacks.remove(callback)
    }

    /**
     * Callback when the layout is closed. We use this to pop the [HistoryFragment] off the backstack.
     * We can't use a method in [DragCallback] because we get ConcurrentModificationExceptions on
     * [mDragCallbacks] when `executePendingTransactions` is called for popping the fragment off the
     * backstack.
     */
    interface CloseCallback {
        /**
         * This callback is called when the [DragLayout] is closed.
         */
        fun onClose()
    }

    /**
     * Callbacks for coordinating with the `RecyclerView` or [HistoryFragment].
     */
    interface DragCallback {

        /**
         * Override this to return the measured height of the calculator display.
         *
         * @return the current height of the display.
         */
        fun displayHeightFetch(): Int

        /**
         * Callback when a drag to open begins.
         */
        fun onStartDraggingOpen()

        /**
         * Callback which is called from our [onRestoreInstanceState] override.
         *
         * @param isOpen the value of our field [isOpen] that was stored by [onSaveInstanceState].
         */
        fun onInstanceStateRestored(isOpen: Boolean)

        /**
         * Animate the RecyclerView text.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         */
        fun whileDragging(yFraction: Float)

        /**
         * Whether we should allow the view to be dragged.
         *
         * @param view the [View] that the user is attempting to capture.
         * @param x the X coordinate of the user's motion event
         * @param y the Y coordinate of the user's motion event
         * @return *true* if the dragging of the [view] should be allowed.
         */
        fun shouldCaptureView(view: View, x: Int, y: Int): Boolean
    }

    /**
     * Our custom [ViewDragHelper.Callback].
     */
    inner class DragHelperCallback : ViewDragHelper.Callback() {
        /**
         * Called when the drag state changes. If the view has stopped moving ([state] is STATE_IDLE)
         * and the top of the captured view of [mDragHelper] is less than half of our [mVerticalRange]
         * (the view is less than half open) we call our [setClosed] method to close our [DragLayout].
         *
         * @param state The new drag state
         */
        override fun onViewDragStateChanged(state: Int) {
            // The view stopped moving.

            if (state == ViewDragHelper.STATE_IDLE
                    && mDragHelper.capturedView!!.top < -(mVerticalRange / 2)) {
                setClosed()
            }
        }

        /**
         * Called when the captured view's position changes as the result of a drag or settle. We
         * loop through all the `c` [DragCallback] in [mDragCallbacks] calling their `whileDragging`
         * callback overrides to inform them of the fraction of our [DragLayout] which is currently
         * exposed.
         *
         * @param changedView View whose position changed
         * @param left New X coordinate of the left edge of the view
         * @param top New Y coordinate of the top edge of the view
         * @param dx Change in X position from the last call
         * @param dy Change in Y position from the last call
         */
        override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
            for (c in mDragCallbacks) {
                // Top is between [-mVerticalRange, 0].
                c.whileDragging(1f + top.toFloat() / mVerticalRange)
            }
        }

        /**
         * Return the magnitude of a draggable child view's vertical range of motion in pixels. We
         * just return our field [mVerticalRange].
         *
         * @param child Child view to check
         * @return range of vertical motion in pixels
         */
        override fun getViewVerticalDragRange(child: View): Int {
            return mVerticalRange
        }

        /**
         * Called when the user's input indicates that they want to capture the given child view
         * with the pointer indicated by [pointerId]. The callback should return *true* if the user
         * is permitted to drag the given view with the indicated pointer. We initialize our
         * variable `point` to the [PointF] stored under the key [pointerId] in [mLastMotionPoints]
         * if there is one or return *false* if it is *null*. We initialize our variable `x` to
         * the X coordinate of `point` and `y` to the Y coordinate of `point`. We then loop over the
         * `c` [DragCallback] in [mDragCallbacks] calling their `shouldCaptureView` methods for
         * [view], `x`, and `y` and return *false* if any of them return *false*. If none of them
         * return *false* we return *true* to the caller allowing the [view] to be captured.
         *
         * @param view Child the user is attempting to capture
         * @param pointerId ID of the pointer attempting the capture
         * @return *true* if capture should be allowed, *false* otherwise
         */
        override fun tryCaptureView(view: View, pointerId: Int): Boolean {
            val point = mLastMotionPoints[pointerId] ?: return false

            val x = point.x.toInt()
            val y = point.y.toInt()

            for (c in mDragCallbacks) {
                if (!c.shouldCaptureView(view, x, y)) {
                    return false
                }
            }
            return true
        }

        /**
         * Restrict the motion of the dragged child view along the vertical axis. The default
         * implementation does not allow vertical motion; the extending class must override this
         * method and provide the desired clamping. We return the larger of minus [mVerticalRange]
         * and the quantity found to be the minimum of [top] and 0.
         *
         * @param child Child view being dragged
         * @param top Attempted motion along the Y axis
         * @param dy Proposed change in position for top
         * @return The new clamped position for top
         */
        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int {
            return max(min(top, 0), -mVerticalRange)
        }

        /**
         * Called when a child view is captured for dragging or settling. The ID of the pointer
         * currently dragging the captured view is supplied. If [activePointerId] is identified
         * as INVALID_POINTER the capture is programmatic instead of pointer-initiated. First we
         * call our super's implementation of [onViewCaptured]. Then if our [DragLayout] is not
         * open we set our [isOpen] property to *true* and call our [onStartDragging] to start
         * dragging our captured view.
         *
         * @param capturedChild Child view that was captured
         * @param activePointerId Pointer id tracking the child capture
         */
        override fun onViewCaptured(capturedChild: View, activePointerId: Int) {
            super.onViewCaptured(capturedChild, activePointerId)

            if (!isOpen) {
                isOpen = true
                onStartDragging()
            }
        }

        /**
         * Called when the child view is no longer being actively dragged. The fling velocity is
         * also supplied, if relevant. The velocity values may be clamped to system minimums or
         * maximums.
         *
         * Calling code may decide to fling or otherwise release the view to let it settle into
         * place. It should do so using `settleCapturedViewAt(int, int)` or
         * `flingCapturedView(int, int, int, int)`. If the Callback invokes one of these methods,
         * the [ViewDragHelper] will enter STATE_SETTLING and the view capture will not fully end
         * until it comes to a complete stop. If neither of these methods is invoked before
         * [onViewReleased] returns, the view will stop in place and the [ViewDragHelper] will
         * return to STATE_IDLE}.
         *
         * We initialize our variable `settleToOpen` to:
         * - *true* when [yvel] is greater than [AUTO_OPEN_SPEED_LIMIT] (this gives higher priority
         * to speed over position).
         * - *false* when [yvel] is less than minus [AUTO_OPEN_SPEED_LIMIT]
         * - or *true* the `top` of [releasedChild] is greater than half open.
         *
         * If `settleToOpen` is *true* and [isOpen] is *true* we call the `settleCapturedViewAt`
         * method of [mDragHelper] to have it settle to an open (Y coordinate 0) position, other
         * wise we have it settle to minus [mVerticalRange] (closed), and if the call to
         * `settleCapturedViewAt` returns *true* (the animation should continue using calls to
         * `continueSettling`) we call the [ViewCompat.postInvalidateOnAnimation] method to cause
         * an invalidate to happen for *this* [DragLayout] on the next animation time step.
         *
         * @param releasedChild The captured child view now being released
         * @param xvel X velocity of the pointer as it left the screen in pixels per second.
         * @param yvel Y velocity of the pointer as it left the screen in pixels per second.
         */
        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            val settleToOpen: Boolean = when {
                yvel > AUTO_OPEN_SPEED_LIMIT -> // Speed has priority over position.
                    true
                yvel < -AUTO_OPEN_SPEED_LIMIT -> false
                else -> releasedChild.top > -(mVerticalRange / 2)
            }

            // If the view is not visible, then settle it closed, not open.
            if (mDragHelper.settleCapturedViewAt(0,
                            if (settleToOpen && isOpen) 0 else -mVerticalRange)) {
                ViewCompat.postInvalidateOnAnimation(this@DragLayout)
            }
        }
    }

    /**
     * Our static constants.
     */
    companion object {
        /**
         * The pixels per second speed limit used to determine whether a release of a fling should
         * auto open our [DragLayout].
         */
        private const val AUTO_OPEN_SPEED_LIMIT = 600.0
        /**
         * Key under which we store our [isOpen] property in the [Bundle] we return from our
         * [onSaveInstanceState] override and retrieve again in our [onRestoreInstanceState]
         * override.
         */
        private const val KEY_IS_OPEN = "IS_OPEN"
        /**
         * Key under which we store the [Parcelable] object containing the state of our super that
         * its [onSaveInstanceState] override returns in the [Bundle] we return from our
         * [onSaveInstanceState] override and retrieve again and restore by calling our super's
         * implementation of [onRestoreInstanceState] in our [onRestoreInstanceState] override.
         */
        private const val KEY_SUPER_STATE = "SUPER_STATE"
    }
}

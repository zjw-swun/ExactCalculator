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
     * - ACTION_MOVE:
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

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        saveLastMotion(event)
        return mDragHelper.shouldInterceptTouchEvent(event)
    }

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

    override fun computeScroll() {
        if (mDragHelper.continueSettling(true)) {
            ViewCompat.postInvalidateOnAnimation(this)
        }
    }

    private fun onStartDragging() {
        for (c in mDragCallbacks) {
            c.onStartDraggingOpen()
        }
        mHistoryFrame.visibility = View.VISIBLE
    }

    fun isViewUnder(view: View, x: Int, y: Int): Boolean {
        view.getHitRect(mHitRect)
        offsetDescendantRectToMyCoords(view.parent as View, mHitRect)
        return mHitRect.contains(x, y)
    }

    fun setClosed() {
        isOpen = false
        mHistoryFrame.visibility = View.INVISIBLE
        if (mCloseCallback != null) {
            mCloseCallback?.onClose()
        }
    }

    fun createAnimator(toOpen: Boolean): Animator {
        if (isOpen == toOpen) {
            return ValueAnimator.ofFloat(0f, 1f).setDuration(0L)
        }

        isOpen = toOpen
        mHistoryFrame.visibility = View.VISIBLE

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                mDragHelper.cancel()
                mDragHelper.smoothSlideViewTo(mHistoryFrame, 0, if (isOpen) 0 else -mVerticalRange)
            }
        })

        return animator
    }

    fun setCloseCallback(callback: CloseCallback) {
        mCloseCallback = callback
    }

    fun addDragCallback(callback: DragCallback) {
        mDragCallbacks.add(callback)
    }

    fun removeDragCallback(callback: DragCallback) {
        mDragCallbacks.remove(callback)
    }

    /**
     * Callback when the layout is closed.
     * We use this to pop the HistoryFragment off the backstack.
     * We can't use a method in DragCallback because we get ConcurrentModificationExceptions on
     * mDragCallbacks when executePendingTransactions() is called for popping the fragment off the
     * backstack.
     */
    interface CloseCallback {
        fun onClose()
    }

    /**
     * Callbacks for coordinating with the RecyclerView or HistoryFragment.
     */
    interface DragCallback {

        fun displayHeightFetch(): Int
        // Callback when a drag to open begins.
        fun onStartDraggingOpen()

        // Callback in onRestoreInstanceState.
        fun onInstanceStateRestored(isOpen: Boolean)

        /**
         * Animate the RecyclerView text.
         *
         * @param yFraction Fraction of the dragged [View] that is visible (0.0-1.0) 0.0 is closed.
         */
        fun whileDragging(yFraction: Float)

        // Whether we should allow the view to be dragged.
        fun shouldCaptureView(view: View, x: Int, y: Int): Boolean
    }

    inner class DragHelperCallback : ViewDragHelper.Callback() {
        override fun onViewDragStateChanged(state: Int) {
            // The view stopped moving.

            if (state == ViewDragHelper.STATE_IDLE && mDragHelper.capturedView!!.top < -(mVerticalRange / 2)) {
                setClosed()
            }
        }

        override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
            for (c in mDragCallbacks) {
                // Top is between [-mVerticalRange, 0].
                c.whileDragging(1f + top.toFloat() / mVerticalRange)
            }
        }

        override fun getViewVerticalDragRange(child: View): Int {
            return mVerticalRange
        }

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

        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int {
            return max(min(top, 0), -mVerticalRange)
        }

        override fun onViewCaptured(capturedChild: View, activePointerId: Int) {
            super.onViewCaptured(capturedChild, activePointerId)

            if (!isOpen) {
                isOpen = true
                onStartDragging()
            }
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            val settleToOpen: Boolean = when {
                yvel > AUTO_OPEN_SPEED_LIMIT -> // Speed has priority over position.
                    true
                yvel < -AUTO_OPEN_SPEED_LIMIT -> false
                else -> releasedChild.top > -(mVerticalRange / 2)
            }

            // If the view is not visible, then settle it closed, not open.
            if (mDragHelper.settleCapturedViewAt(0, if (settleToOpen && isOpen)
                        0
                    else
                        -mVerticalRange)) {
                ViewCompat.postInvalidateOnAnimation(this@DragLayout)
            }
        }
    }

    companion object {

        private const val AUTO_OPEN_SPEED_LIMIT = 600.0
        private const val KEY_IS_OPEN = "IS_OPEN"
        private const val KEY_SUPER_STATE = "SUPER_STATE"
    }
}

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

class DragLayout(context: Context, attrs: AttributeSet) : ViewGroup(context, attrs) {

    private var mHistoryFrame: FrameLayout? = null
    private var mDragHelper: ViewDragHelper? = null

    // No concurrency; allow modifications while iterating.
    private val mDragCallbacks = CopyOnWriteArrayList<DragCallback>()
    private var mCloseCallback: CloseCallback? = null

    @SuppressLint("UseSparseArrays")
    private val mLastMotionPoints = HashMap<Int, PointF>()
    private val mHitRect = Rect()

    private var mVerticalRange: Int = 0
    var isOpen: Boolean = false
        private set

    val isMoving: Boolean
        get() {
            val draggingState = mDragHelper!!.viewDragState
            return draggingState == ViewDragHelper.STATE_DRAGGING || draggingState == ViewDragHelper.STATE_SETTLING
        }

    override fun onFinishInflate() {
        mDragHelper = ViewDragHelper.create(this, 1.0f, DragHelperCallback())
        mHistoryFrame = findViewById(R.id.history_frame)
        super.onFinishInflate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        measureChildren(widthMeasureSpec, heightMeasureSpec)
    }

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
                top = if (mDragHelper!!.capturedView === mHistoryFrame && mDragHelper!!.viewDragState != ViewDragHelper.STATE_IDLE) {
                    child.getTop()
                } else {
                    if (isOpen) 0 else -mVerticalRange
                }
            }
            child.layout(0, top, child.measuredWidth, top + child.measuredHeight)
        }
    }

    override fun onSaveInstanceState(): Parcelable? {
        val bundle = Bundle()
        bundle.putParcelable(KEY_SUPER_STATE, super.onSaveInstanceState())
        bundle.putBoolean(KEY_IS_OPEN, isOpen)
        return bundle
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        var stateLocal = state
        if (stateLocal is Bundle) {
            val bundle = stateLocal as Bundle?
            isOpen = bundle!!.getBoolean(KEY_IS_OPEN)
            mHistoryFrame!!.visibility = if (isOpen) View.VISIBLE else View.INVISIBLE
            for (c in mDragCallbacks) {
                c.onInstanceStateRestored(isOpen)
            }

            stateLocal = bundle.getParcelable(KEY_SUPER_STATE)
        }
        super.onRestoreInstanceState(stateLocal)
    }

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
        return mDragHelper!!.shouldInterceptTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Workaround: do not process the error case where multi-touch would cause a crash.
        if (event.actionMasked == MotionEvent.ACTION_MOVE
                && mDragHelper!!.viewDragState == ViewDragHelper.STATE_DRAGGING
                && mDragHelper!!.activePointerId != ViewDragHelper.INVALID_POINTER
                && event.findPointerIndex(mDragHelper!!.activePointerId) == -1) {
            mDragHelper!!.cancel()
            return false
        }

        saveLastMotion(event)

        mDragHelper!!.processTouchEvent(event)
        return true
    }

    override fun computeScroll() {
        if (mDragHelper!!.continueSettling(true)) {
            ViewCompat.postInvalidateOnAnimation(this)
        }
    }

    private fun onStartDragging() {
        for (c in mDragCallbacks) {
            c.onStartDraggingOpen()
        }
        mHistoryFrame!!.visibility = View.VISIBLE
    }

    fun isViewUnder(view: View, x: Int, y: Int): Boolean {
        view.getHitRect(mHitRect)
        offsetDescendantRectToMyCoords(view.parent as View, mHitRect)
        return mHitRect.contains(x, y)
    }

    fun setClosed() {
        isOpen = false
        mHistoryFrame!!.visibility = View.INVISIBLE
        if (mCloseCallback != null) {
            mCloseCallback!!.onClose()
        }
    }

    fun createAnimator(toOpen: Boolean): Animator {
        if (isOpen == toOpen) {
            return ValueAnimator.ofFloat(0f, 1f).setDuration(0L)
        }

        isOpen = toOpen
        mHistoryFrame!!.visibility = View.VISIBLE

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                mDragHelper!!.cancel()
                mDragHelper!!.smoothSlideViewTo(mHistoryFrame!!, 0, if (isOpen) 0 else -mVerticalRange)
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

            if (state == ViewDragHelper.STATE_IDLE && mDragHelper!!.capturedView!!.top < -(mVerticalRange / 2)) {
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
            if (mDragHelper!!.settleCapturedViewAt(0, if (settleToOpen && isOpen)
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

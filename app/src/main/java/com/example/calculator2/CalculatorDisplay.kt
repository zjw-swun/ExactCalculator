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

import android.annotation.SuppressLint
import android.content.Context
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionManager
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.Toolbar

/**
 * This is the [LinearLayout] in our ui which contains the toolbar, formula, and result. Its id in
 * the layout files display_one_line.xml and display_two_line.xml is R.id.display
 */
class CalculatorDisplay
@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : LinearLayout(context, attrs, defStyleAttr), AccessibilityManager.AccessibilityStateChangeListener {

    /**
     * This [Runnable] is used to hide the [Toolbar] after a 3 second time delay (or immediately).
     */
    private val mHideToolbarRunnable = object : Runnable {
        /**
         * This is either called directly to immediately hide the toolbar, or by using the
         * *postDelayed* method of [View] to have the tool auto-hide after a time delay. First
         * we call the [removeCallbacks] method to remove any duplicates of us to be removed
         * from the message queue. If our [View] has been laid out at least once we call the
         * *beginDelayedTransition* method of the [TransitionManager] to have it run the *fade*
         * transition in [mTransition] to animate the change when we set the visibility of
         * [mToolbar] to INVISIBLE with the final line of code.
         */
        override fun run() {
            // Remove any duplicate callbacks to hide the toolbar.
            removeCallbacks(this)

            // Only animate if we have been laid out at least once.
            if (isLaidOut) {
                TransitionManager.beginDelayedTransition(this@CalculatorDisplay, mTransition)
            }
            mToolbar.visibility = View.INVISIBLE
        }
    }

    /**
     * Our handle to the system level service ACCESSIBILITY_SERVICE
     */
    private val mAccessibilityManager: AccessibilityManager
            = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    /**
     * The [GestureDetector] we use to intercept taps to our [View] which we interpret as a request
     * to show the toolbar if it is not VISIBLE, or to hide it if it is VISIBLE.
     */
    private val mTapDetector: GestureDetector

    /**
     * The [Toolbar] in our [LinearLayout] with id R.id.toolbar
     */
    private lateinit var mToolbar: Toolbar
    /**
     * The [Fade] transition we use when our [mHideToolbarRunnable] runnable hides the toolbar.
     */
    private lateinit var mTransition: Transition

    /**
     *
     */
    private var mForceToolbarVisible: Boolean = false

    /**
     * If set to `true` the toolbar should remain visible.
     */
    var forceToolbarVisible: Boolean
        get() = mForceToolbarVisible || mAccessibilityManager.isEnabled
        set(forceToolbarVisible) {
            if (mForceToolbarVisible != forceToolbarVisible) {
                mForceToolbarVisible = forceToolbarVisible
                showToolbar(!forceToolbarVisible)
            }
        }

    val isToolbarVisible: Boolean
        get() = mToolbar.visibility == View.VISIBLE

    init {
        mTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                // Remove callbacks to hide the toolbar.
                removeCallbacks(mHideToolbarRunnable)

                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (mToolbar.visibility != View.VISIBLE) {
                    showToolbar(true)
                } else {
                    hideToolbar()
                }

                return true
            }
        })

        // Draw the children in reverse order so that the toolbar is on top.
        isChildrenDrawingOrderEnabled = true
    }

    override fun onFinishInflate() {
        super.onFinishInflate()

        mToolbar = findViewById(R.id.toolbar)
        mTransition = Fade()
                .setDuration(FADE_DURATION)
                .addTarget(mToolbar)
    }

    override fun getChildDrawingOrder(childCount: Int, i: Int): Int {
        // Reverse the normal drawing order.
        return childCount - 1 - i
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mAccessibilityManager.addAccessibilityStateChangeListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mAccessibilityManager.removeAccessibilityStateChangeListener(this)
    }

    override fun onAccessibilityStateChanged(enabled: Boolean) {
        // Always show the toolbar whenever accessibility is enabled.
        showToolbar(true)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        mTapDetector.onTouchEvent(event)
        return super.onInterceptTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return mTapDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    /**
     * Shows the toolbar.
     *
     * @param autoHide Automatically ide toolbar again after delay
     */
    fun showToolbar(autoHide: Boolean) {
        // Only animate if we have been laid out at least once.
        if (isLaidOut) {
            TransitionManager.beginDelayedTransition(this, mTransition)
        }
        mToolbar.visibility = View.VISIBLE

        // Remove callbacks to hide the toolbar.
        removeCallbacks(mHideToolbarRunnable)

        // Auto hide the toolbar after 3 seconds.
        if (autoHide && !forceToolbarVisible) {
            postDelayed(mHideToolbarRunnable, AUTO_HIDE_DELAY_MILLIS)
        }
    }

    /**
     * Hides the toolbar.
     */
    fun hideToolbar() {
        if (!forceToolbarVisible) {
            removeCallbacks(mHideToolbarRunnable)
            mHideToolbarRunnable.run()
        }
    }

    companion object {

        /**
         * The duration in milliseconds after which to hide the toolbar.
         */
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L

        /**
         * The duration in milliseconds to fade in/out the toolbar.
         */
        private const val FADE_DURATION = 200L
    }
}

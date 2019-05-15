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
     * Strange sort of backing toggle field for our property [forceToolbarVisible]
     */
    private var mForceToolbarVisible: Boolean = false

    /**
     * If set to `true` the toolbar should remain visible, its last value is stored in our backing
     * toggle field [mForceToolbarVisible]. The [set] method will change the visibility of the tool
     * bar if the [mForceToolbarVisible] changes state as a result of the call.
     */
    var forceToolbarVisible: Boolean
        get() = mForceToolbarVisible || mAccessibilityManager.isEnabled
        set(forceToolbarVisible) {
            if (mForceToolbarVisible != forceToolbarVisible) {
                mForceToolbarVisible = forceToolbarVisible
                showToolbar(!forceToolbarVisible)
            }
        }

    /**
     * If *true* the tool bar [mToolbar] is currently visible.
     */
    val isToolbarVisible: Boolean
        get() = mToolbar.visibility == View.VISIBLE

    init {
        /**
         * The *GestureDetector* which interprets the *MotionEvent* touches which are passed it by
         * our *onInterceptTouchEvent* and *onTouchEvent* overrides.
         */
        mTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            /**
             * Notified when a tap occurs with the down [MotionEvent] that triggered it. This will
             * be triggered immediately for every down event. All other events should be preceded by
             * this. We remove all [mHideToolbarRunnable] runnables from the queue, and return *true*
             * to consume the event.
             *
             * @param e The down motion event.
             * @return true if the event is consumed, else false
             */
            override fun onDown(e: MotionEvent): Boolean {
                // Remove callbacks to hide the toolbar.
                removeCallbacks(mHideToolbarRunnable)

                return true
            }

            /**
             * Notified when a single-tap occurs. If the [mToolbar] tool bar is not visible we call
             * our [showToolbar] method to make it visible with *true* for  the *autoHide* parameter
             * so that it will be made invisible again after a 3 second delay, otherwise we call
             * our [hideToolbar] method to make the tool bar invisible. Finally we return *true* to
             * consume the event.
             *
             * @param e The down motion event of the single-tap.
             * @return true if the event is consumed, else false
             */
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (mToolbar.visibility != View.VISIBLE) {
                    showToolbar(true)
                } else {
                    hideToolbar()
                }

                return true
            }
        })

        // Draw the children in reverse order so that the toolbar is on top. Setting this to `true`
        // causes our `getChildDrawingOrder` method to be called in order to define the order that
        // our children will be drawn (it reverses the normal drawing order).
        isChildrenDrawingOrderEnabled = true
    }

    /**
     * Finalize inflating a view from XML. This is called as the last phase of inflation, after all
     * child views have been added. First we call our super's implementation of [onFinishInflate],
     * then we initialize our field [mToolbar] by finding the [Toolbar] with id R.id.toolbar. We
     * initialize our field [mTransition] with a [Fade] transition that will fade targets in and out,
     * set its duration to FADE_DURATION (200ms) and add [mToolbar] as a target view that this
     * [Transition] will be interested in animating.
     */
    override fun onFinishInflate() {
        super.onFinishInflate()

        mToolbar = findViewById(R.id.toolbar)
        mTransition = Fade()
                .setDuration(FADE_DURATION)
                .addTarget(mToolbar)
    }

    /**
     * Returns the index of the child to draw for this iteration. Override this if you want to
     * change the drawing order of children. By default, it returns [i]. We return [childCount]
     * minus 1, minus [i] (the reverse order of [i]).
     *
     * @param childCount the number of children we have.
     * @param i The current iteration.
     * @return The index of the child to draw this iteration.
     */
    override fun getChildDrawingOrder(childCount: Int, i: Int): Int {
        // Reverse the normal drawing order.
        return childCount - 1 - i
    }

    /**
     * This is called when the view is attached to a window. First we call our super's implementation
     * of [onAttachedToWindow], then we call the *addAccessibilityStateChangeListener* method of
     * [mAccessibilityManager] to add *this* as an *AccessibilityStateChangeListener* (this causes
     * our [onAccessibilityStateChanged] override to be called when there is a change in the
     * accessibility state).
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mAccessibilityManager.addAccessibilityStateChangeListener(this)
    }

    /**
     * This is called when the view is detached from a window. At this point it no longer has a
     * surface for drawing. First we call our super's implementation of [onDetachedFromWindow] then
     * we call the *removeAccessibilityStateChangeListener* method of [mAccessibilityManager] to
     * remove this as a *AccessibilityStateChangeListener*.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mAccessibilityManager.removeAccessibilityStateChangeListener(this)
    }

    /**
     * Called when there is a change in the accessibility state. We just call our method [showToolbar]
     * with the *autoHide* parameter *true* to show the tool bar, and auto hide after a 3 second
     * delay.
     *
     * @param enabled Whether accessibility is enabled.
     */
    override fun onAccessibilityStateChanged(enabled: Boolean) {
        // Always show the toolbar whenever accessibility is enabled.
        showToolbar(true)
    }

    /**
     * Implement this method to intercept all touch screen motion events. This allows you to watch
     * events as they are dispatched to your children, and take ownership of the current gesture at
     * any point. First we call the *onTouchEvent* method of our [GestureDetector] ([mTapDetector])
     * to have it interpret what is going on with appropriate calls to its overrides *onDown* and
     * *onSingleTapConfirmed*. Then we return the value returned by our super's implementation of
     * [onInterceptTouchEvent].
     *
     * @param event The motion event being dispatched down the hierarchy.
     * @return Return true to steal motion events from the children and have
     * them dispatched to this ViewGroup through onTouchEvent().
     * The current target will receive an ACTION_CANCEL event, and no further
     * messages will be delivered here.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        mTapDetector.onTouchEvent(event)
        return super.onInterceptTouchEvent(event)
    }

    /**
     * Implement this method to handle touch screen motion events. We return the value returned by
     * the [onTouchEvent] method of our [GestureDetector] ([mTapDetector]) if it is *true* or else
     * the value value returned by our super's implementation of [onTouchEvent].
     *
     * @param event The motion event.
     * @return True if the event was handled, false otherwise.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return mTapDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    /**
     * Shows the toolbar. If our view has been laid out at least once ([isLaidOut] is *true*) we call
     * the *beginDelayedTransition* of the [TransitionManager] to have it run our fade animation
     * [mTransition] between our current scene and the next frame (which will include the visible
     * tool bar) otherwise we don't bother animating the change. Next we set the visibility of
     * [mToolbar] to VISIBLE, remove all [mHideToolbarRunnable]'s from the queue. If our parameter
     * [autoHide] is *true* and our property [forceToolbarVisible] is *false* we post a delayed
     * running of [mHideToolbarRunnable] to automatically hide the toolbar again after the delay
     * AUTO_HIDE_DELAY_MILLIS (3000ms).
     *
     * @param autoHide Automatically hide toolbar again after delay
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
     * Hides the toolbar. If our property [forceToolbarVisible] is *false* we remove all of the
     * [mHideToolbarRunnable]'s from the queue, and then call the [run] method of [mHideToolbarRunnable]
     * to have it hide the toolbar immediately.
     */
    fun hideToolbar() {
        if (!forceToolbarVisible) {
            removeCallbacks(mHideToolbarRunnable)
            mHideToolbarRunnable.run()
        }
    }

    /**
     * Contains our static constants.
     */
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

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

import android.annotation.TargetApi
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.text.Layout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.TextView

/**
 * [TextView] adapted for displaying the formula and allowing pasting.The JvmOverloads annotation
 * causes the Kotlin compiler to generate overloads that substitute default parameter values.
 *
 * @param context The Context the view is running in, through which it can access the current theme,
 * resources, etc.
 * @param attrs The attributes of the XML tag that is inflating the view.
 * @param defStyleAttr An attribute in the current theme that contains a reference to a style
 * resource that supplies default values for the view. Can be 0 to not look for defaults.
 */
@Suppress("MemberVisibilityCanBePrivate")
class CalculatorFormula
@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0)
    : AlignedTextView(context, attrs, defStyleAttr), MenuItem.OnMenuItemClickListener,
        ClipboardManager.OnPrimaryClipChangedListener {

    /**
     * Temporary paint for use in layout methods.
     */
    private val mTempPaint = TextPaint()

    // The follow three values are used to vary the size of the text based on how many characters
    // need to be displayed, and are set in the dimens.xml files for the different screen sizes.
    // Unfortunately my pixel test device does not match any of the screen sizes so the default
    // dimens.xml is used so there is no change of the text size as you add characters. (Bummer!)
    /**
     * The CalculatorFormula_maxTextSize attribute for this [TextView], defaults to the value
     * returned by the [getTextSize] method (aka the `textSize` property of this [TextView]).
     * It is set in the dimens.xml files for the various screen sizes, with the default 28dp.
     */
    val maximumTextSize: Float
    /**
     * The CalculatorFormula_minTextSize attribute for this [TextView], defaults to the value
     * returned by the [getTextSize] method (aka the `textSize` property of this [TextView]).
     * It is set in the dimens.xml files for the various screen sizes, with the default 28dp.
     */
    val minimumTextSize: Float
    /**
     * The CalculatorFormula_stepTextSize attribute for this [TextView], defaults to the value
     * [maximumTextSize] minus [minimumTextSize] divided by 3. It is set in the dimens.xml files
     * for the various screen sizes, with the default 8dp.
     */
    private val mStepTextSize: Float

    /**
     * A handle to the [ClipboardManager] to use for pasting.
     */
    private val mClipboardManager: ClipboardManager
            = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    /**
     * How wide is the space for our text, used to decide whether to change the text size to fit.
     */
    private var mWidthConstraint = -1
    /**
     * [ActionMode] we use for pasting.
     */
    private var mActionMode: ActionMode? = null
    /**
     * [ActionMode.Callback] that is used for our [mActionMode] pasting [ActionMode].
     */
    private var mPasteActionModeCallback: ActionMode.Callback? = null
    /**
     * The [ContextMenu] that is displayed when a user long clicks us, used to paste from the clipboard,
     * or to paste from memory if one or both are holding something.
     */
    private var mContextMenu: ContextMenu? = null
    /**
     * The [OnTextSizeChangeListener] that is called to animate a change in text size.
     */
    private var mOnTextSizeChangeListener: OnTextSizeChangeListener? = null
    /**
     * The [OnFormulaContextMenuClickListener] whose `onPaste` or `onMemoryRecall` overrides are
     * called when the user selects one or the other of these options from the [ContextMenu]
     */
    private var mOnContextMenuClickListener: OnFormulaContextMenuClickListener? = null
    /**
     * The [Calculator2.OnDisplayMemoryOperationsListener] whose `shouldDisplayMemory` is called to
     * determine if the memory of the calculator is currently holding an expression.
     */
    private var mOnDisplayMemoryOperationsListener: Calculator2.OnDisplayMemoryOperationsListener? = null

    /**
     * Property that can be queried to determine if there is data in the memory of the calculator.
     * The getter for this property returns *false* if our field [mOnDisplayMemoryOperationsListener]
     * is null or if its `shouldDisplayMemory` method returns *false*, otherwise it returns *true*.
     */
    private val isMemoryEnabled: Boolean
        get() = mOnDisplayMemoryOperationsListener != null
                && mOnDisplayMemoryOperationsListener!!.shouldDisplayMemory()

    /**
     * Property that can be queried to determine if there is data on the clipboard. The getter for
     * this property initializes the variable `clip` with the current primary clip on the clipboard.
     * If `clip` is *null* or the `itemCount` field (number of items in the clip data) is 0 it returns
     * *false* to the caller. It then initializes the variable `clipText` to a *null* `CharSequence`.
     * Wrapped in a try block intended to catch and log any Exception, it sets `clipText` to the index
     * 0 item in `clip` coerced to text. Finally it returns *true* if `clipText` is not *null* or
     * empty.
     */
    private val isPasteEnabled: Boolean
        get() {
            val clip = mClipboardManager.primaryClip
            if (clip == null || clip.itemCount == 0) {
                return false
            }
            var clipText: CharSequence? = null
            try {
                clipText = clip.getItemAt(0).coerceToText(context)
            } catch (e: Exception) {
                Log.i("Calculator", "Error reading clipboard:", e)
            }

            return !TextUtils.isEmpty(clipText)
        }

    /**
     * Our init block. We initialize our variable `a` the `TypedArray` that the `obtainStyledAttributes`
     * method of `context` returns for its styled attribute information for the `CalculatorFormula`
     * custom attributes ("minTextSize", "maxTextSize", and "stepTextSize") after resolving the
     * `attrs` attributes of the XML tag that is inflating the view, and the `defStyleAttr` default
     * values. We initialize our field `maximumTextSize` with the CalculatorFormula_maxTextSize value
     * in `a` (defaulting to the `textSize` property), initialize our field `minimumTextSize` with
     * the CalculatorFormula_minTextSize value in `a` (defaulting to the `textSize` property), and
     * initialize our field `mStepTextSize` with the CalculatorFormula_stepTextSize value in `a`
     * (defaulting to `maximumTextSize` minus `minimumTextSize` divided by 3). We then recycle `a`.
     * If the SDK version of the software currently running on this hardware device is greater than
     * or equal to "M" we call our `setupActionMode` method to set up `ActionMode` for paste support,
     * otherwise we call our `setupContextMenu` method to set up `ContextMenu` for paste support.
     */
    init {

        val a = context.obtainStyledAttributes(
                attrs, R.styleable.CalculatorFormula, defStyleAttr, 0)
        maximumTextSize = a.getDimension(
                R.styleable.CalculatorFormula_maxTextSize, textSize)
        minimumTextSize = a.getDimension(
                R.styleable.CalculatorFormula_minTextSize, textSize)
        mStepTextSize = a.getDimension(R.styleable.CalculatorFormula_stepTextSize,
                (maximumTextSize - minimumTextSize) / 3)
        a.recycle()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            setupActionMode()
        } else {
            setupContextMenu()
        }
    }

    /**
     * Measure the view and its content to determine the measured width and the measured height. If
     * our view has __not__ been through at least one layout since it was last attached to or detached
     * from a window we call our [setTextSizeInternal] method to set our text size to [maximumTextSize]
     * in pixels, with the `notifyListener` *false* so that it does not bother to call the
     * `onTextSizeChanged` override of [mOnTextSizeChangeListener], then we set the minimum height
     * property of our view to the vertical distance between lines of text plus the bottom padding
     * and the top padding of our view. We initialize our variable `width` to the width of our parent
     * as specified in [widthMeasureSpec] and if our `minimumWidth` property is not equal to `width`
     * we set it to `width`. We set our [mWidthConstraint] field to the width of our parent as specified
     * in [widthMeasureSpec] minus our left padding and our right padding. We initialize our variable
     * `textSize` to the variable text size calculated by our [getVariableTextSize] method and if
     * the text size of this [TextView] is not equal to `textSize` we call our [setTextSizeInternal]
     * method to set our text size to `textSize` in pixels, with the `notifyListener` *false* so that
     * it does not bother to call the `onTextSizeChanged` override of [mOnTextSizeChangeListener].
     * Finally we call our super's implementation of `onMeasure`.
     *
     * @param widthMeasureSpec horizontal space requirements as imposed by the parent.
     * @param heightMeasureSpec vertical space requirements as imposed by the parent.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!isLaidOut) {
            // Prevent shrinking/resizing with our variable textSize.
            setTextSizeInternal(TypedValue.COMPLEX_UNIT_PX, maximumTextSize,false)
            minimumHeight = (lineHeight + compoundPaddingBottom + compoundPaddingTop)
        }

        // Ensure we are at least as big as our parent.
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (minimumWidth != width) {
            minimumWidth = width
        }

        // Re-calculate our textSize based on new width.
        mWidthConstraint = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
        val textSize = getVariableTextSize(text)
        if (getTextSize() != textSize) {
            setTextSizeInternal(TypedValue.COMPLEX_UNIT_PX, textSize, false)
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * This is called when the view is attached to a window. At this point it has a Surface and will
     * start drawing. First we call our super's implementation of `onAttachedToWindow`, then we call
     * the `addPrimaryClipChangedListener` of our clip board manager [mClipboardManager] to add *this*
     * as a `OnPrimaryClipChangedListener` (our [onPrimaryClipChanged] override will be called when
     * the primary clip changes). Finally we call our [onPrimaryClipChanged] method to initialize
     * our [isLongClickable] property appropriately.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        mClipboardManager.addPrimaryClipChangedListener(this)
        onPrimaryClipChanged()
    }

    /**
     * This is called when the view is detached from a window. At this point it no longer has a
     * surface for drawing. First we call our super's implementation of `onDetachedFromWindow`, then
     * we call the `removePrimaryClipChangedListener` method of [mClipboardManager] to remove *this*
     * as a [ClipboardManager.OnPrimaryClipChangedListener].
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        mClipboardManager.removePrimaryClipChangedListener(this)
    }

    /**
     * This method is called when the text is changed, in case any subclasses would like to know.
     * Within `text`, the `lengthAfter` characters beginning at `start` have just replaced old text
     * that had length `lengthBefore`. It is an error to attempt to make changes to `text` from this
     * callback.
     *
     * First we call our super's implementation of `onTextChanged`. Then we call our [setTextSize]
     * method to set our textsize to the text size value calculated by our [getVariableTextSize]
     * method.
     *
     * @param text The text the [TextView] is displaying
     * @param start The offset of the start of the range of the text that was modified
     * @param lengthBefore The length of the former text that has been replaced
     * @param lengthAfter The length of the replacement modified text
     */
    override fun onTextChanged(text: CharSequence, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)

        setTextSize(TypedValue.COMPLEX_UNIT_PX, getVariableTextSize(text.toString()))
    }

    /**
     * Called to set our text size, and optionally call the `onTextSizeChanged` override of our
     * text size change listener [mOnTextSizeChangeListener] so that it can animate a change in
     * text size. First we save our current `textSize` property in our variable `oldTextSize`.
     * Then we call our super's `setTextSize` method to have it change our text size to [size].
     * If our parameter [notifyListener] is *true*, and [mOnTextSizeChangeListener] is not *null*,
     * and our new `textSize` property is not equal to `oldTextSize` we call the `onTextSizeChanged`
     * override of our text size change listener [mOnTextSizeChangeListener] to have it animate the
     * change from `oldTextSize` to the new text size.
     *
     * @param unit the dimension unit of [size] (COMPLEX_UNIT_PX unless called by [setTextSize]).
     * @param size the size we are to set our text size to.
     * @param notifyListener if *true* we should call the `onTextSizeChanged` override of our
     * on text size change listener [mOnTextSizeChangeListener] so that it can animate a change.
     */
    private fun setTextSizeInternal(unit: Int, size: Float, notifyListener: Boolean) {
        val oldTextSize = textSize
        super.setTextSize(unit, size)
        if (notifyListener && mOnTextSizeChangeListener != null && textSize != oldTextSize) {
            mOnTextSizeChangeListener?.onTextSizeChanged(this, oldTextSize)
        }
    }

    /**
     * Set the default text size to a given unit and value. See [TypedValue] for the possible
     * dimension units. We just call our method [setTextSizeInternal] with *true* for the notify
     * listener parameter.
     *
     * @param unit The desired dimension unit.
     * @param size The desired size in the given units.
     */
    override fun setTextSize(unit: Int, size: Float) {
        setTextSizeInternal(unit, size, true)
    }

    /**
     * Calculates what text size will maximize the amount of [text] that can be displayed without
     * scrolling given the constraints [minimumTextSize] and [maximumTextSize] setting the minimum
     * and maximum text size. If [mWidthConstraint] is less than 0, or [maximumTextSize] is less
     * than of equal to [minimumTextSize] we have not been measured yet so we just return the current
     * value of our `textSize` property. Otherwise we copy our [TextPaint] property into [mTempPaint],
     * then we set our variable `lastFitTextSize` to [minimumTextSize] and loop while `lastFitTextSize`
     * is less than [maximumTextSize]:
     * - We set the `textSize` property to the minimum of `lastFitTextSize` plus [mStepTextSize] and
     * [maximumTextSize]
     * - We call the [Layout.getDesiredWidth] method to determine how wide a layout must be in order
     * to display the specified text with one line per paragraph, and if this is greater than
     * [mWidthConstraint] (our width) we break out of the loop.
     * - Otherwise we set `lastFitTextSize` to the `textSize` property and loop around to try the
     * next largest text size step.
     *
     * When we are done with the loop we return `lastFitTextSize` to the caller.
     *
     * @param text the text we need to fit in our [TextView]
     * @return the textsize in pixels that we should use.
     */
    fun getVariableTextSize(text: CharSequence): Float {
        if (mWidthConstraint < 0 || maximumTextSize <= minimumTextSize) {
            // Not measured, bail early.
            return textSize
        }

        // Capture current paint state.
        mTempPaint.set(paint)

        // Step through increasing text sizes until the text would no longer fit.
        var lastFitTextSize = minimumTextSize
        while (lastFitTextSize < maximumTextSize) {
            mTempPaint.textSize = Math.min(lastFitTextSize + mStepTextSize, maximumTextSize)
            if (Layout.getDesiredWidth(text, mTempPaint) > mWidthConstraint) {
                break
            }
            lastFitTextSize = mTempPaint.textSize
        }

        return lastFitTextSize
    }

    /**
     * Functionally equivalent to setText(), but explicitly announce changes. If the new text is an
     * extension of the old one, announce the addition. Otherwise, e.g. after deletion, announce the
     * entire new text. We initialize our variable `oldText` to our current `text` property, and
     * initialize our variable `separator` to the localized value for the "," digit separator that
     * [KeyMaps.translateResult] returns. We then initialize our variable `added` to the string that
     * the [StringUtils.getExtensionIgnoring] method returns when it compares `newText` and `oldText`
     * while ignoring `separator` characters. We then branch depending on whether `added` is null:
     * - **Not** *null*: If the length of `added` is 1, we initialize our variable `c` to the first
     * (zeroth) character of `added`, initialize our variable `id` to the resource id of the button
     * that `c` came from that the [KeyMaps.keyForChar] method finds (if there is one), then initialize
     * our variable `descr` with the descriptive string that the [KeyMaps.toDescriptiveString] method
     * finds for `id` (if there is one). Then if `descr` is not *null* we call our [announceForAccessibility]
     * method to have the accessibility service speak `descr`, and if it is *null* we call it to have
     * the accessibility service speak the string value of `c`. If the length of `added` is not 1 and
     * is not empty we call our [announceForAccessibility] method to have the accessibility service
     * speak `added`
     * - If `added` is *null* on the otherhand we call our [announceForAccessibility] method to have
     * the accessibility service speak [newText].
     *
     * Finally we call the [setText] method to have our [TextView] set its text to [newText] using
     * `BufferType.SPANNABLE` as the method to store it.
     *
     * @param newText the text that we are to display and announce.
     */
    fun changeTextTo(newText: CharSequence) {
        val oldText = text
        val separator = KeyMaps.translateResult(",")[0]
        val added = StringUtils.getExtensionIgnoring(newText, oldText, separator)
        if (added != null) {
            if (added.length == 1) {
                // The algorithm for pronouncing a single character doesn't seem
                // to respect our hints.  Don't give it the choice.
                val c = added[0]
                val id = KeyMaps.keyForChar(c)
                val descr = KeyMaps.toDescriptiveString(context, id)
                if (descr != null) {
                    announceForAccessibility(descr)
                } else {
                    announceForAccessibility(c.toString())
                }
            } else if (added.isNotEmpty()) {
                announceForAccessibility(added)
            }
        } else {
            announceForAccessibility(newText)
        }
        setText(newText, BufferType.SPANNABLE)
    }

    /**
     * Closes the action mode or context menu if one of them is open and returns *true* if one of
     * them was, returns *false* if neither was open. If [mActionMode] is not *null* we call its
     * `finish` method to finish and close it, then return *true* to the caller. If [mContextMenu]
     * is not *null* we call its `close` to close it, then return *true* to the caller. If both are
     * *null* we return *false* to the caller.
     *
     * @return *true* if either the action mode or context menu was being displayed, otherwise *false*
     */
    fun stopActionModeOrContextMenu(): Boolean {
        if (mActionMode != null) {
            mActionMode?.finish()
            return true
        }
        if (mContextMenu != null) {
            mContextMenu?.close()
            return true
        }
        return false
    }

    /**
     * Sets our [mOnTextSizeChangeListener] to our parameter [listener].
     *
     * @param listener the [OnTextSizeChangeListener] we are to use.
     */
    fun setOnTextSizeChangeListener(listener: OnTextSizeChangeListener) {
        mOnTextSizeChangeListener = listener
    }

    /**
     * Sets our [mOnContextMenuClickListener] to our parameter [listener].
     *
     * @param listener the [OnFormulaContextMenuClickListener] we are to use.
     */
    fun setOnContextMenuClickListener(listener: OnFormulaContextMenuClickListener) {
        mOnContextMenuClickListener = listener
    }

    fun setOnDisplayMemoryOperationsListener(
            listener: Calculator2.OnDisplayMemoryOperationsListener) {
        mOnDisplayMemoryOperationsListener = listener
    }

    /**
     * Use ActionMode for paste support on M and higher.
     */
    @TargetApi(Build.VERSION_CODES.M)
    private fun setupActionMode() {
        mPasteActionModeCallback = object : ActionMode.Callback2() {

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                return if (onMenuItemClick(item)) {
                    mode.finish()
                    true
                } else {
                    false
                }
            }

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                mode.tag = TAG_ACTION_MODE
                val inflater = mode.menuInflater
                return createContextMenu(inflater, menu)
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                mActionMode = null
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                super.onGetContentRect(mode, view, outRect)
                outRect.top += totalPaddingTop
                outRect.right -= totalPaddingRight
                outRect.bottom -= totalPaddingBottom
                // Encourage menu positioning over the rightmost 10% of the screen.
                outRect.left = (outRect.right * 0.9f).toInt()
            }
        }
        setOnLongClickListener {
            mActionMode = startActionMode(mPasteActionModeCallback, ActionMode.TYPE_FLOATING)
            true
        }
    }

    /**
     * Use ContextMenu for paste support on L and lower.
     */
    @Suppress("UNUSED_ANONYMOUS_PARAMETER")
    private fun setupContextMenu() {
        setOnCreateContextMenuListener { contextMenu, view, contextMenuInfo ->
            val inflater = MenuInflater(context)
            createContextMenu(inflater, contextMenu)
            mContextMenu = contextMenu
            for (i in 0 until contextMenu.size()) {
                contextMenu.getItem(i).setOnMenuItemClickListener(this@CalculatorFormula)
            }
        }
        setOnLongClickListener { showContextMenu() }
    }

    private fun createContextMenu(inflater: MenuInflater, menu: Menu): Boolean {
        val isPasteEnabled = isPasteEnabled
        val isMemoryEnabled = isMemoryEnabled
        if (!isPasteEnabled && !isMemoryEnabled) {
            return false
        }

        bringPointIntoView(length())
        inflater.inflate(R.menu.menu_formula, menu)
        val pasteItem = menu.findItem(R.id.menu_paste)
        val memoryRecallItem = menu.findItem(R.id.memory_recall)
        pasteItem.isEnabled = isPasteEnabled
        memoryRecallItem.isEnabled = isMemoryEnabled
        return true
    }

    private fun paste() {
        val primaryClip = mClipboardManager.primaryClip
        if (primaryClip != null && mOnContextMenuClickListener != null) {
            mOnContextMenuClickListener!!.onPaste(primaryClip)
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.memory_recall -> {
                mOnContextMenuClickListener!!.onMemoryRecall()
                true
            }
            R.id.menu_paste -> {
                paste()
                true
            }
            else -> false
        }
    }

    override fun onPrimaryClipChanged() {
        isLongClickable = isPasteEnabled || isMemoryEnabled
    }

    fun onMemoryStateChanged() {
        isLongClickable = isPasteEnabled || isMemoryEnabled
    }

    interface OnTextSizeChangeListener {
        fun onTextSizeChanged(textView: TextView, oldSize: Float)
    }

    interface OnFormulaContextMenuClickListener {
        fun onPaste(clip: ClipData): Boolean
        fun onMemoryRecall()
    }

    companion object {

        const val TAG_ACTION_MODE = "ACTION_MODE"
    }
}

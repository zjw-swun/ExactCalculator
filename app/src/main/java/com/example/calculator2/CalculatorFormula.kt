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
 * [TextView] adapted for displaying the formula and allowing pasting.
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
    private var mOnTextSizeChangeListener: OnTextSizeChangeListener? = null
    private var mOnContextMenuClickListener: OnFormulaContextMenuClickListener? = null
    private var mOnDisplayMemoryOperationsListener: Calculator2.OnDisplayMemoryOperationsListener? = null

    private val isMemoryEnabled: Boolean
        get() = mOnDisplayMemoryOperationsListener != null && mOnDisplayMemoryOperationsListener!!.shouldDisplayMemory()

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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!isLaidOut) {
            // Prevent shrinking/resizing with our variable textSize.
            setTextSizeInternal(TypedValue.COMPLEX_UNIT_PX, maximumTextSize,
                    false /* notifyListener */)
            minimumHeight = (lineHeight + compoundPaddingBottom
                    + compoundPaddingTop)
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
            setTextSizeInternal(TypedValue.COMPLEX_UNIT_PX, textSize, false /* notifyListener */)
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        mClipboardManager.addPrimaryClipChangedListener(this)
        onPrimaryClipChanged()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        mClipboardManager.removePrimaryClipChangedListener(this)
    }

    override fun onTextChanged(text: CharSequence, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)

        setTextSize(TypedValue.COMPLEX_UNIT_PX, getVariableTextSize(text.toString()))
    }

    private fun setTextSizeInternal(unit: Int, size: Float, notifyListener: Boolean) {
        val oldTextSize = textSize
        super.setTextSize(unit, size)
        if (notifyListener && mOnTextSizeChangeListener != null && textSize != oldTextSize) {
            mOnTextSizeChangeListener!!.onTextSizeChanged(this, oldTextSize)
        }
    }

    override fun setTextSize(unit: Int, size: Float) {
        setTextSizeInternal(unit, size, true)
    }

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
     * Functionally equivalent to setText(), but explicitly announce changes.
     * If the new text is an extension of the old one, announce the addition.
     * Otherwise, e.g. after deletion, announce the entire new text.
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

    fun stopActionModeOrContextMenu(): Boolean {
        if (mActionMode != null) {
            mActionMode!!.finish()
            return true
        }
        if (mContextMenu != null) {
            mContextMenu!!.close()
            return true
        }
        return false
    }

    fun setOnTextSizeChangeListener(listener: OnTextSizeChangeListener) {
        mOnTextSizeChangeListener = listener
    }

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
}/* attrs *//* defStyleAttr */

package com.v2ray.ang.util

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.app.AppCompatViewInflater
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatMultiAutoCompleteTextView
import androidx.appcompat.widget.AppCompatTextView

/**
 * Custom AppCompat view inflater that replaces every inflated text widget
 * (TextView / EditText / AutoCompleteTextView / MultiAutoCompleteTextView) with a
 * "safe" subclass that swallows the AOSP platform bug:
 *
 *     java.lang.IllegalStateException: Drag shadow dimensions must be positive
 *         at android.view.View.startDragAndDrop(View.java)
 *         at android.widget.Editor.startDragAndDrop(Editor.java)
 *         at android.widget.Editor.performLongClick(Editor.java)
 *
 * Thrown when the user long-presses a selectable/editable text and Android tries to
 * start a drag-to-move of the selection, but the drag-shadow bitmap computes to a
 * non-positive size (seen e.g. on Android 10/11). `View.startDragAndDrop()` is final
 * and cannot be overridden, so we intercept one frame up at `performLongClick()`,
 * which the framework always funnels the long-press through.
 *
 * Wired app-wide via the theme attribute (see styles.xml), so no per-activity or
 * per-widget changes are needed:
 *
 *     <item name="viewInflaterClass">com.v2ray.ang.util.SafeAppCompatViewInflater</item>
 *
 * Everything else (AppCompat tinting, default styles, selection/copy toolbar) is
 * inherited unchanged — only the buggy drag gesture is suppressed.
 */
class SafeAppCompatViewInflater : AppCompatViewInflater() {

    override fun createTextView(context: Context, attrs: AttributeSet?): AppCompatTextView =
        SafeAppCompatTextView(context, attrs)

    override fun createEditText(context: Context, attrs: AttributeSet?): AppCompatEditText =
        SafeAppCompatEditText(context, attrs)

    override fun createAutoCompleteTextView(
        context: Context,
        attrs: AttributeSet?
    ): AppCompatAutoCompleteTextView = SafeAppCompatAutoCompleteTextView(context, attrs)

    override fun createMultiAutoCompleteTextView(
        context: Context,
        attrs: AttributeSet?
    ): AppCompatMultiAutoCompleteTextView = SafeAppCompatMultiAutoCompleteTextView(context, attrs)
}

// NOTE: the try/catch is inlined in each override on purpose — Kotlin forbids `super`
// calls inside a lambda, so it cannot be factored into a shared higher-order helper.
// By the time the exception is thrown, text selection and the copy toolbar have already
// been applied by the platform, so we simply skip the failed drag and report the
// long-press as handled (return true).

class SafeAppCompatTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatTextView(context, attrs) {
    override fun performLongClick(): Boolean =
        try {
            super.performLongClick()
        } catch (e: IllegalStateException) {
            true
        }
}

class SafeAppCompatEditText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatEditText(context, attrs) {
    override fun performLongClick(): Boolean =
        try {
            super.performLongClick()
        } catch (e: IllegalStateException) {
            true
        }
}

class SafeAppCompatAutoCompleteTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatAutoCompleteTextView(context, attrs) {
    override fun performLongClick(): Boolean =
        try {
            super.performLongClick()
        } catch (e: IllegalStateException) {
            true
        }
}

class SafeAppCompatMultiAutoCompleteTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatMultiAutoCompleteTextView(context, attrs) {
    override fun performLongClick(): Boolean =
        try {
            super.performLongClick()
        } catch (e: IllegalStateException) {
            true
        }
}

package li.mofanx.epso.util

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.sp
import androidx.core.graphics.get
import kotlinx.serialization.json.JsonElement
import li.mofanx.epso.META
import li.mofanx.epso.MainActivity
import li.mofanx.epso.app
import li.songe.json5.Json5
import li.songe.json5.Json5EncoderConfig
import li.songe.json5.encodeToJson5String
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName

private val componentNameCache by lazy { HashMap<String, ComponentName>() }

val KClass<*>.componentName
    get() = componentNameCache.getOrPut(jvmName) { ComponentName(META.appId, jvmName) }

fun Bitmap.isFullTransparent(): Boolean {
    repeat(width) { x ->
        repeat(height) { y ->
            if (this[x, y] != Color.TRANSPARENT) {
                return false
            }
        }
    }
    return true
}

fun MainActivity.fixSomeProblems() {
    fixTransparentNavigationBar()
}

private fun Activity.fixTransparentNavigationBar() {
    // 修复在浅色主题下导航栏背景不透明的问题
    if (AndroidTarget.Q) {
        window.isNavigationBarContrastEnforced = false
    } else {
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
    }
}


fun <S : Comparable<S>> AnimatedContentTransitionScope<S>.getUpDownTransform(): ContentTransform {
    return if (targetState > initialState) {
        slideInVertically { height -> height } + fadeIn() togetherWith
                slideOutVertically { height -> -height } + fadeOut()
    } else {
        slideInVertically { height -> -height } + fadeIn() togetherWith
                slideOutVertically { height -> height } + fadeOut()
    }.using(
        SizeTransform(clip = false)
    )
}

val defaultJson5Config = Json5EncoderConfig(indent = "\u0020\u0020", trailingComma = true)
inline fun <reified T> toJson5String(value: T): String {
    if (value is JsonElement) {
        return Json5.encodeToString(value, defaultJson5Config)
    }
    return json.encodeToJson5String(value, defaultJson5Config)
}

fun drawTextToBitmap(text: String, bitmap: Bitmap) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32.sp.px
        color = Color.BLUE
        textAlign = Paint.Align.CENTER
    }
    val canvas = Canvas(bitmap)
    val strList = text.split('\n')
    strList.forEachIndexed { i, str ->
        canvas.drawText(
            str,
            bitmap.width / 2f,
            (bitmap.height / 2f) + (i - strList.size / 2f) * (paint.textSize + 4.sp.px),
            paint
        )
    }
}

// https://github.com/mofanx/epso/issues/924
private val Drawable.safeDrawable: Drawable?
    get() = if (intrinsicHeight > 0 && intrinsicWidth > 0) {
        this
    } else {
        null
    }

val PackageInfo.pkgIcon: Drawable?
    get() = applicationInfo?.loadIcon(app.packageManager)?.safeDrawable

private fun Char.isAsciiLetter(): Boolean {
    return this in 'a'..'z' || this in 'A'..'Z'
}

private fun Char.isAsciiVar(): Boolean {
    return this.isAsciiLetter() || this in '0'..'9' || this == '_'
}

private fun Char.isAsciiClassVar(): Boolean {
    return this.isAsciiVar() || this == '$'
}

// https://developer.android.com/build/configure-app-module?hl=zh-cn
fun String.isValidAppId(): Boolean {
    if (!contains('.')) return false
    if (!first().isAsciiLetter()) return false
    var i = 0
    while (i < length) {
        val c = get(i)
        if (c == '.') {
            i++
            if (getOrNull(i)?.isAsciiLetter() != true) {
                return false
            }
        } else if (!c.isAsciiVar()) {
            return false
        }
        i++
    }
    return true
}

fun String.isValidActivityId(): Boolean {
    if (isEmpty()) return false
    var i = 0
    while (i < length) {
        val c = get(i)
        if (c == '.') {
            i++
            if (getOrNull(i)?.isAsciiClassVar() == false) {
                return false
            }
        } else if (!c.isAsciiClassVar()) {
            return false
        }
        i++
    }
    return true
}

val isMainThread: Boolean get() = Looper.getMainLooper() == Looper.myLooper()

fun runMainPost(delayMillis: Long = 0L, r: Runnable) {
    if (delayMillis == 0L && isMainThread) {
        r.run()
        return
    }
    Handler(Looper.getMainLooper()).postDelayed(r, delayMillis)
}
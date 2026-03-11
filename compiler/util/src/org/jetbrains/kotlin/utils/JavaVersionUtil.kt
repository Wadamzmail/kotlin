/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.utils

import com.intellij.util.lang.JavaVersion

private val androidJavaVersion by lazy { androidJavaVersion() }

fun currentJavaVersion(): JavaVersion =
    androidJavaVersion ?: JavaVersion.current()

fun androidJavaVersion(): JavaVersion? =
    runCatching {
        val buildClass = Class.forName($$"android.os.Build$VERSION")
        val sdkInt = buildClass.getField("SDK_INT").getInt(null)
        val featureVersion = when {
            sdkInt >= 34 -> 17
            sdkInt >= 31 -> 11
            else -> 8
        }

        JavaVersion.compose(featureVersion)
    }.getOrNull()

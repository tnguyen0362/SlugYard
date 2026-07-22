package com.sluggyard.tv.baselineprofile

import androidx.benchmark.macro.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        val targetPackage = "com.sluggyard.tv"
        rule.collect(
            packageName = targetPackage,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(targetPackage)), 5_000)
            Thread.sleep(3_000)
        }
    }
}

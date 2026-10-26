package com.quickfiles.app

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/** Opt-in so the caller can preserve and restore the user's existing accessibility services. */
class UiAccessibilityDeviceTest {
    @Test fun talkBackCanFocusAndActivateNamedFolderSwitcher() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("talkBack") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            if (root == null) return emptyList()
            return listOf(root) + (0 until root.childCount).flatMap { nodes(root.getChild(it)) }
        }
        fun waitFor(predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue(predicate())
        }
        waitFor { manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).any { it.resolveInfo.serviceInfo.packageName == "com.samsung.android.accessibility.talkback" } }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor { nodes(automation.rootInActiveWindow).any { it.contentDescription?.toString() == "폴더 전환" } }
        val label = nodes(automation.rootInActiveWindow).first { it.contentDescription?.toString() == "폴더 전환" }
        var target = label
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue("Named switcher must have an actionable ancestor", target.isClickable)
        assertTrue("Accessibility focus must be accepted", target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
        assertTrue("Accessibility activation must open the switcher", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitFor { nodes(automation.rootInActiveWindow).any { it.text?.toString() == "나란히 보기" } }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        val graph = AppGraph.get(context)
        val original = runBlocking { graph.settings.settings.first() }
        fun activate(name: String) {
            waitFor { nodes(automation.rootInActiveWindow).any { it.contentDescription?.toString() == name || it.text?.toString() == name } }
            var node = nodes(automation.rootInActiveWindow).first { it.contentDescription?.toString() == name || it.text?.toString() == name }
            while (!node.isClickable && node.parent != null) node = node.parent
            assertTrue("$name accepts accessibility focus", node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            assertTrue("$name accepts accessibility action", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            activate("더보기"); activate("설정")
            waitFor { nodes(automation.rootInActiveWindow).any { it.text?.toString() == original.theme.label } }
            var choice = nodes(automation.rootInActiveWindow).first { it.text?.toString() == original.theme.label }
            while (!choice.isCheckable && !choice.isSelected && choice.parent != null) choice = choice.parent
            assertTrue("Current theme is announced as selected: ${choice.stateDescription}", choice.isSelected || choice.isChecked || choice.stateDescription?.toString() == "선택됨")
            assertTrue(choice.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            repeat(6) {
                if (nodes(automation.rootInActiveWindow).none { it.contentDescription?.toString() == "텍스트 글자 크기" && it.isVisibleToUser }) {
                    nodes(automation.rootInActiveWindow).firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(150)
                }
            }
            waitFor { nodes(automation.rootInActiveWindow).any { it.contentDescription?.toString() == "텍스트 글자 크기" && it.isVisibleToUser } }
            val sliderLabel = nodes(automation.rootInActiveWindow).first { it.contentDescription?.toString() == "텍스트 글자 크기" && it.isVisibleToUser }
            // Android places the label inside the SeekBar accessibility node.
            var slider = sliderLabel
            while (slider.rangeInfo == null && slider.parent != null) slider = slider.parent
            assertNotNull("The label must belong to an adjustable range", slider.rangeInfo)
            assertTrue(slider.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
            assertTrue(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 20f) }))
            waitFor {
                var valueNode = nodes(automation.rootInActiveWindow).firstOrNull { it.contentDescription?.toString() == "텍스트 글자 크기" }
                while (valueNode != null && valueNode.rangeInfo == null && valueNode.parent != null) valueNode = valueNode.parent
                valueNode?.rangeInfo?.current == 20f
            }
            activate("닫기")
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "TALKBACK settingsChoice=true selectedState=true namedSlider=true adjustableRange=true stateFeedback=true\n") })
        } finally {
            instrumentation.runOnMainSync { graph.workspace.panel(null) }
            runBlocking { graph.settings.save(original) }
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "TALKBACK serviceEnabled=true namedButton=true focus=true activation=true\n") })
    }
}

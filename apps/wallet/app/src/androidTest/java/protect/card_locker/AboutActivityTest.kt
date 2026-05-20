package protect.card_locker

import android.app.Instrumentation
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AboutActivityTest {
    @get:Rule
    private val rule: ComposeContentTestRule = createComposeRule()

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    private val content: AboutContent = AboutContent(instrumentation.targetContext)

    @Test
    fun testInitialState(): Unit = runComposeUiTest {
        setContent {
            AboutScreenContent(content = content)
        }

        onNodeWithTag("topbar_catima").assertIsDisplayed()

        onNodeWithTag("card_license").performScrollTo().assertIsDisplayed()
    }
}

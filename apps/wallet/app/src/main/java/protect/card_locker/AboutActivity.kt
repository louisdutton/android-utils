package protect.card_locker

import android.os.Bundle
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.tooling.preview.Preview
import protect.card_locker.compose.CatimaAboutSection
import protect.card_locker.compose.CatimaTopAppBar
import protect.card_locker.compose.theme.CatimaTheme

class AboutActivity : CatimaComponentActivity() {
    private lateinit var content: AboutContent

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fixedEdgeToEdge()

        content = AboutContent(this)
        title = content.pageTitle

        setContent {
            CatimaTheme {
                AboutScreenContent(
                    content = content,
                    onBackPressedDispatcher = onBackPressedDispatcher
                )
            }
        }
    }
}

@Composable
fun AboutScreenContent(
    content: AboutContent,
    onBackPressedDispatcher: OnBackPressedDispatcher? = null,
) {
    Scaffold(
        topBar = { CatimaTopAppBar(content.pageTitle.toString(), onBackPressedDispatcher) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            CatimaAboutSection(
                stringResource(R.string.license),
                stringResource(R.string.app_license),
                modifier = Modifier.testTag("card_license"),
                onClickDialogText = AnnotatedString.fromHtml(content.licenseHtml)
            )
        }
    }
}

@Preview
@Composable
private fun AboutActivityPreview() {
    AboutScreenContent(AboutContent(LocalContext.current))
}

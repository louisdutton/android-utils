package protect.card_locker

import android.os.Bundle
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import protect.card_locker.compose.CatimaAboutSection
import protect.card_locker.compose.CatimaTopAppBar
import protect.card_locker.compose.theme.CatimaTheme

private const val SourceRepositoryUrl = "https://github.com/louisdutton/android-utils"
private const val IssueTrackerUrl = "$SourceRepositoryUrl/issues"

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
                stringResource(R.string.version_history),
                content.versionHistory,
                modifier = Modifier.testTag("card_version_history"),
                onClickDialogText = AnnotatedString.fromHtml(
                    htmlString = content.historyHtml,
                    linkStyles = TextLinkStyles(
                        style = SpanStyle(
                            textDecoration = TextDecoration.Underline,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                )
            )
            CatimaAboutSection(
                stringResource(R.string.credits),
                content.copyrightShort,
                modifier = Modifier.testTag("card_credits"),
                onClickDialogText = AnnotatedString.fromHtml(
                    htmlString = content.contributorInfoHtml,
                    linkStyles = TextLinkStyles(
                        style = SpanStyle(
                            textDecoration = TextDecoration.Underline,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                )
            )
            CatimaAboutSection(
                stringResource(R.string.license),
                stringResource(R.string.app_license),
                modifier = Modifier.testTag("card_license"),
                onClickDialogText = AnnotatedString.fromHtml(
                    htmlString = content.licenseHtml,
                    linkStyles = TextLinkStyles(
                        style = SpanStyle(
                            textDecoration = TextDecoration.Underline,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                )
            )
            CatimaAboutSection(
                stringResource(R.string.source_repository),
                stringResource(R.string.on_github),
                modifier = Modifier.testTag("card_source_github"),
                onClickUrl = SourceRepositoryUrl
            )
            CatimaAboutSection(
                stringResource(R.string.privacy_policy),
                stringResource(R.string.and_data_usage),
                modifier = Modifier.testTag("card_privacy_policy"),
                onClickDialogText = AnnotatedString.fromHtml(
                    htmlString = content.privacyHtml,
                    linkStyles = TextLinkStyles(
                        style = SpanStyle(
                            textDecoration = TextDecoration.Underline,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                )
            )
            CatimaAboutSection(
                stringResource(R.string.report_error),
                stringResource(R.string.on_github),
                modifier = Modifier.testTag("card_report_error"),
                onClickUrl = IssueTrackerUrl
            )
        }
    }
}

@Preview
@Composable
private fun AboutActivityPreview() {
    AboutScreenContent(AboutContent(LocalContext.current))
}

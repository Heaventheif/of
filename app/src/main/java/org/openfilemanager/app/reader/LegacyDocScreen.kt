package org.openfilemanager.app.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openfilemanager.app.R
import org.openfilemanager.app.ui.openWithSystem
import org.openfilemanager.core.DocFormatException
import org.openfilemanager.core.DocTextExtractor

/** Text-only viewer for legacy Word (.doc) files: no formatting, images or tables. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyDocScreen(path: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val file = remember(path) { File(path) }
    var text by remember(path) { mutableStateOf<String?>(null) }
    var error by remember(path) { mutableStateOf<Int?>(null) }

    BackHandler(onBack = onBack)
    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            try {
                val t = DocTextExtractor.extract(file)
                if (t.isBlank()) error = R.string.rd_err_empty else text = t
            } catch (e: DocFormatException) {
                error = when (e.reason) {
                    DocFormatException.Reason.ENCRYPTED -> R.string.rd_err_doc_encrypted
                    DocFormatException.Reason.TOO_LARGE -> R.string.rd_err_too_large
                    DocFormatException.Reason.UNSUPPORTED -> R.string.rd_err_legacy_doc
                    else -> R.string.rd_err_corrupt_doc
                }
            } catch (e: Exception) {
                error = R.string.rd_err_unreadable
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_back)) } },
                actions = { IconButton({ openWithSystem(ctx, path) }) { Icon(Icons.Outlined.OpenInNew, stringResource(R.string.ui_open_with)) } }
            )
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            val t = text
            val e = error
            when {
                t != null -> SelectionContainer {
                    Text(
                        t, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                    )
                }
                e != null -> Text(
                    stringResource(e), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
                else -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

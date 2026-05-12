package com.curiokid.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.curiokid.app.R

/**
 * A small copy IconButton that, when tapped, reveals a dropdown menu with
 * three options: copy question, copy answer, copy both. Used by the chat,
 * history, and parent dashboard screens so the copy affordance is
 * consistent everywhere.
 */
@Composable
fun CopyMenuButton(
    onCopy: (CopyTarget) -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    buttonSize: Dp = 40.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(
        onClick = { expanded = true },
        modifier = modifier.size(buttonSize),
    ) {
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = stringResource(R.string.action_copy),
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy_question)) },
                onClick = {
                    expanded = false
                    onCopy(CopyTarget.QUESTION)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy_answer)) },
                onClick = {
                    expanded = false
                    onCopy(CopyTarget.ANSWER)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy_both)) },
                onClick = {
                    expanded = false
                    onCopy(CopyTarget.BOTH)
                },
            )
        }
    }
}

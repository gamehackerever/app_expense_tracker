package com.expensetracker.offline.ui.components

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expensetracker.offline.data.local.entity.SplitDebtEntity
import com.expensetracker.offline.data.local.entity.TransactionEntity
import com.expensetracker.offline.data.local.entity.TransactionSource
import com.expensetracker.offline.data.local.entity.TransactionType
import com.expensetracker.offline.ui.theme.AppColors
import com.expensetracker.offline.ui.theme.AppRadius
import com.expensetracker.offline.ui.theme.getCategoryColor
import com.expensetracker.offline.ui.theme.getCategoryIcon
import com.expensetracker.offline.ui.theme.hasCategoryIcon
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// FIXED: Centralized CurrencyFormat using safe BigDecimal math over Long (Paise)
object CurrencyFormat {
    private val inLocale: Locale = Locale.forLanguageTag("en-IN")

    private val formatters = object : ThreadLocal<Array<NumberFormat?>>() {
        override fun initialValue(): Array<NumberFormat?> = arrayOfNulls(3)
    }

    private fun formatter(decimals: Int): NumberFormat {
        val d = decimals.coerceIn(0, 2)
        val cache = formatters.get()!!
        return cache[d] ?: NumberFormat.getNumberInstance(inLocale).apply {
            minimumFractionDigits = d
            maximumFractionDigits = d
        }.also { cache[d] = it }
    }

    fun amount(paise: Long, decimals: Int = 2): String {
        val rupees = BigDecimal.valueOf(paise).divide(BigDecimal(100))
        return formatter(decimals).format(rupees)
    }

    fun withSymbol(paise: Long, decimals: Int = 2): String = "₹${amount(paise, decimals)}"

    /** Whole amounts drop the decimals (₹404); amounts with paise keep them (₹404.50). */
    fun smart(paise: Long): String {
        val decimals = if (paise % 100L == 0L) 0 else 2
        return withSymbol(paise, decimals)
    }

    private val TAG_REGEX = Regex("""\s*\[(splitId|creditId):\d+]""")

    fun cleanNote(rawNote: String?): String {
        var text = rawNote ?: return ""
        text = text.replace(TAG_REGEX, "")
        text = text.replace("Settled • ", "")
        if (text.startsWith("Settled by ", ignoreCase = true)) text = text.substring(11)
        if (text.startsWith("Repaid for ", ignoreCase = true)) text = text.substring(11)
        text = text.substringBefore(" (+").substringBefore(" (₹")
        return text.trim(' ', '•', '-', ',', '+', '(')
    }
}

/** Which transactions get a small source marker on the meta line. Default marks the exception (hand-entered). */
enum class SourceMarker { AUTO, MANUAL, NONE }

private val CardShapeDp = AppRadius.large
private val TabularNumbers = TextStyle(fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)

/** One type scale for every row: title (payee, amount), body (meta line), caption (everything else). */
private object RowType {
    val title = 16.sp
    val body = 13.sp
    val caption = 12.sp
}

/** Items/notes stay on one line so rows keep an even height; the full text is in the edit sheet. */
private const val DetailMaxLines = 1

/** Width of badge + gap, used to inset dividers so they start under the text. */
private val DividerInset = 76.dp

@Composable
fun TransactionGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(CardShapeDp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface, shape)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.30f), shape),
        content = content
    )
}

@Composable
fun TransactionRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = DividerInset, end = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardTransactionCard(
    modifier: Modifier = Modifier,
    transaction: TransactionEntity,
    debts: List<SplitDebtEntity> = emptyList(),
    isSettled: Boolean,
    settledBy: String?,
    outstandingAmount: Long? = null, // FIXED: Migrated to Long
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    showActions: Boolean = true,
    grouped: Boolean = false,
    onCardClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onNavigateToOriginalBill: () -> Unit = {},
    showBankBadge: Boolean = false,
    sourceMarker: SourceMarker = SourceMarker.MANUAL,
    colorfulCategories: Boolean = false,
    showCategory: Boolean = true,
    showDate: Boolean = false,
    billLabel: String? = null
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    val isDebit = transaction.type == TransactionType.DEBIT
    val isCredit = transaction.type == TransactionType.CREDIT
    val isSplit = debts.isNotEmpty()
    val isSplitDebit = isDebit && isSplit
    val isRepayment = isCredit && transaction.linkedDebtId != null
    val isDebitSettled = isDebit && isSettled

    // FIXED: Strict Long/Paise math to eliminate floating point settlement bugs
    val originalOwed = remember(debts) { debts.sumOf { it.amountOwed.toLong() } }
    val myShare = remember(transaction.amount, originalOwed) {
        (transaction.amount - originalOwed).coerceAtLeast(0L)
    }

    val headlineAmount = if (isSplitDebit) myShare else transaction.amount
    val showBillLine = isSplitDebit && transaction.amount != myShare

    val glyphColor = if (colorfulCategories) getCategoryColor(transaction.category) else colors.onSurfaceVariant
    val categoryIcon = getCategoryIcon(transaction.category)
    val payeeInitial = remember(transaction.payee) {
        transaction.payee.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
    }

    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val timeText = remember(transaction.timestamp, timeFormat, showDate) {
        val time = timeFormat.format(Date(transaction.timestamp))
        if (showDate) {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(transaction.timestamp)) + " · " + time
        } else time
    }

    val cardInteraction = remember { MutableInteractionSource() }
    val isPressed by cardInteraction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && !grouped) 0.985f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "cardScale"
    )

    val baseColor = if (grouped) Color.Transparent else colors.surface
    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> colors.onSurface.copy(alpha = 0.06f).compositeOver(baseColor)
            grouped && isPressed -> colors.onSurface.copy(alpha = 0.04f).compositeOver(baseColor)
            else -> baseColor
        },
        animationSpec = tween(150),
        label = "cardBgColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) colors.onSurface.copy(alpha = 0.55f) else colors.outlineVariant.copy(alpha = 0.30f),
        animationSpec = tween(150),
        label = "cardBorderColor"
    )

    val amountText = "${if (isDebit) "−" else "+"}${CurrencyFormat.smart(headlineAmount)}"
    val bankText = remember(transaction.bankName, transaction.accountNumber) {
        transaction.bankName?.let { bank ->
            shortBankCode(bank) + (transaction.accountNumber?.let { " ··$it" } ?: "")
        }
    }

    val spokenAmount = buildString {
        append(if (isDebit) "Spent " else "Received ")
        append(CurrencyFormat.smart(headlineAmount))
        if (showBillLine) append(", your share of a ${CurrencyFormat.smart(transaction.amount)} bill")
    }

    val visibleBillLabel = remember(billLabel, transaction.payee) {
        billLabel?.takeIf { label ->
            val bill = label.trim()
            val payee = transaction.payee.trim()
            bill.isNotEmpty() && !payee.contains(bill, ignoreCase = true) && !bill.contains(payee, ignoreCase = true)
        }
    }

    val isAuto = transaction.source != TransactionSource.MANUAL
    val showManualMarker = sourceMarker == SourceMarker.MANUAL && !isAuto
    val showAutoMarker = sourceMarker == SourceMarker.AUTO && isAuto

    val showCategoryText = showCategory || isRepayment
    val metaTail = buildString {
        if (showCategoryText) append(" · ")
        append(timeText)
        if (showBankBadge && bankText != null) {
            append(" · ")
            append(bankText)
        }
        if (showManualMarker) append(" · Manual")
    }

    val positive = AppColors.positive
    val warning = AppColors.warning
    val owes = outstandingAmount ?: originalOwed
    val statusText: String?
    val statusDot: Color?
    val statusIcon: ImageVector?
    val statusColor: Color
    val onStatusClick: (() -> Unit)?

    when {
        isRepayment -> {
            statusText = "View bill"; statusDot = null; statusIcon = Icons.AutoMirrored.Filled.ReceiptLong
            statusColor = colors.onSurface; onStatusClick = onNavigateToOriginalBill
        }
        isSplitDebit && isDebitSettled -> {
            statusText = settledLabel(settledBy); statusDot = positive; statusIcon = null
            statusColor = colors.onSurfaceVariant; onStatusClick = null
        }
        isSplitDebit -> {
            statusText = owedLabel(debts, owes); statusDot = warning; statusIcon = null
            statusColor = colors.onSurfaceVariant; onStatusClick = null
        }
        else -> {
            statusText = null; statusDot = null; statusIcon = null
            statusColor = colors.onSurfaceVariant; onStatusClick = null
        }
    }
    val hasStatusRow = showActions && statusText != null

    val shape = if (grouped) RectangleShape else RoundedCornerShape(CardShapeDp)
    val verticalPadding = if (grouped) 14.dp else 16.dp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .semantics {
                if (selectionMode) stateDescription = if (isSelected) "Selected" else "Not selected"
            }
            .combinedClickable(
                interactionSource = cardInteraction,
                indication = null,
                onClickLabel = if (selectionMode) "Toggle selection" else "Edit transaction",
                onLongClickLabel = "Select transactions",
                role = if (selectionMode) Role.Checkbox else Role.Button,
                onClick = onCardClick,
                onLongClick = onLongClick
            ),
        shape = shape,
        color = bgColor,
        border = if (grouped) null else BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(
                start = 16.dp,
                top = verticalPadding,
                end = 16.dp,
                bottom = verticalPadding
            )
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {

                AnimatedVisibility(
                    visible = selectionMode,
                    enter = fadeIn(tween(180)) + expandHorizontally(tween(220), expandFrom = Alignment.Start),
                    exit = fadeOut(tween(120)) + shrinkHorizontally(tween(180), shrinkTowards = Alignment.Start)
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier
                            .padding(top = 11.dp, end = 12.dp)
                            .size(22.dp)
                    )
                }

                when {
                    isRepayment -> CategoryBadge(icon = Icons.Default.SouthWest, initial = null, tint = AppColors.positive)
                    !hasCategoryIcon(transaction.category) && payeeInitial != null ->
                        CategoryBadge(icon = null, initial = payeeInitial, tint = glyphColor)
                    else -> CategoryBadge(icon = categoryIcon, initial = null, tint = glyphColor)
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = transaction.payee,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = RowType.title,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!transaction.receiptImageUri.isNullOrBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = "Receipt attached",
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(3.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val metaColor = colors.onSurfaceVariant
                        if (showCategoryText) {
                            Text(
                                text = if (isRepayment) "Repayment" else transaction.category,
                                fontSize = RowType.body,
                                fontWeight = FontWeight.Medium,
                                color = metaColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        Text(
                            text = metaTail,
                            fontSize = RowType.body,
                            fontWeight = FontWeight.Medium,
                            color = metaColor,
                            maxLines = 1,
                            softWrap = false
                        )
                        if (showAutoMarker) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = "Auto-captured",
                                tint = metaColor,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    visibleBillLabel?.let {
                        Spacer(Modifier.height(6.dp))
                        InlineMeta(
                            icon = Icons.Default.PushPin,
                            text = "Counted toward $it",
                            color = colors.onSurfaceVariant
                        )
                    }

                    val detail = remember(transaction.itemsSummary, transaction.note, transaction.rawContent, transaction.source) {
                        val summary = flattenLines(transaction.itemsSummary)
                        val noteTxt = transaction.note?.trim()?.takeIf { it.isNotBlank() }

                        val isRawDump = noteTxt != null && transaction.source != TransactionSource.MANUAL && (
                                noteTxt == transaction.rawContent.trim() ||
                                        noteTxt.startsWith("Auto ", ignoreCase = true)
                                )

                        summary ?: if (isRawDump) null else flattenLines(noteTxt)
                    }
                    if (detail != null) {
                        Spacer(Modifier.height(6.dp))
                        InlineMeta(
                            icon = if (!transaction.itemsSummary.isNullOrBlank()) Icons.Default.ShoppingBasket else null,
                            text = detail,
                            color = colors.onSurfaceVariant,
                            maxLines = DetailMaxLines
                        )
                    }

                    if (hasStatusRow && statusText != null) {
                        StatusLine(
                            text = statusText,
                            dotColor = statusDot,
                            icon = statusIcon,
                            textColor = statusColor,
                            onClick = onStatusClick
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = amountText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = RowType.title,
                        color = if (isDebit) colors.onSurface else AppColors.positive,
                        maxLines = 1,
                        softWrap = false,
                        style = TabularNumbers,
                        modifier = Modifier.semantics { contentDescription = spokenAmount }
                    )
                    if (showBillLine) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "bill ${CurrencyFormat.smart(transaction.amount)}",
                            fontSize = RowType.caption,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            style = TabularNumbers
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBadge(icon: ImageVector?, initial: Char?, tint: Color) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                icon != null -> Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                initial != null -> Text(
                    text = initial.toString(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tint
                )
            }
        }
    }
}

@Composable
private fun InlineMeta(
    icon: ImageVector?,
    text: String,
    color: Color,
    maxLines: Int = 1
) {
    Row(verticalAlignment = if (maxLines > 1) Alignment.Top else Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .padding(top = if (maxLines > 1) 2.dp else 0.dp)
                    .size(12.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            fontSize = RowType.caption,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusLine(
    text: String,
    dotColor: Color?,
    icon: ImageVector?,
    textColor: Color,
    onClick: (() -> Unit)?
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fade by animateFloatAsState(if (pressed) 0.5f else 1f, tween(100), label = "statusPress")
    val clickable = onClick != null

    Spacer(Modifier.height(if (clickable) 3.dp else 6.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (onClick != null) {
            Modifier
                .graphicsLayer { alpha = fade }
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                .padding(vertical = 3.dp)
        } else Modifier
    ) {
        Box(modifier = Modifier.size(12.dp), contentAlignment = Alignment.Center) {
            when {
                dotColor != null -> Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                icon != null -> Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(12.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = RowType.caption,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (clickable) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = textColor.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun MinimalActionChip(
    label: String,
    onClick: () -> Unit,
    color: Color,
    isFilled: Boolean,
    visibleHeight: Dp = 36.dp,
    horizontalPadding: Dp = 16.dp
) {
    val colors = MaterialTheme.colorScheme
    val chipShape = CircleShape
    val chipSource = remember { MutableInteractionSource() }
    val chipPressed by chipSource.collectIsPressedAsState()
    val chipFade by animateFloatAsState(if (chipPressed) 0.6f else 1f, tween(100), label = "chipPress")

    val textColor = if (isFilled) color else colors.onSurface

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer { alpha = chipFade }
            .clickable(interactionSource = chipSource, indication = null, role = Role.Button, onClick = onClick)
    ) {
        Surface(
            shape = chipShape,
            color = if (isFilled) colors.onSurface.copy(alpha = 0.04f) else Color.Transparent,
            border = if (isFilled) null else BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.3f)),
            modifier = Modifier.defaultMinSize(minHeight = visibleHeight)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 4.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun flattenLines(text: String?): String? {
    if (text.isNullOrBlank()) return null
    return text.lines()
        .map { it.trim().trimStart('-', '•', '*', ' ') }
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .takeIf { it.isNotBlank() }
}

private fun settledLabel(settledBy: String?): String {
    if (settledBy.isNullOrBlank()) return "Settled"
    if (settledBy.endsWith("people")) return "Settled by $settledBy"

    val shortName = settledBy.trim().split(" ").firstOrNull { it.isNotBlank() }
        ?.lowercase()
        ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        .orEmpty()

    return when {
        shortName.isBlank() -> "Settled"
        shortName.length > 10 -> "Settled by ${shortName.take(9)}…"
        else -> "Settled by $shortName"
    }
}

// FIXED: Receives Long and uses updated CurrencyFormat logic
private fun owedLabel(debts: List<SplitDebtEntity>, owes: Long): String {
    val unsettled = debts.filter { it.settledAt == null }
    val firstName = unsettled.singleOrNull()
        ?.debtorName?.trim()?.split(" ")?.firstOrNull { it.isNotBlank() }
        .orEmpty()
    val amount = CurrencyFormat.smart(owes)
    return when {
        firstName.isNotBlank() -> "$firstName owes $amount"
        unsettled.size > 1 -> "${unsettled.size} people owe $amount"
        else -> "Owed $amount"
    }
}

private fun shortBankCode(bankName: String): String = when {
    bankName.contains("HDFC", true) -> "HDFC"
    bankName.contains("SBI", true) || bankName.contains("State Bank", true) -> "SBI"
    bankName.contains("ICICI", true) -> "ICICI"
    bankName.contains("Axis", true) -> "AXIS"
    bankName.contains("Kotak", true) -> "KOTAK"
    bankName.contains("PNB", true) || bankName.contains("Punjab National", true) -> "PNB"
    bankName.contains("Federal", true) -> "FED"
    bankName.contains("Canara", true) -> "CANARA"
    bankName.contains("Union", true) -> "UNION"
    bankName.contains("IDFC", true) -> "IDFC"
    bankName.contains("IndusInd", true) -> "INDUS"
    bankName.contains("Yes Bank", true) -> "YES"
    bankName.contains("South Indian", true) -> "SIB"
    else -> bankName.trim().split(" ").firstOrNull { it.isNotBlank() }?.take(5)?.uppercase()
        ?: bankName.take(5).uppercase()
}
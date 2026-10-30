package com.expensetracker.offline.data.repository

/**
 * Accounts that never send SMS and are tracked by hand: savings, fixed deposits, investments, loans.
 * The model and the net-worth maths are plain Kotlin so they can be tested without Android.
 */
enum class ManualAccountKind(
    val label: String,
    val isLiability: Boolean,
    /** Label for the optional recurring amount; empty = this kind has none. */
    val recurringLabel: String,
    val amountLabel: String
) {
    SAVINGS("Savings / cash", false, "", "Current balance"),
    FD("Fixed deposit", false, "", "Current value"),
    INVESTMENT("Investment", false, "Monthly SIP", "Current value"),
    LOAN("Loan", true, "Monthly EMI", "Outstanding amount"),
    OTHER_DEBT("Other debt", true, "Monthly payment", "Outstanding amount")
}

data class ManualAccount(
    val id: String,
    val kind: ManualAccountKind,
    val name: String,
    /** Current value for assets, outstanding amount for debts. Always >= 0, in paise. */
    val amountPaise: Long,
    /** Optional monthly SIP / EMI in paise; 0 = none. */
    val monthlyPaise: Long = 0L,
    /** Day of month the SIP / EMI is due (1..28); 0 = not set. */
    val dueDay: Int = 0,
    val updatedAt: Long = 0L
)

data class NetWorth(
    val bankPaise: Long,
    val manualAssetsPaise: Long,
    val cardDebtPaise: Long,
    val manualDebtPaise: Long,
    /** Sum of every SIP / EMI the user entered, per month. */
    val monthlyCommitmentsPaise: Long
) {
    val assetsPaise: Long get() = bankPaise + manualAssetsPaise
    val debtsPaise: Long get() = cardDebtPaise + manualDebtPaise
    val netPaise: Long get() = assetsPaise - debtsPaise
}

object NetWorthMath {
    fun compute(bankBalancesPaise: List<Long>, manual: List<ManualAccount>, cardDebtPaise: Long): NetWorth =
        NetWorth(
            bankPaise = bankBalancesPaise.sumOf { it.coerceAtLeast(0L) },
            manualAssetsPaise = manual.filter { !it.kind.isLiability }.sumOf { it.amountPaise.coerceAtLeast(0L) },
            cardDebtPaise = cardDebtPaise.coerceAtLeast(0L),
            manualDebtPaise = manual.filter { it.kind.isLiability }.sumOf { it.amountPaise.coerceAtLeast(0L) },
            monthlyCommitmentsPaise = manual.sumOf { it.monthlyPaise.coerceAtLeast(0L) }
        )
}

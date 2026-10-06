package com.example.myapplication;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Java helper: integer cents for money; exact decimal quantities for stock. */
public final class MoneyMath {
    private MoneyMath() {}

    public static long cents(String input) {
        String value = input.trim();
        if (!value.matches("\\d+(\\.\\d{1,2})?"))
            throw new IllegalArgumentException("Enter money such as 1250.00, without commas.");
        long cents = new BigDecimal(value).movePointRight(2).longValueExact();
        if (cents > 1000000000000L)
            throw new IllegalArgumentException("Amount is outside the supported range.");
        return cents;
    }

    public static BigDecimal quantity(String input) {
        String value = input.trim();
        if (!value.matches("\\d+(\\.\\d{1,3})?"))
            throw new IllegalArgumentException("Use up to 3 decimal places for quantity.");
        BigDecimal qty = new BigDecimal(value);
        if (qty.signum() <= 0 || qty.compareTo(new BigDecimal("1000000000")) > 0)
            throw new IllegalArgumentException("Quantity must be positive and at most 1 billion.");
        return qty;
    }

    public static long lineTotal(long priceCents, BigDecimal qty) {
        return BigDecimal.valueOf(priceCents).multiply(qty)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static String money(long cents) {
        return String.format(Locale.US, "KES %,.2f", BigDecimal.valueOf(cents, 2));
    }

    public static String units(BigDecimal qty) {
        return qty.stripTrailingZeros().toPlainString();
    }
}
